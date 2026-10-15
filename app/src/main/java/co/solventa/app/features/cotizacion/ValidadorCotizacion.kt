package co.solventa.app.features.cotizacion

/** Lo que el cliente escribe en el formulario, tal cual. */
data class DatosCotizacion(
    val monto: String,
    val plazo: String,
    val ciudad: String,
    val entidad: String,
)

enum class CampoCotizacion { MONTO, PLAZO, CIUDAD, ENTIDAD }

enum class ErrorCotizacion { OBLIGATORIO, MONTO_FUERA_DE_RANGO, PLAZO_FUERA_DE_RANGO, CIUDAD_INVALIDA, ENTIDAD_INVALIDA }

/**
 * Validación de los campos obligatorios de la cotización (SOLVENTAG5-95, criterio 4).
 *
 * Usa los rangos de `DatosBienVidaHipotecario` del contrato del BFF móvil, que publica el núcleo
 * para que la pantalla valide antes de enviar. Las reglas del ramo las sigue aplicando el núcleo
 * (responde 422 `DATOS_BIEN_INVALIDOS`); aquí solo se evita enviar lo que ya sabemos inválido.
 */
object ValidadorCotizacion {

    val MONTO = 10_000_000L..1_200_000_000L
    val PLAZO = 5..30
    private val CIUDAD = 2..80
    private val ENTIDAD = 2..120

    /** El error de cada campo inválido; un mapa vacío significa que no hay errores. */
    fun validar(datos: DatosCotizacion): Map<CampoCotizacion, ErrorCotizacion> = buildMap {
        errorNumero(datos.monto, MONTO, ErrorCotizacion.MONTO_FUERA_DE_RANGO)
            ?.let { put(CampoCotizacion.MONTO, it) }
        errorNumero(datos.plazo, PLAZO.first.toLong()..PLAZO.last.toLong(), ErrorCotizacion.PLAZO_FUERA_DE_RANGO)
            ?.let { put(CampoCotizacion.PLAZO, it) }
        errorTexto(datos.ciudad, CIUDAD, ErrorCotizacion.CIUDAD_INVALIDA)
            ?.let { put(CampoCotizacion.CIUDAD, it) }
        errorTexto(datos.entidad, ENTIDAD, ErrorCotizacion.ENTIDAD_INVALIDA)
            ?.let { put(CampoCotizacion.ENTIDAD, it) }
    }

    /** La solicitud para el BFF, o `null` si algún campo es inválido. */
    fun solicitud(datos: DatosCotizacion, clienteId: String?): SolicitudCotizacion? {
        if (validar(datos).isNotEmpty()) return null
        return SolicitudCotizacion(
            montoCredito = datos.monto.trim().toLong(),
            plazoAnios = datos.plazo.trim().toInt(),
            ciudadInmueble = datos.ciudad.trim(),
            entidadAcreedora = datos.entidad.trim(),
            clienteId = clienteId,
        )
    }

    private fun errorNumero(texto: String, rango: LongRange, fueraDeRango: ErrorCotizacion): ErrorCotizacion? {
        val valor = texto.trim()
        if (valor.isEmpty()) return ErrorCotizacion.OBLIGATORIO
        val numero = valor.toLongOrNull() ?: return fueraDeRango
        return if (numero in rango) null else fueraDeRango
    }

    private fun errorTexto(texto: String, largo: IntRange, invalido: ErrorCotizacion): ErrorCotizacion? {
        val valor = texto.trim()
        return when {
            valor.isEmpty() -> ErrorCotizacion.OBLIGATORIO
            valor.length !in largo -> invalido
            else -> null
        }
    }
}
