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
import com.google.android.material.textfield.TextInputLayout
import com.sun.net.httpserver.HttpServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
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

    private fun CotizacionActivity.error(idCampo: Int): String? =
        findViewById<TextInputLayout>(idCampo).error?.toString()

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
    fun `si el servicio no está disponible lo informa y deja reintentar`() {
        abrir(bffFalso(503, """{"codigo":"NUCLEO_NO_DISPONIBLE","detalle":"x"}""")) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            assertEquals(actividad.getString(R.string.cotizacion_error_no_disponible), actividad.texto(R.id.mensajeError))
            assertFalse(actividad.visible(R.id.resultado))
            assertFalse(actividad.visible(R.id.progreso))
            assertTrue(actividad.findViewById<View>(R.id.cotizar).isEnabled)
            assertTrue(actividad.findViewById<View>(R.id.campoMonto).isEnabled)
        }
    }

    @Test
    fun `sin conexión lo informa y al reintentar con el servicio arriba cotiza`() {
        val url = bffFalso(201, cotizada)
        servidor?.stop(0)
        abrir(url) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            assertEquals(actividad.getString(R.string.cotizacion_error_sin_conexion), actividad.texto(R.id.mensajeError))
            assertTrue(actividad.findViewById<View>(R.id.cotizar).isEnabled)

            // El servicio vuelve: reintentar con el mismo formulario.
            actividad.cliente = ClienteCotizacion(bffFalso(201, cotizada), timeoutMs = 1_000)
            actividad.findViewById<View>(R.id.cotizar).performClick()
            val limite = System.currentTimeMillis() + 5_000
            while (!actividad.visible(R.id.resultado) && System.currentTimeMillis() < limite) {
                Thread.sleep(20)
                shadowOf(Looper.getMainLooper()).idle()
            }
            assertTrue(actividad.visible(R.id.resultado))
        }
    }

    @Test
    fun `si el núcleo rechaza datos marca los campos señalados`() {
        val rechazo = """{"codigo":"DATOS_BIEN_INVALIDOS","detalle":"x","campos":["montoCredito","plazoAnios"]}"""
        abrir(bffFalso(422, rechazo)) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            val revisar = actividad.getString(R.string.cotizacion_error_revisar_dato)
            assertEquals(revisar, actividad.error(R.id.campoMonto))
            assertEquals(revisar, actividad.error(R.id.campoPlazo))
            assertNull(actividad.error(R.id.campoCiudad))
            assertEquals(actividad.getString(R.string.cotizacion_error_formato), actividad.texto(R.id.mensajeError))
        }
    }

    @Test
    fun `si el núcleo rechaza sin señalar campos da un mensaje general`() {
        abrir(bffFalso(422, """{"codigo":"RAMO_NO_SOPORTADO","detalle":"x"}""")) { actividad ->
            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            assertEquals(actividad.getString(R.string.cotizacion_error_no_cotizable), actividad.texto(R.id.mensajeError))
        }
    }

    @Test
    fun `al abrir cotizar está deshabilitado y no hay errores`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            assertFalse(actividad.findViewById<View>(R.id.cotizar).isEnabled)
            listOf(R.id.campoMonto, R.id.campoPlazo, R.id.campoCiudad, R.id.campoEntidad).forEach {
                assertNull(actividad.error(it))
            }
        }
    }

    @Test
    fun `con datos incompletos cotizar sigue deshabilitado y no envía nada`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            actividad.escribir(R.id.monto, "200000000")
            val boton = actividad.findViewById<View>(R.id.cotizar)
            boton.performClick()
            shadowOf(Looper.getMainLooper()).idle()

            assertFalse(boton.isEnabled)
            assertTrue(recibido.isEmpty())
        }
    }

    @Test
    fun `cada campo fuera de rango muestra su mensaje y no deja cotizar`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            actividad.llenarValidos()
            actividad.escribir(R.id.monto, "5000000")
            actividad.escribir(R.id.plazo, "40")
            actividad.escribir(R.id.ciudad, "B")
            actividad.escribir(R.id.entidad, "")

            assertEquals(actividad.getString(R.string.cotizacion_error_monto), actividad.error(R.id.campoMonto))
            assertEquals(actividad.getString(R.string.cotizacion_error_plazo), actividad.error(R.id.campoPlazo))
            assertEquals(actividad.getString(R.string.cotizacion_error_ciudad), actividad.error(R.id.campoCiudad))
            assertEquals(actividad.getString(R.string.cotizacion_error_obligatorio), actividad.error(R.id.campoEntidad))
            assertFalse(actividad.findViewById<View>(R.id.cotizar).isEnabled)

            actividad.llenarValidos()
            assertNull(actividad.error(R.id.campoMonto))
            assertTrue(actividad.findViewById<View>(R.id.cotizar).isEnabled)
        }
    }

    @Test
    fun `los campos de texto no admiten más caracteres que el contrato`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            actividad.llenarValidos()
            actividad.escribir(R.id.entidad, "x".repeat(130))
            actividad.escribir(R.id.ciudad, "y".repeat(90))

            assertEquals(120, actividad.findViewById<EditText>(R.id.entidad).text.length)
            assertEquals(80, actividad.findViewById<EditText>(R.id.ciudad).text.length)
            assertNull(actividad.error(R.id.campoEntidad))
            assertTrue(actividad.findViewById<View>(R.id.cotizar).isEnabled)
        }
    }

    @Test
    fun `al salir de un campo vacío muestra que es obligatorio`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            val plazo = actividad.findViewById<EditText>(R.id.plazo)
            plazo.requestFocus()
            actividad.findViewById<EditText>(R.id.ciudad).requestFocus()

            assertEquals(actividad.getString(R.string.cotizacion_error_obligatorio), actividad.error(R.id.campoPlazo))
            assertNull(actividad.error(R.id.campoMonto))
        }
    }

    @Test
    fun `conserva los errores al recrear la pantalla`() {
        ActivityScenario.launch<CotizacionActivity>(
            CotizacionActivity.intent(ApplicationProvider.getApplicationContext()),
        ).use { escenario ->
            escenario.onActivity { it.escribir(R.id.plazo, "40") }
            escenario.recreate()
            escenario.onActivity { actividad ->
                assertEquals(actividad.getString(R.string.cotizacion_error_plazo), actividad.error(R.id.campoPlazo))
            }
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

    @Test
    @Config(qualifiers = "en")
    fun `en inglés muestra los textos, la prima y la vigencia en inglés`() {
        abrir(bffFalso(201, cotizada)) { actividad ->
            assertEquals("Get quote", actividad.texto(R.id.cotizar))

            actividad.llenarValidos()
            actividad.cotizarYEsperar()

            assertTrue(actividad.texto(R.id.prima), actividad.texto(R.id.prima).contains("70,000"))
            assertEquals("Offer valid until October 22, 2026", actividad.texto(R.id.vigencia))
            assertEquals("Quote No. 8F1E2D3C", actividad.texto(R.id.numeroCotizacion))
        }
    }
}
