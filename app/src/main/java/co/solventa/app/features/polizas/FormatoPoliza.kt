package co.solventa.app.features.polizas

import co.solventa.app.features.cotizacion.FormatoCotizacion
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Fechas de la póliza en el idioma de la app, con las mismas convenciones de la cotización
 * ([FormatoCotizacion]). Las vigencias son fechas de calendario de Colombia («2026-11-11»); los
 * pagos traen fecha y hora, que se muestran en la zona de Colombia.
 */
object FormatoPoliza {

    private val ZONA_COLOMBIA = ZoneId.of("America/Bogota")

    /** Fecha de calendario con el formato largo del idioma, como la cotización: «11 de noviembre de 2026». */
    fun fecha(iso: String, locale: Locale): String {
        val formato = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale)
        return try {
            LocalDate.parse(iso).format(formato)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(iso).atZoneSameInstant(ZONA_COLOMBIA).format(formato)
            } catch (_: DateTimeParseException) {
                iso
            }
        }
    }

    /** Fecha y hora de un instante en la zona de Colombia: «11 de noviembre de 2026, 3:45 p. m.». */
    fun fechaHora(iso: String, locale: Locale): String = try {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(ZONA_COLOMBIA)
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT).withLocale(locale))
    } catch (_: DateTimeParseException) {
        iso
    }

    /** «11 de noviembre de 2026 – 11 de noviembre de 2046». */
    fun vigencia(desde: String, hasta: String, locale: Locale): String =
        "${fecha(desde, locale)} – ${fecha(hasta, locale)}"

    fun pesos(valor: Long, moneda: String, locale: Locale): String = FormatoCotizacion.pesos(valor, moneda, locale)
}
