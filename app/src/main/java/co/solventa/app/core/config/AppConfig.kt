package co.solventa.app.core.config

import co.solventa.app.BuildConfig

/**
 * Configuración que cambia por ambiente. Viene del flavor con el que se compila la app
 * (dev, qa o prod; ver app/build.gradle.kts).
 */
data class AppConfig(
    val ambiente: String,
    /** URL base del BFF móvil (conector C1). */
    val apiBaseUrl: String,
    val cognitoRegion: String = "",
    /** Id del cliente app-movil en Cognito; vacío si el ambiente no pide inicio de sesión (local). */
    val cognitoClienteMovilId: String = "",
) {
    /** `false` en local: el BFF usa la sesión simulada y la app no pide usuario ni contraseña. */
    val conInicioDeSesion: Boolean get() = cognitoClienteMovilId.isNotBlank()

    companion object {
        fun actual(): AppConfig = AppConfig(
            ambiente = BuildConfig.FLAVOR,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            cognitoRegion = BuildConfig.COGNITO_REGION,
            cognitoClienteMovilId = BuildConfig.COGNITO_CLIENTE_MOVIL_ID,
        )
    }
}
