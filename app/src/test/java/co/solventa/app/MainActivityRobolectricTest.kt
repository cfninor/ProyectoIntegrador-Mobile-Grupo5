package co.solventa.app

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.app.features.onboarding.OnboardingActivity
import org.junit.Assert.assertEquals
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
}
