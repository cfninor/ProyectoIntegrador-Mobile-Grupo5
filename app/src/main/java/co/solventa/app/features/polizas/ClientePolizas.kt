package co.solventa.app.features.polizas

import co.solventa.app.core.red.ClienteHttp
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection

/** Resultado de pedir las pólizas del cliente. */
sealed interface ResultadoPolizas {
    /** 200: las pólizas del cliente; vacía si no tiene ninguna (criterio 5: no es un error). */
    data class Listadas(val polizas: List<Poliza>) : ResultadoPolizas

    /** 503, cuerpo ilegible u otra respuesta inesperada. */
    data object NoDisponible : ResultadoPolizas

    /** No hubo respuesta: sin red, tiempo de espera o conexión rechazada. */
    data object SinConexion : ResultadoPolizas

    /** 401: sin sesión o con la sesión vencida; la app vuelve a pedir el inicio de sesión. */
    data object NoAutenticado : ResultadoPolizas
}

/** Resultado de pedir una póliza. */
sealed interface ResultadoPoliza {
    data class Encontrada(val poliza: Poliza) : ResultadoPoliza

    /** 404: no existe o no es del cliente; las dos se ven igual (criterio 4). */
    data object NoEncontrada : ResultadoPoliza

    data object NoDisponible : ResultadoPoliza

    data object SinConexion : ResultadoPoliza

    /** 401: sin sesión o con la sesión vencida; la app vuelve a pedir el inicio de sesión. */
    data object NoAutenticado : ResultadoPoliza
}

/** Resultado de descargar el PDF de la póliza. */
sealed interface ResultadoDocumento {
    class Descargado(val pdf: ByteArray) : ResultadoDocumento

    /** 409: el documento todavía se está generando. */
    data object NoDisponibleAun : ResultadoDocumento

    data object NoDisponible : ResultadoDocumento

    data object SinConexion : ResultadoDocumento

    /** 401: sin sesión o con la sesión vencida; la app vuelve a pedir el inicio de sesión. */
    data object NoAutenticado : ResultadoDocumento
}

/** Lo que las pantallas de la billetera necesitan del BFF móvil; las pruebas lo reemplazan. */
interface FuentePolizas {
    fun listar(): ResultadoPolizas

    fun obtener(polizaId: String): ResultadoPoliza

    /** El PDF de la póliza: la carátula con las coberturas, las exclusiones y la versión de las condiciones. */
    fun descargar(polizaId: String): ResultadoDocumento
}

/**
 * Cliente de las rutas de la billetera del BFF móvil (SOLVENTAG5-104). Solo lee: la app no decide
 * qué pólizas son del cliente, eso lo resuelve el BFF con el núcleo (`IPoliza.vigentes_de`). Cada
 * llamada bloquea el hilo: se llama fuera del hilo principal.
 */
class ClientePolizas(
    apiBaseUrl: String,
    timeoutMs: Int = ClienteHttp.TIMEOUT_MS,
    token: () -> String? = { null },
) : FuentePolizas {
    private val http = ClienteHttp(apiBaseUrl, timeoutMs, token)

    override fun listar(): ResultadoPolizas = try {
        val respuesta = http.getJson(RUTA)
        interpretarLista(respuesta.codigo, respuesta.cuerpo)
    } catch (_: IOException) {
        ResultadoPolizas.SinConexion
    }

    override fun obtener(polizaId: String): ResultadoPoliza = try {
        val respuesta = http.getJson("$RUTA/$polizaId")
        interpretarPoliza(respuesta.codigo, respuesta.cuerpo)
    } catch (_: IOException) {
        ResultadoPoliza.SinConexion
    }

    override fun descargar(polizaId: String): ResultadoDocumento = try {
        val (codigo, pdf) = http.getBytes("$RUTA/$polizaId/documento", TIPO_PDF)
        when {
            pdf != null -> ResultadoDocumento.Descargado(pdf)
            codigo == HttpURLConnection.HTTP_UNAUTHORIZED -> ResultadoDocumento.NoAutenticado
            codigo == HttpURLConnection.HTTP_CONFLICT -> ResultadoDocumento.NoDisponibleAun
            else -> ResultadoDocumento.NoDisponible
        }
    } catch (_: IOException) {
        ResultadoDocumento.SinConexion
    }

    companion object {
        private const val RUTA = "/v1/polizas"
        private const val TIPO_PDF = "application/pdf"

        fun interpretarLista(codigo: Int, cuerpo: String): ResultadoPolizas = try {
            when (codigo) {
                HttpURLConnection.HTTP_OK -> ResultadoPolizas.Listadas(LectorPoliza.lista(JSONArray(cuerpo)))
                HttpURLConnection.HTTP_UNAUTHORIZED -> ResultadoPolizas.NoAutenticado
                else -> ResultadoPolizas.NoDisponible
            }
        } catch (_: JSONException) {
            ResultadoPolizas.NoDisponible
        }

        fun interpretarPoliza(codigo: Int, cuerpo: String): ResultadoPoliza = try {
            when (codigo) {
                HttpURLConnection.HTTP_OK -> ResultadoPoliza.Encontrada(LectorPoliza.poliza(JSONObject(cuerpo)))
                HttpURLConnection.HTTP_NOT_FOUND -> ResultadoPoliza.NoEncontrada
                HttpURLConnection.HTTP_UNAUTHORIZED -> ResultadoPoliza.NoAutenticado
                else -> ResultadoPoliza.NoDisponible
            }
        } catch (_: JSONException) {
            ResultadoPoliza.NoDisponible
        }
    }
}
