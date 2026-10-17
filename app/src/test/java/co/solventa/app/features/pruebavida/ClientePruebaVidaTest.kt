package co.solventa.app.features.pruebavida

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class ClientePruebaVidaTest {

    private var servidor: HttpServer? = null

    /** BFF móvil falso en un puerto libre de localhost; guarda la última petición recibida. */
    private fun bffFalso(codigo: Int, respuesta: String, recibido: MutableMap<String, String>): String {
        val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        http.createContext("/") { intercambio ->
            recibido["metodo"] = intercambio.requestMethod
            recibido["ruta"] = intercambio.requestURI.path
            recibido["contentType"] = intercambio.requestHeaders.getFirst("Content-Type").orEmpty()
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

    // --- iniciar ---------------------------------------------------------------------------

    @Test
    fun `201 crea el intento con su gesto y vencimiento`() {
        val recibido = mutableMapOf<String, String>()
        val url = bffFalso(
            201,
            """{"pruebaVidaId":"4c6d8e1a-2b3f-4a5c-9d7e-1f2a3b4c5d6e","gesto":"PARPADEAR","intento":1,"intentosRestantes":2,"expiraEn":"2026-10-09T14:32:00Z"}""",
            recibido,
        )

        val resultado = ClientePruebaVida(url).iniciar("9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e")

        assertEquals(
            ResultadoIniciarPruebaVida.Iniciada("4c6d8e1a-2b3f-4a5c-9d7e-1f2a3b4c5d6e", Gesto.PARPADEAR, 1, 2),
            resultado,
        )
        assertEquals("POST", recibido["metodo"])
        assertEquals("/v1/onboarding/9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e/pruebas-vida", recibido["ruta"])
    }

    @Test
    fun `200 devuelve el intento PENDIENTE que ya existía`() {
        val url = bffFalso(
            200,
            """{"pruebaVidaId":"4c6d8e1a-2b3f-4a5c-9d7e-1f2a3b4c5d6e","gesto":"SONREIR","intento":1,"intentosRestantes":2,"expiraEn":"2026-10-09T14:32:00Z"}""",
            mutableMapOf(),
        )

        val resultado = ClientePruebaVida(url).iniciar("clienteId")

        assertEquals(
            ResultadoIniciarPruebaVida.Iniciada("4c6d8e1a-2b3f-4a5c-9d7e-1f2a3b4c5d6e", Gesto.SONREIR, 1, 2),
            resultado,
        )
    }

    @Test
    fun `404 es cliente no encontrado`() {
        val url = bffFalso(404, """{"codigo":"CLIENTE_NO_ENCONTRADO","detalle":"x"}""", mutableMapOf())

        assertEquals(ResultadoIniciarPruebaVida.ClienteNoEncontrado, ClientePruebaVida(url).iniciar("x"))
    }

    @Test
    fun `409 es sin intentos`() {
        val url = bffFalso(409, """{"codigo":"SIN_INTENTOS","detalle":"x"}""", mutableMapOf())

        assertEquals(ResultadoIniciarPruebaVida.SinIntentos, ClientePruebaVida(url).iniciar("x"))
    }

    @Test
    fun `503 es no disponible`() {
        val url = bffFalso(503, """{"codigo":"NUCLEO_NO_DISPONIBLE","detalle":"x"}""", mutableMapOf())

        assertEquals(ResultadoIniciarPruebaVida.NoDisponible, ClientePruebaVida(url).iniciar("x"))
    }

    @Test
    fun `un 201 con un gesto desconocido es no disponible`() {
        val url = bffFalso(
            201,
            """{"pruebaVidaId":"x","gesto":"NO_EXISTE","intento":1,"intentosRestantes":2,"expiraEn":"x"}""",
            mutableMapOf(),
        )

        assertEquals(ResultadoIniciarPruebaVida.NoDisponible, ClientePruebaVida(url).iniciar("x"))
    }

    @Test
    fun `sin servidor iniciar es sin conexión`() {
        val url = bffFalso(201, "{}", mutableMapOf())
        servidor?.stop(0)

        assertEquals(ResultadoIniciarPruebaVida.SinConexion, ClientePruebaVida(url, timeoutMs = 1_000).iniciar("x"))
    }

    // --- registrarCapturas ------------------------------------------------------------------

    private val cuadros = listOf("foto1".toByteArray(), "foto2".toByteArray(), "foto3".toByteArray())

    @Test
    fun `envía los cuadros como multipart y devuelve la aprobación`() {
        val recibido = mutableMapOf<String, String>()
        val url = bffFalso(200, """{"resultado":"APROBADA","intento":1,"intentosRestantes":2}""", recibido)

        val resultado = ClientePruebaVida(url).registrarCapturas("clienteId", "pruebaVidaId", cuadros)

        assertEquals(ResultadoCapturaPruebaVida.Resuelta(ResultadoPruebaVida.APROBADA, null, 2), resultado)
        assertEquals("POST", recibido["metodo"])
        assertEquals("/v1/onboarding/clienteId/pruebas-vida/pruebaVidaId/capturas", recibido["ruta"])
        assertTrue(recibido.getValue("contentType").startsWith("multipart/form-data"))
        val cuerpo = recibido.getValue("cuerpo")
        assertEquals(3, Regex("name=\"cuadros\"").findAll(cuerpo).count())
        assertTrue(cuerpo.contains("foto1"))
        assertTrue(cuerpo.contains("Content-Type: image/jpeg"))
    }

    @Test
    fun `rechazada trae su motivo y los intentos restantes`() {
        val url = bffFalso(
            200,
            """{"resultado":"RECHAZADA","motivo":"ROSTRO_NO_COINCIDE","intento":1,"intentosRestantes":2}""",
            mutableMapOf(),
        )

        val resultado = ClientePruebaVida(url).registrarCapturas("c", "p", cuadros)

        assertEquals(
            ResultadoCapturaPruebaVida.Resuelta(ResultadoPruebaVida.RECHAZADA, MotivoPruebaVida.ROSTRO_NO_COINCIDE, 2),
            resultado,
        )
    }

    @Test
    fun `el tercer rechazo pasa a revisión manual sin intentos restantes`() {
        val url = bffFalso(
            200,
            """{"resultado":"REVISION_MANUAL","motivo":"GESTO_NO_DETECTADO","intento":3,"intentosRestantes":0}""",
            mutableMapOf(),
        )

        val resultado = ClientePruebaVida(url).registrarCapturas("c", "p", cuadros)

        assertEquals(
            ResultadoCapturaPruebaVida.Resuelta(ResultadoPruebaVida.REVISION_MANUAL, MotivoPruebaVida.GESTO_NO_DETECTADO, 0),
            resultado,
        )
    }

    @Test
    fun `404 es recurso no encontrado`() {
        val url = bffFalso(404, """{"codigo":"PRUEBA_VIDA_NO_ENCONTRADA","detalle":"x"}""", mutableMapOf())

        assertEquals(ResultadoCapturaPruebaVida.RecursoNoEncontrado, ClientePruebaVida(url).registrarCapturas("c", "p", cuadros))
    }

    @Test
    fun `409 es intento no disponible`() {
        val url = bffFalso(409, """{"codigo":"INTENTO_VENCIDO","detalle":"x"}""", mutableMapOf())

        assertEquals(ResultadoCapturaPruebaVida.IntentoNoDisponible, ClientePruebaVida(url).registrarCapturas("c", "p", cuadros))
    }

    @Test
    fun `503 del núcleo o del proveedor KYC es no disponible`() {
        val url = bffFalso(503, """{"codigo":"PROVEEDOR_KYC_NO_DISPONIBLE","detalle":"x"}""", mutableMapOf())

        assertEquals(ResultadoCapturaPruebaVida.NoDisponible, ClientePruebaVida(url).registrarCapturas("c", "p", cuadros))
    }

    @Test
    fun `504 el proveedor sin respuesta también es no disponible`() {
        val url = bffFalso(504, """{"codigo":"PROVEEDOR_KYC_SIN_RESPUESTA","detalle":"x"}""", mutableMapOf())

        assertEquals(ResultadoCapturaPruebaVida.NoDisponible, ClientePruebaVida(url).registrarCapturas("c", "p", cuadros))
    }

    @Test
    fun `sin servidor registrarCapturas es sin conexión`() {
        val url = bffFalso(200, "{}", mutableMapOf())
        servidor?.stop(0)

        val resultado = ClientePruebaVida(url, timeoutMs = 1_000).registrarCapturas("c", "p", cuadros)

        assertEquals(ResultadoCapturaPruebaVida.SinConexion, resultado)
    }
}
