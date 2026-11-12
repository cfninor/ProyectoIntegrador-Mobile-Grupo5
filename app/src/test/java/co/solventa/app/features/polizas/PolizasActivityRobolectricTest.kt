package co.solventa.app.features.polizas

import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.solventa.app.R
import co.solventa.app.core.sesion.InicioSesionActivity
import co.solventa.app.features.cotizacion.CotizacionActivity
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
import java.io.File

/** Fuente de prueba: responde lo que se le indique y anota lo que se le pide. */
class FuentePrueba : FuentePolizas {
    var lista: ResultadoPolizas = ResultadoPolizas.Listadas(listOf(PolizasPrueba.poliza()))
    var poliza: ResultadoPoliza = ResultadoPoliza.Encontrada(PolizasPrueba.poliza())
    var documento: ResultadoDocumento = ResultadoDocumento.Descargado("%PDF-1.7".toByteArray())
    val pedidos = mutableListOf<String>()

    override fun listar(): ResultadoPolizas = lista.also { pedidos += "listar" }

    override fun obtener(polizaId: String): ResultadoPoliza = poliza.also { pedidos += "obtener $polizaId" }

    override fun descargar(polizaId: String): ResultadoDocumento = documento.also { pedidos += "descargar $polizaId" }
}

/** «Mis pólizas» y el detalle en la JVM con Robolectric (SOLVENTAG5-104). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es")
class PolizasActivityRobolectricTest {

    private val fuente = FuentePrueba()

    @Before
    fun preparar() {
        MisPolizasActivity.fabricaFuente = { fuente }
        DetallePolizaActivity.fabricaFuente = { fuente }
        InicioSesionActivity.faltaSesion = { false }
    }

    @After
    fun restaurar() {
        InicioSesionActivity.faltaSesion = { false }
    }

    private fun AppCompatActivity.esperar(hasta: () -> Boolean) {
        val limite = System.currentTimeMillis() + 5_000
        while (!hasta() && System.currentTimeMillis() < limite) {
            Thread.sleep(10)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun AppCompatActivity.visible(id: Int) = findViewById<View>(id).visibility == View.VISIBLE

    private fun AppCompatActivity.texto(id: Int) = findViewById<TextView>(id).text.toString()

    private fun lista(prueba: (MisPolizasActivity) -> Unit) {
        ActivityScenario.launch(MisPolizasActivity::class.java).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.esperar { !actividad.visible(R.id.cargando) }
                prueba(actividad)
            }
        }
    }

    private fun detalle(prueba: (DetallePolizaActivity) -> Unit) {
        val intent = DetallePolizaActivity.intent(ApplicationProvider.getApplicationContext(), PolizasPrueba.POLIZA_ID)
        ActivityScenario.launch<DetallePolizaActivity>(intent).use { escenario ->
            escenario.onActivity { actividad ->
                actividad.esperar { !actividad.visible(R.id.cargando) }
                prueba(actividad)
            }
        }
    }

    private fun siguiente(actividad: AppCompatActivity): Intent? = shadowOf(actividad).nextStartedActivity

    // --- «Mis pólizas» ------------------------------------------------------------------------

    @Test
    fun `cada póliza muestra su producto, número, estado y vigencia`() {
        fuente.lista = ResultadoPolizas.Listadas(
            listOf(PolizasPrueba.poliza(), PolizasPrueba.poliza(PolizasPrueba.paraFirma)),
        )
        lista { actividad ->
            val tarjetas = actividad.findViewById<ViewGroup>(R.id.lista)
            assertTrue(actividad.visible(R.id.lista))
            assertEquals(2, tarjetas.childCount)
            val primera = tarjetas.getChildAt(0)
            assertEquals("Vida deudor hipotecario", primera.findViewById<TextView>(R.id.producto).text.toString())
            assertEquals("Póliza N.º SV-VH-2026-000123", primera.findViewById<TextView>(R.id.numero).text.toString())
            assertEquals("Vigente", primera.findViewById<TextView>(R.id.estado).text.toString())
            assertEquals(
                "Vigencia: 11 de noviembre de 2026 – 11 de noviembre de 2046",
                primera.findViewById<TextView>(R.id.vigencia).text.toString(),
            )
            val segunda = tarjetas.getChildAt(1)
            assertEquals("Póliza en proceso de emisión", segunda.findViewById<TextView>(R.id.numero).text.toString())
            assertEquals("Pendiente de firma", segunda.findViewById<TextView>(R.id.estado).text.toString())
        }
    }

    @Test
    fun `tocar una póliza abre su detalle`() {
        lista { actividad ->
            actividad.findViewById<ViewGroup>(R.id.lista).getChildAt(0).performClick()

            val abierta = siguiente(actividad)
            assertEquals(DetallePolizaActivity::class.java.name, abierta?.component?.className)
        }
    }

    @Test
    fun `sin pólizas muestra el estado vacío que lleva a cotizar`() {
        fuente.lista = ResultadoPolizas.Listadas(emptyList())
        lista { actividad ->
            assertTrue(actividad.visible(R.id.vacio))
            assertFalse(actividad.visible(R.id.error))

            actividad.findViewById<View>(R.id.cotizar).performClick()

            assertEquals(CotizacionActivity::class.java.name, siguiente(actividad)?.component?.className)
        }
    }

    @Test
    fun `una lista desde la copia sin conexión (SOLVENTAG5-105) se muestra igual que con red, con el aviso`() {
        fuente.lista = ResultadoPolizas.DesdeCache(listOf(PolizasPrueba.poliza()), "2026-11-11T20:00:00Z")
        lista { actividad ->
            assertTrue(actividad.visible(R.id.lista))
            assertEquals(1, actividad.findViewById<ViewGroup>(R.id.lista).childCount)
            assertTrue(actividad.visible(R.id.avisoSinConexion))
            assertEquals(
                "Sin conexión · actualizado el 11 de noviembre de 2026",
                actividad.texto(R.id.avisoSinConexion),
            )
        }
    }

    @Test
    fun `una lista vacía desde la copia sin conexión muestra el estado vacío, con el aviso`() {
        fuente.lista = ResultadoPolizas.DesdeCache(emptyList(), "2026-11-11T20:00:00Z")
        lista { actividad ->
            assertTrue(actividad.visible(R.id.vacio))
            assertTrue(actividad.visible(R.id.avisoSinConexion))
        }
    }

    @Test
    fun `con red, el aviso de sin conexión no se muestra`() {
        fuente.lista = ResultadoPolizas.Listadas(listOf(PolizasPrueba.poliza()))
        lista { actividad ->
            assertFalse(actividad.visible(R.id.avisoSinConexion))
        }
    }

    @Test
    fun `si el BFF no responde muestra el error y reintenta`() {
        fuente.lista = ResultadoPolizas.SinConexion
        lista { actividad ->
            assertTrue(actividad.visible(R.id.error))
            assertEquals(
                "No pudimos conectarnos. Revisa tu conexión e intenta de nuevo.",
                actividad.texto(R.id.mensajeError),
            )

            fuente.lista = ResultadoPolizas.NoDisponible
            actividad.findViewById<View>(R.id.reintentar).performClick()
            actividad.esperar { fuente.pedidos.size == 2 && !actividad.visible(R.id.cargando) }

            assertEquals(
                "No pudimos cargar tus pólizas. Intenta de nuevo en unos minutos.",
                actividad.texto(R.id.mensajeError),
            )
        }
    }

    @Test
    fun `sin sesión pide el inicio de sesión antes de cargar`() {
        InicioSesionActivity.faltaSesion = { true }
        ActivityScenario.launch(MisPolizasActivity::class.java).use { escenario ->
            escenario.onActivity { actividad ->
                assertEquals(InicioSesionActivity::class.java.name, siguiente(actividad)?.component?.className)
                assertTrue(fuente.pedidos.isEmpty())
            }
        }
    }

    @Test
    fun `si el BFF responde 401 vuelve a pedir el inicio de sesión`() {
        fuente.lista = ResultadoPolizas.NoAutenticado
        lista { actividad ->
            actividad.esperar { shadowOf(actividad).peekNextStartedActivity() != null }

            assertEquals(InicioSesionActivity::class.java.name, siguiente(actividad)?.component?.className)
        }
    }

    // --- Detalle --------------------------------------------------------------------------------

    @Test
    fun `el detalle muestra la cobertura, el beneficiario, los pagos y el certificado`() {
        detalle { actividad ->
            assertTrue(actividad.visible(R.id.contenido))
            assertEquals("Póliza N.º SV-VH-2026-000123", actividad.texto(R.id.numero))
            assertEquals("María Fernanda López Rojas\nCC ••••4050", actividad.texto(R.id.tomador))
            assertTrue(actividad.texto(R.id.sumaAsegurada), actividad.texto(R.id.sumaAsegurada).contains("200.000.000"))
            assertTrue(actividad.texto(R.id.prima), actividad.texto(R.id.prima).endsWith("al mes"))
            assertEquals("Banco de Ejemplo", actividad.texto(R.id.beneficiario))
            assertTrue(actividad.visible(R.id.filaBeneficiario))
            assertTrue(actividad.texto(R.id.proximoCobro), actividad.texto(R.id.proximoCobro).startsWith("11 de diciembre de 2026 por"))
            val pagos = actividad.findViewById<ViewGroup>(R.id.pagos)
            assertEquals(1, pagos.childCount)
            assertTrue((pagos.getChildAt(0) as TextView).text.contains("VISA •••• 4242"))
            assertFalse(actividad.visible(R.id.sinPagos))
            assertTrue(actividad.visible(R.id.seccionCertificado))
            assertTrue(actividad.texto(R.id.certificado), actividad.texto(R.id.certificado).contains("FIRMA-00042"))
            assertEquals(listOf("obtener ${PolizasPrueba.POLIZA_ID}"), fuente.pedidos)
        }
    }

    @Test
    fun `un detalle desde la copia sin conexión (SOLVENTAG5-105) se muestra igual que con red, con el aviso`() {
        fuente.poliza = ResultadoPoliza.DesdeCache(PolizasPrueba.poliza(), "2026-11-11T20:00:00Z")
        detalle { actividad ->
            assertTrue(actividad.visible(R.id.contenido))
            assertEquals("Póliza N.º SV-VH-2026-000123", actividad.texto(R.id.numero))
            assertTrue(actividad.visible(R.id.avisoSinConexion))
            assertEquals(
                "Sin conexión · actualizado el 11 de noviembre de 2026",
                actividad.texto(R.id.avisoSinConexion),
            )
        }
    }

    @Test
    fun `con red, el detalle no muestra el aviso de sin conexión`() {
        detalle { actividad ->
            assertFalse(actividad.visible(R.id.avisoSinConexion))
        }
    }

    @Test
    fun `una póliza sin emitir oculta lo que todavía no tiene`() {
        fuente.poliza = ResultadoPoliza.Encontrada(PolizasPrueba.poliza(PolizasPrueba.paraFirma))
        detalle { actividad ->
            assertFalse(actividad.visible(R.id.filaBeneficiario))
            assertFalse(actividad.visible(R.id.filaProximoCobro))
            assertTrue(actividad.visible(R.id.sinPagos))
            assertFalse(actividad.visible(R.id.seccionCertificado))
        }
    }

    @Test
    fun `una póliza ajena o inexistente se muestra como no encontrada, sin reintentar`() {
        fuente.poliza = ResultadoPoliza.NoEncontrada
        detalle { actividad ->
            assertEquals("No encontramos esta póliza.", actividad.texto(R.id.mensajeError))
            assertFalse(actividad.visible(R.id.reintentar))
            assertFalse(actividad.visible(R.id.contenido))
        }
    }

    @Test
    fun `si el BFF falla, reintentar vuelve a pedir la póliza`() {
        fuente.poliza = ResultadoPoliza.NoDisponible
        detalle { actividad ->
            assertTrue(actividad.visible(R.id.reintentar))
            fuente.poliza = ResultadoPoliza.Encontrada(PolizasPrueba.poliza())

            actividad.findViewById<View>(R.id.reintentar).performClick()
            actividad.esperar { actividad.visible(R.id.contenido) }

            assertTrue(actividad.visible(R.id.contenido))
        }
    }

    @Test
    fun `descarga el PDF en la caché privada y lo abre con el visor`() {
        detalle { actividad ->
            actividad.findViewById<View>(R.id.descargarPoliza).performClick()
            actividad.esperar { shadowOf(actividad).peekNextStartedActivity() != null }

            val ver = siguiente(actividad)
            assertNotNull(ver)
            assertEquals(Intent.ACTION_VIEW, ver?.action)
            assertEquals("application/pdf", ver?.type)
            assertTrue((ver?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            val archivo = File(actividad.cacheDir, "documentos/SV-VH-2026-000123.pdf")
            assertEquals("%PDF-1.7", archivo.readText())
            assertTrue(actividad.findViewById<View>(R.id.descargarPoliza).isEnabled)
        }
    }

    @Test
    fun `si el documento todavía se genera lo dice`() {
        fuente.documento = ResultadoDocumento.NoDisponibleAun
        detalle { actividad ->
            actividad.findViewById<View>(R.id.descargarPoliza).performClick()
            actividad.esperar { actividad.texto(R.id.mensajeDocumento).startsWith("Este documento") }

            assertTrue(actividad.visible(R.id.mensajeDocumento))
            assertEquals(
                "Este documento todavía no está disponible. Intenta más tarde.",
                actividad.texto(R.id.mensajeDocumento),
            )
        }
    }

    @Test
    fun `si la descarga falla lo dice`() {
        fuente.documento = ResultadoDocumento.NoDisponible
        detalle { actividad ->
            actividad.findViewById<View>(R.id.descargarPoliza).performClick()
            actividad.esperar { actividad.texto(R.id.mensajeDocumento).startsWith("No pudimos descargar") }

            assertEquals("No pudimos descargar el documento. Intenta de nuevo.", actividad.texto(R.id.mensajeDocumento))
        }
    }

    @Test
    fun `si la sesión venció en el detalle vuelve a pedirla`() {
        fuente.poliza = ResultadoPoliza.NoAutenticado
        detalle { actividad ->
            actividad.esperar { shadowOf(actividad).peekNextStartedActivity() != null }

            assertEquals(InicioSesionActivity::class.java.name, siguiente(actividad)?.component?.className)
        }
    }
}
