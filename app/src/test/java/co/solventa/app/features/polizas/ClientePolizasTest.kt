package co.solventa.app.features.polizas

import co.solventa.app.features.polizas.PolizasPrueba.POLIZA_ID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientePolizasTest {

    private val bff = BffPrueba()

    @After
    fun apagar() = bff.close()

    private fun cliente(token: String? = "id-token") = ClientePolizas(bff.url, timeoutMs = 1_000, token = { token })

    @Test
    fun `lista las pólizas con el token de la sesión`() {
        bff.json("/v1/polizas", 200, "[${PolizasPrueba.emitida},${PolizasPrueba.paraFirma}]")

        val resultado = cliente().listar() as ResultadoPolizas.Listadas

        assertEquals(listOf("SV-VH-2026-000123", null), resultado.polizas.map { it.numero })
        assertEquals(listOf("Bearer id-token"), bff.autorizaciones)
    }

    @Test
    fun `sin sesión no envía la cabecera de autorización`() {
        bff.json("/v1/polizas", 200, "[]")

        assertEquals(ResultadoPolizas.Listadas(emptyList()), cliente(token = null).listar())
        assertEquals(listOf<String?>(null), bff.autorizaciones)
    }

    @Test
    fun `interpreta el 401, el 503 y un cuerpo ilegible`() {
        assertEquals(ResultadoPolizas.NoAutenticado, ClientePolizas.interpretarLista(401, "{}"))
        assertEquals(ResultadoPolizas.NoDisponible, ClientePolizas.interpretarLista(503, "{}"))
        assertEquals(ResultadoPolizas.NoDisponible, ClientePolizas.interpretarLista(200, "no es json"))
        assertEquals(ResultadoPolizas.NoDisponible, ClientePolizas.interpretarLista(200, "[{\"polizaId\":\"x\"}]"))
    }

    @Test
    fun `obtiene una póliza y distingue la que no existe o es ajena`() {
        bff.json("/v1/polizas/$POLIZA_ID", 200, PolizasPrueba.emitida)

        val encontrada = cliente().obtener(POLIZA_ID) as ResultadoPoliza.Encontrada

        assertEquals("Banco de Ejemplo", encontrada.poliza.beneficiario)
        assertEquals(ResultadoPoliza.NoEncontrada, ClientePolizas.interpretarPoliza(404, "{}"))
        assertEquals(ResultadoPoliza.NoAutenticado, ClientePolizas.interpretarPoliza(401, "{}"))
        assertEquals(ResultadoPoliza.NoDisponible, ClientePolizas.interpretarPoliza(503, "{}"))
        assertEquals(ResultadoPoliza.NoDisponible, ClientePolizas.interpretarPoliza(200, "["))
    }

    @Test
    fun `descarga el PDF de la póliza`() {
        val pdf = "%PDF-1.7".toByteArray()
        bff.respuestas["/v1/polizas/$POLIZA_ID/documento"] = BffPrueba.Respuesta(200, pdf, "application/pdf")

        val resultado = cliente().descargar(POLIZA_ID) as ResultadoDocumento.Descargado

        assertArrayEquals(pdf, resultado.pdf)
        assertEquals(listOf("/v1/polizas/$POLIZA_ID/documento"), bff.rutas)
    }

    @Test
    fun `el documento en generación, el 401 y otros errores`() {
        val ruta = "/v1/polizas/$POLIZA_ID/documento"
        bff.json(ruta, 409, "{}")
        assertEquals(ResultadoDocumento.NoDisponibleAun, cliente().descargar(POLIZA_ID))
        bff.json(ruta, 401, "{}")
        assertEquals(ResultadoDocumento.NoAutenticado, cliente().descargar(POLIZA_ID))
        bff.json(ruta, 503, "{}")
        assertEquals(ResultadoDocumento.NoDisponible, cliente().descargar(POLIZA_ID))
    }

    @Test
    fun `sin respuesta del BFF es sin conexión`() {
        val apagado = ClientePolizas("http://localhost:1", timeoutMs = 500)

        assertEquals(ResultadoPolizas.SinConexion, apagado.listar())
        assertEquals(ResultadoPoliza.SinConexion, apagado.obtener(POLIZA_ID))
        assertEquals(ResultadoDocumento.SinConexion, apagado.descargar(POLIZA_ID))
    }

    @Test
    fun `lee la póliza emitida con su certificado y su pago`() {
        val poliza = PolizasPrueba.poliza()

        assertEquals(EstadoPoliza.VIGENTE, poliza.estado)
        assertEquals("CC ••••4050", poliza.tomador.documentoEnmascarado)
        assertEquals(ProximoCobro("2026-12-11", 70_000, "COP"), poliza.proximoCobro)
        assertEquals(
            listOf(PagoPoliza("2026-11-11T19:50:00Z", 70_000, "COP", "VISA •••• 4242", "PAGO-7781")),
            poliza.pagos,
        )
        assertEquals("FIRMA-00042", poliza.certificado?.referencia)
    }

    @Test
    fun `una póliza para firma no trae número, pagos, certificado ni beneficiario`() {
        val poliza = PolizasPrueba.poliza(PolizasPrueba.paraFirma)

        assertNull(poliza.numero)
        assertTrue(poliza.pagos.isEmpty())
        assertNull(poliza.certificado)
        assertNull(poliza.beneficiario)
        assertNull(poliza.proximoCobro)
    }

    @Test
    fun `el historial de pagos del BFF reemplaza al comprobante y un estado nuevo no rompe la app`() {
        val json = JSONObject(PolizasPrueba.emitida)
            .put("estado", "SUSPENDIDA")
            .put(
                "pagos",
                org.json.JSONArray().put(
                    JSONObject().put("fecha", "2026-12-11T15:00:00Z").put("monto", 70000).put("moneda", "COP"),
                ),
            )

        val poliza = LectorPoliza.poliza(json)

        assertEquals(EstadoPoliza.DESCONOCIDO, poliza.estado)
        assertEquals(listOf(PagoPoliza("2026-12-11T15:00:00Z", 70_000, "COP", "", null)), poliza.pagos)
    }
}
