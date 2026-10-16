package co.solventa.app.core.red

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Respuesta HTTP del BFF móvil: el código y el cuerpo como texto. */
data class RespuestaHttp(val codigo: Int, val cuerpo: String)

/**
 * Llamadas JSON al BFF móvil (conector C1) con `HttpURLConnection`, que ya viene en Android.
 * Bloquea el hilo: se llama fuera del hilo principal.
 */
class ClienteHttp(
    private val apiBaseUrl: String,
    private val timeoutMs: Int = TIMEOUT_MS,
) {

    /** Lanza [IOException] si no hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    @Throws(IOException::class)
    fun postJson(ruta: String, cuerpo: JSONObject): RespuestaHttp {
        val conexion = URI("$apiBaseUrl$ruta").toURL().openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = timeoutMs
            conexion.readTimeout = timeoutMs
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conexion.setRequestProperty("Accept", "application/json")
            conexion.outputStream.use { it.write(cuerpo.toString().toByteArray()) }

            val codigo = conexion.responseCode
            val flujo = if (codigo < HttpURLConnection.HTTP_BAD_REQUEST) {
                conexion.inputStream
            } else {
                conexion.errorStream
            }
            return RespuestaHttp(codigo, flujo?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            conexion.disconnect()
        }
    }

    companion object {
        const val TIMEOUT_MS = 10_000
    }
}
