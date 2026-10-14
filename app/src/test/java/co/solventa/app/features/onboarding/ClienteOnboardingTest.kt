package co.solventa.app.features.onboarding

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetSocketAddress

class ClienteOnboardingTest {

    private val datos = DatosBasicos(
        tipoDocumento = TipoDocumento.CC,
        numeroDocumento = " 1020304050 ",
        nombres = "María Fernanda",
        apellidos = "López Rojas",
        correo = "maria.lopez@correo.com",
        celular = "3001234567",
    )

    private var servidor: HttpServer? = null

    /** BFF móvil falso en un puerto libre de localhost; guarda la última petición recibida. */
    private fun bffFalso(codigo: Int, respuesta: String, recibido: MutableMap<String, String>): String {
        val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        http.createContext("/") { intercambio ->
            recibido["metodo"] = intercambio.requestMethod
            recibido["ruta"] = intercambio.requestURI.path
            recibido["cuerpo"] = intercambio.requestBody.bufferedReader().readText()
            val bytes = respuesta.toByteArray()
            intercambio.sendResponseHeaders(codigo, bytes.size.toLong().coerceAtLeast(1))
            intercambio.responseBody.use { it.write(bytes) }
        }
        http.start()
        servidor = http
        return "http://localhost:${http.address.port}"
    }

    @After
    fun apagar() {
        servidor?.stop(0)
    }

    @Test
    fun `envía los datos limpios al BFF y devuelve el cliente iniciado`() {
        val recibido = mutableMapOf<String, String>()
        val url = bffFalso(
            201,
            """{"clienteId":"9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e","estado":"ONBOARDING_EN_CURSO","fechaInicio":"2026-10-13T14:30:00Z"}""",
            recibido,
        )

        val resultado = ClienteOnboarding(url).iniciar(datos)

        assertEquals(
            ResultadoOnboarding.Iniciado("9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e", "ONBOARDING_EN_CURSO"),
            resultado,
        )
        assertEquals("POST", recibido["metodo"])
        assertEquals("/v1/onboarding", recibido["ruta"])
        val cuerpo = JSONObject(recibido.getValue("cuerpo"))
        assertEquals("CC", cuerpo.getJSONObject("documento").getString("tipo"))
        assertEquals("1020304050", cuerpo.getJSONObject("documento").getString("numero"))
        assertEquals("María Fernanda", cuerpo.getJSONObject("nombre").getString("nombres"))
        assertEquals("3001234567", cuerpo.getJSONObject("contacto").getString("celular"))
    }

    @Test
    fun `un 422 señala los campos que rechazó el BFF`() {
        val url = bffFalso(
            422,
            """{"detail":[
                {"loc":["body","contacto","celular"],"msg":"String should match pattern","type":"string_pattern_mismatch"},
                {"loc":["body","documento"],"msg":"Value error","type":"value_error"},
                {"loc":["body","otro"],"msg":"Extra inputs are not permitted","type":"extra_forbidden"}
            ]}""",
            mutableMapOf(),
        )

        val resultado = ClienteOnboarding(url).iniciar(datos)

        assertEquals(
            ResultadoOnboarding.Invalido(setOf(Campo.CELULAR, Campo.NUMERO_DOCUMENTO)),
            resultado,
        )
    }

    @Test
    fun `un 503 del BFF es servicio no disponible`() {
        val url = bffFalso(
            503,
            """{"codigo":"NUCLEO_NO_DISPONIBLE","detalle":"El servicio no está disponible."}""",
            mutableMapOf(),
        )

        assertEquals(ResultadoOnboarding.NoDisponible, ClienteOnboarding(url).iniciar(datos))
    }

    @Test
    fun `una respuesta 201 mal formada es servicio no disponible`() {
        val url = bffFalso(201, "no es json", mutableMapOf())

        assertEquals(ResultadoOnboarding.NoDisponible, ClienteOnboarding(url).iniciar(datos))
    }

    @Test
    fun `sin servidor es sin conexión`() {
        val url = bffFalso(201, "{}", mutableMapOf())
        servidor?.stop(0)

        assertEquals(ResultadoOnboarding.SinConexion, ClienteOnboarding(url, timeoutMs = 1_000).iniciar(datos))
    }
}
