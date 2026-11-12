package co.solventa.app.core.sesion

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

/** El cliente de Cognito contra un servidor local que responde como `InitiateAuth`. */
class ClienteCognitoTest {

    private var servidor: HttpServer? = null
    private val recibido = mutableMapOf<String, String>()

    @After
    fun apagar() {
        servidor?.stop(0)
    }

    private fun cognitoFalso(codigo: Int, respuesta: String): ClienteCognito {
        val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        http.createContext("/") { intercambio ->
            recibido["destino"] = intercambio.requestHeaders.getFirst("X-Amz-Target")
            recibido["tipo"] = intercambio.requestHeaders.getFirst("Content-Type")
            recibido["cuerpo"] = intercambio.requestBody.bufferedReader().readText()
            val bytes = respuesta.toByteArray()
            intercambio.sendResponseHeaders(codigo, bytes.size.toLong().coerceAtLeast(1))
            intercambio.responseBody.use { it.write(bytes) }
        }
        http.start()
        servidor = http
        return ClienteCognito(
            region = "us-east-1",
            clienteId = "cliente-movil",
            timeoutMs = 1_000,
            url = "http://localhost:${http.address.port}/",
            reloj = { 1_000_000L },
        )
    }

    private val autenticado = """
        {"AuthenticationResult":{"IdToken":"id-1","AccessToken":"acceso","RefreshToken":"refresco-1",
         "ExpiresIn":900,"TokenType":"Bearer"},"ChallengeParameters":{}}
    """.trimIndent()

    @Test
    fun `inicia sesión con usuario y contraseña del cliente de la app`() {
        val cognito = cognitoFalso(200, autenticado)

        val resultado = cognito.iniciarSesion(" maria.lopez@correo.com ", "Secreta-123")

        assertEquals(ResultadoInicioSesion.Iniciada(Tokens("id-1", "refresco-1", 1_900_000L)), resultado)
        assertEquals("AWSCognitoIdentityProviderService.InitiateAuth", recibido["destino"])
        assertEquals("application/x-amz-json-1.1", recibido["tipo"])
        val cuerpo = JSONObject(recibido.getValue("cuerpo"))
        assertEquals("USER_PASSWORD_AUTH", cuerpo.getString("AuthFlow"))
        assertEquals("cliente-movil", cuerpo.getString("ClientId"))
        assertEquals("maria.lopez@correo.com", cuerpo.getJSONObject("AuthParameters").getString("USERNAME"))
    }

    @Test
    fun `al renovar conserva el token de refresco, que Cognito no devuelve`() {
        val cognito = cognitoFalso(200, """{"AuthenticationResult":{"IdToken":"id-2","ExpiresIn":900}}""")

        val resultado = cognito.renovar("refresco-1")

        assertEquals(ResultadoInicioSesion.Iniciada(Tokens("id-2", "refresco-1", 1_900_000L)), resultado)
        val cuerpo = JSONObject(recibido.getValue("cuerpo"))
        assertEquals("REFRESH_TOKEN_AUTH", cuerpo.getString("AuthFlow"))
        assertEquals("refresco-1", cuerpo.getJSONObject("AuthParameters").getString("REFRESH_TOKEN"))
    }

    @Test
    fun `credenciales inválidas y cuenta inexistente dan el mismo resultado`() {
        val cognito = cognitoFalso(400, """{"__type":"NotAuthorizedException","message":"Incorrect"}""")
        assertEquals(ResultadoInicioSesion.CredencialesInvalidas, cognito.iniciarSesion("a@b.co", "x"))

        assertEquals(
            ResultadoInicioSesion.CredencialesInvalidas,
            cognito.interpretar(400, """{"__type":"com.amazonaws#UserNotFoundException"}""", null),
        )
    }

    @Test
    fun `cuenta sin confirmar, retos, otros errores y cuerpos ilegibles`() {
        val cognito = cognitoFalso(200, "{}")

        assertEquals(
            ResultadoInicioSesion.CuentaSinConfirmar,
            cognito.interpretar(400, """{"__type":"UserNotConfirmedException"}""", null),
        )
        assertEquals(
            ResultadoInicioSesion.NoDisponible,
            cognito.interpretar(200, """{"ChallengeName":"NEW_PASSWORD_REQUIRED","Session":"s"}""", null),
        )
        assertEquals(
            ResultadoInicioSesion.NoDisponible,
            cognito.interpretar(400, """{"__type":"TooManyRequestsException"}""", null),
        )
        assertEquals(ResultadoInicioSesion.NoDisponible, cognito.interpretar(500, "", null))
        assertEquals(ResultadoInicioSesion.NoDisponible, cognito.interpretar(200, "no es json", null))
    }

    @Test
    fun `sin respuesta de Cognito es sin conexión`() {
        val cognito = ClienteCognito("us-east-1", "c", timeoutMs = 500, url = "http://localhost:1/")

        assertEquals(ResultadoInicioSesion.SinConexion, cognito.iniciarSesion("a@b.co", "x"))
    }
}
