package co.solventa.app.core.sesion

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Tokens de una sesión de Cognito: el ID token que se envía al BFF y el de refresco. */
data class Tokens(val idToken: String, val refreshToken: String, val expiraEnMs: Long)

/** Resultado de iniciar sesión o de renovarla. */
sealed interface ResultadoInicioSesion {
    data class Iniciada(val tokens: Tokens) : ResultadoInicioSesion

    /**
     * Correo o contraseña incorrectos, o una cuenta que no existe: el mismo mensaje para las
     * dos, sin revelar si el usuario existe (como el portal, SOLVENTAG5-207).
     */
    data object CredencialesInvalidas : ResultadoInicioSesion

    /** La cuenta existe pero no ha confirmado su correo. */
    data object CuentaSinConfirmar : ResultadoInicioSesion

    /** Cognito bloqueó los intentos por un rato, o pide un paso que la app no tiene. */
    data object NoDisponible : ResultadoInicioSesion

    data object SinConexion : ResultadoInicioSesion
}

/**
 * Inicio de sesión del cliente con el API de Cognito (`InitiateAuth`), sin SDK: usuario y
 * contraseña (`USER_PASSWORD_AUTH`) y renovación con el token de refresco. La contraseña va solo a
 * Cognito, por TLS; nunca al BFF ni a disco. Bloquea el hilo: se llama fuera del hilo principal.
 */
open class ClienteCognito(
    region: String,
    private val clienteId: String,
    private val timeoutMs: Int = TIMEOUT_MS,
    /** La URL del API de Cognito; las pruebas la reemplazan por un servidor local. */
    private val url: String = "https://cognito-idp.$region.amazonaws.com/",
    private val reloj: () -> Long = System::currentTimeMillis,
) {

    open fun iniciarSesion(correo: String, contrasena: String): ResultadoInicioSesion = autenticar(
        flujo = "USER_PASSWORD_AUTH",
        parametros = JSONObject().put("USERNAME", correo.trim()).put("PASSWORD", contrasena),
        refreshAnterior = null,
    )

    /** Cognito no devuelve otro token de refresco al renovar: se conserva el mismo. */
    open fun renovar(refreshToken: String): ResultadoInicioSesion = autenticar(
        flujo = "REFRESH_TOKEN_AUTH",
        parametros = JSONObject().put("REFRESH_TOKEN", refreshToken),
        refreshAnterior = refreshToken,
    )

    private fun autenticar(flujo: String, parametros: JSONObject, refreshAnterior: String?): ResultadoInicioSesion {
        val cuerpo = JSONObject()
            .put("AuthFlow", flujo)
            .put("ClientId", clienteId)
            .put("AuthParameters", parametros)
        return try {
            val (codigo, texto) = enviar(cuerpo)
            interpretar(codigo, texto, refreshAnterior)
        } catch (_: IOException) {
            ResultadoInicioSesion.SinConexion
        }
    }

    private fun enviar(cuerpo: JSONObject): Pair<Int, String> {
        val conexion = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = timeoutMs
            conexion.readTimeout = timeoutMs
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/x-amz-json-1.1")
            conexion.setRequestProperty("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
            conexion.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
            val codigo = conexion.responseCode
            val flujo = if (codigo < HttpURLConnection.HTTP_BAD_REQUEST) conexion.inputStream else conexion.errorStream
            return codigo to flujo?.bufferedReader()?.use { it.readText() }.orEmpty()
        } finally {
            conexion.disconnect()
        }
    }

    internal fun interpretar(codigo: Int, texto: String, refreshAnterior: String?): ResultadoInicioSesion = try {
        val json = JSONObject(texto.ifEmpty { "{}" })
        if (codigo == HttpURLConnection.HTTP_OK) {
            val resultado = json.optJSONObject("AuthenticationResult")
                ?: return ResultadoInicioSesion.NoDisponible // un reto (p. ej., cambiar la contraseña)
            ResultadoInicioSesion.Iniciada(
                Tokens(
                    idToken = resultado.getString("IdToken"),
                    refreshToken = resultado.optString("RefreshToken").ifEmpty { refreshAnterior.orEmpty() },
                    expiraEnMs = reloj() + resultado.getLong("ExpiresIn") * MS_POR_SEGUNDO,
                ),
            )
        } else {
            // "__type": "NotAuthorizedException" o "com.amazonaws...#UserNotFoundException".
            when (json.optString("__type").substringAfterLast('#')) {
                "NotAuthorizedException", "UserNotFoundException" -> ResultadoInicioSesion.CredencialesInvalidas
                "UserNotConfirmedException" -> ResultadoInicioSesion.CuentaSinConfirmar
                else -> ResultadoInicioSesion.NoDisponible
            }
        }
    } catch (_: JSONException) {
        ResultadoInicioSesion.NoDisponible
    }

    companion object {
        const val TIMEOUT_MS = 10_000
        private const val MS_POR_SEGUNDO = 1_000L
    }
}
