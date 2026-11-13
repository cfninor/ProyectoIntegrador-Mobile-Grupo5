package co.solventa.app.core.almacenamiento

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * El manejo de claves ausentes/inválidas en la JVM (Parte 1 de SOLVENTAG5-105): Robolectric no
 * implementa el proveedor "AndroidKeyStore", así que estas pruebas remplazan la llave por una AES
 * normal. El cifrado real contra el Keystore va en androidTest.
 */
@RunWith(AndroidJUnit4::class)
class AlmacenCifradoKeystoreRobolectricTest {

    private fun llaveDePrueba(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun almacen(llave: SecretKey = llaveDePrueba()) =
        AlmacenCifradoKeystore(ApplicationProvider.getApplicationContext(), llave = { llave })

    @Test
    fun `guarda y lee el mismo texto`() {
        val almacen = almacen()

        almacen.guardar("sub-1", "contenido secreto")

        assertEquals("contenido secreto", almacen.leer("sub-1"))
    }

    @Test
    fun `sin nada guardado, leer devuelve null`() {
        assertNull(almacen().leer("sub-ausente"))
    }

    @Test
    fun `tras borrar, leer devuelve null`() {
        val almacen = almacen()
        almacen.guardar("sub-1", "contenido")

        almacen.borrar("sub-1")

        assertNull(almacen.leer("sub-1"))
    }

    @Test
    fun `con una llave distinta no se puede descifrar y leer devuelve null en vez de lanzar`() {
        val contexto = ApplicationProvider.getApplicationContext<android.content.Context>()
        AlmacenCifradoKeystore(contexto, llave = { llaveDePrueba() }).guardar("sub-1", "contenido")

        val conOtraLlave = AlmacenCifradoKeystore(contexto, llave = { llaveDePrueba() })

        assertNull(conOtraLlave.leer("sub-1"))
    }

    @Test
    fun `cada clave tiene su propio archivo`() {
        val almacen = almacen()
        almacen.guardar("sub-1", "uno")
        almacen.guardar("sub-2", "dos")

        assertEquals("uno", almacen.leer("sub-1"))
        assertEquals("dos", almacen.leer("sub-2"))
    }
}
