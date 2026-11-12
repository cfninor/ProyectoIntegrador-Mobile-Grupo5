package co.solventa.app.core.almacenamiento

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Cifrado real contra el Android Keystore del dispositivo o emulador (Parte 1 de SOLVENTAG5-105). */
@RunWith(AndroidJUnit4::class)
class AlmacenCifradoKeystoreTest {

    private val contexto = ApplicationProvider.getApplicationContext<Context>()
    private val almacen = AlmacenCifradoKeystore(contexto)

    @Test
    fun guardaYLeeElMismoTextoConLaLlaveDelKeystore() {
        almacen.guardar("sub-prueba-1", "contenido secreto")

        assertEquals("contenido secreto", almacen.leer("sub-prueba-1"))

        almacen.borrar("sub-prueba-1")
    }

    @Test
    fun elArchivoGuardadoNoContieneElTextoEnClaro() {
        almacen.guardar("sub-prueba-2", "informacion sensible del cliente")

        val archivo = File(contexto.filesDir, "billetera/sub-prueba-2.bin")
        assertFalse(archivo.readText(Charsets.ISO_8859_1).contains("informacion sensible"))

        almacen.borrar("sub-prueba-2")
    }

    @Test
    fun trasBorrarLeerDevuelveNull() {
        almacen.guardar("sub-prueba-3", "dato")

        almacen.borrar("sub-prueba-3")

        assertNull(almacen.leer("sub-prueba-3"))
    }
}
