package co.solventa.app.features.cotizacion

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.app.BuildConfig
import co.solventa.app.R
import co.solventa.app.core.red.PermisoRedLocal
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CotizacionActivityTest {

    @get:Rule
    val regla = ActivityScenarioRule(CotizacionActivity::class.java)

    /** En dev con Android 17 la pantalla pediría el permiso de red local con un diálogo del sistema. */
    @Before
    fun concederRedLocal() {
        if (android.os.Build.VERSION.SDK_INT >= ANDROID_17 && BuildConfig.FLAVOR == "dev") {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(BuildConfig.APPLICATION_ID, PermisoRedLocal.PERMISO)
        }
    }

    private fun escribir(campo: Int, valor: String) {
        onView(withId(campo)).perform(scrollTo(), replaceText(valor), closeSoftKeyboard())
    }

    private fun llenarValidos() {
        escribir(R.id.monto, "200000000")
        escribir(R.id.plazo, "20")
        escribir(R.id.ciudad, "Bogotá")
        escribir(R.id.entidad, "Banco de Ejemplo")
    }

    /** Reintenta la verificación hasta [limiteMs]: la respuesta llega desde otro hilo. */
    private fun esperar(limiteMs: Long = 5_000, verificacion: () -> Unit) {
        val fin = System.currentTimeMillis() + limiteMs
        while (true) {
            try {
                verificacion()
                return
            } catch (error: Throwable) {
                if (System.currentTimeMillis() > fin) throw error
                Thread.sleep(100)
            }
        }
    }

    @Test
    fun cotizarEstaDeshabilitadoAlAbrir() {
        onView(withText(R.string.cotizacion_titulo)).check(matches(isDisplayed()))
        onView(withId(R.id.cotizar)).check(matches(isNotEnabled()))
    }

    @Test
    fun cotizarSeHabilitaCuandoLosCamposSonValidos() {
        llenarValidos()

        onView(withId(R.id.cotizar)).perform(scrollTo()).check(matches(isEnabled()))
    }

    @Test
    fun montoYPlazoFueraDeRangoMuestranSusErroresYNoDejanCotizar() {
        llenarValidos()
        escribir(R.id.monto, "5000000")
        escribir(R.id.plazo, "40")

        onView(withId(R.id.campoMonto))
            .check(matches(hasDescendant(withText(R.string.cotizacion_error_monto))))
        onView(withId(R.id.campoPlazo))
            .check(matches(hasDescendant(withText(R.string.cotizacion_error_plazo))))
        onView(withId(R.id.cotizar)).check(matches(isNotEnabled()))
    }

    @Test
    fun sinConexionMuestraElMensajeYDejaReintentar() {
        // Puerto cerrado en el propio dispositivo: la conexión se rechaza de inmediato.
        regla.scenario.onActivity { it.cliente = ClienteCotizacion("http://127.0.0.1:1", timeoutMs = 2_000) }
        llenarValidos()
        onView(withId(R.id.cotizar)).perform(scrollTo(), click())

        esperar {
            onView(withId(R.id.mensajeError)).check(matches(withText(R.string.cotizacion_error_sin_conexion)))
        }
        onView(withId(R.id.resultado)).check(matches(not(isDisplayed())))
        onView(withId(R.id.monto)).check(matches(withText("200000000")))
        onView(withId(R.id.cotizar)).check(matches(isEnabled()))
    }

    private companion object {
        const val ANDROID_17 = 37
    }
}
