package co.solventa.app.core.sesion

/**
 * La sesión del cliente mientras la app está abierta (SOLVENTAG5-104). Solo en memoria: los tokens
 * no se escriben en disco ni salen en respaldos (caso 6.4). Al cerrar la app, el cliente vuelve a
 * iniciar sesión, como en el portal.
 */
object SesionCliente {

    /** Se renueva un minuto antes de vencer, para que el BFF no reciba un token ya vencido. */
    private const val MARGEN_MS = 60_000L

    @Volatile
    private var tokens: Tokens? = null

    /** El cliente de Cognito del ambiente; las pruebas lo reemplazan. */
    @Volatile
    internal var cognito: ClienteCognito? = null

    @Volatile
    internal var reloj: () -> Long = System::currentTimeMillis

    val iniciada: Boolean get() = tokens != null

    fun guardar(nuevos: Tokens) {
        tokens = nuevos
    }

    fun cerrar() {
        tokens = null
    }

    /**
     * El ID token vigente, renovado si está por vencer. Null si no hay sesión o si Cognito ya no
     * la renueva (refresco vencido o revocado); entonces la sesión se cierra y la app vuelve a
     * pedir el inicio de sesión. Bloquea
     * el hilo si renueva: se llama fuera del hilo principal.
     */
    @Synchronized
    fun idToken(): String? {
        val actuales = tokens ?: return null
        if (reloj() < actuales.expiraEnMs - MARGEN_MS) return actuales.idToken
        return when (val renovada = cognito?.renovar(actuales.refreshToken)) {
            is ResultadoInicioSesion.Iniciada -> {
                tokens = renovada.tokens
                renovada.tokens.idToken
            }
            // Sin red no se cierra la sesión: la petición al BFF tampoco saldrá y la pantalla
            // dirá que no hay conexión; al volver la red, se renueva.
            ResultadoInicioSesion.SinConexion -> actuales.idToken
            else -> {
                tokens = null
                null
            }
        }
    }
}
