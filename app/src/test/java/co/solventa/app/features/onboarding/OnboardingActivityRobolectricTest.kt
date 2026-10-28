package co.solventa.app.features.onboarding

import android.os.Looper
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.app.R
import co.solventa.app.features.cotizacion.CotizacionActivity
import co.solventa.app.features.pruebavida.ClientePruebaVida
import co.solventa.app.features.pruebavida.PruebaVidaActivity
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.net.InetSocketAddress

/**
 * La pantalla completa en la JVM con Robolectric: validación en vivo, envío al BFF y cada
 * respuesta. El BFF es un servidor HTTP de prueba en localhost.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingActivityRobolectricTest {

    private var servidor: HttpServer? = null

    @After
    fun apagar() {
        servidor?.stop(0)
    }

    private fun bffFalso(codigo: Int, respuesta: String): String {
        val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        http.createContext("/") { intercambio ->
            val bytes = respuesta.toByteArray()
            intercambio.sendResponseHeaders(codigo, bytes.size.toLong().coerceAtLeast(1))
            intercambio.responseBody.use { it.write(bytes) }
        }
        http.start()
        servidor = http
        return "http://localhost:${http.address.port}"
    }

    private fun abrir(apiBaseUrl: String? = null, prueba: (OnboardingActivity) -> Unit) {
        ActivityScenario.launch(OnboardingActivity::class.java).use { escenario ->
            escenario.onActivity { actividad ->
                apiBaseUrl?.let { actividad.cliente = ClienteOnboarding(it, timeoutMs = 1_000) }
                prueba(actividad)
            }
        }
    }

    /** Elige una opción de la lista como lo hace el cliente al tocarla. */
    private fun OnboardingActivity.elegirTipo(tipo: TipoDocumento) {
        val lista = findViewById<MaterialAutoCompleteTextView>(R.id.tipoDocumento)
        lista.setText(lista.adapter.getItem(tipo.ordinal).toString(), false)
        lista.onItemClickListener.onItemClick(null, null, tipo.ordinal, tipo.ordinal.toLong())
    }

    private fun OnboardingActivity.llenarValidos() {
        elegirTipo(TipoDocumento.CC)
        escribir(R.id.numeroDocumento, "1020304050")
        escribir(R.id.nombres, "María Fernanda")
        escribir(R.id.apellidos, "López Rojas")
        escribir(R.id.correo, "maria.lopez@correo.com")
        escribir(R.id.celular, "3001234567")
    }

    private fun OnboardingActivity.escribir(id: Int, texto: String) {
        findViewById<android.widget.EditText>(id).setText(texto)
    }

    private fun OnboardingActivity.error(idCampo: Int): CharSequence? =
        findViewById<com.google.android.material.textfield.TextInputLayout>(idCampo).error

    private fun OnboardingActivity.continuar() = findViewById<View>(R.id.continuar)

    private fun OnboardingActivity.mensajeError(): String? =
        findViewById<android.widget.TextView>(R.id.mensajeError)
            .takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    /** Toca «Continuar» y espera a que el hilo de red termine y la respuesta llegue a la pantalla. */
    private fun OnboardingActivity.enviarYEsperar() {
        continuar().performClick()
        val limite = System.currentTimeMillis() + 5_000
        while (findViewById<View>(R.id.progreso).visibility == View.VISIBLE &&
            System.currentTimeMillis() < limite
        ) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `al abrir no hay errores y continuar está deshabilitado`() = abrir { actividad ->
        assertNull(actividad.error(R.id.campoApellidos))
        assertFalse(actividad.continuar().isEnabled)
    }

    @Test
    fun `con datos válidos se habilita continuar`() = abrir { actividad ->
        actividad.llenarValidos()

        assertTrue(actividad.continuar().isEnabled)
    }

    @Test
    fun `cada campo inválido muestra su mensaje y no deja continuar`() = abrir { actividad ->
        actividad.llenarValidos()
        actividad.escribir(R.id.numeroDocumento, "12A")
        actividad.escribir(R.id.nombres, "Ana2")
        actividad.escribir(R.id.correo, "maria")
        actividad.escribir(R.id.celular, "123")
        actividad.escribir(R.id.apellidos, "")

        assertEquals(actividad.getString(R.string.onboarding_error_documento), actividad.error(R.id.campoNumeroDocumento))
        assertEquals(actividad.getString(R.string.onboarding_error_nombre), actividad.error(R.id.campoNombres))
        assertEquals(actividad.getString(R.string.onboarding_error_obligatorio), actividad.error(R.id.campoApellidos))
        assertEquals(actividad.getString(R.string.onboarding_error_correo), actividad.error(R.id.campoCorreo))
        assertEquals(actividad.getString(R.string.onboarding_error_celular), actividad.error(R.id.campoCelular))
        assertFalse(actividad.continuar().isEnabled)
    }

    @Test
    fun `un pasaporte acepta letras y muestra su propio mensaje`() = abrir { actividad ->
        actividad.llenarValidos()
        actividad.elegirTipo(TipoDocumento.PA)
        actividad.escribir(R.id.numeroDocumento, "AB-1")

        assertEquals(actividad.getString(R.string.onboarding_error_pasaporte), actividad.error(R.id.campoNumeroDocumento))

        actividad.escribir(R.id.numeroDocumento, "AB123456")
        assertNull(actividad.error(R.id.campoNumeroDocumento))
        assertTrue(actividad.continuar().isEnabled)
    }

    @Test
    fun `un 201 muestra la pantalla de éxito`() {
        val url = bffFalso(
            201,
            """{"clienteId":"9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e","estado":"ONBOARDING_EN_CURSO","fechaInicio":"2026-10-13T14:30:00Z"}""",
        )
        abrir(url) { actividad ->
            actividad.llenarValidos()
            actividad.enviarYEsperar()

            assertEquals(View.GONE, actividad.findViewById<View>(R.id.formulario).visibility)
            assertEquals(View.VISIBLE, actividad.findViewById<View>(R.id.exito).visibility)

            // Desde el éxito sigue a la prueba de vida con su clienteId.
            actividad.findViewById<View>(R.id.verificarIdentidad).performClick()
            val pruebaVida = shadowOf(actividad).nextStartedActivity
            assertEquals(PruebaVidaActivity::class.java.name, pruebaVida.component?.className)
            assertEquals(
                "9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e",
                pruebaVida.getStringExtra(PruebaVidaActivity.EXTRA_CLIENTE_ID),
            )

            // O puede cotizar directamente con su clienteId.
            actividad.findViewById<View>(R.id.cotizarSeguro).performClick()
            val siguiente = shadowOf(actividad).nextStartedActivity
            assertEquals(CotizacionActivity::class.java.name, siguiente.component?.className)
            assertEquals(
                "9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e",
                siguiente.getStringExtra(CotizacionActivity.EXTRA_CLIENTE_ID),
            )
        }
    }

    @Test
    fun `un 422 marca los campos que rechazó el BFF`() {
        val url = bffFalso(422, """{"detail":[{"loc":["body","contacto","celular"],"msg":"x","type":"y"}]}""")
        abrir(url) { actividad ->
            actividad.llenarValidos()
            actividad.enviarYEsperar()

            val revisar = actividad.getString(R.string.onboarding_error_formato)
            assertEquals(revisar, actividad.error(R.id.campoCelular))
            assertEquals(revisar, actividad.mensajeError())
        }
    }

    @Test
    fun `un 503 muestra que el servicio no está disponible`() {
        val url = bffFalso(503, """{"codigo":"NUCLEO_NO_DISPONIBLE","detalle":"x"}""")
        abrir(url) { actividad ->
            actividad.llenarValidos()
            actividad.enviarYEsperar()

            assertEquals(actividad.getString(R.string.onboarding_error_no_disponible), actividad.mensajeError())
            assertTrue(actividad.continuar().isEnabled)
        }
    }

    @Test
    fun `sin BFF muestra que no hubo conexión`() {
        val url = bffFalso(201, "{}")
        servidor?.stop(0)
        abrir(url) { actividad ->
            actividad.llenarValidos()
            actividad.enviarYEsperar()

            assertEquals(actividad.getString(R.string.onboarding_error_sin_conexion), actividad.mensajeError())
        }
    }

    @Test
    fun `conserva el tipo de documento al recrear la pantalla`() {
        ActivityScenario.launch(OnboardingActivity::class.java).use { escenario ->
            escenario.onActivity { it.llenarValidos() }
            escenario.recreate()
            escenario.onActivity { actividad ->
                assertTrue(actividad.continuar().isEnabled)
            }
        }
    }

    // --- D6: reabrir con un clienteId existente (SOLVENTAG5-91) ---------------------------------

    private fun bffOnboardingFalso(codigo: Int, cuerpo: String): String {
        val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        http.createContext("/") { intercambio ->
            val bytes = cuerpo.toByteArray()
            intercambio.sendResponseHeaders(codigo, bytes.size.toLong().coerceAtLeast(1))
            intercambio.responseBody.use { it.write(bytes) }
        }
        http.start()
        servidor = http
        return "http://localhost:${http.address.port}"
    }

    private fun abrirConClienteId(apiBaseUrl: String, prueba: (OnboardingActivity) -> Unit) {
        OnboardingActivity.clientePruebaVidaDePrueba = ClientePruebaVida(apiBaseUrl, timeoutMs = 1_000)
        val intent = OnboardingActivity.intent(ApplicationProvider.getApplicationContext(), "clienteId")
        try {
            ActivityScenario.launch<OnboardingActivity>(intent).use { escenario ->
                escenario.onActivity { actividad -> actividad.esperarConsulta(); prueba(actividad) }
            }
        } finally {
            OnboardingActivity.clientePruebaVidaDePrueba = null
        }
    }

    private fun OnboardingActivity.esperarConsulta() {
        val limite = System.currentTimeMillis() + 5_000
        while (findViewById<View>(R.id.consultando).visibility == View.VISIBLE &&
            System.currentTimeMillis() < limite
        ) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `reabrir con siguientePaso COTIZAR navega directo a cotizar`() {
        val url = bffOnboardingFalso(
            200,
            """{"clienteId":"c","estado":"ONBOARDING_EN_CURSO","estadoKYC":"VERIFICADO","siguientePaso":"COTIZAR","intentosRestantes":2}""",
        )
        abrirConClienteId(url) { actividad ->
            val siguiente = shadowOf(actividad).nextStartedActivity
            assertNotNull(siguiente)
            assertEquals(CotizacionActivity::class.java.name, siguiente.component?.className)
            assertEquals("clienteId", siguiente.getStringExtra(CotizacionActivity.EXTRA_CLIENTE_ID))
            assertTrue(actividad.isFinishing)
        }
    }

    @Test
    fun `reabrir con siguientePaso ESPERAR_REVISION muestra la pantalla en revisión sin botones`() {
        val url = bffOnboardingFalso(
            200,
            """{"clienteId":"c","estado":"ONBOARDING_EN_CURSO","estadoKYC":"EN_REVISION_MANUAL","siguientePaso":"ESPERAR_REVISION","intentosRestantes":0}""",
        )
        abrirConClienteId(url) { actividad ->
            assertEquals(View.VISIBLE, actividad.findViewById<View>(R.id.enRevision).visibility)
            assertEquals(View.GONE, actividad.findViewById<View>(R.id.exito).visibility)
            assertEquals(View.GONE, actividad.findViewById<View>(R.id.formulario).visibility)
        }
    }

    @Test
    fun `reabrir con siguientePaso PRUEBA_VIDA muestra el éxito con las dos acciones de siempre`() {
        val url = bffOnboardingFalso(
            200,
            """{"clienteId":"c","estado":"ONBOARDING_EN_CURSO","estadoKYC":"PENDIENTE","siguientePaso":"PRUEBA_VIDA","intentosRestantes":3}""",
        )
        abrirConClienteId(url) { actividad ->
            assertEquals(View.VISIBLE, actividad.findViewById<View>(R.id.exito).visibility)
            assertEquals(View.VISIBLE, actividad.findViewById<View>(R.id.verificarIdentidad).visibility)
            assertEquals(View.VISIBLE, actividad.findViewById<View>(R.id.cotizarSeguro).visibility)

            actividad.findViewById<View>(R.id.verificarIdentidad).performClick()
            val siguiente = shadowOf(actividad).nextStartedActivity
            assertEquals(PruebaVidaActivity::class.java.name, siguiente.component?.className)
            assertEquals("clienteId", siguiente.getStringExtra(PruebaVidaActivity.EXTRA_CLIENTE_ID))
        }
    }

    @Test
    fun `intent sin clienteId abre el formulario de siempre`() {
        val intent = OnboardingActivity.intent(ApplicationProvider.getApplicationContext())
        ActivityScenario.launch<OnboardingActivity>(intent).use { escenario ->
            escenario.onActivity { actividad ->
                assertEquals(View.VISIBLE, actividad.findViewById<View>(R.id.formulario).visibility)
                assertFalse(actividad.continuar().isEnabled)
            }
        }
    }

    @Test
    fun `una falla de red al consultar el estado no bloquea abrir, cae al éxito de siempre`() {
        val url = bffOnboardingFalso(200, "{}")
        servidor?.stop(0)
        abrirConClienteId(url) { actividad ->
            assertEquals(View.VISIBLE, actividad.findViewById<View>(R.id.exito).visibility)
            assertEquals(View.VISIBLE, actividad.findViewById<View>(R.id.verificarIdentidad).visibility)
            assertEquals(View.VISIBLE, actividad.findViewById<View>(R.id.cotizarSeguro).visibility)
        }
    }
}
