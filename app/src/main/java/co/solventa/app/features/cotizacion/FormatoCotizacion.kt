package co.solventa.app.features.cotizacion

import java.text.NumberFormat
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Currency
import java.util.Locale

/** Cómo se muestran en pantalla los valores de la cotización, en español de Colombia. */
object FormatoCotizacion {

    private val COLOMBIA = Locale.forLanguageTag("es-CO")
    private val ZONA_COLOMBIA = ZoneId.of("America/Bogota")
    private val FECHA = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", COLOMBIA)

    /** Valor en pesos sin decimales, por ejemplo «$ 70.000». */
    fun pesos(valor: Long, moneda: String = "COP"): String {
        val formato = NumberFormat.getCurrencyInstance(COLOMBIA)
        formato.currency = Currency.getInstance(moneda)
        formato.maximumFractionDigits = 0
        return formato.format(valor)
    }

    /** Fecha ISO 8601 del núcleo en la zona de Colombia, por ejemplo «22 de octubre de 2026». */
    fun fecha(iso: String): String = try {
        OffsetDateTime.parse(iso).atZoneSameInstant(ZONA_COLOMBIA).format(FECHA)
    } catch (_: DateTimeParseException) {
        iso
    }
}
