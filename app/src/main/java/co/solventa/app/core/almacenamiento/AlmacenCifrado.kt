package co.solventa.app.core.almacenamiento

/**
 * Guarda y lee datos cifrados en el dispositivo, atados a esta app, sin respaldo ni transferencia
 * entre dispositivos (SOLVENTAG5-105, R2). Implementación real en [AlmacenCifradoKeystore].
 */
interface AlmacenCifrado {
    fun guardar(clave: String, datos: String)

    /** Null si no hay nada guardado para [clave] o si no se pudo descifrar (R4). */
    fun leer(clave: String): String?

    fun borrar(clave: String)
}
