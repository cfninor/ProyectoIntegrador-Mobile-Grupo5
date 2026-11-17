package co.solventa.app.features.contratacion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Datos de prueba de la contratación (los del contrato 1.2.0). */
object ContratacionPrueba {
    const val COTIZACION_ID = "3a5c7e9b-1d2f-4a6b-8c0d-2e4f6a8b0c1d"
    const val SOLICITUD_ID = "5c7e9a1b-3d4f-4b6c-8d0e-4f6a8b0c2d3e"
    const val POLIZA_ID = "2b4d6f8a-3c5e-4a7b-9d1f-4e6a8c0b2d4f"
    const val FIRMA_ID = "7e9a1c3d-5f6b-4c8d-9e0f-6a8b0c2d4e5f"

    fun solicitud(estado: EstadoSolicitud, primaFinal: Long? = null, aceptada: Boolean = false) =
        Solicitud(SOLICITUD_ID, estado, 70_000, "COP", primaFinal, aceptada)

    fun poliza(estado: String = "PENDIENTE_FIRMA", generado: Boolean = true, numero: String? = null) =
        PolizaEnTramite(POLIZA_ID, estado, generado, 70_000, "COP", numero)

    fun firma(estado: EstadoFirma) = Firma(FIRMA_ID, estado, "2026-11-20T15:00:00Z")

    val documento = DocumentoFirma(
        huella = "abc123",
        tomador = "María Fernanda Pérez Gómez · CC ••••5678",
        sumaAsegurada = 200_000_000,
        prima = 70_000,
        moneda = "COP",
        vigenciaDesde = "2026-11-17",
        vigenciaHasta = "2046-11-17",
        coberturas = listOf("Fallecimiento del deudor"),
        exclusiones = listOf("Actos de guerra o terrorismo"),
        versionCondiciones = "VH-2026.1",
    )
}

/** Fuente de prueba: responde lo que se le indique y anota cada llamada. */
class FuenteContratacionPrueba : FuenteContratacion {
    var aceptacion: Respuesta<Solicitud> = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.ACEPTADA))
    var consultas: ArrayDeque<Respuesta<Solicitud>> = ArrayDeque()
    var primaAceptada: Respuesta<Solicitud> =
        Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.AJUSTADA, 91_000, aceptada = true))
    var generacion: Respuesta<PolizaEnTramite> = Respuesta.Ok(ContratacionPrueba.poliza())
    var polizas: ArrayDeque<Respuesta<PolizaEnTramite>> = ArrayDeque()
    var cobros: ArrayDeque<Respuesta<Pago>> = ArrayDeque()
    var pedidas: ArrayDeque<Respuesta<Firma>> = ArrayDeque()
    var reenvio: Respuesta<Firma> = Respuesta.Ok(ContratacionPrueba.firma(EstadoFirma.ENVIADA))
    var documento: Respuesta<DocumentoFirma> = Respuesta.Ok(ContratacionPrueba.documento)
    var firmas: ArrayDeque<Respuesta<Firma>> = ArrayDeque()
    var emision: Respuesta<PolizaEnTramite> =
        Respuesta.Ok(ContratacionPrueba.poliza("VIGENTE", numero = "SV-VH-2026-000001"))
    val llamadas = mutableListOf<String>()
    val claves = mutableListOf<String>()

    private fun <T> siguiente(cola: ArrayDeque<Respuesta<T>>, porDefecto: Respuesta<T>): Respuesta<T> =
        cola.removeFirstOrNull() ?: porDefecto

    override fun aceptarOferta(cotizacionId: String) = aceptacion.also { llamadas += "aceptarOferta" }

    override fun solicitud(solicitudId: String) =
        siguiente(consultas, Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.ACEPTADA)))
            .also { llamadas += "solicitud" }

    override fun aceptarPrimaAjustada(solicitudId: String) = primaAceptada.also { llamadas += "aceptarPrima" }

    override fun generarPoliza(solicitudId: String) = generacion.also { llamadas += "generarPoliza" }

    override fun poliza(polizaId: String) =
        siguiente(polizas, Respuesta.Ok(ContratacionPrueba.poliza())).also { llamadas += "poliza" }

    override fun cobrar(polizaId: String, tokenTarjeta: String, clave: String): Respuesta<Pago> {
        llamadas += "cobrar $tokenTarjeta"
        claves += clave
        return siguiente(cobros, Respuesta.Ok(Pago(EstadoPago.APROBADO, "VISA", "4242")))
    }

    override fun pedirFirma(polizaId: String) =
        siguiente(pedidas, Respuesta.Ok(ContratacionPrueba.firma(EstadoFirma.ENVIADA))).also { llamadas += "pedirFirma" }

    override fun reenviarFirma(polizaId: String) = reenvio.also { llamadas += "reenviarFirma" }

    override fun documentoFirma(polizaId: String) = documento.also { llamadas += "documentoFirma" }

    override fun firmar(polizaId: String, codigo: String) =
        siguiente(firmas, Respuesta.Ok(ContratacionPrueba.firma(EstadoFirma.FIRMADA))).also { llamadas += "firmar $codigo" }

    override fun emitir(polizaId: String) = emision.also { llamadas += "emitir" }
}

class FlujoContratacionTest {

    private val fuente = FuenteContratacionPrueba()
    private var tokenPasarela: String? = "tok-1"
    private var numeroClave = 0
    private val flujo = FlujoContratacion(fuente, { _, _ -> tokenPasarela }, { "clave-${++numeroClave}" })

    private val pagoPendiente = Respuesta.Rechazo("PAGO_NO_APROBADO")

    // --- Criterio 1: aceptar la oferta y seguir la solicitud hasta la decisión ---------------------

    @Test
    fun `una solicitud que se evalúa queda esperando y se consulta hasta la decisión`() {
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.PENDIENTE_EVALUACION))

        val paso = flujo.iniciar(ContratacionPrueba.COTIZACION_ID)

        assertEquals(Paso.Evaluando(ContratacionPrueba.SOLICITUD_ID), paso)
        assertEquals(listOf("aceptarOferta"), fuente.llamadas)
    }

    @Test
    fun `aceptada, genera la póliza y, sin el pago, pide pagar la prima`() {
        fuente.pedidas.add(pagoPendiente)

        val paso = flujo.iniciar(ContratacionPrueba.COTIZACION_ID)

        assertEquals(Paso.Pago(ContratacionPrueba.POLIZA_ID, 70_000, "COP"), paso)
        assertEquals(listOf("aceptarOferta", "generarPoliza", "pedirFirma"), fuente.llamadas)
    }

    @Test
    fun `al consultar, una solicitud evaluada sigue esperando`() {
        fuente.consultas.add(Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.EVALUADA)))

        assertEquals(Paso.Evaluando(ContratacionPrueba.SOLICITUD_ID), flujo.consultarSolicitud(ContratacionPrueba.SOLICITUD_ID))
    }

    @Test
    fun `criterio 5 - la revisión manual es un estado pendiente, no un error`() {
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.EN_REVISION))

        assertEquals(Paso.EnRevision(ContratacionPrueba.SOLICITUD_ID), flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
    }

    @Test
    fun `una solicitud rechazada lo dice sin seguir`() {
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.RECHAZADA))

        assertEquals(Paso.Rechazada, flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
        assertEquals(listOf("aceptarOferta"), fuente.llamadas)
    }

    @Test
    fun `criterio 5 - ajustada, el cliente acepta primero la nueva prima`() {
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.AJUSTADA, 91_000))

        val paso = flujo.iniciar(ContratacionPrueba.COTIZACION_ID)

        assertEquals(Paso.Ajustada(ContratacionPrueba.SOLICITUD_ID, 70_000, 91_000, "COP"), paso)
        assertEquals(listOf("aceptarOferta"), fuente.llamadas)
    }

    @Test
    fun `aceptada la nueva prima, genera la póliza`() {
        fuente.pedidas.add(pagoPendiente)

        val paso = flujo.aceptarPrima(ContratacionPrueba.SOLICITUD_ID)

        assertTrue(paso is Paso.Pago)
        assertEquals(listOf("aceptarPrima", "generarPoliza", "pedirFirma"), fuente.llamadas)
    }

    @Test
    fun `si el ajuste ya no aplica, sigue con la solicitud`() {
        fuente.primaAceptada = Respuesta.Rechazo("AJUSTE_NO_APLICA")
        fuente.consultas.add(Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.RECHAZADA)))

        assertEquals(Paso.Rechazada, flujo.aceptarPrima(ContratacionPrueba.SOLICITUD_ID))
    }

    @Test
    fun `una solicitud ajustada ya aceptada va directo a la póliza`() {
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.AJUSTADA, 91_000, aceptada = true))
        fuente.pedidas.add(pagoPendiente)

        assertTrue(flujo.iniciar(ContratacionPrueba.COTIZACION_ID) is Paso.Pago)
    }

    @Test
    fun `una solicitud con un estado desconocido sigue esperando`() {
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.DESCONOCIDO))

        assertTrue(flujo.iniciar(ContratacionPrueba.COTIZACION_ID) is Paso.Evaluando)
    }

    // --- Criterio 4: identidad verificada; oferta vencida y cotización ajena ---------------------

    @Test
    fun `criterio 4 - sin la identidad verificada lleva al paso del onboarding`() {
        fuente.aceptacion = Respuesta.Rechazo("KYC_NO_VERIFICADO", siguientePaso = "PRUEBA_VIDA")

        assertEquals(Paso.Kyc("PRUEBA_VIDA"), flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
    }

    @Test
    fun `una oferta vencida ofrece cotizar de nuevo`() {
        fuente.aceptacion = Respuesta.Rechazo("OFERTA_VENCIDA")

        assertEquals(Paso.OfertaVencida, flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
    }

    @Test
    fun `una cotización que no es del cliente no se puede reintentar`() {
        fuente.aceptacion = Respuesta.Rechazo("COTIZACION_NO_ENCONTRADA")

        assertEquals(Paso.Error(1, Aviso.COTIZACION_NO_ENCONTRADA, null), flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
    }

    @Test
    fun `un rechazo desconocido al aceptar es un error sin reintento`() {
        fuente.aceptacion = Respuesta.Rechazo("OTRO")

        assertEquals(Paso.Error(1, Aviso.NO_DISPONIBLE, null), flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
    }

    // --- Sesión, red y servicio, iguales en todo el recorrido ------------------------------------

    @Test
    fun `sin sesión pide iniciarla y repite lo mismo al volver`() {
        fuente.aceptacion = Respuesta.NoAutenticado

        val paso = flujo.iniciar(ContratacionPrueba.COTIZACION_ID) as Paso.IniciarSesion
        fuente.aceptacion = Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.EN_REVISION))

        assertEquals(Paso.EnRevision(ContratacionPrueba.SOLICITUD_ID), paso.reintentar())
    }

    @Test
    fun `sin red o sin servicio ofrece reintentar`() {
        fuente.aceptacion = Respuesta.SinConexion
        val sinRed = flujo.iniciar(ContratacionPrueba.COTIZACION_ID) as Paso.Error
        fuente.aceptacion = Respuesta.NoDisponible
        val sinServicio = flujo.iniciar(ContratacionPrueba.COTIZACION_ID) as Paso.Error

        assertEquals(Aviso.SIN_CONEXION, sinRed.aviso)
        assertEquals(Aviso.NO_DISPONIBLE, sinServicio.aviso)
        assertTrue(sinRed.reintentar != null && sinServicio.reintentar != null)
    }

    @Test
    fun `un rechazo al consultar la solicitud es un error`() {
        fuente.consultas.add(Respuesta.Rechazo("SOLICITUD_SUSCRIPCION_NO_ENCONTRADA"))

        assertEquals(Paso.Error(1, Aviso.NO_DISPONIBLE, null), flujo.consultarSolicitud(ContratacionPrueba.SOLICITUD_ID))
    }

    // --- Póliza y documento ------------------------------------------------------------------------

    @Test
    fun `mientras el documento se genera, espera y consulta la póliza`() {
        fuente.generacion = Respuesta.Ok(ContratacionPrueba.poliza(generado = false))

        assertEquals(Paso.GenerandoDocumento(ContratacionPrueba.POLIZA_ID), flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
    }

    @Test
    fun `una póliza ya vigente está emitida`() {
        fuente.generacion = Respuesta.Ok(ContratacionPrueba.poliza("VIGENTE", numero = "SV-1"))

        val paso = flujo.iniciar(ContratacionPrueba.COTIZACION_ID) as Paso.Emitida

        assertEquals("SV-1", paso.poliza.numero)
    }

    @Test
    fun `los motivos de una solicitud no emitible llevan a su estado`() {
        val casos = mapOf(
            "EN_REVISION" to Paso.EnRevision(ContratacionPrueba.SOLICITUD_ID),
            "RECHAZADA" to Paso.Rechazada,
            "SIN_DECISION" to Paso.Evaluando(ContratacionPrueba.SOLICITUD_ID),
            "OTRO" to Paso.Error(2, Aviso.NO_DISPONIBLE, null),
        )
        casos.forEach { (motivo, esperado) ->
            fuente.generacion = Respuesta.Rechazo("SOLICITUD_NO_EMITIBLE", motivo = motivo)
            assertEquals(motivo, esperado, flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
        }
    }

    @Test
    fun `la prima ajustada sin aceptar vuelve a la solicitud`() {
        fuente.generacion = Respuesta.Rechazo("SOLICITUD_NO_EMITIBLE", motivo = "PRIMA_AJUSTADA_SIN_ACEPTAR")
        fuente.consultas.add(Respuesta.Ok(ContratacionPrueba.solicitud(EstadoSolicitud.AJUSTADA, 91_000)))

        assertTrue(flujo.iniciar(ContratacionPrueba.COTIZACION_ID) is Paso.Ajustada)
    }

    @Test
    fun `al consultar la póliza, un rechazo es un error`() {
        fuente.polizas.add(Respuesta.Rechazo("POLIZA_NO_ENCONTRADA"))

        assertEquals(Paso.Error(2, Aviso.NO_DISPONIBLE, null), flujo.consultarPoliza(ContratacionPrueba.POLIZA_ID))
    }

    @Test
    fun `al pedir la firma, el documento en generación espera y el cliente sin contacto es un error`() {
        fuente.pedidas.add(Respuesta.Rechazo("DOCUMENTO_EN_GENERACION"))
        assertEquals(Paso.GenerandoDocumento(ContratacionPrueba.POLIZA_ID), flujo.iniciar(ContratacionPrueba.COTIZACION_ID))

        fuente.pedidas.add(Respuesta.Rechazo("CLIENTE_SIN_CONTACTO"))
        assertEquals(Paso.Error(3, Aviso.SIN_CONTACTO, null), flujo.iniciar(ContratacionPrueba.COTIZACION_ID))

        fuente.pedidas.add(Respuesta.Rechazo("OTRO"))
        assertEquals(Aviso.NO_DISPONIBLE, (flujo.iniciar(ContratacionPrueba.COTIZACION_ID) as Paso.Error).aviso)
    }

    // --- Pago ------------------------------------------------------------------------------------

    @Test
    fun `paga con el token de la pasarela y una clave nueva, y sigue a la firma`() {
        fuente.pedidas.add(pagoPendiente)
        flujo.iniciar(ContratacionPrueba.COTIZACION_ID)

        val paso = flujo.pagar(ContratacionPrueba.POLIZA_ID, "4242424242424242", "12/30")

        assertEquals(Paso.Firma(ContratacionPrueba.POLIZA_ID, ContratacionPrueba.documento), paso)
        assertEquals(listOf("clave-1"), fuente.claves)
        assertTrue("cobrar tok-1" in fuente.llamadas)
    }

    @Test
    fun `si la pasarela no tokeniza, vuelve al pago con el aviso sin cobrar`() {
        fuente.pedidas.add(pagoPendiente)
        flujo.iniciar(ContratacionPrueba.COTIZACION_ID)
        tokenPasarela = null

        val paso = flujo.pagar(ContratacionPrueba.POLIZA_ID, "4242424242424242", "12/30")

        assertEquals(Paso.Pago(ContratacionPrueba.POLIZA_ID, 70_000, "COP", Aviso.TARJETA_NO_TOKENIZADA), paso)
        assertTrue(fuente.claves.isEmpty())
    }

    @Test
    fun `un pago rechazado vuelve al pago con el aviso`() {
        fuente.pedidas.add(pagoPendiente)
        flujo.iniciar(ContratacionPrueba.COTIZACION_ID)
        fuente.cobros.add(Respuesta.Ok(Pago(EstadoPago.RECHAZADO, null, null)))

        val paso = flujo.pagar(ContratacionPrueba.POLIZA_ID, "4000000000000001", "12/30")

        assertEquals(Paso.Pago(ContratacionPrueba.POLIZA_ID, 70_000, "COP", Aviso.PAGO_RECHAZADO), paso)
    }

    @Test
    fun `un pago rechazado sin la póliza en memoria la consulta`() {
        fuente.cobros.add(Respuesta.Ok(Pago(EstadoPago.RECHAZADO, null, null)))
        fuente.pedidas.add(pagoPendiente)

        val paso = flujo.pagar(ContratacionPrueba.POLIZA_ID, "4000000000000001", "12/30")

        assertEquals(Paso.Pago(ContratacionPrueba.POLIZA_ID, 70_000, "COP"), paso)
    }

    @Test
    fun `un pago procesando se sigue con otra clave, sin cobrar dos veces`() {
        fuente.cobros.add(Respuesta.Ok(Pago(EstadoPago.PROCESANDO, null, null)))

        val paso = flujo.pagar(ContratacionPrueba.POLIZA_ID, "4242424242424242", "12/30")
        assertEquals(Paso.PagoProcesando(ContratacionPrueba.POLIZA_ID), paso)

        flujo.consultarPago(ContratacionPrueba.POLIZA_ID)
        assertEquals(listOf("clave-1", "clave-2"), fuente.claves)
    }

    @Test
    fun `sin un pago en curso, consultar el pago consulta la póliza`() {
        fuente.pedidas.add(pagoPendiente)

        assertTrue(flujo.consultarPago(ContratacionPrueba.POLIZA_ID) is Paso.Pago)
        assertTrue(fuente.claves.isEmpty())
    }

    @Test
    fun `sin red al cobrar, el reintento usa la misma clave`() {
        fuente.cobros.add(Respuesta.SinConexion)

        val error = flujo.pagar(ContratacionPrueba.POLIZA_ID, "4242424242424242", "12/30") as Paso.Error
        error.reintentar!!()

        assertEquals(listOf("clave-1", "clave-1"), fuente.claves)
    }

    @Test
    fun `los rechazos del cobro llevan a su paso`() {
        fuente.cobros.add(Respuesta.Rechazo("CLAVE_IDEMPOTENCIA_EN_CURSO"))
        assertEquals(Paso.PagoProcesando(ContratacionPrueba.POLIZA_ID), flujo.pagar(ContratacionPrueba.POLIZA_ID, "4242424242424242", "12/30"))

        fuente.cobros.add(Respuesta.Rechazo("POLIZA_NO_PAGABLE"))
        assertTrue(flujo.pagar(ContratacionPrueba.POLIZA_ID, "4242424242424242", "12/30") is Paso.Firma)

        fuente.cobros.add(Respuesta.Rechazo("OTRO"))
        assertEquals(Aviso.NO_DISPONIBLE, (flujo.pagar(ContratacionPrueba.POLIZA_ID, "4242424242424242", "12/30") as Paso.Pago).aviso)

        fuente.cobros.add(Respuesta.Ok(Pago(EstadoPago.REVERSADO, null, null)))
        assertEquals(Aviso.NO_DISPONIBLE, (flujo.pagar(ContratacionPrueba.POLIZA_ID, "4242424242424242", "12/30") as Paso.Error).aviso)
    }

    // --- Criterio 2: firma y emisión ----------------------------------------------------------------

    @Test
    fun `criterio 2 - firma con el código y emite la póliza`() {
        val paso = flujo.firmar(ContratacionPrueba.POLIZA_ID, "123456") as Paso.Emitida

        assertEquals("SV-VH-2026-000001", paso.poliza.numero)
        assertEquals(listOf("firmar 123456", "emitir"), fuente.llamadas)
    }

    @Test
    fun `una firma ya firmada al pedirla emite la póliza`() {
        fuente.pedidas.add(Respuesta.Ok(ContratacionPrueba.firma(EstadoFirma.FIRMADA)))

        assertTrue(flujo.iniciar(ContratacionPrueba.COTIZACION_ID) is Paso.Emitida)
    }

    @Test
    fun `una firma vencida no se puede retomar`() {
        fuente.pedidas.add(Respuesta.Ok(ContratacionPrueba.firma(EstadoFirma.VENCIDA)))

        assertEquals(Paso.Error(3, Aviso.FIRMA_VENCIDA, null), flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
    }

    @Test
    fun `sin la vista previa del documento, firma igual`() {
        fuente.documento = Respuesta.NoDisponible

        assertEquals(Paso.Firma(ContratacionPrueba.POLIZA_ID, null), flujo.iniciar(ContratacionPrueba.COTIZACION_ID))
    }

    @Test
    fun `un código errado dice los intentos que quedan y conserva el documento`() {
        flujo.iniciar(ContratacionPrueba.COTIZACION_ID)
        fuente.firmas.add(Respuesta.Rechazo("CODIGO_INVALIDO", intentosRestantes = 3))

        val paso = flujo.firmar(ContratacionPrueba.POLIZA_ID, "000000")

        assertEquals(Paso.Firma(ContratacionPrueba.POLIZA_ID, ContratacionPrueba.documento, Aviso.CODIGO_INVALIDO, 3), paso)
    }

    @Test
    fun `los demás rechazos de la firma llevan a su paso`() {
        fuente.firmas.add(Respuesta.Rechazo("CODIGO_VENCIDO"))
        assertEquals(Aviso.CODIGO_VENCIDO, (flujo.firmar(ContratacionPrueba.POLIZA_ID, "1") as Paso.Firma).aviso)

        fuente.firmas.add(Respuesta.Rechazo("CODIGO_USADO"))
        assertTrue(flujo.firmar(ContratacionPrueba.POLIZA_ID, "1") is Paso.Emitida)

        fuente.firmas.add(Respuesta.Rechazo("ENLACE_VENCIDO"))
        assertEquals(Paso.Error(3, Aviso.FIRMA_VENCIDA, null), flujo.firmar(ContratacionPrueba.POLIZA_ID, "1"))

        fuente.firmas.add(Respuesta.Rechazo("DOCUMENTO_ALTERADO"))
        val alterado = flujo.firmar(ContratacionPrueba.POLIZA_ID, "1") as Paso.Error
        assertEquals(Aviso.NO_DISPONIBLE, alterado.aviso)
        assertTrue(alterado.reintentar!!() is Paso.Firma)
    }

    @Test
    fun `reenviar el código lo avisa, y si ya no se puede, sigue con la póliza`() {
        assertEquals(Aviso.CODIGO_REENVIADO, (flujo.reenviarCodigo(ContratacionPrueba.POLIZA_ID) as Paso.Firma).aviso)

        fuente.reenvio = Respuesta.Rechazo("FIRMA_NO_REENVIABLE")
        fuente.pedidas.add(Respuesta.Ok(ContratacionPrueba.firma(EstadoFirma.FIRMADA)))
        assertTrue(flujo.reenviarCodigo(ContratacionPrueba.POLIZA_ID) is Paso.Emitida)
    }

    @Test
    fun `si no se puede emitir, ofrece reintentar consultando la póliza`() {
        fuente.emision = Respuesta.Rechazo("POLIZA_NO_EMITIBLE", motivo = "FIRMA_PENDIENTE")

        val paso = flujo.emitir(ContratacionPrueba.POLIZA_ID) as Paso.Error

        assertEquals(Aviso.NO_EMITIBLE, paso.aviso)
        assertTrue(paso.reintentar!!() is Paso.Firma)
    }

    @Test
    fun `cada paso dice su número de 4`() {
        assertEquals(1, Paso.Rechazada.numero)
        assertEquals(1, Paso.OfertaVencida.numero)
        assertEquals(1, Paso.Kyc(null).numero)
        assertEquals(2, Paso.GenerandoDocumento("p").numero)
        assertEquals(2, Paso.PagoProcesando("p").numero)
        assertEquals(3, Paso.Firma("p", null).numero)
        assertEquals(4, Paso.Emitida(ContratacionPrueba.poliza()).numero)
        assertNull(Paso.Firma("p", null).aviso)
    }
}
