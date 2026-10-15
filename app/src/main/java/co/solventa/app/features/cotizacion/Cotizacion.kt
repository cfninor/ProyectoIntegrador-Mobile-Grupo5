package co.solventa.app.features.cotizacion

/** Ramos del catálogo v1 (`Ramo` del contrato del BFF móvil). */
enum class Ramo { VIDA_HIPOTECARIO }

/**
 * Solicitud de cotización del seguro de vida hipotecario (SOLVENTAG5-95). El [clienteId] es el del
 * onboarding; sin él, el núcleo cotiza con el nivel de riesgo por defecto.
 */
data class SolicitudCotizacion(
    val montoCredito: Long,
    val plazoAnios: Int,
    val ciudadInmueble: String,
    val entidadAcreedora: String,
    val clienteId: String? = null,
    val ramo: Ramo = Ramo.VIDA_HIPOTECARIO,
)

/** Cotización que calcula el núcleo; la app solo la muestra (`Cotizacion` del contrato). */
data class Cotizacion(
    val cotizacionId: String,
    val prima: Long,
    val moneda: String,
    val periodicidad: String,
    val vigenteHasta: String,
    val calculadaSinPerfil: Boolean,
    val motivoSinPerfil: String?,
)
