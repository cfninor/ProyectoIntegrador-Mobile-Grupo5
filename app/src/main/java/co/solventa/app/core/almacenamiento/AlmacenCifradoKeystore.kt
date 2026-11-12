package co.solventa.app.core.almacenamiento

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [AlmacenCifrado] con AES/GCM y la llave en Android Keystore (alias [ALIAS], no exportable), un
 * archivo por clave en `filesDir/billetera/`. [llave] se puede remplazar en pruebas de JVM, donde
 * Robolectric no implementa el proveedor "AndroidKeyStore"; el cifrado real contra el Keystore se
 * prueba en androidTest.
 */
class AlmacenCifradoKeystore(
    context: Context,
    private val llave: () -> SecretKey = { llaveKeystore() },
) : AlmacenCifrado {

    private val carpeta = File(context.filesDir, "billetera").apply { mkdirs() }

    override fun guardar(clave: String, datos: String) {
        val cipher = Cipher.getInstance(TRANSFORMACION).apply { init(Cipher.ENCRYPT_MODE, llave()) }
        val cifrado = cipher.doFinal(datos.toByteArray(Charsets.UTF_8))
        archivo(clave).writeBytes(cipher.iv + cifrado)
    }

    override fun leer(clave: String): String? {
        val bytes = archivo(clave).takeIf { it.exists() }?.readBytes() ?: return null
        return try {
            val iv = bytes.copyOfRange(0, TAMANO_IV)
            val cifrado = bytes.copyOfRange(TAMANO_IV, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMACION).apply {
                init(Cipher.DECRYPT_MODE, llave(), GCMParameterSpec(TAMANO_TAG_BITS, iv))
            }
            String(cipher.doFinal(cifrado), Charsets.UTF_8)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    override fun borrar(clave: String) {
        archivo(clave).delete()
    }

    private fun archivo(clave: String) = File(carpeta, "$clave.bin")

    companion object {
        private const val ALIAS = "billetera_clave"
        private const val TRANSFORMACION = "AES/GCM/NoPadding"
        private const val TAMANO_IV = 12
        private const val TAMANO_TAG_BITS = 128

        private fun llaveKeystore(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generador.init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            return generador.generateKey()
        }
    }
}
