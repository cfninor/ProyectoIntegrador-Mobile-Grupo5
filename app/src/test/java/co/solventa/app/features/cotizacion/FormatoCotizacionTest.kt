package co.solventa.app.features.cotizacion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FormatoCotizacionTest {

    @Test
    fun `muestra la prima en pesos colombianos sin decimales`() {
        val texto = FormatoCotizacion.pesos(70_000)

        assertTrue(texto, texto.contains("70.000"))
        assertTrue(texto, texto.contains("$"))
        assertTrue(texto, !texto.contains(","))
    }

    @Test
    fun `separa los miles con punto`() {
        assertTrue(FormatoCotizacion.pesos(1_234_567).contains("1.234.567"))
    }

    @Test
    fun `muestra la vigencia en la zona de Colombia`() {
        assertEquals("22 de octubre de 2026", FormatoCotizacion.fecha("2026-10-22T15:00:00Z"))
        // 02:00 UTC del 23 todavía es el 22 en Bogotá (UTC-5).
        assertEquals("22 de octubre de 2026", FormatoCotizacion.fecha("2026-10-23T02:00:00Z"))
    }

    @Test
    fun `una fecha que no es ISO se muestra tal cual`() {
        assertEquals("pronto", FormatoCotizacion.fecha("pronto"))
    }

    @Test
    fun `en inglés usa las convenciones del idioma`() {
        val ingles = FormatoCotizacion.localeDe(Locale.ENGLISH)

        assertTrue(FormatoCotizacion.pesos(70_000, locale = ingles), FormatoCotizacion.pesos(70_000, locale = ingles).contains("70,000"))
        assertEquals("October 22, 2026", FormatoCotizacion.fecha("2026-10-22T15:00:00Z", ingles))
    }

    @Test
    fun `cualquier español se formatea como en Colombia`() {
        assertEquals(FormatoCotizacion.COLOMBIA, FormatoCotizacion.localeDe(Locale.forLanguageTag("es-ES")))
    }
}
