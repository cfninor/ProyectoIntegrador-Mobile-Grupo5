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

    /** No se obtuvo una cotización. */
    data object NoDisponible : ResultadoCotizacion
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
        ResultadoCotizacion.NoDisponible
    }

    companion object {
        private const val RUTA = "/v1/cotizaciones"

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
            if (codigo == HttpURLConnection.HTTP_CREATED) {
                ResultadoCotizacion.Cotizada(cotizacion(JSONObject(cuerpo)))
            } else {
                ResultadoCotizacion.NoDisponible
            }
        } catch (_: JSONException) {
            ResultadoCotizacion.NoDisponible
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
