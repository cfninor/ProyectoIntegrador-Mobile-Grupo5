package co.solventa.app.features.pruebavida

import co.solventa.app.core.red.ClienteHttp
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/** Gesto que el núcleo sortea al iniciar el intento (D4). */
enum class Gesto { PARPADEAR, GIRAR_IZQUIERDA, GIRAR_DERECHA, SONREIR }

/** Lo decide el proveedor KYC; REVISION_MANUAL es el resultado del tercer rechazo (D3). */
enum class ResultadoPruebaVida { APROBADA, RECHAZADA, REVISION_MANUAL }

/** Solo presente cuando el resultado no es APROBADA. */
enum class MotivoPruebaVida { ROSTRO_NO_COINCIDE, SUPLANTACION, GESTO_NO_DETECTADO, CALIDAD_INSUFICIENTE }

/** Resultado de pedir al BFF móvil que inicie un intento de prueba de vida. */
sealed interface ResultadoIniciarPruebaVida {
    /** 200 (ya había uno PENDIENTE) o 201 (nuevo): el intento con su gesto y vencimiento. */
    data class Iniciada(
        val pruebaVidaId: String,
        val gesto: Gesto,
        val intento: Int,
        val intentosRestantes: Int,
    ) : ResultadoIniciarPruebaVida

    /** 404: el cliente no existe. */
    data object ClienteNoEncontrado : ResultadoIniciarPruebaVida

    /** 409: ya agotó los 3 intentos (D3). */
    data object SinIntentos : ResultadoIniciarPruebaVida

    /** 503 u otra respuesta inesperada. */
    data object NoDisponible : ResultadoIniciarPruebaVida

    /** No hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    data object SinConexion : ResultadoIniciarPruebaVida
}

/** Resultado de enviar los cuadros capturados. */
sealed interface ResultadoCapturaPruebaVida {
    /** 200: el proveedor resolvió el intento. */
    data class Resuelta(
        val resultado: ResultadoPruebaVida,
        val motivo: MotivoPruebaVida?,
        val intentosRestantes: Int,
    ) : ResultadoCapturaPruebaVida

    /** 404: el cliente o el intento no existen (o el intento no es de ese cliente). */
    data object RecursoNoEncontrado : ResultadoCapturaPruebaVida

    /** 409: el intento ya se resolvió o venció (INTENTO_CERRADO/INTENTO_VENCIDO); hay que
     * iniciar uno nuevo. */
    data object IntentoNoDisponible : ResultadoCapturaPruebaVida

    /** 503 (núcleo o proveedor KYC caídos), 504 (proveedor sin respuesta) u otra respuesta
     * inesperada. El intento no se consume: se puede reintentar el envío. */
    data object NoDisponible : ResultadoCapturaPruebaVida

    /** No hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    data object SinConexion : ResultadoCapturaPruebaVida
}

/**
 * Cliente de las dos rutas de prueba de vida del BFF móvil (SOLVENTAG5-90): iniciar un intento y
 * enviar sus capturas. Ningún cuadro se guarda en el dispositivo (D2): vive en memoria hasta que
 * sale en la petición. Bloquea el hilo: se llama fuera del hilo principal.
 */
class ClientePruebaVida(
    apiBaseUrl: String,
    timeoutMs: Int = TIMEOUT_MS_CAPTURAS,
) {
    private val http = ClienteHttp(apiBaseUrl, timeoutMs)

    fun iniciar(clienteId: String): ResultadoIniciarPruebaVida = try {
        val respuesta = http.postJson(rutaIniciar(clienteId), JSONObject())
        interpretarIniciar(respuesta.codigo, respuesta.cuerpo)
    } catch (_: IOException) {
        ResultadoIniciarPruebaVida.SinConexion
    }

    fun registrarCapturas(
        clienteId: String,
        pruebaVidaId: String,
        cuadros: List<ByteArray>,
    ): ResultadoCapturaPruebaVida = try {
        val respuesta = http.postMultipartJpegs(rutaCapturas(clienteId, pruebaVidaId), CAMPO_CUADROS, cuadros)
        interpretarCapturas(respuesta.codigo, respuesta.cuerpo)
    } catch (_: IOException) {
        ResultadoCapturaPruebaVida.SinConexion
    }

    companion object {
        /** Más largo que el de `iniciar`: el núcleo puede esperar hasta 25 s al proveedor KYC. */
        const val TIMEOUT_MS_CAPTURAS = 30_000
        private const val CAMPO_CUADROS = "cuadros"

        private fun rutaIniciar(clienteId: String) = "/v1/onboarding/$clienteId/pruebas-vida"

        private fun rutaCapturas(clienteId: String, pruebaVidaId: String) =
            "/v1/onboarding/$clienteId/pruebas-vida/$pruebaVidaId/capturas"

        fun interpretarIniciar(codigo: Int, cuerpo: String): ResultadoIniciarPruebaVida = try {
            when (codigo) {
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED -> {
                    val json = JSONObject(cuerpo)
                    ResultadoIniciarPruebaVida.Iniciada(
                        pruebaVidaId = json.getString("pruebaVidaId"),
                        gesto = Gesto.valueOf(json.getString("gesto")),
                        intento = json.getInt("intento"),
                        intentosRestantes = json.getInt("intentosRestantes"),
                    )
                }
                HttpURLConnection.HTTP_NOT_FOUND -> ResultadoIniciarPruebaVida.ClienteNoEncontrado
                HttpURLConnection.HTTP_CONFLICT -> ResultadoIniciarPruebaVida.SinIntentos
                else -> ResultadoIniciarPruebaVida.NoDisponible
            }
        } catch (_: JSONException) {
            ResultadoIniciarPruebaVida.NoDisponible
        } catch (_: IllegalArgumentException) {
            ResultadoIniciarPruebaVida.NoDisponible
        }

        fun interpretarCapturas(codigo: Int, cuerpo: String): ResultadoCapturaPruebaVida = try {
            when (codigo) {
                HttpURLConnection.HTTP_OK -> {
                    val json = JSONObject(cuerpo)
                    ResultadoCapturaPruebaVida.Resuelta(
                        resultado = ResultadoPruebaVida.valueOf(json.getString("resultado")),
                        motivo = json.optString("motivo").takeIf { it.isNotEmpty() }?.let(MotivoPruebaVida::valueOf),
                        intentosRestantes = json.getInt("intentosRestantes"),
                    )
                }
                HttpURLConnection.HTTP_NOT_FOUND -> ResultadoCapturaPruebaVida.RecursoNoEncontrado
                HttpURLConnection.HTTP_CONFLICT -> ResultadoCapturaPruebaVida.IntentoNoDisponible
                else -> ResultadoCapturaPruebaVida.NoDisponible
            }
        } catch (_: JSONException) {
            ResultadoCapturaPruebaVida.NoDisponible
        } catch (_: IllegalArgumentException) {
            ResultadoCapturaPruebaVida.NoDisponible
        }
    }
}
