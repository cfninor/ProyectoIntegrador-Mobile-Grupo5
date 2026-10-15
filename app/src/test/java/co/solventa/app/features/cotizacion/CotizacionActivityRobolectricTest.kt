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

    private fun bffFalso(codigo: Int, respuesta: String): String {
        val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        http.createContext("/") { intercambio ->
            recibido["cuerpo"] = intercambio.requestBody.bufferedReader().readText()
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
}
