package co.solventa.app.features.cotizacion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidadorCotizacionTest {

    private val validos = DatosCotizacion(
        monto = "200000000",
        plazo = "20",
        ciudad = "Bogotá",
        entidad = "Banco de Ejemplo",
    )

    private fun errores(datos: DatosCotizacion) = ValidadorCotizacion.validar(datos)

    @Test
    fun `acepta datos dentro de los rangos del contrato`() {
        assertTrue(errores(validos).isEmpty())
    }

    @Test
    fun `todos los campos son obligatorios`() {
        val vacios = DatosCotizacion("", " ", "", "  ")

        assertEquals(CampoCotizacion.entries.associateWith { ErrorCotizacion.OBLIGATORIO }, errores(vacios))
    }

    @Test
    fun `acepta los extremos de cada rango`() {
        listOf("10000000", "1200000000").forEach { monto ->
            assertTrue(monto, errores(validos.copy(monto = monto)).isEmpty())
        }
        listOf("5", "30").forEach { plazo ->
            assertTrue(plazo, errores(validos.copy(plazo = plazo)).isEmpty())
        }
        assertTrue(errores(validos.copy(ciudad = "Ca", entidad = "x".repeat(120))).isEmpty())
    }

    @Test
    fun `rechaza montos fuera del rango o que no son números`() {
        listOf("9999999", "1200000001", "200.000.000", "abc").forEach { monto ->
            assertEquals(
                monto,
                mapOf(CampoCotizacion.MONTO to ErrorCotizacion.MONTO_FUERA_DE_RANGO),
                errores(validos.copy(monto = monto)),
            )
        }
    }

    @Test
    fun `rechaza plazos fuera del rango`() {
        listOf("4", "31", "0").forEach { plazo ->
            assertEquals(
                plazo,
                mapOf(CampoCotizacion.PLAZO to ErrorCotizacion.PLAZO_FUERA_DE_RANGO),
                errores(validos.copy(plazo = plazo)),
            )
        }
    }

    @Test
    fun `rechaza textos de un solo carácter o demasiado largos`() {
        assertEquals(
            mapOf(CampoCotizacion.CIUDAD to ErrorCotizacion.CIUDAD_INVALIDA),
            errores(validos.copy(ciudad = "B")),
        )
        assertEquals(
            mapOf(CampoCotizacion.CIUDAD to ErrorCotizacion.CIUDAD_INVALIDA),
            errores(validos.copy(ciudad = "x".repeat(81))),
        )
        assertEquals(
            mapOf(CampoCotizacion.ENTIDAD to ErrorCotizacion.ENTIDAD_INVALIDA),
            errores(validos.copy(entidad = "x".repeat(121))),
        )
    }

    @Test
    fun `arma la solicitud sin espacios sobrantes y con el cliente`() {
        val solicitud = ValidadorCotizacion.solicitud(
            validos.copy(monto = " 200000000 ", ciudad = "  Bogotá "),
            clienteId = "9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e",
        )

        assertEquals(
            SolicitudCotizacion(200_000_000, 20, "Bogotá", "Banco de Ejemplo", "9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e"),
            solicitud,
        )
    }

    @Test
    fun `con algún campo inválido no hay solicitud`() {
        assertNull(ValidadorCotizacion.solicitud(validos.copy(plazo = "50"), clienteId = null))
    }
}
