package co.solventa.app.core.sesion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SesionClienteTest {

    private var ahora = 0L
    private val renovaciones = mutableListOf<String>()

    /** Un Cognito que responde a la renovación con [resultado]. */
    private fun cognito(resultado: ResultadoInicioSesion) = object : ClienteCognito("us-east-1", "c") {
        override fun renovar(refreshToken: String): ResultadoInicioSesion {
            renovaciones += refreshToken
            return resultado
        }
    }

    private fun iniciar(cognito: ClienteCognito) {
        SesionCliente.cognito = cognito
        SesionCliente.reloj = { ahora }
        SesionCliente.guardar(Tokens("id-1", "refresco-1", expiraEnMs = 900_000))
    }

    @After
    fun limpiar() {
        SesionCliente.alCerrar = {}
        SesionCliente.cerrar()
        SesionCliente.cognito = null
        SesionCliente.reloj = System::currentTimeMillis
    }

    @Test
    fun `sin sesión no hay token`() {
        SesionCliente.cerrar()

        assertFalse(SesionCliente.iniciada)
        assertNull(SesionCliente.idToken())
    }

    @Test
    fun `un token vigente se usa sin renovar`() {
        iniciar(cognito(ResultadoInicioSesion.NoDisponible))
        ahora = 839_999

        assertEquals("id-1", SesionCliente.idToken())
        assertTrue(renovaciones.isEmpty())
    }

    @Test
    fun `un minuto antes de vencer se renueva con el token de refresco`() {
        iniciar(cognito(ResultadoInicioSesion.Iniciada(Tokens("id-2", "refresco-1", 1_800_000))))
        ahora = 840_000

        assertEquals("id-2", SesionCliente.idToken())
        assertEquals(listOf("refresco-1"), renovaciones)
        assertEquals("id-2", SesionCliente.idToken())
    }

    @Test
    fun `si Cognito ya no renueva la sesión, se cierra`() {
        iniciar(cognito(ResultadoInicioSesion.CredencialesInvalidas))
        ahora = 900_000

        assertNull(SesionCliente.idToken())
        assertFalse(SesionCliente.iniciada)
    }

    @Test
    fun `sin red la sesión no se cierra`() {
        iniciar(cognito(ResultadoInicioSesion.SinConexion))
        ahora = 900_000

        assertEquals("id-1", SesionCliente.idToken())
        assertTrue(SesionCliente.iniciada)
    }

    @Test
    fun `cerrar avisa con el id token que tenía la sesión`() {
        iniciar(cognito(ResultadoInicioSesion.NoDisponible))
        val avisos = mutableListOf<String?>()
        SesionCliente.alCerrar = { avisos += it }

        SesionCliente.cerrar()

        assertEquals(listOf("id-1"), avisos)
    }

    @Test
    fun `sin sesión previa, cerrar avisa con null`() {
        val avisos = mutableListOf<String?>()
        SesionCliente.alCerrar = { avisos += it }

        SesionCliente.cerrar()

        assertEquals(listOf<String?>(null), avisos)
    }
}
