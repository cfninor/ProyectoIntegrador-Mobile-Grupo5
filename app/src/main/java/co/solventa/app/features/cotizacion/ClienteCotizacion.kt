package co.solventa.app.features.cotizacion

import co.solventa.app.core.red.ClienteHttp
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/** Resultado de pedir una cotización al BFF móvil. */
sealed interface ResultadoCotizacion {
    /** 201: el núcleo calculó la prima. */
    data class Cotizada(val cotizacion: Cotizacion) : ResultadoCotizacion

    /** 422: el núcleo rechazó los datos; trae los campos que señaló (vacío si no señaló ninguno). */
    data class Invalida(val campos: Set<CampoCotizacion>) : ResultadoCotizacion

    /** 503 u otra respuesta inesperada: el servicio de cotización no está disponible. */
    data object NoDisponible : ResultadoCotizacion

    /** No hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    data object SinConexion : ResultadoCotizacion
}

/**
 * Cliente de `POST /v1/cotizaciones` del BFF móvil: el mismo backend de cotización que usa el canal
 * web (SOLVENTAG5-47). La app no calcula la prima ni aplica las reglas del ramo; solo envía los
 * datos y muestra lo que responde el núcleo. [cotizar] bloquea el hilo: se llama fuera del
 * hilo principal.
 */
class ClienteCotizacion(
    apiBaseUrl: String,
    timeoutMs: Int = ClienteHttp.TIMEOUT_MS,
) {
    private val http = ClienteHttp(apiBaseUrl, timeoutMs)

    fun cotizar(solicitud: SolicitudCotizacion): ResultadoCotizacion = try {
        val respuesta = http.postJson(RUTA, cuerpo(solicitud))
        interpretar(respuesta.codigo, respuesta.cuerpo)
    } catch (_: IOException) {
        ResultadoCotizacion.SinConexion
    }

    companion object {
        private const val RUTA = "/v1/cotizaciones"
        private const val HTTP_UNPROCESSABLE = 422

        /** Nombre de cada campo del bien en el contrato. */
        private val CAMPOS_POR_NOMBRE = mapOf(
            "montoCredito" to CampoCotizacion.MONTO,
            "plazoAnios" to CampoCotizacion.PLAZO,
            "ciudadInmueble" to CampoCotizacion.CIUDAD,
            "entidadAcreedora" to CampoCotizacion.ENTIDAD,
        )

        /** Cuerpo con la forma de `SolicitudCotizacion` del contrato. */
        fun cuerpo(solicitud: SolicitudCotizacion): JSONObject {
            val json = JSONObject()
                .put("ramo", solicitud.ramo.name)
                .put(
                    "datosBien",
                    JSONObject()
                        .put("montoCredito", solicitud.montoCredito)
                        .put("plazoAnios", solicitud.plazoAnios)
                        .put("ciudadInmueble", solicitud.ciudadInmueble.trim())
                        .put("entidadAcreedora", solicitud.entidadAcreedora.trim()),
                )
            solicitud.clienteId?.let { json.put("clienteId", it) }
            return json
        }

        fun interpretar(codigo: Int, cuerpo: String): ResultadoCotizacion = try {
            when (codigo) {
                HttpURLConnection.HTTP_CREATED -> ResultadoCotizacion.Cotizada(cotizacion(JSONObject(cuerpo)))
                HTTP_UNPROCESSABLE -> ResultadoCotizacion.Invalida(camposConError(JSONObject(cuerpo)))
                else -> ResultadoCotizacion.NoDisponible
            }
        } catch (_: JSONException) {
            ResultadoCotizacion.NoDisponible
        }

        /**
         * El núcleo responde `DATOS_BIEN_INVALIDOS` con la lista `campos`; el BFF, si el cuerpo está
         * mal formado, responde `detail[].loc` = ["body", "datosBien", "<campo>"].
         */
        private fun camposConError(json: JSONObject): Set<CampoCotizacion> {
            val nombres = json.optJSONArray("campos")?.let { campos ->
                (0 until campos.length()).map { campos.optString(it) }
            } ?: json.optJSONArray("detail")?.let { detalle ->
                (0 until detalle.length()).mapNotNull { i ->
                    val loc = detalle.optJSONObject(i)?.optJSONArray("loc") ?: return@mapNotNull null
                    loc.optString(loc.length() - 1)
                }
            }.orEmpty()
            return nombres.mapNotNull(CAMPOS_POR_NOMBRE::get).toSet()
        }

        private fun cotizacion(json: JSONObject) = Cotizacion(
            cotizacionId = json.getString("cotizacionId"),
            prima = json.getLong("prima"),
            moneda = json.getString("moneda"),
            periodicidad = json.getString("periodicidad"),
            vigenteHasta = json.getString("vigenteHasta"),
            calculadaSinPerfil = json.getBoolean("calculadaSinPerfil"),
            motivoSinPerfil = json.optString("motivoSinPerfil").takeIf { it.isNotEmpty() },
        )
    }
}
