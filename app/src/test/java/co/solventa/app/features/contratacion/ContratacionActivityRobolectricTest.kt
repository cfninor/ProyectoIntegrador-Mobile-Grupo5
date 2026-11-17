package co.solventa.app.features.contratacion

import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.app.R
import co.solventa.app.core.sesion.InicioSesionActivity
import co.solventa.app.features.onboarding.OnboardingActivity
import com.google.android.material.textfield.TextInputLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** La pantalla «Contratar» en la JVM con Robolectric (SOLVENTAG5-96). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es")
class ContratacionActivityRobolectricTest {

    private val fuente = FuenteContratacionPrueba()
    private var token: String? = "tok-1"

    @Before
    fun preparar() {
        ContratacionActivity.fabricaFlujo = { FlujoContratacion(fuente, { _, _ -> token }) }
        ContratacionActivity.intervaloMs = 60_000
        ContratacionActivity.intervaloRevisionMs = 60_000
        InicioSesionActivity.faltaSesion = { false }
    }

    @After
    fun restaurar() {
        ContratacionActivity.intervaloMs = 3_000
        ContratacionActivity.intervaloRevisionMs = 15_000
    }

    private fun ContratacionActivity.esperar(hasta: () -> Boolean = { paso != null && !visible(R.id.progreso) || paso.esperaAlNucleo() }) {
        val limite = System.currentTimeMillis() + 5_000
        while (!hasta() && System.currentTimeMillis() < limite) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun Paso?.esperaAlNucleo() =
        this is Paso.Evaluando || this is Paso.GenerandoDocumento || this is Paso.PagoProcesando

    private fun ContratacionActivity.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    private fun ContratacionActivity.texto(id: Int) = findViewById<TextView>(id).text.toString()

    private fun ContratacionActivity.escribir(id: Int, valor: String) = findViewById<TextView>(id).setText(valor)

    private fun ContratacionActivity.tocar(id: Int, hasta: (Paso?) -> Boolean) {
        findViewById<View>(id).performClick()
        esperar { hasta(paso) }
    }

    private fun abrir(prueba: (ContratacionActivity) -> Unit) {
        val intent = ContratacionActivity.intent(ApplicationProvider.getApplicationContext(), ContratacionPrueba.COTIZACION_ID)
        ActivityScenario.launch<ContratacionActivity>(intent).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.esperar()
                prueba(actividad)
            }
        }
    }

    @Test
    fun `criterios 1 y 2 - acepta, paga, firma con el código y emite la póliza`() {
        fuente.pedidas.add(Respuesta.Rechazo("PAGO_NO_APROBADO"))
        abrir { actividad ->
            assertTrue(actividad.paso is Paso.Pago)
            assertEquals("Paso 2 de 4", actividad.texto(R.id.numeroPaso))
            assertTrue(actividad.visible(R.id.grupoPago))

            actividad.escribir(R.id.tarjeta, "4242 4242 4242 4242")
            actividad.escribir(R.id.vencimiento, "12/30")
            actividad.tocar(R.id.pagar) { it is Paso.Firma }

            assertEquals("Paso 3 de 4", actividad.texto(R.id.numeroPaso))
            assertTrue(actividad.visible(R.id.grupoFirma))
            assertTrue(actividad.texto(R.id.documento).contains("Fallecimiento del deudor"))
            assertFalse(actividad.findViewById<View>(R.id.firmar).isEnabled)

            actividad.escribir(R.id.codigo, "123456")
            assertTrue(actividad.findViewById<View>(R.id.firmar).isEnabled)
            actividad.tocar(R.id.firmar) { it is Paso.Emitida }

            // SOLVENTAG5-97: emitida, abre la confirmación de la póliza y se cierra.
            val siguiente = shadowOf(actividad).nextStartedActivity
            assertEquals(ConfirmacionActivity::class.java.name, siguiente.component?.className)
            assertEquals(ContratacionPrueba.POLIZA_ID, siguiente.getStringExtra(ConfirmacionActivity.EXTRA_POLIZA_ID))
            assertTrue(actividad.isFinishing)
        }
        assertTrue(fuente.llamadas.containsAll(listOf("aceptarOferta", "generarPoliza", "cobrar tok-1", "firmar 123456", "emitir")))
    }

    @Test
    fun `una tarjeta mal escrita se marca sin llamar a la pasarela ni cobrar`() {
        fuente.pedidas.add(Respuesta.Rechazo("PAGO_NO_APROBADO"))
        abrir { actividad ->
            actividad.escribir(R.id.tarjeta, "123")
            actividad.escribir(R.id.vencimiento, "13/30")
            actividad.findViewById<View>(R.id.pagar).performClick()

            assertNotNull(actividad.findViewById<TextInputLayout>(R.id.campoTarjeta).error)
            assertNotNull(actividad.findViewById<TextInputLayout>(R.id.campoVencimiento).error)
            assertTrue(fuente.claves.isEmpty())
        }
    }

    @Test
    fun `un código errado dice cuántos intentos quedan`() {
        fuente.firmas.add(Respuesta.Rechazo("CODIGO_INVALIDO", intentosRestantes = 2))
        abrir { actividad ->
            actividad.escribir(R.id.codigo, "000000")
            actividad.tocar(R.id.firmar) { it is Paso.Firma && it.aviso != null }

            assertTrue(actividad.visible(R.id.aviso))
            assertEquals("El código no es correcto. Te quedan 2 intentos.", actividad.texto(R.id.aviso))
        }
    }

    @Test
    fun `reenviar el código lo avisa`() {
        abrir { actividad ->
            actividad.tocar(R.id.reenviar) { it is Paso.Firma && it.aviso == Aviso.CODIGO_REENVIADO }

            assertEquals("Te enviamos un código nuevo.", actividad.texto(R.id.aviso))
        }
    }

    @Test
    fun `criterio 5 - en revisión manual es un estado pendiente, no un error`() {
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.EN_REVISION))
        abrir { actividad ->
            assertEquals("Tu solicitud está en revisión", actividad.texto(R.id.estado))
            assertFalse(actividad.visible(R.id.aviso))
            assertEquals("Consultar de nuevo", actividad.texto(R.id.accion))

            fuente.consultas.add(Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.RECHAZADA)))
            actividad.tocar(R.id.accion) { it == Paso.Rechazada }
            assertEquals("No pudimos aprobar tu solicitud", actividad.texto(R.id.estado))
        }
    }

    @Test
    fun `criterio 6 - mientras se evalúa, la pantalla vuelve a consultar sin dejar la petición abierta`() {
        ContratacionActivity.intervaloMs = 0
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.PENDIENTE_EVALUACION))
        fuente.consultas.add(Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.EVALUADA)))
        fuente.consultas.add(Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.EN_REVISION)))
        abrir { actividad ->
            actividad.esperar { actividad.paso is Paso.EnRevision }

            assertTrue(actividad.paso is Paso.EnRevision)
            assertEquals(2, fuente.llamadas.count { it == "solicitud" })
        }
    }

    @Test
    fun `criterio 5 - con la prima ajustada, el cliente la acepta antes de seguir`() {
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.AJUSTADA, 91_000))
        fuente.pedidas.add(Respuesta.Rechazo("PAGO_NO_APROBADO"))
        abrir { actividad ->
            assertEquals("Tu prima cambió", actividad.texto(R.id.estado))
            assertTrue(actividad.texto(R.id.detalle).contains("91.000"))

            actividad.tocar(R.id.accion) { it is Paso.Pago }
            assertTrue("aceptarPrima" in fuente.llamadas)
        }
    }

    @Test
    fun `criterio 4 - sin la identidad verificada lleva a terminar el onboarding`() {
        fuente.aceptacion = Respuesta.Rechazo("KYC_NO_VERIFICADO", siguientePaso = "PRUEBA_VIDA")
        abrir { actividad ->
            assertEquals("Termina la verificación de tu identidad", actividad.texto(R.id.estado))
            assertEquals("Terminar el onboarding", actividad.texto(R.id.accion))

            actividad.findViewById<View>(R.id.accion).performClick()
            actividad.esperar { shadowOf(actividad).peekNextStartedActivity() != null }
            // Sin sesión en la prueba no hay cliente del token: empieza el onboarding.
            assertEquals(OnboardingActivity::class.java.name, shadowOf(actividad).nextStartedActivity.component?.className)
        }
    }

    @Test
    fun `con la identidad en revisión manual solo informa`() {
        fuente.aceptacion = Respuesta.Rechazo("KYC_NO_VERIFICADO", siguientePaso = "ESPERAR_REVISION")
        abrir { actividad ->
            assertTrue(actividad.texto(R.id.detalle).contains("revisión manual"))
            assertEquals("Volver", actividad.texto(R.id.accion))
            assertFalse(actividad.visible(R.id.accionSecundaria))
        }
    }

    @Test
    fun `sin conexión lo dice y reintenta`() {
        fuente.aceptacion = Respuesta.SinConexion
        abrir { actividad ->
            assertEquals("No pudimos continuar", actividad.texto(R.id.estado))
            assertTrue(actividad.texto(R.id.aviso).contains("conexión"))
            assertEquals("Reintentar", actividad.texto(R.id.accion))

            fuente.aceptacion = Respuesta.Rechazo("OFERTA_VENCIDA")
            actividad.tocar(R.id.accion) { it == Paso.OfertaVencida }
            assertEquals("Cotizar de nuevo", actividad.texto(R.id.accion))
        }
    }

    @Test
    fun `un error sin reintento solo ofrece volver`() {
        fuente.aceptacion = Respuesta.Rechazo("COTIZACION_NO_ENCONTRADA")
        abrir { actividad ->
            assertEquals("Volver", actividad.texto(R.id.accion))
            assertFalse(actividad.visible(R.id.accionSecundaria))
            actividad.findViewById<View>(R.id.accion).performClick()
            assertTrue(actividad.isFinishing)
        }
    }

    @Test
    fun `los pasos que esperan al núcleo muestran el progreso`() {
        fuente.generacion = Respuesta.Ok(ContratacionPrueba.poliza(generado = false))
        abrir { actividad ->
            assertTrue(actividad.paso is Paso.GenerandoDocumento)
            assertTrue(actividad.visible(R.id.progreso))
            assertEquals("Estamos preparando tu póliza", actividad.texto(R.id.estado))

            actividad.mostrar(Paso.PagoProcesando(ContratacionPrueba.POLIZA_ID))
            assertEquals("Estamos confirmando tu pago", actividad.texto(R.id.estado))
            actividad.mostrar(Paso.Error(4, Aviso.NO_EMITIBLE, null))
            assertEquals("No pudimos emitir la póliza todavía.", actividad.texto(R.id.aviso))
            actividad.mostrar(Paso.Firma(ContratacionPrueba.POLIZA_ID, null, Aviso.CODIGO_VENCIDO))
            assertTrue(actividad.texto(R.id.documento).contains("vista previa"))
        }
    }

    @Test
    @Config(qualifiers = "en")
    fun `los textos están en inglés`() {
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.EN_REVISION))
        abrir { actividad ->
            assertEquals("Step 1 of 4", actividad.texto(R.id.numeroPaso))
            assertEquals("Your application is under review", actividad.texto(R.id.estado))
        }
    }
}
