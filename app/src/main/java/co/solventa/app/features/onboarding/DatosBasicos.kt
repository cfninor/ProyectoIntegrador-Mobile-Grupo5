package co.solventa.app.features.onboarding

/** Tipos de documento que acepta el BFF móvil (`TipoDocumento` del contrato). */
enum class TipoDocumento {
    /** Cédula de ciudadanía. */
    CC,

    /** Cédula de extranjería. */
    CE,

    /** Pasaporte. */
    PA,

    /** Permiso por protección temporal. */
    PPT,
}

/** Datos básicos que el cliente ingresa para iniciar el onboarding (SOLVENTAG5-89). */
data class DatosBasicos(
    val tipoDocumento: TipoDocumento?,
    val numeroDocumento: String,
    val nombres: String,
    val apellidos: String,
    val correo: String,
    val celular: String,
) {
    /** Copia sin espacios al inicio y al final, como los recibe el BFF. */
    fun limpios(): DatosBasicos = copy(
        numeroDocumento = numeroDocumento.trim(),
        nombres = nombres.trim(),
        apellidos = apellidos.trim(),
        correo = correo.trim(),
        celular = celular.trim(),
    )
}
