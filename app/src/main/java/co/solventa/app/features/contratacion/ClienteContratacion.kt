package co.solventa.app.features.contratacion

import co.solventa.app.core.red.ClienteHttp
import co.solventa.app.core.red.RespuestaHttp
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** Resultado de una llamada de la contratación al BFF móvil. */
sealed interface Respuesta<out T> {
    data class Ok<T>(val valor: T) : Respuesta<T>

    /**
     * 404, 409 o 422 con el cuerpo `Error`: el [codigo] estable (por ejemplo KYC_NO_VERIFICADO o
     * CODIGO_INVALIDO) y, cuando el contrato los trae, el [motivo], los [intentosRestantes] del
     * código de firma y el [siguientePaso] del onboarding.
     */
    data class Rechazo(
        val codigo: String,
        val motivo: String? = null,
        val intentosRestantes: Int? = null,
        val siguientePaso: String? = null,
    ) : Respuesta<Nothing>

    /** 401: sin sesión o con la sesión vencida; la app vuelve a pedir el inicio de sesión. */
    data object NoAutenticado : Respuesta<Nothing>

    /** 503, cuerpo ilegible u otra respuesta inesperada. */
    data object NoDisponible : Respuesta<Nothing>

    /** No hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    data object SinConexion : Respuesta<Nothing>
}

/** Lo que la contratación necesita del BFF móvil (contrato 1.2.0); las pruebas lo reemplazan. */
interface FuenteContratacion {
    fun aceptarOferta(cotizacionId: String): Respuesta<Solicitud>

    fun solicitud(solicitudId: String): Respuesta<Solicitud>

    fun aceptarPrimaAjustada(solicitudId: String): Respuesta<Solicitud>

    fun generarPoliza(solicitudId: String): Respuesta<PolizaEnTramite>

    fun poliza(polizaId: String): Respuesta<PolizaEnTramite>

    /** [clave] es la Idempotency-Key del intento: la misma en un reintento de red (SOLVENTAG5-121). */
    fun cobrar(polizaId: String, tokenTarjeta: String, clave: String): Respuesta<Pago>

    fun pedirFirma(polizaId: String): Respuesta<Firma>

    fun reenviarFirma(polizaId: String): Respuesta<Firma>

    fun documentoFirma(polizaId: String): Respuesta<DocumentoFirma>

    fun firmar(polizaId: String, codigo: String): Respuesta<Firma>

    fun emitir(polizaId: String): Respuesta<PolizaEnTramite>
}

/**
 * Cliente de las rutas de contratación del BFF móvil (SOLVENTAG5-96). No decide nada del negocio:
 * el BFF y el núcleo aplican las reglas, y la app solo interpreta los códigos. Cada llamada
 * bloquea el hilo: se llama fuera del hilo principal.
 */
class ClienteContratacion(
    apiBaseUrl: String,
    timeoutMs: Int = ClienteHttp.TIMEOUT_MS,
    token: () -> String? = { null },
) : FuenteContratacion {
    private val http = ClienteHttp(apiBaseUrl, timeoutMs, token)

    override fun aceptarOferta(cotizacionId: String) =
        post(SOLICITUDES, JSONObject().put("cotizacionId", cotizacionId), LectorContratacion::solicitud)

    override fun solicitud(solicitudId: String) = get("$SOLICITUDES/$solicitudId", LectorContratacion::solicitud)

    override fun aceptarPrimaAjustada(solicitudId: String) =
        post("$SOLICITUDES/$solicitudId/aceptacion-prima", JSONObject(), LectorContratacion::solicitud)

    override fun generarPoliza(solicitudId: String) =
        post("$SOLICITUDES/$solicitudId/poliza", JSONObject(), LectorContratacion::poliza)

    override fun poliza(polizaId: String) = get("$POLIZAS/$polizaId", LectorContratacion::poliza)

    override fun cobrar(polizaId: String, tokenTarjeta: String, clave: String) = post(
        "$POLIZAS/$polizaId/cobro",
        JSONObject().put("medioPago", "TARJETA").put("token", tokenTarjeta),
        LectorContratacion::pago,
        mapOf("Idempotency-Key" to clave),
    )

    override fun pedirFirma(polizaId: String) = post("$POLIZAS/$polizaId/firma", JSONObject(), LectorContratacion::firma)

    override fun reenviarFirma(polizaId: String) =
        post("$POLIZAS/$polizaId/firma/reenvio", JSONObject(), LectorContratacion::firma)

    override fun documentoFirma(polizaId: String) =
        get("$POLIZAS/$polizaId/firma/documento", LectorContratacion::documento)

    override fun firmar(polizaId: String, codigo: String) =
        post("$POLIZAS/$polizaId/firma/validacion", JSONObject().put("codigo", codigo), LectorContratacion::firma)

    override fun emitir(polizaId: String) = post("$POLIZAS/$polizaId/emision", JSONObject(), LectorContratacion::poliza)

    private fun <T> get(ruta: String, leer: (JSONObject) -> T): Respuesta<T> = try {
        interpretar(http.getJson(ruta), leer)
    } catch (_: IOException) {
        Respuesta.SinConexion
    }

    private fun <T> post(
        ruta: String,
        cuerpo: JSONObject,
        leer: (JSONObject) -> T,
        cabeceras: Map<String, String> = emptyMap(),
    ): Respuesta<T> = try {
        interpretar(http.postJson(ruta, cuerpo, cabeceras), leer)
    } catch (_: IOException) {
        Respuesta.SinConexion
    }

    companion object {
        private const val SOLICITUDES = "/v1/solicitudes-suscripcion"
        private const val POLIZAS = "/v1/polizas"
        private val RECHAZOS = setOf(
            HttpURLConnection.HTTP_NOT_FOUND,
            HttpURLConnection.HTTP_CONFLICT,
            HTTP_UNPROCESSABLE,
        )

        /** 200 y 201 se leen con [leer]; 404, 409 y 422 con el cuerpo `Error`; 401, sin sesión. */
        fun <T> interpretar(respuesta: RespuestaHttp, leer: (JSONObject) -> T): Respuesta<T> = try {
            when (respuesta.codigo) {
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED -> Respuesta.Ok(leer(JSONObject(respuesta.cuerpo)))
                HttpURLConnection.HTTP_UNAUTHORIZED -> Respuesta.NoAutenticado
                in RECHAZOS -> rechazo(JSONObject(respuesta.cuerpo))
                else -> Respuesta.NoDisponible
            }
        } catch (_: JSONException) {
            Respuesta.NoDisponible
        }

        /** Un 422 de validación (`detail`, sin `codigo`) se lee como VALIDACION. */
        private fun rechazo(json: JSONObject) = Respuesta.Rechazo(
            codigo = json.optString("codigo").ifEmpty { VALIDACION },
            motivo = json.optString("motivo").takeIf { it.isNotEmpty() },
            intentosRestantes = if (json.has("intentosRestantes")) json.getInt("intentosRestantes") else null,
            siguientePaso = json.optString("siguientePaso").takeIf { it.isNotEmpty() },
        )

        const val VALIDACION = "VALIDACION"
        private const val HTTP_UNPROCESSABLE = 422
    }
}

/** Tokeniza la tarjeta con la pasarela; las pruebas lo reemplazan. */
fun interface PasarelaPagos {
    /** El token de la tarjeta, o null si la pasarela no la tokenizó o no respondió. */
    fun tokenizar(numero: String, vencimiento: String): String?
}

/**
 * La pasarela de pagos (simulada en dev, ADR 0004 D8). La app tokeniza la tarjeta directo con la
 * pasarela, nunca con el BFF: el número de la tarjeta no toca los servidores de Solventa, igual
 * que en el portal. Bloquea el hilo: se llama fuera del hilo principal.
 */
class ClientePasarela(private val pasarelaUrl: String, private val timeoutMs: Int = ClienteHttp.TIMEOUT_MS) :
    PasarelaPagos {

    override fun tokenizar(numero: String, vencimiento: String): String? = try {
        val conexion = URI("$pasarelaUrl/tokenizaciones").toURL().openConnection() as HttpURLConnection
        try {
            conexion.requestMethod = "POST"
            conexion.connectTimeout = timeoutMs
            conexion.readTimeout = timeoutMs
            conexion.doOutput = true
            conexion.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conexion.setRequestProperty("Accept", "application/json")
            val cuerpo = JSONObject().put("numero", numero).put("vencimiento", vencimiento)
            conexion.outputStream.use { it.write(cuerpo.toString().toByteArray()) }
            if (conexion.responseCode == HttpURLConnection.HTTP_OK) {
                val texto = conexion.inputStream.bufferedReader().use { it.readText() }
                JSONObject(texto).optString("token").takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } finally {
            conexion.disconnect()
        }
    } catch (_: IOException) {
        null
    } catch (_: JSONException) {
        null
    }
}
