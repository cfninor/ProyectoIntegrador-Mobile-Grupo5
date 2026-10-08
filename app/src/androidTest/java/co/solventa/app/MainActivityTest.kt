package co.solventa.app

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val regla = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun muestraLaMarcaYElPropositoDeLaApp() {
        onView(withText(R.string.marca)).check(matches(isDisplayed()))
        onView(withText(R.string.inicio_subtitulo)).check(matches(isDisplayed()))
    }
}
