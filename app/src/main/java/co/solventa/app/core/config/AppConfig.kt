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
) {
    companion object {
        fun actual(): AppConfig = AppConfig(
            ambiente = BuildConfig.FLAVOR,
            apiBaseUrl = BuildConfig.API_BASE_URL,
        )
    }
}
