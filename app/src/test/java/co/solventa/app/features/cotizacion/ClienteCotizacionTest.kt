package co.solventa.app.features.cotizacion

import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.net.InetSocketAddress

class ClienteCotizacionTest {

    private val solicitud = SolicitudCotizacion(
        montoCredito = 200_000_000,
        plazoAnios = 20,
        ciudadInmueble = " Bogotá ",
        entidadAcreedora = "Banco de Ejemplo",
        clienteId = "9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e",
    )

    private val cotizada = """
        {"cotizacionId":"8f1e2d3c-4b5a-4c6d-9e7f-0a1b2c3d4e5f","ramo":"VIDA_HIPOTECARIO",
         "datosBien":{"montoCredito":200000000,"plazoAnios":20,"ciudadInmueble":"Bogotá","entidadAcreedora":"Banco de Ejemplo"},
         "clienteId":"9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e","prima":70000,"moneda":"COP","periodicidad":"MENSUAL",
         "fechaCotizacion":"2026-10-15T15:00:00Z","vigenteHasta":"2026-10-22T15:00:00Z","estado":"VIGENTE",
         "calculadaSinPerfil":false}
    """.trimIndent()

    private var servidor: HttpServer? = null

    /** BFF móvil de prueba en un puerto libre de localhost; guarda la última petición recibida. */
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
    fun `envía el ramo y los datos del bien al BFF y devuelve la cotización del núcleo`() {
        val recibido = mutableMapOf<String, String>()
        val url = bffFalso(201, cotizada, recibido)

        val resultado = ClienteCotizacion(url).cotizar(solicitud)

        assertEquals(
            ResultadoCotizacion.Cotizada(
                Cotizacion(
                    cotizacionId = "8f1e2d3c-4b5a-4c6d-9e7f-0a1b2c3d4e5f",
                    prima = 70_000,
                    moneda = "COP",
                    periodicidad = "MENSUAL",
                    vigenteHasta = "2026-10-22T15:00:00Z",
                    calculadaSinPerfil = false,
                    motivoSinPerfil = null,
                ),
            ),
            resultado,
        )
        assertEquals("POST", recibido["metodo"])
        assertEquals("/v1/cotizaciones", recibido["ruta"])
        val cuerpo = JSONObject(recibido.getValue("cuerpo"))
        assertEquals("VIDA_HIPOTECARIO", cuerpo.getString("ramo"))
        assertEquals("9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e", cuerpo.getString("clienteId"))
        val bien = cuerpo.getJSONObject("datosBien")
        assertEquals(200_000_000L, bien.getLong("montoCredito"))
        assertEquals(20, bien.getInt("plazoAnios"))
        assertEquals("Bogotá", bien.getString("ciudadInmueble"))
        assertEquals("Banco de Ejemplo", bien.getString("entidadAcreedora"))
    }

    @Test
    fun `sin clienteId no lo envía`() {
        val recibido = mutableMapOf<String, String>()
        val url = bffFalso(201, cotizada, recibido)

        ClienteCotizacion(url).cotizar(solicitud.copy(clienteId = null))

        assertFalse(JSONObject(recibido.getValue("cuerpo")).has("clienteId"))
    }

    @Test
    fun `lee si la prima se calculó sin perfil y el motivo`() {
        val sinPerfil = cotizada
            .replace("\"calculadaSinPerfil\":false", "\"calculadaSinPerfil\":true,\"motivoSinPerfil\":\"PERFIL_NO_DISPONIBLE\"")
        val url = bffFalso(201, sinPerfil, mutableMapOf())

        val resultado = ClienteCotizacion(url).cotizar(solicitud) as ResultadoCotizacion.Cotizada

        assertEquals(true, resultado.cotizacion.calculadaSinPerfil)
        assertEquals("PERFIL_NO_DISPONIBLE", resultado.cotizacion.motivoSinPerfil)
    }

    @Test
    fun `un 422 del núcleo señala los campos del bien que rechazó`() {
        val url = bffFalso(
            422,
            """{"codigo":"DATOS_BIEN_INVALIDOS","detalle":"x","campos":["montoCredito","plazoAnios","otro"]}""",
            mutableMapOf(),
        )

        assertEquals(
            ResultadoCotizacion.Invalida(setOf(CampoCotizacion.MONTO, CampoCotizacion.PLAZO)),
            ClienteCotizacion(url).cotizar(solicitud),
        )
    }

    @Test
    fun `un 422 del BFF por cuerpo mal formado señala el campo de su loc`() {
        val url = bffFalso(
            422,
            """{"detail":[{"loc":["body","datosBien","ciudadInmueble"],"msg":"x","type":"y"}]}""",
            mutableMapOf(),
        )

        assertEquals(
            ResultadoCotizacion.Invalida(setOf(CampoCotizacion.CIUDAD)),
            ClienteCotizacion(url).cotizar(solicitud),
        )
    }

    @Test
    fun `un ramo no soportado es una solicitud inválida sin campos`() {
        val url = bffFalso(422, """{"codigo":"RAMO_NO_SOPORTADO","detalle":"x"}""", mutableMapOf())

        assertEquals(ResultadoCotizacion.Invalida(emptySet()), ClienteCotizacion(url).cotizar(solicitud))
    }

    @Test
    fun `una respuesta distinta de 201 no es una cotización`() {
        val url = bffFalso(503, """{"codigo":"NUCLEO_NO_DISPONIBLE","detalle":"x"}""", mutableMapOf())

        assertEquals(ResultadoCotizacion.NoDisponible, ClienteCotizacion(url).cotizar(solicitud))
    }

    @Test
    fun `un 201 mal formado no es una cotización`() {
        val url = bffFalso(201, "no es json", mutableMapOf())

        assertEquals(ResultadoCotizacion.NoDisponible, ClienteCotizacion(url).cotizar(solicitud))
    }

    @Test
    fun `sin servidor es sin conexión`() {
        val url = bffFalso(201, "{}", mutableMapOf())
        servidor?.stop(0)

        assertEquals(ResultadoCotizacion.SinConexion, ClienteCotizacion(url, timeoutMs = 1_000).cotizar(solicitud))
    }
}
