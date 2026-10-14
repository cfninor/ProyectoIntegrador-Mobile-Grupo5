package co.solventa.app.features.onboarding

import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import co.solventa.app.R
import org.hamcrest.Matchers.equalTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingActivityTest {

    @get:Rule
    val regla = ActivityScenarioRule(OnboardingActivity::class.java)

    private fun texto(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun escribir(campo: Int, valor: String) {
        onView(withId(campo)).perform(scrollTo(), replaceText(valor), closeSoftKeyboard())
    }

    private fun elegirCedula() {
        onView(withId(R.id.tipoDocumento)).perform(scrollTo(), click())
        onData(equalTo(texto(R.string.onboarding_tipo_cc))).inRoot(isPlatformPopup()).perform(click())
    }

    private fun llenarValidos() {
        elegirCedula()
        escribir(R.id.numeroDocumento, "1020304050")
        escribir(R.id.nombres, "María Fernanda")
        escribir(R.id.apellidos, "López Rojas")
        escribir(R.id.correo, "maria.lopez@correo.com")
        escribir(R.id.celular, "3001234567")
    }

    @Test
    fun continuarEstaDeshabilitadoAlAbrir() {
        onView(withText(R.string.onboarding_titulo)).check(matches(isDisplayed()))
        onView(withId(R.id.continuar)).check(matches(isNotEnabled()))
    }

    @Test
    fun continuarSeHabilitaCuandoTodosLosCamposSonValidos() {
        llenarValidos()

        onView(withId(R.id.continuar)).perform(scrollTo()).check(matches(isEnabled()))
    }

    @Test
    fun unCelularInvalidoMuestraSuErrorYNoDejaContinuar() {
        llenarValidos()
        escribir(R.id.celular, "123")

        onView(withId(R.id.campoCelular))
            .check(matches(hasDescendant(withText(R.string.onboarding_error_celular))))
        onView(withId(R.id.continuar)).check(matches(isNotEnabled()))
    }
}
