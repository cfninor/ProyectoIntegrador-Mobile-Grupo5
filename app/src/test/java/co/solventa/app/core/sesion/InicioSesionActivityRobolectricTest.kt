package co.solventa.app.core.sesion

import android.app.Activity
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.app.R
import co.solventa.app.core.config.AppConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es")
class InicioSesionActivityRobolectricTest {

    private val intentos = mutableListOf<String>()

    private fun cognito(resultado: ResultadoInicioSesion) = object : ClienteCognito("us-east-1", "c") {
        override fun iniciarSesion(correo: String, contrasena: String): ResultadoInicioSesion {
            intentos += correo
            return resultado
        }
    }

    @After
    fun limpiar() {
        SesionCliente.cerrar()
        SesionCliente.cognito = null
    }

    private fun InicioSesionActivity.escribir(correo: String, contrasena: String) {
        findViewById<EditText>(R.id.correo).setText(correo)
        findViewById<EditText>(R.id.contrasena).setText(contrasena)
    }

    private fun InicioSesionActivity.entrarYEsperar(listo: () -> Boolean) {
        findViewById<View>(R.id.entrar).performClick()
        val limite = System.currentTimeMillis() + 5_000
        while (!listo() && System.currentTimeMillis() < limite) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun InicioSesionActivity.error() = findViewById<TextView>(R.id.mensajeError)

    @Test
    fun `iniciar sesión se habilita solo con un correo válido y una contraseña`() {
        ActivityScenario.launch(InicioSesionActivity::class.java).use { escenario ->
            escenario.onActivity { actividad ->
                val entrar = actividad.findViewById<View>(R.id.entrar)
                assertFalse(entrar.isEnabled)
                actividad.escribir("no-es-correo", "Secreta-123")
                assertFalse(entrar.isEnabled)
                actividad.escribir("maria.lopez@correo.com", "")
                assertFalse(entrar.isEnabled)
                actividad.escribir("maria.lopez@correo.com", "Secreta-123")
                assertTrue(entrar.isEnabled)
            }
        }
    }

    @Test
    fun `al iniciar sesión guarda la sesión y vuelve con RESULT_OK`() {
        SesionCliente.cognito = cognito(ResultadoInicioSesion.Iniciada(Tokens("id", "refresco", Long.MAX_VALUE)))
        ActivityScenario.launch(InicioSesionActivity::class.java).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.escribir("maria.lopez@correo.com", "Secreta-123")
                actividad.entrarYEsperar { actividad.isFinishing }

                assertTrue(actividad.isFinishing)
                assertEquals(Activity.RESULT_OK, shadowOf(actividad).resultCode)
            }
        }
        assertTrue(SesionCliente.iniciada)
        assertEquals(listOf("maria.lopez@correo.com"), intentos)
    }

    @Test
    fun `con credenciales inválidas muestra un mensaje que no revela si la cuenta existe`() {
        SesionCliente.cognito = cognito(ResultadoInicioSesion.CredencialesInvalidas)
        ActivityScenario.launch(InicioSesionActivity::class.java).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.escribir("maria.lopez@correo.com", "mala")
                actividad.entrarYEsperar { actividad.error().visibility == View.VISIBLE }

                assertEquals("El correo o la contraseña no son correctos.", actividad.error().text.toString())
                assertEquals("", actividad.findViewById<EditText>(R.id.contrasena).text.toString())
                assertFalse(actividad.isFinishing)
            }
        }
        assertFalse(SesionCliente.iniciada)
    }

    @Test
    fun `los demás errores tienen su mensaje`() {
        val casos = mapOf(
            ResultadoInicioSesion.CuentaSinConfirmar to "Confirma tu correo desde el portal de Solventa antes de iniciar sesión.",
            ResultadoInicioSesion.NoDisponible to "No pudimos iniciar sesión. Intenta de nuevo en unos minutos.",
            ResultadoInicioSesion.SinConexion to "No pudimos conectarnos. Revisa tu conexión e intenta de nuevo.",
        )
        casos.forEach { (resultado, mensaje) ->
            SesionCliente.cognito = cognito(resultado)
            ActivityScenario.launch(InicioSesionActivity::class.java).use { escenario ->
                escenario.onActivity { actividad ->
                    actividad.escribir("maria.lopez@correo.com", "Secreta-123")
                    actividad.entrarYEsperar { actividad.error().visibility == View.VISIBLE }

                    assertEquals(mensaje, actividad.error().text.toString())
                }
            }
        }
    }

    @Test
    fun `configurar prepara Cognito solo si el ambiente pide sesión`() {
        InicioSesionActivity.configurar(AppConfig("dev", "http://bff"))
        assertNull(SesionCliente.cognito)

        InicioSesionActivity.configurar(AppConfig("dev", "http://bff", "us-east-1", "cliente-movil"))
        assertTrue(SesionCliente.cognito != null)
    }
}
