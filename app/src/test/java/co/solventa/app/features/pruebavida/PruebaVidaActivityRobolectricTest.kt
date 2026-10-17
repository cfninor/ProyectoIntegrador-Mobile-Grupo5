package co.solventa.app.features.pruebavida

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.app.R
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.net.InetSocketAddress

/**
 * La pantalla completa en la JVM con Robolectric: permiso de cámara, el intento que pide al BFF
 * y cada resultado de las capturas. El envío de los cuadros se ejercita llamando directamente a
 * [PruebaVidaActivity.enviarCapturas] (`@VisibleForTesting`): CameraX no corre bajo Robolectric,
 * así que la captura en sí no la cubre esta prueba.
 */
@RunWith(AndroidJUnit4::class)
class PruebaVidaActivityRobolectricTest {

    private var servidor: HttpServer? = null
    private val cuadros = listOf(ByteArray(4), ByteArray(4), ByteArray(4))

    @After
    fun apagar() {
        servidor?.stop(0)
    }

    /** `respuestaIniciar` para `POST .../pruebas-vida`, `respuestaCapturas` para `.../capturas`. */
    private fun bffFalso(
        respuestaIniciar: Pair<Int, String> =
            200 to """{"pruebaVidaId":"p1","gesto":"PARPADEAR","intento":1,"intentosRestantes":2,"expiraEn":"x"}""",
        respuestaCapturas: Pair<Int, String> = 200 to """{"resultado":"APROBADA","intento":1,"intentosRestantes":2}""",
    ): String {
        val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)
        http.createContext("/") { intercambio ->
            val (codigo, cuerpo) = if (intercambio.requestURI.path.endsWith("/capturas")) {
                respuestaCapturas
            } else {
                respuestaIniciar
            }
            val bytes = cuerpo.toByteArray()
            intercambio.sendResponseHeaders(codigo, bytes.size.toLong().coerceAtLeast(1))
            intercambio.responseBody.use { it.write(bytes) }
        }
        http.start()
        servidor = http
        return "http://localhost:${http.address.port}"
    }

    private fun concederPermisoCamara() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.CAMERA)
    }

    /** `onCreate` ya dispara la petición: el cliente de prueba hay que fijarlo antes de lanzar
     * la actividad, en [PruebaVidaActivity.clienteDePrueba] (ver esa clase). */
    private fun abrir(apiBaseUrl: String, prueba: (PruebaVidaActivity) -> Unit) {
        concederPermisoCamara()
        PruebaVidaActivity.clienteDePrueba = ClientePruebaVida(apiBaseUrl, timeoutMs = 2_000)
        val intent = PruebaVidaActivity.intent(ApplicationProvider.getApplicationContext(), "9b1d4e2a-clienteId")
        try {
            ActivityScenario.launch<PruebaVidaActivity>(intent).use { escenario ->
                escenario.onActivity { actividad -> prueba(actividad) }
            }
        } finally {
            PruebaVidaActivity.clienteDePrueba = null
        }
    }

    private fun PruebaVidaActivity.seccionVisible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    /** Espera a que termine la petición en curso (deja de mostrarse `cargando`). */
    private fun PruebaVidaActivity.esperar() {
        val limite = System.currentTimeMillis() + 5_000
        while (seccionVisible(R.id.cargando) && System.currentTimeMillis() < limite) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun PruebaVidaActivity.textoDe(id: Int): String = findViewById<TextView>(id).text.toString()

    /** Robolectric no simula el diálogo real: solo registra la solicitud (`lastRequestedPermission`).
     * Hay que invocar el resultado a mano para que el launcher de [PruebaVidaActivity] reaccione. */
    private fun PruebaVidaActivity.negarPermisoCamara() {
        val solicitud = shadowOf(this).lastRequestedPermission
        onRequestPermissionsResult(
            solicitud.requestCode,
            solicitud.requestedPermissions,
            IntArray(solicitud.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
    }

    @Test
    fun `sin permiso de cámara muestra el error y permite finalizar`() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).denyPermissions(Manifest.permission.CAMERA)
        val intent = PruebaVidaActivity.intent(ApplicationProvider.getApplicationContext(), "clienteId")
        ActivityScenario.launch<PruebaVidaActivity>(intent).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.negarPermisoCamara()
                assertTrue(actividad.seccionVisible(R.id.resultado))
                assertEquals(
                    actividad.getString(R.string.prueba_vida_error_permiso_camara),
                    actividad.textoDe(R.id.resultadoMensaje),
                )
                assertEquals(
                    actividad.getString(R.string.prueba_vida_finalizar),
                    actividad.findViewById<Button>(R.id.resultadoAccion).text.toString(),
                )
            }
        }
    }

    @Test
    fun `con permiso concedido pide el intento y muestra el gesto a realizar`() {
        val url = bffFalso(
            respuestaIniciar = 201 to
                """{"pruebaVidaId":"p1","gesto":"GIRAR_IZQUIERDA","intento":1,"intentosRestantes":2,"expiraEn":"x"}""",
        )
        abrir(url) { actividad ->
            actividad.esperar()

            assertTrue(actividad.seccionVisible(R.id.camara))
            assertEquals(
                actividad.getString(R.string.prueba_vida_gesto_girar_izquierda),
                actividad.textoDe(R.id.gesto),
            )
            assertEquals(actividad.getString(R.string.prueba_vida_intento, 1, 2), actividad.textoDe(R.id.intento))
        }
    }

    @Test
    fun `un 404 al iniciar no deja reintentar`() {
        val url = bffFalso(respuestaIniciar = 404 to """{"codigo":"CLIENTE_NO_ENCONTRADO","detalle":"x"}""")
        abrir(url) { actividad ->
            actividad.esperar()

            assertTrue(actividad.seccionVisible(R.id.resultado))
            assertEquals(
                actividad.getString(R.string.prueba_vida_finalizar),
                actividad.findViewById<Button>(R.id.resultadoAccion).text.toString(),
            )
        }
    }

    @Test
    fun `un 503 al iniciar deja reintentar`() {
        val url = bffFalso(respuestaIniciar = 503 to """{"codigo":"NUCLEO_NO_DISPONIBLE","detalle":"x"}""")
        abrir(url) { actividad ->
            actividad.esperar()

            assertEquals(
                actividad.getString(R.string.prueba_vida_reintentar),
                actividad.findViewById<Button>(R.id.resultadoAccion).text.toString(),
            )
        }
    }

    @Test
    fun `aprobada muestra el mensaje final sin intentos restantes`() {
        val url = bffFalso(respuestaCapturas = 200 to """{"resultado":"APROBADA","intento":1,"intentosRestantes":2}""")
        abrir(url) { actividad ->
            actividad.esperar()
            actividad.enviarCapturas(cuadros)
            actividad.esperar()

            assertTrue(actividad.seccionVisible(R.id.resultado))
            assertEquals(actividad.getString(R.string.prueba_vida_aprobada_titulo), actividad.textoDe(R.id.resultadoTitulo))
            assertFalse(actividad.seccionVisible(R.id.resultadoIntentos))
        }
    }

    @Test
    fun `rechazada muestra el motivo y los intentos restantes, y reintentar vuelve a pedir el intento`() {
        val url = bffFalso(
            respuestaIniciar = 201 to
                """{"pruebaVidaId":"p1","gesto":"PARPADEAR","intento":1,"intentosRestantes":2,"expiraEn":"x"}""",
            respuestaCapturas = 200 to
                """{"resultado":"RECHAZADA","motivo":"ROSTRO_NO_COINCIDE","intento":1,"intentosRestantes":2}""",
        )
        abrir(url) { actividad ->
            actividad.esperar()
            actividad.enviarCapturas(cuadros)
            actividad.esperar()

            assertEquals(
                actividad.getString(R.string.prueba_vida_motivo_rostro_no_coincide),
                actividad.textoDe(R.id.resultadoMensaje),
            )
            assertEquals(actividad.getString(R.string.prueba_vida_intentos_restantes, 2), actividad.textoDe(R.id.resultadoIntentos))

            actividad.findViewById<Button>(R.id.resultadoAccion).performClick()
            actividad.esperar()

            assertTrue(actividad.seccionVisible(R.id.camara))
        }
    }

    @Test
    fun `el tercer rechazo pasa a revisión manual`() {
        val url = bffFalso(
            respuestaCapturas = 200 to
                """{"resultado":"REVISION_MANUAL","motivo":"CALIDAD_INSUFICIENTE","intento":3,"intentosRestantes":0}""",
        )
        abrir(url) { actividad ->
            actividad.esperar()
            actividad.enviarCapturas(cuadros)
            actividad.esperar()

            assertEquals(
                actividad.getString(R.string.prueba_vida_revision_manual_titulo),
                actividad.textoDe(R.id.resultadoTitulo),
            )
            assertEquals(
                actividad.getString(R.string.prueba_vida_finalizar),
                actividad.findViewById<Button>(R.id.resultadoAccion).text.toString(),
            )
        }
    }

    @Test
    fun `un 503 al enviar las capturas no consume el intento y vuelve a la cámara`() {
        val url = bffFalso(respuestaCapturas = 503 to """{"codigo":"PROVEEDOR_KYC_NO_DISPONIBLE","detalle":"x"}""")
        abrir(url) { actividad ->
            actividad.esperar()
            actividad.enviarCapturas(cuadros)
            actividad.esperar()

            assertTrue(actividad.seccionVisible(R.id.camara))
            assertEquals(actividad.getString(R.string.prueba_vida_error_envio), actividad.textoDe(R.id.mensajeError))
            assertTrue(actividad.findViewById<Button>(R.id.capturar).isEnabled)
        }
    }

    @Test
    fun `un intento vencido al enviar las capturas obliga a reiniciar`() {
        val url = bffFalso(respuestaCapturas = 409 to """{"codigo":"INTENTO_VENCIDO","detalle":"x"}""")
        abrir(url) { actividad ->
            actividad.esperar()
            actividad.enviarCapturas(cuadros)
            actividad.esperar()

            assertTrue(actividad.seccionVisible(R.id.resultado))
            assertEquals(
                actividad.getString(R.string.prueba_vida_reintentar),
                actividad.findViewById<Button>(R.id.resultadoAccion).text.toString(),
            )
        }
    }
}
