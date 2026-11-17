package co.solventa.app.features.contratacion

import java.util.UUID

/** Un aviso de la pantalla; la actividad lo convierte en texto en español o en inglés. */
enum class Aviso {
    SIN_CONEXION,
    NO_DISPONIBLE,
    COTIZACION_NO_ENCONTRADA,
    TARJETA_NO_TOKENIZADA,
    PAGO_RECHAZADO,
    CODIGO_INVALIDO,
    CODIGO_VENCIDO,
    CODIGO_REENVIADO,
    FIRMA_VENCIDA,
    SIN_CONTACTO,
    NO_EMITIBLE,
}

/**
 * Lo que la pantalla muestra en cada momento de la contratación (SOLVENTAG5-96). Los pasos que
 * esperan al núcleo ([Evaluando], [EnRevision], [GenerandoDocumento] y [PagoProcesando]) los vuelve a
 * consultar la pantalla cada pocos segundos: ninguna petición queda abierta (ADR 0007).
 */
sealed interface Paso {
    /** El número del paso en «Paso N de 4»: solicitud, pago, firma y emisión. */
    val numero: Int

    data class Evaluando(val solicitudId: String) : Paso {
        override val numero = 1
    }

    /** Criterio 5: la revisión manual es un estado pendiente con lo que sigue, no un error. */
    data class EnRevision(val solicitudId: String) : Paso {
        override val numero = 1
    }

    data object Rechazada : Paso {
        override val numero = 1
    }

    /** Criterio 5: con resultado ajustado, el cliente acepta primero la nueva prima. */
    data class Ajustada(val solicitudId: String, val primaAnterior: Long, val primaNueva: Long, val moneda: String) :
        Paso {
        override val numero = 1
    }

    data class GenerandoDocumento(val polizaId: String) : Paso {
        override val numero = 2
    }

    data class Pago(val polizaId: String, val prima: Long, val moneda: String, val aviso: Aviso? = null) : Paso {
        override val numero = 2
    }

    data class PagoProcesando(val polizaId: String) : Paso {
        override val numero = 2
    }

    /** Criterio 2: la firma adaptada al móvil, con la vista previa y el código de un solo uso. */
    data class Firma(
        val polizaId: String,
        val documento: DocumentoFirma?,
        val aviso: Aviso? = null,
        val intentosRestantes: Int? = null,
    ) : Paso {
        override val numero = 3
    }

    /** La póliza VIGENTE. La confirmación completa es de la SOLVENTAG5-97. */
    data class Emitida(val poliza: PolizaEnTramite) : Paso {
        override val numero = 4
    }

    /** Criterio 4: la identidad no está verificada; la pantalla lleva a terminar el onboarding. */
    data class Kyc(val siguientePaso: String?) : Paso {
        override val numero = 1
    }

    data object OfertaVencida : Paso {
        override val numero = 1
    }

    /** Un error; con [reintentar], la pantalla ofrece «Reintentar» y repite lo mismo. */
    data class Error(override val numero: Int, val aviso: Aviso, val reintentar: (() -> Paso)?) : Paso

    /** 401: la pantalla pide el inicio de sesión y, al volver, repite lo mismo. */
    data class IniciarSesion(override val numero: Int, val reintentar: () -> Paso) : Paso
}

/**
 * El recorrido de contratación desde la app (SOLVENTAG5-96): aceptar la oferta, seguir la
 * solicitud, aceptar la prima ajustada, generar la póliza, pagar, firmar y emitir. No tiene reglas
 * de negocio: las aplica el núcleo, y aquí solo se decide qué mostrar según lo que responde el
 * BFF. Cada operación es idempotente en el núcleo, así que empezar otra vez desde la cotización
 * lleva al mismo punto en que el cliente iba.
 *
 * Bloquea el hilo (llama al BFF y a la pasarela): se llama fuera del hilo principal.
 */
class FlujoContratacion(
    private val fuente: FuenteContratacion,
    private val pasarela: PasarelaPagos,
    private val nuevaClave: () -> String = { UUID.randomUUID().toString() },
) {
    /** La póliza en trámite, para volver al pago con su prima si el cobro se rechaza. */
    private var poliza: PolizaEnTramite? = null

    /** El último documento a firmar, para no volver a pedirlo con cada código errado. */
    private var documento: DocumentoFirma? = null

    /** El token del último intento de pago, para seguir un cobro PROCESANDO. */
    private var tokenTarjeta: String? = null

    fun iniciar(cotizacionId: String): Paso = manejar(
        fuente.aceptarOferta(cotizacionId),
        1,
        { iniciar(cotizacionId) },
        ok = ::trasSolicitud,
    ) {
        when (it.codigo) {
            "KYC_NO_VERIFICADO" -> Paso.Kyc(it.siguientePaso)
            "OFERTA_VENCIDA" -> Paso.OfertaVencida
            "COTIZACION_NO_ENCONTRADA" -> Paso.Error(1, Aviso.COTIZACION_NO_ENCONTRADA, null)
            else -> Paso.Error(1, Aviso.NO_DISPONIBLE, null)
        }
    }

    fun consultarSolicitud(solicitudId: String): Paso = manejar(
        fuente.solicitud(solicitudId),
        1,
        { consultarSolicitud(solicitudId) },
        ok = ::trasSolicitud,
    ) { Paso.Error(1, Aviso.NO_DISPONIBLE, null) }

    // AJUSTE_NO_APLICA: ya no hay nueva prima que aceptar; se sigue con la solicitud.
    fun aceptarPrima(solicitudId: String): Paso = manejar(
        fuente.aceptarPrimaAjustada(solicitudId),
        1,
        { aceptarPrima(solicitudId) },
        ok = ::trasSolicitud,
    ) { consultarSolicitud(solicitudId) }

    fun consultarPoliza(polizaId: String): Paso = manejar(
        fuente.poliza(polizaId),
        2,
        { consultarPoliza(polizaId) },
        ok = ::trasPoliza,
    ) { Paso.Error(2, Aviso.NO_DISPONIBLE, null) }

    /** Tokeniza la tarjeta con la pasarela y cobra con una Idempotency-Key nueva. */
    fun pagar(polizaId: String, numero: String, vencimiento: String): Paso {
        val token = pasarela.tokenizar(numero, vencimiento) ?: return pago(polizaId, Aviso.TARJETA_NO_TOKENIZADA)
        tokenTarjeta = token
        return cobrar(polizaId, token, nuevaClave())
    }

    /**
     * Sigue un pago PROCESANDO: otro intento con otra clave. Si el núcleo tiene un pago APROBADO o
     * PROCESANDO, lo devuelve sin cobrar (SOLVENTAG5-75); nunca cobra dos veces.
     */
    fun consultarPago(polizaId: String): Paso {
        val token = tokenTarjeta ?: return consultarPoliza(polizaId)
        return cobrar(polizaId, token, nuevaClave())
    }

    fun firmar(polizaId: String, codigo: String): Paso = manejar(
        fuente.firmar(polizaId, codigo),
        3,
        { firmar(polizaId, codigo) },
        ok = { trasFirma(polizaId, it) },
    ) {
        when (it.codigo) {
            "CODIGO_INVALIDO" -> Paso.Firma(polizaId, documento, Aviso.CODIGO_INVALIDO, it.intentosRestantes)
            "CODIGO_VENCIDO" -> Paso.Firma(polizaId, documento, Aviso.CODIGO_VENCIDO)
            "CODIGO_USADO" -> emitir(polizaId)
            "ENLACE_VENCIDO" -> Paso.Error(3, Aviso.FIRMA_VENCIDA, null)
            else -> Paso.Error(3, Aviso.NO_DISPONIBLE) { consultarPoliza(polizaId) }
        }
    }

    // FIRMA_NO_REENVIABLE: la firma cambió de estado (se firmó o venció); se sigue con la póliza.
    fun reenviarCodigo(polizaId: String): Paso = manejar(
        fuente.reenviarFirma(polizaId),
        3,
        { reenviarCodigo(polizaId) },
        ok = { Paso.Firma(polizaId, documento, Aviso.CODIGO_REENVIADO) },
    ) { consultarPoliza(polizaId) }

    fun emitir(polizaId: String): Paso = manejar(
        fuente.emitir(polizaId),
        4,
        { emitir(polizaId) },
        ok = { Paso.Emitida(it) },
    ) { Paso.Error(4, Aviso.NO_EMITIBLE) { consultarPoliza(polizaId) } }

    private fun trasSolicitud(solicitud: Solicitud): Paso = when (solicitud.estado) {
        EstadoSolicitud.ACEPTADA -> generarPoliza(solicitud.solicitudId)
        EstadoSolicitud.AJUSTADA ->
            if (solicitud.primaAjustadaAceptada) {
                generarPoliza(solicitud.solicitudId)
            } else {
                Paso.Ajustada(
                    solicitud.solicitudId,
                    solicitud.prima,
                    solicitud.primaFinal ?: solicitud.prima,
                    solicitud.moneda,
                )
            }
        EstadoSolicitud.EN_REVISION -> Paso.EnRevision(solicitud.solicitudId)
        EstadoSolicitud.RECHAZADA -> Paso.Rechazada
        else -> Paso.Evaluando(solicitud.solicitudId)
    }

    private fun generarPoliza(solicitudId: String): Paso = manejar(
        fuente.generarPoliza(solicitudId),
        2,
        { generarPoliza(solicitudId) },
        ok = ::trasPoliza,
    ) {
        when (it.motivo) {
            "EN_REVISION" -> Paso.EnRevision(solicitudId)
            "RECHAZADA" -> Paso.Rechazada
            "SIN_DECISION" -> Paso.Evaluando(solicitudId)
            "PRIMA_AJUSTADA_SIN_ACEPTAR" -> consultarSolicitud(solicitudId)
            else -> Paso.Error(2, Aviso.NO_DISPONIBLE, null)
        }
    }

    private fun trasPoliza(nueva: PolizaEnTramite): Paso {
        poliza = nueva
        return when {
            nueva.vigente -> Paso.Emitida(nueva)
            !nueva.documentoGenerado -> Paso.GenerandoDocumento(nueva.polizaId)
            else -> pedirFirma(nueva)
        }
    }

    /** La firma exige el pago APROBADO: si falta, el núcleo responde PAGO_NO_APROBADO y se paga. */
    private fun pedirFirma(actual: PolizaEnTramite): Paso = manejar(
        fuente.pedirFirma(actual.polizaId),
        3,
        { pedirFirma(actual) },
        ok = { trasFirma(actual.polizaId, it) },
    ) {
        when (it.codigo) {
            "PAGO_NO_APROBADO" -> Paso.Pago(actual.polizaId, actual.prima, actual.moneda)
            "DOCUMENTO_EN_GENERACION" -> Paso.GenerandoDocumento(actual.polizaId)
            "CLIENTE_SIN_CONTACTO" -> Paso.Error(3, Aviso.SIN_CONTACTO, null)
            else -> Paso.Error(3, Aviso.NO_DISPONIBLE) { consultarPoliza(actual.polizaId) }
        }
    }

    private fun trasFirma(polizaId: String, firma: Firma): Paso = when (firma.estado) {
        EstadoFirma.FIRMADA -> emitir(polizaId)
        EstadoFirma.VENCIDA -> Paso.Error(3, Aviso.FIRMA_VENCIDA, null)
        else -> {
            val leido = fuente.documentoFirma(polizaId)
            if (leido is Respuesta.Ok) documento = leido.valor
            Paso.Firma(polizaId, documento)
        }
    }

    // Sin red se reintenta con la misma clave: si el cobro sí llegó, no se cobra otra vez.
    private fun cobrar(polizaId: String, token: String, clave: String): Paso = manejar(
        fuente.cobrar(polizaId, token, clave),
        2,
        { cobrar(polizaId, token, clave) },
        ok = {
            when (it.estado) {
                EstadoPago.APROBADO -> consultarPoliza(polizaId)
                EstadoPago.PROCESANDO -> Paso.PagoProcesando(polizaId)
                EstadoPago.RECHAZADO -> pago(polizaId, Aviso.PAGO_RECHAZADO)
                else -> Paso.Error(2, Aviso.NO_DISPONIBLE) { consultarPoliza(polizaId) }
            }
        },
    ) {
        when (it.codigo) {
            "CLAVE_IDEMPOTENCIA_EN_CURSO" -> Paso.PagoProcesando(polizaId)
            "POLIZA_NO_PAGABLE" -> consultarPoliza(polizaId)
            else -> pago(polizaId, Aviso.NO_DISPONIBLE)
        }
    }

    private fun pago(polizaId: String, aviso: Aviso): Paso {
        val actual = poliza ?: return consultarPoliza(polizaId)
        return Paso.Pago(polizaId, actual.prima, actual.moneda, aviso)
    }

    /**
     * 401, sin red y 503 son iguales en todo el recorrido; [ok] sigue con la respuesta 2xx y
     * [rechazo], con un 404, 409 o 422 del BFF.
     */
    private fun <T> manejar(
        respuesta: Respuesta<T>,
        numero: Int,
        repetir: () -> Paso,
        ok: (T) -> Paso,
        rechazo: (Respuesta.Rechazo) -> Paso,
    ): Paso = when (respuesta) {
        is Respuesta.Ok -> ok(respuesta.valor)
        is Respuesta.Rechazo -> rechazo(respuesta)
        Respuesta.NoAutenticado -> Paso.IniciarSesion(numero, repetir)
        Respuesta.SinConexion -> Paso.Error(numero, Aviso.SIN_CONEXION, repetir)
        Respuesta.NoDisponible -> Paso.Error(numero, Aviso.NO_DISPONIBLE, repetir)
    }
}
