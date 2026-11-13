package co.solventa.app.features.polizas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class DecodificadorJwtTest {

    private fun jwt(payloadJson: String): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.toByteArray())
        return "cabecera.$payload.firma"
    }

    @Test
    fun `lee el sub de un token con el formato de Cognito`() {
        val sub = "9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e"

        assertEquals(sub, DecodificadorJwt.sub(jwt("""{"sub":"$sub"}""")))
    }

    @Test
    fun `sin claim sub, devuelve null`() {
        assertNull(DecodificadorJwt.sub(jwt("""{"otro":"valor"}""")))
    }

    @Test
    fun `un sub con una forma inesperada (no un UUID) se rechaza`() {
        assertNull(DecodificadorJwt.sub(jwt("""{"sub":"../../etc/passwd"}""")))
    }

    @Test
    fun `un token sin la forma de un JWT devuelve null en vez de lanzar`() {
        assertNull(DecodificadorJwt.sub("no-es-un-jwt"))
    }

    @Test
    fun `un payload que no es JSON devuelve null en vez de lanzar`() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("no es json".toByteArray())

        assertNull(DecodificadorJwt.sub("cabecera.$payload.firma"))
    }
}
