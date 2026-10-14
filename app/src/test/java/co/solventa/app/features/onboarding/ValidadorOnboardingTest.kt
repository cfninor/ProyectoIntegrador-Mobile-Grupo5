package co.solventa.app.features.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidadorOnboardingTest {

    private val validos = DatosBasicos(
        tipoDocumento = TipoDocumento.CC,
        numeroDocumento = "1020304050",
        nombres = "María Fernanda",
        apellidos = "López Rojas",
        correo = "maria.lopez@correo.com",
        celular = "3001234567",
    )

    private fun errores(datos: DatosBasicos) = ValidadorOnboarding.validar(datos)

    @Test
    fun `acepta datos básicos con formato válido`() {
        assertTrue(errores(validos).isEmpty())
    }

    @Test
    fun `ignora los espacios al inicio y al final`() {
        val datos = validos.copy(nombres = "  Ana  ", correo = " ana@correo.co ", celular = " 3001234567")

        assertTrue(errores(datos).isEmpty())
        assertEquals("Ana", datos.limpios().nombres)
    }

    @Test
    fun `todos los campos son obligatorios`() {
        val vacios = DatosBasicos(null, "", "", "", "", "")

        assertEquals(Campo.entries.associateWith { ErrorCampo.OBLIGATORIO }, errores(vacios))
    }

    @Test
    fun `acepta números de documento según su tipo`() {
        listOf(
            TipoDocumento.CC to "123456",
            TipoDocumento.CE to "1234567",
            TipoDocumento.PPT to "1234567890",
            TipoDocumento.PA to "AB123456",
        ).forEach { (tipo, numero) ->
            val datos = validos.copy(tipoDocumento = tipo, numeroDocumento = numero)
            assertTrue("$tipo $numero", errores(datos).isEmpty())
        }
    }

    @Test
    fun `rechaza documentos numéricos cortos, largos o con letras`() {
        listOf("12345", "12345678901", "10203A4050").forEach { numero ->
            val datos = validos.copy(numeroDocumento = numero)
            assertEquals(numero, mapOf(Campo.NUMERO_DOCUMENTO to ErrorCampo.DOCUMENTO_INVALIDO), errores(datos))
        }
    }

    @Test
    fun `rechaza pasaportes con caracteres especiales o de más de 12 caracteres`() {
        listOf("AB-123", "AB1234567890X").forEach { numero ->
            val datos = validos.copy(tipoDocumento = TipoDocumento.PA, numeroDocumento = numero)
            assertEquals(numero, mapOf(Campo.NUMERO_DOCUMENTO to ErrorCampo.PASAPORTE_INVALIDO), errores(datos))
        }
    }

    @Test
    fun `rechaza nombres con números, símbolos, espacios dobles o fuera de largo`() {
        listOf("A", "Juan2", "Ana@", "Ana  María", "-Ana", "x".repeat(61)).forEach { nombre ->
            val datos = validos.copy(nombres = nombre)
            assertEquals(nombre, mapOf(Campo.NOMBRES to ErrorCampo.NOMBRE_INVALIDO), errores(datos))
        }
    }

    @Test
    fun `acepta apellidos con tildes, apóstrofos, guiones y espacios`() {
        listOf("Núñez", "O'Neill", "Pérez-Gómez", "de la Torre").forEach { apellido ->
            assertTrue(apellido, errores(validos.copy(apellidos = apellido)).isEmpty())
        }
    }

    @Test
    fun `rechaza correos con formato inválido`() {
        listOf("maria", "maria@", "@correo.com", "maria@correo", "maria lopez@correo.com").forEach { correo ->
            val datos = validos.copy(correo = correo)
            assertEquals(correo, mapOf(Campo.CORREO to ErrorCampo.CORREO_INVALIDO), errores(datos))
        }
    }

    @Test
    fun `rechaza celulares que no son móviles colombianos de 10 dígitos`() {
        listOf("300123456", "30012345678", "2001234567", "300-123-4567").forEach { celular ->
            val datos = validos.copy(celular = celular)
            assertEquals(celular, mapOf(Campo.CELULAR to ErrorCampo.CELULAR_INVALIDO), errores(datos))
        }
    }
}
