package co.solventa.app.features.contratacion

import android.content.Intent
import android.net.ConnectivityManager
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.app.MainActivity
import co.solventa.app.R
import co.solventa.app.core.sesion.InicioSesionActivity
import co.solventa.app.features.polizas.DetallePolizaActivity
import co.solventa.app.features.polizas.FuentePrueba
import co.solventa.app.features.polizas.PolizasPrueba
import co.solventa.app.features.polizas.ResultadoPoliza
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** La confirmación «¡Tu póliza fue emitida!» en la JVM con Robolectric (SOLVENTAG5-97). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es")
class ConfirmacionActivityRobolectricTest {

    private val fuente = FuentePrueba()

    @Before
    fun preparar() {
        ConfirmacionActivity.fabricaFuente = { fuente }
        ConfirmacionActivity.intervaloMs = 60_000
        InicioSesionActivity.faltaSesion = { false }
    }

    @After
    fun restaurar() {
        ConfirmacionActivity.intervaloMs = 3_000
    }

    private fun ConfirmacionActivity.esperar(hasta: () -> Boolean) {
        val limite = System.currentTimeMillis() + 5_000
        while (!hasta() && System.currentTimeMillis() < limite) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun ConfirmacionActivity.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    private fun ConfirmacionActivity.texto(id: Int) = findViewById<TextView>(id).text.toString()

    private fun abrir(prueba: (ConfirmacionActivity) -> Unit) {
        val intent = ConfirmacionActivity.intent(ApplicationProvider.getApplicationContext(), PolizasPrueba.POLIZA_ID)
        ActivityScenario.launch<ConfirmacionActivity>(intent).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.esperar { actividad.resultado != null }
                prueba(actividad)
            }
        }
    }

    @Test
    fun `criterio 1 - muestra el número, el producto, la cobertura, la prima final y la vigencia`() {
        abrir { actividad ->
            assertTrue(actividad.visible(R.id.contenido))
            assertEquals("SV-VH-2026-000123", actividad.texto(R.id.numero))
            assertTrue(actividad.texto(R.id.producto).isNotBlank())
            assertTrue(actividad.texto(R.id.cobertura), actividad.texto(R.id.cobertura).contains("200.000.000"))
            assertTrue(actividad.texto(R.id.prima), actividad.texto(R.id.prima).contains("70.000"))
            assertTrue(actividad.texto(R.id.prima).endsWith("al mes"))
            assertTrue(actividad.texto(R.id.vigencia).contains("2046"))
            assertEquals("Vigente", actividad.texto(R.id.estado))
            assertFalse(actividad.visible(R.id.avisoSinConexion))
            assertEquals(listOf("obtener ${PolizasPrueba.POLIZA_ID}"), fuente.pedidos)
        }
    }

    @Test
    fun `ver mi póliza abre el detalle e ir al inicio vuelve al tablero`() {
        abrir { actividad ->
            actividad.findViewById<View>(R.id.verPoliza).performClick()
            val detalle = shadowOf(actividad).nextStartedActivity
            assertEquals(DetallePolizaActivity::class.java.name, detalle.component?.className)

            actividad.findViewById<View>(R.id.irAlInicio).performClick()
            val inicio = shadowOf(actividad).nextStartedActivity
            assertEquals(MainActivity::class.java.name, inicio.component?.className)
            assertTrue(inicio.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
            assertTrue(actividad.isFinishing)
        }
    }

    @Test
    fun `criterio 2 - mientras la emisión no termina, un estado intermedio que vuelve a consultar`() {
        ConfirmacionActivity.intervaloMs = 0
        fuente.poliza = ResultadoPoliza.Encontrada(PolizasPrueba.poliza(PolizasPrueba.paraFirma))
        abrir { actividad ->
            assertTrue(actividad.visible(R.id.cargando))
            assertTrue(actividad.texto(R.id.textoCargando).contains("emitiendo"))

            fuente.poliza = ResultadoPoliza.Encontrada(PolizasPrueba.poliza())
            actividad.esperar { actividad.visible(R.id.contenido) }

            assertTrue(actividad.visible(R.id.contenido))
            assertTrue(fuente.pedidos.size >= 2)
        }
    }

    @Test
    fun `criterio 3 - sin red muestra el último estado conocido con el aviso`() {
        fuente.poliza = ResultadoPoliza.DesdeCache(PolizasPrueba.poliza(), "2026-11-16T15:00:00Z")
        abrir { actividad ->
            assertTrue(actividad.visible(R.id.contenido))
            assertTrue(actividad.visible(R.id.avisoSinConexion))
            assertTrue(actividad.texto(R.id.avisoSinConexion).startsWith("Sin conexión"))
            assertEquals("SV-VH-2026-000123", actividad.texto(R.id.numero))
        }
    }

    @Test
    fun `criterio 3 - al volver la red se actualiza`() {
        fuente.poliza = ResultadoPoliza.SinConexion
        abrir { actividad ->
            assertTrue(actividad.visible(R.id.error))
            assertTrue(actividad.texto(R.id.mensajeError).contains("cuando vuelva la red"))

            fuente.poliza = ResultadoPoliza.Encontrada(PolizasPrueba.poliza())
            val red = actividad.getSystemService(ConnectivityManager::class.java)
            val callbacks = shadowOf(red).networkCallbacks
            assertTrue(callbacks.isNotEmpty())
            callbacks.forEach { it.onAvailable(red.activeNetwork ?: return@forEach) }
            actividad.esperar { actividad.visible(R.id.contenido) }

            assertTrue(actividad.visible(R.id.contenido))
            assertFalse(actividad.visible(R.id.avisoSinConexion))
        }
    }

    @Test
    fun `una póliza que no es del titular no se muestra ni se reintenta`() {
        fuente.poliza = ResultadoPoliza.NoEncontrada
        abrir { actividad ->
            assertTrue(actividad.visible(R.id.error))
            assertEquals("No encontramos esta póliza en tu cuenta.", actividad.texto(R.id.mensajeError))
            assertFalse(actividad.visible(R.id.reintentar))
        }
    }

    @Test
    fun `si el servicio no responde, reintentar vuelve a cargar`() {
        fuente.poliza = ResultadoPoliza.NoDisponible
        abrir { actividad ->
            assertTrue(actividad.visible(R.id.reintentar))

            fuente.poliza = ResultadoPoliza.Encontrada(PolizasPrueba.poliza())
            actividad.findViewById<View>(R.id.reintentar).performClick()
            actividad.esperar { actividad.visible(R.id.contenido) }
            assertTrue(actividad.visible(R.id.contenido))
        }
    }

    @Test
    fun `sin sesión pide el inicio de sesión`() {
        fuente.poliza = ResultadoPoliza.NoAutenticado
        abrir { actividad ->
            val siguiente = shadowOf(actividad).nextStartedActivity
            assertEquals(InicioSesionActivity::class.java.name, siguiente.component?.className)
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun `los textos están en inglés`() {
        abrir { actividad ->
            assertEquals("Active", actividad.texto(R.id.estado))
            assertTrue(actividad.texto(R.id.cobertura).endsWith("sum insured"))
        }
    }
}
