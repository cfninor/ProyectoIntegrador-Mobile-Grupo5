package co.solventa.app.core.config

import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigTest {

    @Test
    fun `la configuración corresponde a un ambiente conocido`() {
        val config = AppConfig.actual()

        assertTrue(config.ambiente in setOf("dev", "qa", "prod"))
    }

    @Test
    fun `la URL del BFF móvil es absoluta`() {
        val config = AppConfig.actual()

        assertTrue(config.apiBaseUrl.startsWith("http://") || config.apiBaseUrl.startsWith("https://"))
    }
}
