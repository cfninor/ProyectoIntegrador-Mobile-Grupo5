package co.solventa.app.features.polizas

import co.solventa.app.core.almacenamiento.AlmacenCifrado
import co.solventa.app.core.sesion.SesionCliente
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Un [AlmacenCifrado] de prueba en memoria, para los casos de R1-R5 sin el Keystore real. */
private class AlmacenPrueba : AlmacenCifrado {
    private val archivos = mutableMapOf<String, String>()
    val borrados = mutableListOf<String>()

    override fun guardar(clave: String, datos: String) {
        archivos[clave] = datos
    }

    override fun leer(clave: String): String? = archivos[clave]

    override fun borrar(clave: String) {
        archivos.remove(clave)
        borrados += clave
    }
}

/** El `sub` de Cognito es un UUID: basta para que [DecodificadorJwt] lo acepte. */
private fun jwtConSub(sub: String): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"sub":"$sub"}""".toByteArray())
    return "cabecera.$payload.firma"
}

private const val SUB_1 = "9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e"

class RepositorioBilleteraTest {

    private val fuente = FuentePrueba()
    private val almacen = AlmacenPrueba()
    private var sub: String? = SUB_1
    private val repositorio = RepositorioBilletera(fuente, almacen, sub = { sub })

    @After
    fun limpiar() {
        SesionCliente.alCerrar = {}
    }

    @Test
    fun `con red, lista tal cual y la copia queda disponible para después`() {
        fuente.lista = ResultadoPolizas.Listadas(listOf(PolizasPrueba.poliza()))

        val resultado = repositorio.listar()

        assertEquals(fuente.lista, resultado)
        fuente.lista = ResultadoPolizas.SinConexion
        assertTrue(repositorio.listar() is ResultadoPolizas.DesdeCache)
    }

    @Test
    fun `sin red, con copia, sirve la lista guardada con la fecha`() {
        fuente.lista = ResultadoPolizas.Listadas(listOf(PolizasPrueba.poliza()))
        repositorio.listar()
        fuente.lista = ResultadoPolizas.SinConexion

        val resultado = repositorio.listar() as ResultadoPolizas.DesdeCache

        assertEquals(1, resultado.polizas.size)
        assertEquals(PolizasPrueba.POLIZA_ID, resultado.polizas[0].polizaId)
        assertTrue(resultado.guardadoEn.isNotBlank())
    }

    @Test
    fun `sin red y sin copia, la lista se comporta como vacía (R4)`() {
        fuente.lista = ResultadoPolizas.SinConexion

        assertEquals(ResultadoPolizas.Listadas(emptyList()), repositorio.listar())
    }

    @Test
    fun `NoDisponible y NoAutenticado no son sin conexión, se propagan igual`() {
        fuente.lista = ResultadoPolizas.NoDisponible
        assertEquals(ResultadoPolizas.NoDisponible, repositorio.listar())

        fuente.lista = ResultadoPolizas.NoAutenticado
        assertEquals(ResultadoPolizas.NoAutenticado, repositorio.listar())
    }

    @Test
    fun `con red, obtener devuelve tal cual y guarda el detalle`() {
        val poliza = PolizasPrueba.poliza()
        fuente.poliza = ResultadoPoliza.Encontrada(poliza)

        val resultado = repositorio.obtener(poliza.polizaId)

        assertEquals(ResultadoPoliza.Encontrada(poliza), resultado)
    }

    @Test
    fun `sin red, con el detalle guardado, lo sirve con la fecha`() {
        val poliza = PolizasPrueba.poliza()
        fuente.poliza = ResultadoPoliza.Encontrada(poliza)
        repositorio.obtener(poliza.polizaId)
        fuente.poliza = ResultadoPoliza.SinConexion

        val resultado = repositorio.obtener(poliza.polizaId) as ResultadoPoliza.DesdeCache

        assertEquals(poliza.polizaId, resultado.poliza.polizaId)
        assertTrue(resultado.guardadoEn.isNotBlank())
    }

    @Test
    fun `sin red y sin ese detalle guardado, se comporta como no encontrada (R4)`() {
        fuente.poliza = ResultadoPoliza.SinConexion

        assertEquals(ResultadoPoliza.NoEncontrada, repositorio.obtener("otra-poliza"))
    }

    @Test
    fun `R1 el certificado de firma nunca queda en la copia guardada`() {
        val poliza = PolizasPrueba.poliza(PolizasPrueba.emitida)
        assertTrue(poliza.certificado != null)
        fuente.poliza = ResultadoPoliza.Encontrada(poliza)
        repositorio.obtener(poliza.polizaId)
        fuente.poliza = ResultadoPoliza.SinConexion

        val resultado = repositorio.obtener(poliza.polizaId) as ResultadoPoliza.DesdeCache

        assertEquals(null, resultado.poliza.certificado)
    }

    @Test
    fun `R3 la copia de una cuenta no se sirve a otra`() {
        fuente.lista = ResultadoPolizas.Listadas(listOf(PolizasPrueba.poliza()))
        repositorio.listar()

        sub = "otra-cuenta-9999"
        fuente.lista = ResultadoPolizas.SinConexion

        assertEquals(ResultadoPolizas.Listadas(emptyList()), repositorio.listar())
    }

    @Test
    fun `R5 cerrar sesión borra la copia de la cuenta que tenía`() {
        fuente.lista = ResultadoPolizas.Listadas(listOf(PolizasPrueba.poliza()))
        repositorio.listar()

        repositorio.alCerrarSesion(jwtConSub(SUB_1))

        assertTrue(almacen.borrados.contains(SUB_1))
        fuente.lista = ResultadoPolizas.SinConexion
        assertEquals(ResultadoPolizas.Listadas(emptyList()), repositorio.listar())
    }

    @Test
    fun `sin sesión previa, cerrar no intenta borrar nada`() {
        repositorio.alCerrarSesion(null)

        assertTrue(almacen.borrados.isEmpty())
    }

    @Test
    fun `RepositorioBilletera registra el borrado en SesionCliente al cerrar`() {
        fuente.lista = ResultadoPolizas.Listadas(listOf(PolizasPrueba.poliza()))
        repositorio.listar()

        SesionCliente.alCerrar(jwtConSub(SUB_1))

        assertNull(almacen.leer(SUB_1))
    }

    @Test
    fun `descargar no se cachea, pasa directo a la fuente`() {
        repositorio.descargar(PolizasPrueba.POLIZA_ID)

        assertEquals(listOf("descargar ${PolizasPrueba.POLIZA_ID}"), fuente.pedidos)
    }
}
