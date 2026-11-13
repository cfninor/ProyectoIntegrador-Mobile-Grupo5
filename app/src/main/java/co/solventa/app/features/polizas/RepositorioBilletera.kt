package co.solventa.app.features.polizas

import co.solventa.app.core.almacenamiento.AlmacenCifrado
import co.solventa.app.core.sesion.SesionCliente
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant

/**
 * Billetera sin conexión (SOLVENTAG5-105): envuelve [FuentePolizas] con una copia cifrada por
 * cuenta en [almacen], guardada bajo el `sub` de la sesión activa (R3). Con red, guarda la
 * respuesta y la devuelve tal cual; sin red, sirve la copia si existe o se comporta como si no
 * hubiera datos (R1-R4, ver Referencia común de la tarea). `descargar` no se cachea: el PDF y el
 * certificado de firma quedan fuera de alcance (D3, R1).
 */
class RepositorioBilletera(
    private val fuente: FuentePolizas,
    private val almacen: AlmacenCifrado,
    /** El `sub` de la sesión activa; null si no hay sesión. */
    private val sub: () -> String?,
    private val reloj: () -> String = { Instant.now().toString() },
) : FuentePolizas {

    init {
        // R5: SesionCliente no conoce la billetera, solo entrega el token que tenía al cerrar.
        SesionCliente.alCerrar = ::alCerrarSesion
    }

    override fun listar(): ResultadoPolizas = when (val resultado = fuente.listar()) {
        is ResultadoPolizas.Listadas -> {
            actualizar { it.copy(lista = resultado.polizas.map(Poliza::sinCertificado)) }
            resultado
        }
        ResultadoPolizas.SinConexion -> {
            val copia = copiaActual()
            if (copia == null) ResultadoPolizas.Listadas(emptyList())
            else ResultadoPolizas.DesdeCache(copia.lista, copia.guardadoEn)
        }
        else -> resultado
    }

    override fun obtener(polizaId: String): ResultadoPoliza = when (val resultado = fuente.obtener(polizaId)) {
        is ResultadoPoliza.Encontrada -> {
            val sinCertificado = resultado.poliza.sinCertificado()
            actualizar { it.copy(detalles = it.detalles + (polizaId to sinCertificado)) }
            resultado
        }
        ResultadoPoliza.SinConexion -> {
            val copia = copiaActual()
            val guardada = copia?.detalles?.get(polizaId)
            if (guardada == null) ResultadoPoliza.NoEncontrada
            else ResultadoPoliza.DesdeCache(guardada, copia.guardadoEn)
        }
        else -> resultado
    }

    override fun descargar(polizaId: String): ResultadoDocumento = fuente.descargar(polizaId)

    /** Borra la copia de la cuenta que tenía [idTokenAnterior] (R5); nada si no había sesión. */
    internal fun alCerrarSesion(idTokenAnterior: String?) {
        idTokenAnterior?.let(DecodificadorJwt::sub)?.let(almacen::borrar)
    }

    private fun copiaActual(): CopiaBilletera? = sub()?.let { almacen.leer(it)?.let(CopiaBilletera::deJson) }

    private fun actualizar(cambiar: (CopiaBilletera) -> CopiaBilletera) {
        val clave = sub() ?: return
        val base = almacen.leer(clave)?.let(CopiaBilletera::deJson) ?: CopiaBilletera.vacia()
        almacen.guardar(clave, cambiar(base).copy(guardadoEn = reloj()).aJson())
    }
}

/** El contenido del archivo cifrado de una cuenta (ver «Modelo de datos» de la tarea). */
private data class CopiaBilletera(
    val guardadoEn: String,
    val lista: List<Poliza>,
    val detalles: Map<String, Poliza>,
) {
    fun aJson(): String = JSONObject()
        .put("guardadoEn", guardadoEn)
        .put("lista", org.json.JSONArray(lista.map { it.aJson() }))
        .put("detalles", JSONObject().apply { detalles.forEach { (id, poliza) -> put(id, poliza.aJson()) } })
        .toString()

    companion object {
        fun vacia() = CopiaBilletera(guardadoEn = "", lista = emptyList(), detalles = emptyMap())

        /** Null si el JSON no tiene la forma esperada (archivo de una versión futura, por ejemplo). */
        fun deJson(texto: String): CopiaBilletera? = try {
            val json = JSONObject(texto)
            val lista = json.getJSONArray("lista")
            val detalles = json.getJSONObject("detalles")
            CopiaBilletera(
                guardadoEn = json.getString("guardadoEn"),
                lista = (0 until lista.length()).map { lista.getJSONObject(it).aPoliza() },
                detalles = detalles.keys().asSequence().associateWith { detalles.getJSONObject(it).aPoliza() },
            )
        } catch (_: JSONException) {
            null
        }
    }
}

/** Nunca se guarda el certificado de firma sin conexión (R1): solo el resto de los campos. */
private fun Poliza.sinCertificado(): Poliza = if (certificado == null) this else copy(certificado = null)

/** El formato propio de la copia sin conexión, no el del contrato del BFF (ver [LectorPoliza]). */
private fun Poliza.aJson(): JSONObject = JSONObject()
    .put("polizaId", polizaId)
    .put("numero", numero)
    .put("ramo", ramo)
    .put("estado", estado.name)
    .put("vigenciaDesde", vigenciaDesde)
    .put("vigenciaHasta", vigenciaHasta)
    .put("sumaAsegurada", sumaAsegurada)
    .put("prima", prima)
    .put("moneda", moneda)
    .put("periodicidad", periodicidad)
    .put(
        "tomador",
        JSONObject()
            .put("nombres", tomador.nombres)
            .put("apellidos", tomador.apellidos)
            .put("documentoEnmascarado", tomador.documentoEnmascarado),
    )
    .put("beneficiario", beneficiario)
    .put("proximoCobro", proximoCobro?.let { JSONObject().put("fecha", it.fecha).put("valor", it.valor).put("moneda", it.moneda) })
    .put(
        "pagos",
        org.json.JSONArray(
            pagos.map { pago ->
                JSONObject()
                    .put("fecha", pago.fecha)
                    .put("monto", pago.monto)
                    .put("moneda", pago.moneda)
                    .put("medio", pago.medio)
                    .put("referencia", pago.referencia)
            },
        ),
    )

private fun JSONObject.aPoliza(): Poliza {
    val tomador = getJSONObject("tomador")
    val pagos = getJSONArray("pagos")
    return Poliza(
        polizaId = getString("polizaId"),
        numero = if (isNull("numero")) null else optString("numero").takeIf { it.isNotEmpty() },
        ramo = getString("ramo"),
        estado = EstadoPoliza.entries.firstOrNull { it.name == getString("estado") } ?: EstadoPoliza.DESCONOCIDO,
        vigenciaDesde = getString("vigenciaDesde"),
        vigenciaHasta = getString("vigenciaHasta"),
        sumaAsegurada = getLong("sumaAsegurada"),
        prima = getLong("prima"),
        moneda = getString("moneda"),
        periodicidad = getString("periodicidad"),
        tomador = TomadorPoliza(
            nombres = tomador.getString("nombres"),
            apellidos = tomador.getString("apellidos"),
            documentoEnmascarado = tomador.getString("documentoEnmascarado"),
        ),
        beneficiario = if (isNull("beneficiario")) null else optString("beneficiario").takeIf { it.isNotEmpty() },
        proximoCobro = optJSONObject("proximoCobro")?.let {
            ProximoCobro(it.getString("fecha"), it.getLong("valor"), it.getString("moneda"))
        },
        pagos = (0 until pagos.length()).map { i ->
            val pago = pagos.getJSONObject(i)
            PagoPoliza(
                fecha = pago.getString("fecha"),
                monto = pago.getLong("monto"),
                moneda = pago.getString("moneda"),
                medio = pago.getString("medio"),
                referencia = if (pago.isNull("referencia")) null else pago.optString("referencia").takeIf { it.isNotEmpty() },
            )
        },
        // R1: el certificado de firma nunca se guarda sin conexión; la copia nunca lo trae.
        certificado = null,
    )
}
