package co.solventa.app

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.app.features.cotizacion.CotizacionActivity
import co.solventa.app.features.onboarding.OnboardingActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class MainActivityRobolectricTest {

    @Test
    fun `iniciar onboarding abre la pantalla de onboarding`() {
        ActivityScenario.launch(MainActivity::class.java).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.findViewById<View>(R.id.iniciarOnboarding).performClick()

                val siguiente = shadowOf(actividad).nextStartedActivity
                assertEquals(OnboardingActivity::class.java.name, siguiente.component?.className)
            }
        }
    }

    @Test
    fun `cotizar un seguro abre la pantalla de cotización sin cliente`() {
        ActivityScenario.launch(MainActivity::class.java).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.findViewById<View>(R.id.cotizar).performClick()

                val siguiente = shadowOf(actividad).nextStartedActivity
                assertEquals(CotizacionActivity::class.java.name, siguiente.component?.className)
                assertNull(siguiente.getStringExtra(CotizacionActivity.EXTRA_CLIENTE_ID))
            }
        }
    }
}
