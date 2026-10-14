package co.solventa.app.features.onboarding

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Resultado de pedir al BFF móvil que inicie el onboarding. */
sealed interface ResultadoOnboarding {
    /** 201: el núcleo creó el Cliente (criterio 3). */
    data class Iniciado(val clienteId: String, val estado: String) : ResultadoOnboarding

    /** 422: el BFF rechazó el formato; trae los campos que señaló. */
    data class Invalido(val campos: Set<Campo>) : ResultadoOnboarding

    /** 503 u otra respuesta inesperada del servidor. */
    data object NoDisponible : ResultadoOnboarding

    /** No hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    data object SinConexion : ResultadoOnboarding
}

/**
 * Cliente de `POST /v1/onboarding` del BFF móvil (conector C1). [iniciar] bloquea el hilo:
 * se llama fuera del hilo principal.
 */
class ClienteOnboarding(
    private val apiBaseUrl: String,
    private val timeoutMs: Int = TIMEOUT_MS,
) {

    fun iniciar(datos: DatosBasicos): ResultadoOnboarding {
        val conexion = try {
            URL("$apiBaseUrl$RUTA").openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return ResultadoOnboarding.SinConexion
        }
        return try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = timeoutMs
            conexion.readTimeout = timeoutMs
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conexion.setRequestProperty("Accept", "application/json")
            conexion.outputStream.use { it.write(cuerpo(datos).toString().toByteArray()) }

            val codigo = conexion.responseCode
            val flujo = if (codigo < HttpURLConnection.HTTP_BAD_REQUEST) {
                conexion.inputStream
            } else {
                conexion.errorStream
            }
            interpretar(codigo, flujo?.bufferedReader()?.use { it.readText() }.orEmpty())
        } catch (_: IOException) {
            ResultadoOnboarding.SinConexion
        } finally {
            conexion.disconnect()
        }
    }

    companion object {
        private const val RUTA = "/v1/onboarding"
        private const val TIMEOUT_MS = 10_000
        private const val HTTP_UNPROCESSABLE = 422

        /** Ruta de cada campo en el cuerpo, como la reporta el BFF en `detail[].loc`. */
        private val CAMPOS_POR_RUTA = mapOf(
            listOf("documento", "tipo") to Campo.TIPO_DOCUMENTO,
            // El BFF valida el número según el tipo sobre el bloque `documento` completo.
            listOf("documento") to Campo.NUMERO_DOCUMENTO,
            listOf("documento", "numero") to Campo.NUMERO_DOCUMENTO,
            listOf("nombre", "nombres") to Campo.NOMBRES,
            listOf("nombre", "apellidos") to Campo.APELLIDOS,
            listOf("contacto", "correo") to Campo.CORREO,
            listOf("contacto", "celular") to Campo.CELULAR,
        )

        /** Cuerpo de la petición con la forma de `DatosBasicosCliente` del contrato. */
        fun cuerpo(datos: DatosBasicos): JSONObject {
            val limpios = datos.limpios()
            return JSONObject()
                .put(
                    "documento",
                    JSONObject()
                        .put("tipo", limpios.tipoDocumento?.name)
                        .put("numero", limpios.numeroDocumento),
                )
                .put(
                    "nombre",
                    JSONObject().put("nombres", limpios.nombres).put("apellidos", limpios.apellidos),
                )
                .put(
                    "contacto",
                    JSONObject().put("correo", limpios.correo).put("celular", limpios.celular),
                )
        }

        fun interpretar(codigo: Int, cuerpo: String): ResultadoOnboarding = try {
            when (codigo) {
                HttpURLConnection.HTTP_CREATED -> {
                    val json = JSONObject(cuerpo)
                    ResultadoOnboarding.Iniciado(json.getString("clienteId"), json.getString("estado"))
                }
                HTTP_UNPROCESSABLE -> ResultadoOnboarding.Invalido(camposConError(JSONObject(cuerpo)))
                else -> ResultadoOnboarding.NoDisponible
            }
        } catch (_: JSONException) {
            ResultadoOnboarding.NoDisponible
        }

        private fun camposConError(json: JSONObject): Set<Campo> {
            val detalle = json.optJSONArray("detail") ?: return emptySet()
            return (0 until detalle.length()).mapNotNull { i ->
                val loc = detalle.optJSONObject(i)?.optJSONArray("loc") ?: return@mapNotNull null
                // loc = ["body", "<bloque>", "<campo>"], o ["body", "<bloque>"] si es del bloque.
                val ruta = (1 until loc.length()).map { loc.optString(it) }.take(2)
                CAMPOS_POR_RUTA[ruta]
            }.toSet()
        }
    }
}
