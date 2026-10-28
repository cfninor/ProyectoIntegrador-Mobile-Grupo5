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

/** Estado de verificación de identidad del cliente (SOLVENTAG5-91, D1). */
enum class EstadoKyc { PENDIENTE, VERIFICADO, EN_REVISION_MANUAL }

/**
 * A dónde navega la app según `estadoKYC` (D4, R7). El núcleo decide, la app no lo deduce: un
 * valor que todavía no conoce se trata como [ESPERAR_REVISION], nunca como error.
 */
enum class SiguientePaso { PRUEBA_VIDA, COTIZAR, ESPERAR_REVISION }

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

    /** 409 SIN_INTENTOS: ya agotó los 3 intentos, o ya está en revisión manual (D3, R4). */
    data object SinIntentos : ResultadoIniciarPruebaVida

    /** 409 KYC_YA_VERIFICADO: no hace falta repetir la prueba (R4). La app consulta
     * [ClientePruebaVida.consultarOnboarding] para saber a dónde navegar. */
    data object KycYaVerificado : ResultadoIniciarPruebaVida

    /** 503 u otra respuesta inesperada. */
    data object NoDisponible : ResultadoIniciarPruebaVida

    /** No hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    data object SinConexion : ResultadoIniciarPruebaVida
}

/** Resultado de enviar los cuadros capturados. */
sealed interface ResultadoCapturaPruebaVida {
    /** 200: el proveedor resolvió el intento. `siguientePaso` decide a dónde navega la app
     * (R7); un valor desconocido llega aquí ya como [SiguientePaso.ESPERAR_REVISION]. */
    data class Resuelta(
        val resultado: ResultadoPruebaVida,
        val motivo: MotivoPruebaVida?,
        val intentosRestantes: Int,
        val estadoKyc: EstadoKyc,
        val siguientePaso: SiguientePaso,
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

/** Resultado de consultar el estado del onboarding (D6, SOLVENTAG5-91). */
sealed interface ResultadoConsultarOnboarding {
    /** 200: estado actual, para que la app pinte lo mismo si cerró a mitad de camino. */
    data class Obtenido(
        val estadoKyc: EstadoKyc,
        val siguientePaso: SiguientePaso,
        val intentosRestantes: Int,
    ) : ResultadoConsultarOnboarding

    /** 404: el cliente no existe. */
    data object ClienteNoEncontrado : ResultadoConsultarOnboarding

    /** 503 u otra respuesta inesperada. */
    data object NoDisponible : ResultadoConsultarOnboarding

    /** No hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    data object SinConexion : ResultadoConsultarOnboarding
}

/** Resultado de solicitar la revisión manual voluntaria (R6, SOLVENTAG5-91). */
sealed interface ResultadoSolicitarRevisionManual {
    /** 200: aceptada (o ya estaba en revisión: la 91 la responde igual, idempotente). */
    data class Aceptada(val estadoKyc: EstadoKyc, val siguientePaso: SiguientePaso) :
        ResultadoSolicitarRevisionManual

    /** 404: el cliente no existe. */
    data object ClienteNoEncontrado : ResultadoSolicitarRevisionManual

    /** 409 REVISION_MANUAL_NO_DISPONIBLE, 503 u otra respuesta inesperada. */
    data object NoDisponible : ResultadoSolicitarRevisionManual

    /** No hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    data object SinConexion : ResultadoSolicitarRevisionManual
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

    /** D6: la app lo consulta al abrir el resultado y al volver al onboarding, para pintar el
     * mismo estado si cerró a mitad de camino. */
    fun consultarOnboarding(clienteId: String): ResultadoConsultarOnboarding = try {
        val respuesta = http.getJson(rutaOnboarding(clienteId))
        interpretarOnboarding(respuesta.codigo, respuesta.cuerpo)
    } catch (_: IOException) {
        ResultadoConsultarOnboarding.SinConexion
    }

    /** R6: revisión manual voluntaria tras un rechazo con intentos restantes. */
    fun solicitarRevisionManual(clienteId: String): ResultadoSolicitarRevisionManual = try {
        val respuesta = http.postJson(rutaRevisionManual(clienteId), JSONObject())
        interpretarRevisionManual(respuesta.codigo, respuesta.cuerpo)
    } catch (_: IOException) {
        ResultadoSolicitarRevisionManual.SinConexion
    }

    companion object {
        /** Más largo que el de `iniciar`: el núcleo puede esperar hasta 25 s al proveedor KYC. */
        const val TIMEOUT_MS_CAPTURAS = 30_000
        private const val CAMPO_CUADROS = "cuadros"

        private fun rutaIniciar(clienteId: String) = "/v1/onboarding/$clienteId/pruebas-vida"

        private fun rutaCapturas(clienteId: String, pruebaVidaId: String) =
            "/v1/onboarding/$clienteId/pruebas-vida/$pruebaVidaId/capturas"

        private fun rutaOnboarding(clienteId: String) = "/v1/onboarding/$clienteId"

        private fun rutaRevisionManual(clienteId: String) = "/v1/onboarding/$clienteId/revision-manual"

        /** R7: un valor que la app no conoce todavía se trata como ESPERAR_REVISION, nunca
         * como error (a diferencia de `Gesto`/`ResultadoPruebaVida`/`MotivoPruebaVida`, que si
         * no coinciden hacen fallar la respuesta entera: ver los `catch` de más abajo). */
        private fun siguientePasoDe(valor: String): SiguientePaso =
            SiguientePaso.entries.find { it.name == valor } ?: SiguientePaso.ESPERAR_REVISION

        /** Mismo criterio que [siguientePasoDe], solo para el esquema de "capturas", donde
         * `estadoKYC` también es opcional (D7). */
        private fun estadoKycDe(valor: String): EstadoKyc =
            EstadoKyc.entries.find { it.name == valor } ?: EstadoKyc.PENDIENTE

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
                HttpURLConnection.HTTP_CONFLICT -> when (JSONObject(cuerpo).optString("codigo")) {
                    "KYC_YA_VERIFICADO" -> ResultadoIniciarPruebaVida.KycYaVerificado
                    else -> ResultadoIniciarPruebaVida.SinIntentos
                }
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
                        // Opcionales en el esquema de "capturas" (D7 de la SOLVENTAG5-91): si
                        // faltaran, no se trata como una respuesta inválida.
                        estadoKyc = estadoKycDe(json.optString("estadoKYC")),
                        siguientePaso = siguientePasoDe(json.optString("siguientePaso")),
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

        fun interpretarOnboarding(codigo: Int, cuerpo: String): ResultadoConsultarOnboarding = try {
            when (codigo) {
                HttpURLConnection.HTTP_OK -> {
                    val json = JSONObject(cuerpo)
                    ResultadoConsultarOnboarding.Obtenido(
                        estadoKyc = EstadoKyc.valueOf(json.getString("estadoKYC")),
                        siguientePaso = siguientePasoDe(json.getString("siguientePaso")),
                        intentosRestantes = json.getInt("intentosRestantes"),
                    )
                }
                HttpURLConnection.HTTP_NOT_FOUND -> ResultadoConsultarOnboarding.ClienteNoEncontrado
                else -> ResultadoConsultarOnboarding.NoDisponible
            }
        } catch (_: JSONException) {
            ResultadoConsultarOnboarding.NoDisponible
        } catch (_: IllegalArgumentException) {
            ResultadoConsultarOnboarding.NoDisponible
        }

        fun interpretarRevisionManual(codigo: Int, cuerpo: String): ResultadoSolicitarRevisionManual = try {
            when (codigo) {
                HttpURLConnection.HTTP_OK -> {
                    val json = JSONObject(cuerpo)
                    ResultadoSolicitarRevisionManual.Aceptada(
                        estadoKyc = EstadoKyc.valueOf(json.getString("estadoKYC")),
                        siguientePaso = siguientePasoDe(json.getString("siguientePaso")),
                    )
                }
                HttpURLConnection.HTTP_NOT_FOUND -> ResultadoSolicitarRevisionManual.ClienteNoEncontrado
                else -> ResultadoSolicitarRevisionManual.NoDisponible
            }
        } catch (_: JSONException) {
            ResultadoSolicitarRevisionManual.NoDisponible
        } catch (_: IllegalArgumentException) {
            ResultadoSolicitarRevisionManual.NoDisponible
        }
    }
}
