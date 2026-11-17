package co.solventa.app.features.contratacion

import co.solventa.app.core.red.RespuestaHttp
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/** Cómo la app lee las respuestas de contratación del BFF móvil (contrato 1.2.0). */
class ClienteContratacionTest {

    private val solicitudAjustada = """
        {"solicitudId":"s-1","cotizacionId":"c-1","clienteId":"k-1","estado":"AJUSTADA",
         "cobertura":{},"prima":70000,"moneda":"COP","periodicidad":"MENSUAL","canal":"movil",
         "aceptadaEn":"2026-11-17T10:00:00Z","decision":{"resultado":"AJUSTADA","primaFinal":91000}}
    """.trimIndent()

    private val poliza = """
        {"polizaId":"p-1","estado":"PENDIENTE_FIRMA","prima":70000,"moneda":"COP",
         "documento":{"estado":"GENERADO"},"numeroPoliza":null}
    """.trimIndent()

    private fun <T> leer(codigo: Int, cuerpo: String, lector: (org.json.JSONObject) -> T) =
        ClienteContratacion.interpretar(RespuestaHttp(codigo, cuerpo), lector)

    @Test
    fun `lee la solicitud ajustada con la nueva prima`() {
        val solicitud = (leer(201, solicitudAjustada, LectorContratacion::solicitud) as Respuesta.Ok).valor

        assertEquals(EstadoSolicitud.AJUSTADA, solicitud.estado)
        assertEquals(91_000L, solicitud.primaFinal)
        assertFalse(solicitud.primaAjustadaAceptada)
    }

    @Test
    fun `una solicitud sin decisión no trae nueva prima, y un estado nuevo es desconocido`() {
        val cuerpo = """{"solicitudId":"s","estado":"OTRO","prima":1,"moneda":"COP","primaAjustadaAceptadaEn":"x"}"""

        val solicitud = (leer(200, cuerpo, LectorContratacion::solicitud) as Respuesta.Ok).valor

        assertEquals(EstadoSolicitud.DESCONOCIDO, solicitud.estado)
        assertNull(solicitud.primaFinal)
        assertTrue(solicitud.primaAjustadaAceptada)
    }

    @Test
    fun `lee la póliza en trámite, con el documento generado y sin número`() {
        val leida = (leer(200, poliza, LectorContratacion::poliza) as Respuesta.Ok).valor

        assertTrue(leida.documentoGenerado)
        assertFalse(leida.vigente)
        assertNull(leida.numero)
    }

    @Test
    fun `lee la póliza emitida con su número`() {
        val cuerpo = """{"polizaId":"p","estado":"VIGENTE","prima":1,"moneda":"COP","numeroPoliza":"SV-1"}"""

        val leida = (leer(201, cuerpo, LectorContratacion::poliza) as Respuesta.Ok).valor

        assertTrue(leida.vigente)
        assertFalse(leida.documentoGenerado)
        assertEquals("SV-1", leida.numero)
    }

    @Test
    fun `lee el pago, la firma y el documento a firmar`() {
        val pago = (leer(201, """{"estado":"APROBADO","marca":"VISA","ultimos4":"4242"}""", LectorContratacion::pago) as Respuesta.Ok).valor
        val sinTarjeta = (leer(200, """{"estado":"PROCESANDO","marca":null}""", LectorContratacion::pago) as Respuesta.Ok).valor
        val firma = (leer(200, """{"firmaId":"f","estado":"ABIERTA","venceEn":"v"}""", LectorContratacion::firma) as Respuesta.Ok).valor
        val documento = """
            {"huella":"h","tomador":{"nombres":"Ana","apellidos":"Ruiz","documentoEnmascarado":"CC ••••1"},
             "sumaAsegurada":2,"prima":1,"moneda":"COP","vigenciaDesde":"a","vigenciaHasta":"b",
             "condiciones":{"version":"VH-1","coberturas":["c1","c2"],"exclusiones":["e1"]}}
        """.trimIndent()
        val leido = (leer(200, documento, LectorContratacion::documento) as Respuesta.Ok).valor

        assertEquals(Pago(EstadoPago.APROBADO, "VISA", "4242"), pago)
        assertEquals(Pago(EstadoPago.PROCESANDO, null, null), sinTarjeta)
        assertEquals(Firma("f", EstadoFirma.ABIERTA, "v"), firma)
        assertEquals("Ana Ruiz · CC ••••1", leido.tomador)
        assertEquals(listOf("c1", "c2"), leido.coberturas)
        assertEquals("VH-1", leido.versionCondiciones)
    }

    @Test
    fun `404, 409 y 422 son rechazos con su código, motivo, intentos y siguiente paso`() {
        val kyc = leer(409, """{"codigo":"KYC_NO_VERIFICADO","detalle":"d","estadoKYC":"PENDIENTE","siguientePaso":"PRUEBA_VIDA"}""", LectorContratacion::firma)
        val invalido = leer(409, """{"codigo":"CODIGO_INVALIDO","detalle":"d","intentosRestantes":2}""", LectorContratacion::firma)
        val motivo = leer(409, """{"codigo":"SOLICITUD_NO_EMITIBLE","detalle":"d","motivo":"EN_REVISION"}""", LectorContratacion::firma)
        val noEncontrada = leer(404, """{"codigo":"POLIZA_NO_ENCONTRADA","detalle":"d"}""", LectorContratacion::firma)
        val validacion = leer(422, """{"detail":[]}""", LectorContratacion::firma)

        assertEquals(Respuesta.Rechazo("KYC_NO_VERIFICADO", siguientePaso = "PRUEBA_VIDA"), kyc)
        assertEquals(Respuesta.Rechazo("CODIGO_INVALIDO", intentosRestantes = 2), invalido)
        assertEquals(Respuesta.Rechazo("SOLICITUD_NO_EMITIBLE", motivo = "EN_REVISION"), motivo)
        assertEquals(Respuesta.Rechazo("POLIZA_NO_ENCONTRADA"), noEncontrada)
        assertEquals(Respuesta.Rechazo(ClienteContratacion.VALIDACION), validacion)
    }

    @Test
    fun `401 pide la sesión, y 503, otro código o un cuerpo ilegible es no disponible`() {
        assertEquals(Respuesta.NoAutenticado, leer(401, "", LectorContratacion::firma))
        assertEquals(Respuesta.NoDisponible, leer(503, "{}", LectorContratacion::firma))
        assertEquals(Respuesta.NoDisponible, leer(400, "{}", LectorContratacion::firma))
        assertEquals(Respuesta.NoDisponible, leer(200, "no es json", LectorContratacion::firma))
        assertEquals(Respuesta.NoDisponible, leer(200, "{}", LectorContratacion::firma))
    }

    @Test
    fun `sin red, cada llamada responde sin conexión`() {
        // Un puerto local cerrado: la conexión se rechaza de inmediato.
        val cliente = ClienteContratacion("http://127.0.0.1:9", timeoutMs = 500)

        assertEquals(Respuesta.SinConexion, cliente.solicitud("s"))
        assertEquals(Respuesta.SinConexion, cliente.aceptarOferta("c"))
        assertEquals(Respuesta.SinConexion, cliente.cobrar("p", "tok-1", "clave"))
    }

    @Test
    fun `sin la pasarela, la tarjeta no se tokeniza`() {
        assertNull(ClientePasarela("http://127.0.0.1:9", timeoutMs = 500).tokenizar("4242424242424242", "12/30"))
    }

    /** Un BFF y una pasarela de prueba que anotan método, ruta, cabeceras y cuerpo de cada petición. */
    private class ServidorPrueba(private val responder: (String) -> Pair<Int, String>) : AutoCloseable {
        data class Peticion(val metodo: String, val ruta: String, val autorizacion: String?, val clave: String?, val cuerpo: String)

        val peticiones = mutableListOf<Peticion>()
        private val http = HttpServer.create(InetSocketAddress("localhost", 0), 0).apply {
            createContext("/") { intercambio ->
                val ruta = intercambio.requestURI.path
                peticiones += Peticion(
                    intercambio.requestMethod,
                    ruta,
                    intercambio.requestHeaders.getFirst("Authorization"),
                    intercambio.requestHeaders.getFirst("Idempotency-Key"),
                    intercambio.requestBody.bufferedReader().use { it.readText() },
                )
                val (codigo, cuerpo) = responder(ruta)
                val bytes = cuerpo.toByteArray()
                intercambio.responseHeaders.add("Content-Type", "application/json")
                intercambio.sendResponseHeaders(codigo, bytes.size.toLong().coerceAtLeast(1))
                intercambio.responseBody.use { it.write(bytes) }
            }
            start()
        }

        val url: String get() = "http://localhost:${http.address.port}"

        override fun close() = http.stop(0)
    }

    @Test
    fun `cada operación va a su ruta con el token, la clave del cobro y su cuerpo`() {
        val solicitud = """{"solicitudId":"s","estado":"ACEPTADA","prima":1,"moneda":"COP"}"""
        val polizaJson = """{"polizaId":"p","estado":"PENDIENTE_FIRMA","prima":1,"moneda":"COP"}"""
        val firma = """{"firmaId":"f","estado":"ENVIADA","venceEn":"v"}"""
        val documento = """{"huella":"h","tomador":{"nombres":"A","apellidos":"B","documentoEnmascarado":"C"},
            "sumaAsegurada":1,"prima":1,"moneda":"COP","vigenciaDesde":"a","vigenciaHasta":"b",
            "condiciones":{"version":"v","coberturas":[],"exclusiones":[]}}"""
        ServidorPrueba { ruta ->
            200 to when {
                ruta.startsWith("/v1/solicitudes-suscripcion") && !ruta.endsWith("/poliza") -> solicitud
                ruta.endsWith("/cobro") -> """{"estado":"APROBADO"}"""
                ruta.endsWith("/documento") -> documento
                ruta.contains("/firma") -> firma
                else -> polizaJson
            }
        }.use { bff ->
            val cliente = ClienteContratacion(bff.url, timeoutMs = 1_000, token = { "id-token" })

            assertTrue(cliente.aceptarOferta("c") is Respuesta.Ok)
            assertTrue(cliente.solicitud("s") is Respuesta.Ok)
            assertTrue(cliente.aceptarPrimaAjustada("s") is Respuesta.Ok)
            assertTrue(cliente.generarPoliza("s") is Respuesta.Ok)
            assertTrue(cliente.poliza("p") is Respuesta.Ok)
            assertTrue(cliente.cobrar("p", "tok-1", "clave-1") is Respuesta.Ok)
            assertTrue(cliente.pedirFirma("p") is Respuesta.Ok)
            assertTrue(cliente.reenviarFirma("p") is Respuesta.Ok)
            assertTrue(cliente.documentoFirma("p") is Respuesta.Ok)
            assertTrue(cliente.firmar("p", "123456") is Respuesta.Ok)
            assertTrue(cliente.emitir("p") is Respuesta.Ok)

            assertEquals(
                listOf(
                    "POST /v1/solicitudes-suscripcion",
                    "GET /v1/solicitudes-suscripcion/s",
                    "POST /v1/solicitudes-suscripcion/s/aceptacion-prima",
                    "POST /v1/solicitudes-suscripcion/s/poliza",
                    "GET /v1/polizas/p",
                    "POST /v1/polizas/p/cobro",
                    "POST /v1/polizas/p/firma",
                    "POST /v1/polizas/p/firma/reenvio",
                    "GET /v1/polizas/p/firma/documento",
                    "POST /v1/polizas/p/firma/validacion",
                    "POST /v1/polizas/p/emision",
                ),
                bff.peticiones.map { "${it.metodo} ${it.ruta}" },
            )
            assertTrue(bff.peticiones.all { it.autorizacion == "Bearer id-token" })
            assertEquals("c", JSONObject(bff.peticiones[0].cuerpo).getString("cotizacionId"))
            val cobro = bff.peticiones[5]
            assertEquals("clave-1", cobro.clave)
            assertEquals("TARJETA", JSONObject(cobro.cuerpo).getString("medioPago"))
            assertEquals("tok-1", JSONObject(cobro.cuerpo).getString("token"))
            assertEquals("123456", JSONObject(bff.peticiones[9].cuerpo).getString("codigo"))
            assertTrue(bff.peticiones.filterIndexed { i, _ -> i != 5 }.all { it.clave == null })
        }
    }

    @Test
    fun `tokeniza la tarjeta con la pasarela, sin el token de la sesión`() {
        ServidorPrueba { 200 to """{"token":"tok-9","marca":"VISA","ultimos4":"4242","vencimiento":"12/30"}""" }.use { pasarela ->
            assertEquals("tok-9", ClientePasarela(pasarela.url, timeoutMs = 1_000).tokenizar("4242424242424242", "12/30"))

            val peticion = pasarela.peticiones.single()
            assertEquals("POST /tokenizaciones", "${peticion.metodo} ${peticion.ruta}")
            assertNull(peticion.autorizacion)
            assertEquals("12/30", JSONObject(peticion.cuerpo).getString("vencimiento"))
        }
    }

    @Test
    fun `si la pasarela rechaza la tarjeta o responde sin token, no hay token`() {
        ServidorPrueba { 422 to """{"codigo":"X"}""" }.use { pasarela ->
            assertNull(ClientePasarela(pasarela.url, timeoutMs = 1_000).tokenizar("1", "13/99"))
        }
        ServidorPrueba { 200 to "no es json" }.use { pasarela ->
            assertNull(ClientePasarela(pasarela.url, timeoutMs = 1_000).tokenizar("4242424242424242", "12/30"))
        }
    }
}
