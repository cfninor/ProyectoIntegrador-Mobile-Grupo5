package co.solventa.app.features.polizas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FormatoPolizaTest {

    private val colombia = Locale.forLanguageTag("es-CO")

    @Test
    fun `la vigencia en español y en inglés`() {
        assertEquals(
            "November 11, 2026 – November 11, 2046",
            FormatoPoliza.vigencia("2026-11-11", "2046-11-11", Locale.US),
        )
        assertEquals(
            "11 de noviembre de 2026 – 11 de noviembre de 2046",
            FormatoPoliza.vigencia("2026-11-11", "2046-11-11", colombia),
        )
    }

    @Test
    fun `una fecha y hora se muestra en la zona de Colombia`() {
        // 02:00 UTC del 12 es 21:00 del 11 en Bogotá.
        assertEquals("November 11, 2026", FormatoPoliza.fecha("2026-11-12T02:00:00Z", Locale.US))
        val fechaHora = FormatoPoliza.fechaHora("2026-11-12T02:00:00Z", Locale.US)
        assertTrue(fechaHora, fechaHora.startsWith("November 11, 2026") && fechaHora.contains("9:00"))
    }

    @Test
    fun `un valor que no es fecha se muestra tal cual`() {
        assertEquals("pronto", FormatoPoliza.fecha("pronto", Locale.US))
        assertEquals("pronto", FormatoPoliza.fechaHora("pronto", Locale.US))
    }

    @Test
    fun `los pesos sin decimales`() {
        assertTrue(FormatoPoliza.pesos(200_000_000, "COP", colombia).contains("200.000.000"))
    }
}
