package co.solventa.app.features.polizas

import org.json.JSONArray
import org.json.JSONObject

/** Estado de la póliza en el núcleo. [DESCONOCIDO] para un valor que esta versión de la app no conoce. */
enum class EstadoPoliza { PENDIENTE_FIRMA, VIGENTE, CANCELADA, VENCIDA, DESCONOCIDO }

/**
 * El certificado de la firma electrónica (SOLVENTAG5-57): no es un PDF, son los datos con que el
 * proveedor certificó la firma, como los muestra el portal web.
 */
data class CertificadoFirma(val firmadaEn: String, val huella: String, val referencia: String)

data class TomadorPoliza(
    val nombres: String,
    val apellidos: String,
    /** Como lo entrega el núcleo, por ejemplo «CC ••••4821»: la app nunca ve el documento completo. */
    val documentoEnmascarado: String,
)

/** Un pago de la prima, del más reciente al más antiguo. */
data class PagoPoliza(
    val fecha: String,
    val monto: Long,
    val moneda: String,
    /** Por ejemplo «VISA •••• 4242»; vacío si el pago no trae la tarjeta. */
    val medio: String,
    val referencia: String?,
)

data class ProximoCobro(val fecha: String, val valor: Long, val moneda: String)

/**
 * La póliza del cliente con la forma del esquema `Poliza` del núcleo (SOLVENTAG5-56 y 58). El
 * [beneficiario] es la entidad acreedora del crédito, que el núcleo lee de la solicitud.
 */
data class Poliza(
    val polizaId: String,
    /** «SV-VH-2026-000123»; null si todavía no se ha emitido. */
    val numero: String?,
    val ramo: String,
    val estado: EstadoPoliza,
    val vigenciaDesde: String,
    val vigenciaHasta: String,
    val sumaAsegurada: Long,
    val prima: Long,
    val moneda: String,
    val periodicidad: String,
    val tomador: TomadorPoliza,
    val beneficiario: String?,
    val proximoCobro: ProximoCobro?,
    val pagos: List<PagoPoliza>,
    /** Solo en la póliza emitida (`documentos.certificadoFirma`). */
    val certificado: CertificadoFirma?,
)

/** Lee las pólizas del cuerpo JSON del BFF móvil. Lanza `JSONException` si falta un campo obligatorio. */
object LectorPoliza {

    fun lista(json: JSONArray): List<Poliza> = (0 until json.length()).map { poliza(json.getJSONObject(it)) }

    fun poliza(json: JSONObject): Poliza {
        val tomador = json.getJSONObject("tomador")
        return Poliza(
            polizaId = json.getString("polizaId"),
            numero = json.texto("numeroPoliza"),
            ramo = json.getString("ramo"),
            estado = estado(json.getString("estado")),
            vigenciaDesde = json.getString("vigenciaDesde"),
            vigenciaHasta = json.getString("vigenciaHasta"),
            sumaAsegurada = json.getLong("sumaAsegurada"),
            prima = json.getLong("prima"),
            moneda = json.getString("moneda"),
            periodicidad = json.getString("periodicidad"),
            tomador = TomadorPoliza(
                nombres = tomador.getString("nombres"),
                apellidos = tomador.getString("apellidos"),
                documentoEnmascarado = tomador.getString("documentoEnmascarado"),
            ),
            beneficiario = json.texto("beneficiario"),
            proximoCobro = json.optJSONObject("proximoCobro")?.let {
                ProximoCobro(it.getString("fecha"), it.getLong("valor"), it.getString("moneda"))
            },
            pagos = pagos(json),
            certificado = json.optJSONObject("documentos")?.optJSONObject("certificadoFirma")?.let {
                CertificadoFirma(it.getString("firmadaEn"), it.getString("huella"), it.getString("referencia"))
            },
        )
    }

    private fun estado(valor: String): EstadoPoliza =
        EstadoPoliza.entries.firstOrNull { it.name == valor } ?: EstadoPoliza.DESCONOCIDO

    /**
     * El historial `pagos` si el BFF lo envía; si no, el comprobante de la primera prima que trae la
     * póliza emitida (`documentos.comprobantePago`). Sin ninguno de los dos, una lista vacía.
     */
    private fun pagos(json: JSONObject): List<PagoPoliza> {
        json.optJSONArray("pagos")?.let { pagos ->
            return (0 until pagos.length()).map { pago(pagos.getJSONObject(it), "fecha") }
        }
        val comprobante = json.optJSONObject("documentos")?.optJSONObject("comprobantePago")
            ?: return emptyList()
        return listOf(pago(comprobante, "aprobadoEn"))
    }

    private fun pago(json: JSONObject, campoFecha: String): PagoPoliza {
        val marca = json.texto("marca")
        val ultimos4 = json.texto("ultimos4")
        return PagoPoliza(
            fecha = json.getString(campoFecha),
            monto = json.getLong("monto"),
            moneda = json.getString("moneda"),
            medio = if (marca != null && ultimos4 != null) "$marca •••• $ultimos4" else "",
            referencia = json.texto("referencia"),
        )
    }

    /** El texto del campo, o null si no viene o viene vacío (el núcleo omite los opcionales). */
    private fun JSONObject.texto(campo: String): String? =
        if (isNull(campo)) null else optString(campo).takeIf { it.isNotEmpty() }
}
