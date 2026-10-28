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
    fun getJson(ruta: String): RespuestaHttp {
        val conexion = URI("$apiBaseUrl$ruta").toURL().openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "GET"
            conexion.connectTimeout = timeoutMs
            conexion.readTimeout = timeoutMs
            conexion.setRequestProperty("Accept", TIPO_JSON)

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

    /** Lanza [IOException] si no hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    @Throws(IOException::class)
    fun postJson(ruta: String, cuerpo: JSONObject): RespuestaHttp {
        val conexion = URI("$apiBaseUrl$ruta").toURL().openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = timeoutMs
            conexion.readTimeout = timeoutMs
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "$TIPO_JSON; charset=utf-8")
            conexion.setRequestProperty("Accept", TIPO_JSON)
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

    /**
     * Envía [archivos] como un `multipart/form-data` con varias partes del mismo [campo], cada
     * una JPEG. Lanza [IOException] si no hubo respuesta.
     */
    @Throws(IOException::class)
    fun postMultipartJpegs(ruta: String, campo: String, archivos: List<ByteArray>): RespuestaHttp {
        val limite = "SolventaLimite${System.currentTimeMillis()}"
        val conexion = URI("$apiBaseUrl$ruta").toURL().openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = timeoutMs
            conexion.readTimeout = timeoutMs
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "multipart/form-data; boundary=$limite")
            conexion.setRequestProperty("Accept", TIPO_JSON)
            conexion.outputStream.use { salida ->
                archivos.forEachIndexed { indice, archivo ->
                    salida.write("--$limite\r\n".toByteArray())
                    salida.write(
                        "Content-Disposition: form-data; name=\"$campo\"; filename=\"$campo$indice.jpg\"\r\n"
                            .toByteArray(),
                    )
                    salida.write("Content-Type: image/jpeg\r\n\r\n".toByteArray())
                    salida.write(archivo)
                    salida.write("\r\n".toByteArray())
                }
                salida.write("--$limite--\r\n".toByteArray())
            }

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
        private const val TIPO_JSON = "application/json"
    }
}
