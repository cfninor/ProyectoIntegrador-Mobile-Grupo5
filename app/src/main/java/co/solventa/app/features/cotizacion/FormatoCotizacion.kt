package co.solventa.app.features.cotizacion

import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * Cómo se muestran en pantalla los valores de la cotización, según el idioma de la app: en
 * español, con las convenciones de Colombia («$ 70.000», «22 de octubre de 2026»); en otro idioma,
 * con las de ese idioma («COP 70,000», «October 22, 2026»). La hora es siempre la de Colombia.
 */
object FormatoCotizacion {

    val COLOMBIA: Locale = Locale.forLanguageTag("es-CO")
    private val ZONA_COLOMBIA = ZoneId.of("America/Bogota")

    /** El locale para formatear: Colombia si la app está en español; el del idioma, si no. */
    fun localeDe(idioma: Locale): Locale = if (idioma.language == "es") COLOMBIA else idioma

    /** Valor en pesos sin decimales. */
    fun pesos(valor: Long, moneda: String = "COP", locale: Locale = COLOMBIA): String {
        val formato = NumberFormat.getCurrencyInstance(locale)
        formato.currency = Currency.getInstance(moneda)
        formato.maximumFractionDigits = 0
        return formato.format(valor)
    }

    /** Fecha ISO 8601 del núcleo en la zona de Colombia, con el formato largo del idioma. */
    fun fecha(iso: String, locale: Locale = COLOMBIA): String = try {
        OffsetDateTime.parse(iso)
            .atZoneSameInstant(ZONA_COLOMBIA)
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
    } catch (_: DateTimeParseException) {
        iso
    }
}
