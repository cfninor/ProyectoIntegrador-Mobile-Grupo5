package co.solventa.app.features.onboarding

/**
 * Validación de formato de los datos básicos del onboarding (SOLVENTAG5-89, criterio 2).
 *
 * Usa los mismos patrones que el contrato del BFF móvil (`DatosBasicosCliente` en
 * contracts/bff-movil/openapi.yaml del repositorio principal): si cambian allá, cambian aquí.
 * El BFF repite la validación, así que esta solo evita enviar datos que ya sabemos inválidos.
 */
object ValidadorOnboarding {

    private const val LETRAS = "A-Za-zÁÉÍÓÚÜÑáéíóúüñ"
    private val PATRON_NOMBRE = Regex("^[$LETRAS]+([ '\\-][$LETRAS]+)*$")
    private val PATRON_NUMERO_DOCUMENTO = Regex("^[A-Za-z0-9]{6,12}$")
    private val PATRON_NUMERICO = Regex("^[0-9]{6,10}$")
    private val PATRON_CORREO =
        Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}$")
    private val PATRON_CELULAR = Regex("^3[0-9]{9}$")

    private const val NOMBRE_MIN = 2
    private const val NOMBRE_MAX = 60
    private const val CORREO_MAX = 254

    /** Devuelve el error de cada campo inválido; un mapa vacío significa que no hay errores. */
    fun validar(datos: DatosBasicos): Map<Campo, ErrorCampo> = buildMap {
        errorTipoDocumento(datos.tipoDocumento)?.let { put(Campo.TIPO_DOCUMENTO, it) }
        errorNumeroDocumento(datos.tipoDocumento, datos.numeroDocumento)
            ?.let { put(Campo.NUMERO_DOCUMENTO, it) }
        errorNombre(datos.nombres)?.let { put(Campo.NOMBRES, it) }
        errorNombre(datos.apellidos)?.let { put(Campo.APELLIDOS, it) }
        errorCorreo(datos.correo)?.let { put(Campo.CORREO, it) }
        errorCelular(datos.celular)?.let { put(Campo.CELULAR, it) }
    }

    private fun errorTipoDocumento(tipo: TipoDocumento?): ErrorCampo? =
        if (tipo == null) ErrorCampo.OBLIGATORIO else null

    private fun errorNumeroDocumento(tipo: TipoDocumento?, numero: String): ErrorCampo? {
        val valor = numero.trim()
        return when {
            valor.isEmpty() -> ErrorCampo.OBLIGATORIO
            tipo == TipoDocumento.PA ->
                if (PATRON_NUMERO_DOCUMENTO.matches(valor)) null else ErrorCampo.PASAPORTE_INVALIDO
            else -> if (PATRON_NUMERICO.matches(valor)) null else ErrorCampo.DOCUMENTO_INVALIDO
        }
    }

    private fun errorNombre(nombre: String): ErrorCampo? {
        val valor = nombre.trim()
        return when {
            valor.isEmpty() -> ErrorCampo.OBLIGATORIO
            valor.length !in NOMBRE_MIN..NOMBRE_MAX || !PATRON_NOMBRE.matches(valor) ->
                ErrorCampo.NOMBRE_INVALIDO
            else -> null
        }
    }

    private fun errorCorreo(correo: String): ErrorCampo? {
        val valor = correo.trim()
        return when {
            valor.isEmpty() -> ErrorCampo.OBLIGATORIO
            valor.length > CORREO_MAX || !PATRON_CORREO.matches(valor) -> ErrorCampo.CORREO_INVALIDO
            else -> null
        }
    }

    private fun errorCelular(celular: String): ErrorCampo? {
        val valor = celular.trim()
        return when {
            valor.isEmpty() -> ErrorCampo.OBLIGATORIO
            !PATRON_CELULAR.matches(valor) -> ErrorCampo.CELULAR_INVALIDO
            else -> null
        }
    }
}

enum class Campo { TIPO_DOCUMENTO, NUMERO_DOCUMENTO, NOMBRES, APELLIDOS, CORREO, CELULAR }

enum class ErrorCampo {
    OBLIGATORIO,
    DOCUMENTO_INVALIDO,
    PASAPORTE_INVALIDO,
    NOMBRE_INVALIDO,
    CORREO_INVALIDO,
    CELULAR_INVALIDO,
}
