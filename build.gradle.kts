// Los plugins se declaran aquí sin aplicarse y cada módulo los aplica.
// Con AGP 9 el soporte de Kotlin viene integrado: no se aplica org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application) apply false
}
