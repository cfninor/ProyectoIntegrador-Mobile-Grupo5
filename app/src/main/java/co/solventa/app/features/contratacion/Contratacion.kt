package co.solventa.app.features.contratacion

import org.json.JSONObject

/** Estado de la solicitud de suscripción; [DESCONOCIDO] para un valor que esta versión no conoce. */
enum class EstadoSolicitud { PENDIENTE_EVALUACION, EVALUADA, ACEPTADA, AJUSTADA, RECHAZADA, EN_REVISION, DESCONOCIDO }

enum class EstadoPago { APROBADO, RECHAZADO, PROCESANDO, REVERSADO, DESCONOCIDO }

enum class EstadoFirma { PENDIENTE_ENVIO, ENVIADA, ABIERTA, FIRMADA, VENCIDA, DESCONOCIDO }

/**
 * La solicitud de suscripción (`SolicitudSuscripcion` del contrato 1.2.0). [primaFinal] es la nueva
 * prima de una solicitud AJUSTADA (`decision.primaFinal`); [primaAjustadaAceptada], si el cliente
 * ya la aceptó.
 */
data class Solicitud(
    val solicitudId: String,
    val estado: EstadoSolicitud,
    val prima: Long,
    val moneda: String,
    val primaFinal: Long?,
    val primaAjustadaAceptada: Boolean,
)

/** Lo que la contratación necesita de la póliza: su estado, el del documento y la prima a cobrar. */
data class PolizaEnTramite(
    val polizaId: String,
    val estado: String,
    val documentoGenerado: Boolean,
    val prima: Long,
    val moneda: String,
    val numero: String?,
) {
    val vigente: Boolean get() = estado == ESTADO_VIGENTE

    companion object {
        const val ESTADO_VIGENTE = "VIGENTE"
    }
}

data class Pago(val estado: EstadoPago, val marca: String?, val ultimos4: String?)

data class Firma(val firmaId: String, val estado: EstadoFirma, val venceEn: String)

/** La vista previa que el cliente revisa antes de firmar (`DocumentoParaFirmar`). */
data class DocumentoFirma(
    val huella: String,
    val tomador: String,
    val sumaAsegurada: Long,
    val prima: Long,
    val moneda: String,
    val vigenciaDesde: String,
    val vigenciaHasta: String,
    val coberturas: List<String>,
    val exclusiones: List<String>,
    val versionCondiciones: String,
)

/** Lee los cuerpos JSON del BFF móvil. Lanza `JSONException` si falta un campo obligatorio. */
object LectorContratacion {

    fun solicitud(json: JSONObject): Solicitud {
        val decision = json.optJSONObject("decision")
        return Solicitud(
            solicitudId = json.getString("solicitudId"),
            estado = enumDe(json.getString("estado"), EstadoSolicitud.DESCONOCIDO),
            prima = json.getLong("prima"),
            moneda = json.getString("moneda"),
            primaFinal = decision?.takeIf { it.has("primaFinal") && !it.isNull("primaFinal") }?.getLong("primaFinal"),
            primaAjustadaAceptada = json.has("primaAjustadaAceptadaEn") && !json.isNull("primaAjustadaAceptadaEn"),
        )
    }

    fun poliza(json: JSONObject): PolizaEnTramite = PolizaEnTramite(
        polizaId = json.getString("polizaId"),
        estado = json.getString("estado"),
        documentoGenerado = json.optJSONObject("documento")?.optString("estado") == "GENERADO",
        prima = json.getLong("prima"),
        moneda = json.getString("moneda"),
        numero = json.optString("numeroPoliza").takeIf { it.isNotEmpty() && !json.isNull("numeroPoliza") },
    )

    fun pago(json: JSONObject): Pago = Pago(
        estado = enumDe(json.getString("estado"), EstadoPago.DESCONOCIDO),
        marca = json.optString("marca").takeIf { it.isNotEmpty() && !json.isNull("marca") },
        ultimos4 = json.optString("ultimos4").takeIf { it.isNotEmpty() && !json.isNull("ultimos4") },
    )

    fun firma(json: JSONObject): Firma = Firma(
        firmaId = json.getString("firmaId"),
        estado = enumDe(json.getString("estado"), EstadoFirma.DESCONOCIDO),
        venceEn = json.getString("venceEn"),
    )

    fun documento(json: JSONObject): DocumentoFirma {
        val tomador = json.getJSONObject("tomador")
        val condiciones = json.getJSONObject("condiciones")
        return DocumentoFirma(
            huella = json.getString("huella"),
            tomador = "${tomador.getString("nombres")} ${tomador.getString("apellidos")} · " +
                tomador.getString("documentoEnmascarado"),
            sumaAsegurada = json.getLong("sumaAsegurada"),
            prima = json.getLong("prima"),
            moneda = json.getString("moneda"),
            vigenciaDesde = json.getString("vigenciaDesde"),
            vigenciaHasta = json.getString("vigenciaHasta"),
            coberturas = textos(condiciones, "coberturas"),
            exclusiones = textos(condiciones, "exclusiones"),
            versionCondiciones = condiciones.getString("version"),
        )
    }

    private fun textos(json: JSONObject, campo: String): List<String> {
        val lista = json.getJSONArray(campo)
        return (0 until lista.length()).map(lista::getString)
    }

    private inline fun <reified E : Enum<E>> enumDe(valor: String, desconocido: E): E =
        enumValues<E>().firstOrNull { it.name == valor } ?: desconocido
}
