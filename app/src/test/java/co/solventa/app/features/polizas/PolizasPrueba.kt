package co.solventa.app.features.polizas

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** Pólizas de ejemplo con la forma del contrato del BFF móvil 1.1.0. */
object PolizasPrueba {

    const val POLIZA_ID = "2b4d6f8a-3c5e-4a7b-9d1f-4e6a8c0b2d4f"

    /** Una póliza VIGENTE emitida, con el certificado de la firma y el comprobante del pago. */
    val emitida = """
        {"polizaId":"$POLIZA_ID","solicitudId":"6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
         "cotizacionId":"8f1e2d3c-4b5a-4c6d-9e7f-0a1b2c3d4e5f","clienteId":"9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e",
         "estado":"VIGENTE","ramo":"VIDA_HIPOTECARIO","sumaAsegurada":200000000,"prima":70000,"moneda":"COP",
         "periodicidad":"MENSUAL","vigenciaDesde":"2026-11-11","vigenciaHasta":"2046-11-11",
         "tomador":{"nombres":"María Fernanda","apellidos":"López Rojas","documentoEnmascarado":"CC ••••4050"},
         "condiciones":{"version":"VH-2026.1","coberturas":["Muerte"],"exclusiones":[]},
         "documento":{"estado":"GENERADO"},"numeroPoliza":"SV-VH-2026-000123","emitidaEn":"2026-11-11T20:00:00Z",
         "proximoCobro":{"fecha":"2026-12-11","valor":70000,"moneda":"COP"},
         "documentos":{
           "certificadoFirma":{"firmadaEn":"2026-11-11T19:55:00Z","ip":"203.0.113.10",
             "huella":"${"a".repeat(64)}","referencia":"FIRMA-00042"},
           "comprobantePago":{"pagoId":"4d2e1f3a-5b6c-4d7e-8f9a-0b1c2d3e4f5a","referencia":"PAGO-7781",
             "marca":"VISA","ultimos4":"4242","monto":70000,"moneda":"COP","aprobadoEn":"2026-11-11T19:50:00Z"}},
         "beneficiario":"Banco de Ejemplo"}
    """.trimIndent()

    /** Una póliza para firma: sin número, sin pagos, sin certificado y sin beneficiario. */
    val paraFirma = """
        {"polizaId":"7a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d","solicitudId":"6c1e2d3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f",
         "cotizacionId":"8f1e2d3c-4b5a-4c6d-9e7f-0a1b2c3d4e5f","clienteId":"9b1d4e2a-7c3f-4a8b-8e6d-2f1a3b5c7d9e",
         "estado":"PENDIENTE_FIRMA","ramo":"VIDA_HIPOTECARIO","sumaAsegurada":150000000,"prima":52000,"moneda":"COP",
         "periodicidad":"MENSUAL","vigenciaDesde":"2026-11-10","vigenciaHasta":"2041-11-10",
         "tomador":{"nombres":"María Fernanda","apellidos":"López Rojas","documentoEnmascarado":"CC ••••4050"},
         "condiciones":{"version":"VH-2026.1","coberturas":["Muerte"],"exclusiones":[]},
         "documento":{"estado":"GENERANDO"}}
    """.trimIndent()

    fun poliza(json: String = emitida): Poliza = LectorPoliza.poliza(org.json.JSONObject(json))
}

/** Un BFF de prueba en un puerto libre: responde por ruta y anota cada petición. */
class BffPrueba : AutoCloseable {
    data class Respuesta(val codigo: Int, val cuerpo: ByteArray, val tipo: String = "application/json")

    val rutas = mutableListOf<String>()
    val autorizaciones = mutableListOf<String?>()
    val respuestas = mutableMapOf<String, Respuesta>()
    private val http = HttpServer.create(InetSocketAddress("localhost", 0), 0)

    init {
        http.createContext("/") { intercambio ->
            val ruta = intercambio.requestURI.path
            rutas += ruta
            autorizaciones += intercambio.requestHeaders.getFirst("Authorization")
            val respuesta = respuestas[ruta] ?: Respuesta(404, "{}".toByteArray())
            intercambio.responseHeaders.add("Content-Type", respuesta.tipo)
            intercambio.sendResponseHeaders(respuesta.codigo, respuesta.cuerpo.size.toLong().coerceAtLeast(1))
            intercambio.responseBody.use { it.write(respuesta.cuerpo) }
        }
        http.start()
    }

    val url: String get() = "http://localhost:${http.address.port}"

    fun json(ruta: String, codigo: Int, cuerpo: String) {
        respuestas[ruta] = Respuesta(codigo, cuerpo.toByteArray())
    }

    override fun close() = http.stop(0)
}
