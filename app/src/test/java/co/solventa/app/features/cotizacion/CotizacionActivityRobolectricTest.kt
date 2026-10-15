package co.solventa.app.features.cotizacion

import android.content.Intent
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.app.R
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** La pantalla de cotización en la JVM con Robolectric, contra un BFF de prueba en localhost. */
@RunWith(AndroidJUnit4::class)
class CotizacionActivityRobolectricTest {

    private val cotizada = """
        {"cotizacionId":"8f1e2d3c-4b5a-4c6d-9e7f-0a1b2c3d4e5f","ramo":"VIDA_HIPOTECARIO",
         "datosBien":{"montoCredito":200000000,"plazoAnios":20,"ciudadInmueble":"Bogotá","entidadAcreedora":"Banco de Ejemplo"},
         "prima":70000,"moneda":"COP","periodicidad":"MENSUAL","fechaCotizacion":"2026-10-15T15:00:00Z",
         "vigenteHasta":"2026-10-22T15:00:00Z","estado":"VIGENTE","calculadaSinPerfil":false}
    """.trimIndent()

    private var servidor: HttpServer? = null
    private val recibido = mutableMapOf<String, String>()

    @After
    fun apagar() {
        servidor?.stop(0)
    }

    private val peticiones = AtomicInteger()

    /** BFF de prueba; [demoraMs] simula al núcleo esperando el perfil de riesgo. */
    private fun bffFalso(codigo: Int, respuesta: String, demoraMs: Long = 0): String {
        val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        http.executor = Executors.newCachedThreadPool()
        http.createContext("/") { intercambio ->
            peticiones.incrementAndGet()
            recibido["cuerpo"] = intercambio.requestBody.bufferedReader().readText()
            Thread.sleep(demoraMs)
            val bytes = respuesta.toByteArray()
            intercambio.sendResponseHeaders(codigo, bytes.size.toLong().coerceAtLeast(1))
            intercambio.responseBody.use { it.write(bytes) }
        }
        http.start()
        servidor = http
        return "http://localhost:${http.address.port}"
    }

    private fun abrir(apiBaseUrl: String, intent: Intent? = null, prueba: (CotizacionActivity) -> Unit) {
        val lanzar = intent ?: CotizacionActivity.intent(ApplicationProvider.getApplicationContext())
        ActivityScenario.launch<CotizacionActivity>(lanzar).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.cliente = ClienteCotizacion(apiBaseUrl, timeoutMs = 1_000)
                prueba(actividad)
            }
        }
    }

    private fun CotizacionActivity.escribir(id: Int, texto: String) {
        findViewById<EditText>(id).setText(texto)
    }

    private fun CotizacionActivity.llenarValidos() {
        escribir(R.id.monto, "200000000")
        escribir(R.id.plazo, "20")
        escribir(R.id.ciudad, "Bogotá")
        escribir(R.id.entidad, "Banco de Ejemplo")
    }

    private fun CotizacionActivity.texto(id: Int) = findViewById<TextView>(id).text.toString()

    private fun CotizacionActivity.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    /** Toca «Cotizar» y espera a que la respuesta del BFF llegue a la pantalla. */
    private fun CotizacionActivity.cotizarYEsperar() {
        findViewById<View>(R.id.cotizar).performClick()
        val limite = System.currentTimeMillis() + 5_000
        while (!visible(R.id.resultado) && !visible(R.id.mensajeError) && System.currentTimeMillis() < limite) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun `muestra la prima mensual, la vigencia y el número de cotización`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            assertTrue(actividad.visible(R.id.resultado))
            assertFalse(actividad.visible(R.id.formulario))
            assertTrue(actividad.texto(R.id.prima), actividad.texto(R.id.prima).contains("70.000"))
            assertEquals("Oferta válida hasta el 22 de octubre de 2026", actividad.texto(R.id.vigencia))
            assertEquals("Cotización N.º 8F1E2D3C", actividad.texto(R.id.numeroCotizacion))
            // Calculada con el perfil del cliente: sin aviso.
            assertFalse(actividad.visible(R.id.avisoSinPerfil))
        }
    }

    @Test
    fun `envía al BFF los datos del crédito`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            val cuerpo = JSONObject(recibido.getValue("cuerpo"))
            assertEquals(200_000_000L, cuerpo.getJSONObject("datosBien").getLong("montoCredito"))
            assertEquals(20, cuerpo.getJSONObject("datosBien").getInt("plazoAnios"))
            assertFalse(cuerpo.has("clienteId"))
        }
    }

    @Test
    fun `si viene del onboarding envía su clienteId`() {
        val url = bffFalso(201, cotizada)
        val intent = CotizacionActivity.intent(
            ApplicationProvider.getApplicationContext(),
            "9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e",
        )
        abrir(url, intent) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            assertEquals(
                "9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e",
                JSONObject(recibido.getValue("cuerpo")).getString("clienteId"),
            )
        }
    }

    @Test
    fun `nueva cotización vuelve al formulario`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()
            actividad.findViewById<View>(R.id.nuevaCotizacion).performClick()

            assertTrue(actividad.visible(R.id.formulario))
            assertFalse(actividad.visible(R.id.resultado))
        }
    }

    @Test
    fun `si no hay cotización lo informa`() {
        abrir(bffFalso(503, """{"codigo":"NUCLEO_NO_DISPONIBLE","detalle":"x"}""")) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            assertEquals(actividad.getString(R.string.cotizacion_error_general), actividad.texto(R.id.mensajeError))
            assertFalse(actividad.visible(R.id.resultado))
        }
    }

    @Test
    fun `con datos incompletos no envía nada`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            actividad.escribir(R.id.monto, "200000000")
            actividad.findViewById<View>(R.id.cotizar).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(recibido.isEmpty())
            assertTrue(actividad.visible(R.id.formulario))
        }
    }

    @Test
    fun `mientras el núcleo calcula la pantalla no se bloquea y no repite la solicitud`() {
        abrir(bffFalso(201, cotizada, demoraMs = 800)) { actividad ->
            actividad.llenarValidos()
            val boton = actividad.findViewById<View>(R.id.cotizar)

            val inicio = System.nanoTime()
            boton.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            val ms = (System.nanoTime() - inicio) / 1_000_000

            assertTrue("el toque tardó $ms ms en el hilo principal", ms < 300)
            assertTrue(actividad.visible(R.id.progreso))
            assertFalse(boton.isEnabled)
            assertFalse(actividad.findViewById<View>(R.id.campoMonto).isEnabled)

            boton.performClick()
            actividad.cotizarYEsperar()

            assertEquals(1, peticiones.get())
            assertTrue(actividad.visible(R.id.resultado))
            assertFalse(actividad.visible(R.id.progreso))
            assertTrue(boton.isEnabled)
        }
    }

    private fun sinPerfil(motivo: String) = cotizada.replace(
        "\"calculadaSinPerfil\":false",
        "\"calculadaSinPerfil\":true,\"motivoSinPerfil\":\"$motivo\"",
    )

    private fun avisoPara(motivo: String, mensaje: Int) {
        abrir(bffFalso(201, sinPerfil(motivo))) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            assertTrue(actividad.visible(R.id.resultado))
            assertTrue(actividad.texto(R.id.prima).contains("70.000"))
            assertTrue(actividad.visible(R.id.avisoSinPerfil))
            assertEquals(actividad.getString(mensaje), actividad.texto(R.id.avisoSinPerfil))
        }
    }

    @Test
    fun `si el perfil no respondió a tiempo muestra la prima con su aviso`() =
        avisoPara("PERFIL_NO_DISPONIBLE", R.string.cotizacion_sin_perfil_no_disponible)

    @Test
    fun `sin cliente muestra la prima con su aviso`() =
        avisoPara("SIN_CLIENTE", R.string.cotizacion_sin_perfil_sin_cliente)

    @Test
    fun `sin autorización de datos muestra la prima con su aviso`() =
        avisoPara("SIN_CONSENTIMIENTO", R.string.cotizacion_sin_perfil_sin_consentimiento)
}
