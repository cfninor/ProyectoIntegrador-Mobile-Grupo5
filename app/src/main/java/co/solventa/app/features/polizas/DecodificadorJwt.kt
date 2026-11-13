package co.solventa.app.features.polizas

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * El claim `sub` de un ID token de Cognito, sin verificar la firma: ya la validó Cognito al
 * emitirlo (SOLVENTAG5-105). Se usa como nombre del archivo cifrado de la billetera; por eso solo
 * acepta el formato de `sub` de Cognito (un UUID), nunca el texto del token tal cual, para que un
 * token con una forma inesperada no pueda escribirse como ruta de archivo.
 */
object DecodificadorJwt {

    private val FORMATO_SUB = Regex("[0-9a-fA-F-]{1,64}")

    fun sub(idToken: String): String? {
        val payload = idToken.split(".").getOrNull(1) ?: return null
        return try {
            val json = JSONObject(String(Base64.getUrlDecoder().decode(payload.conRelleno()), Charsets.UTF_8))
            json.optString("sub").takeIf { it.isNotEmpty() && FORMATO_SUB.matches(it) }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: JSONException) {
            null
        }
    }

    /** Base64url del JWT sin relleno (`=`); el decodificador de Java lo exige. */
    private fun String.conRelleno(): String {
        val faltante = (4 - length % 4) % 4
        return this + "=".repeat(faltante)
    }
}
