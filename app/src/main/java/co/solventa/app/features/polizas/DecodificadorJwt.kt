package co.solventa.app.features.polizas

import org.json.JSONException
import org.json.JSONObject
import java.util.Base64

/**
 * Claims de un ID token de Cognito, sin verificar la firma: ya la validó Cognito al emitirlo y el
 * BFF la vuelve a validar. El `sub` es el nombre del archivo cifrado de la billetera
 * (SOLVENTAG5-105); por eso solo acepta el formato de `sub` de Cognito, nunca el texto del token
 * tal cual, para que un token con una forma inesperada no pueda escribirse como ruta de archivo.
 */
object DecodificadorJwt {

    private val FORMATO_SUB = Regex("[0-9a-fA-F-]{1,64}")
    private val FORMATO_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun sub(idToken: String): String? = claim(idToken, "sub")?.takeIf { FORMATO_SUB.matches(it) }

    /**
     * El `custom:cliente_id` del token (SOLVENTAG5-96): la cotización que se va a contratar se pide
     * con el cliente de la sesión, porque el BFF solo deja aceptar una cotización del cliente del
     * token. Solo un UUID; cualquier otra cosa, null.
     */
    fun clienteId(idToken: String): String? =
        claim(idToken, "custom:cliente_id")?.takeIf { FORMATO_UUID.matches(it) }

    private fun claim(idToken: String, nombre: String): String? {
        val payload = idToken.split(".").getOrNull(1) ?: return null
        return try {
            val json = JSONObject(String(Base64.getUrlDecoder().decode(payload.conRelleno()), Charsets.UTF_8))
            json.optString(nombre).takeIf { it.isNotEmpty() }
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
