package co.solventa.app.features.polizas

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import co.solventa.app.R
import co.solventa.app.core.config.AppConfig
import co.solventa.app.core.red.PermisoRedLocal
import co.solventa.app.core.sesion.InicioSesionActivity
import co.solventa.app.core.sesion.SesionCliente
import co.solventa.app.databinding.ActivityDetallePolizaBinding
import co.solventa.app.features.cotizacion.FormatoCotizacion
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Detalle de la póliza (SOLVENTAG5-104, criterios 2 y 3): el tomador con su documento enmascarado,
 * la suma asegurada, la prima, el beneficiario, la vigencia, el próximo cobro y el historial de
 * pagos; la descarga del PDF con la carátula y las condiciones generales, y el certificado de la
 * firma. La
 * póliza se pide al BFF por su id: una ajena responde 404 y se muestra como no encontrada.
 */
class DetallePolizaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetallePolizaBinding
    private val fuente: FuentePolizas by lazy { fabricaFuente() }
    private val hilo: ExecutorService = Executors.newSingleThreadExecutor()
    private val polizaId: String by lazy { intent.getStringExtra(EXTRA_POLIZA_ID).orEmpty() }
    private var poliza: Poliza? = null
    private var descargando = false

    /** La sesión venció mientras se veía la póliza: con una nueva se vuelve a cargar. */
    private val pedirSesion = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { resultado ->
        if (resultado.resultCode == RESULT_OK) cargar() else finish()
    }

    /** Lo que se estaba por hacer cuando se pidió el permiso de red local. */
    private var pendiente: (() -> Unit)? = null
    private val pedirRedLocal = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        val accion = pendiente
        pendiente = null
        if (concedido) accion?.invoke() else mostrarError(R.string.error_red_local)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDetallePolizaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        binding.reintentar.setOnClickListener { cargar() }
        binding.descargarPoliza.setOnClickListener { descargar() }
        cargar()
    }

    override fun onDestroy() {
        hilo.shutdownNow()
        super.onDestroy()
    }

    private fun conRedLocal(accion: () -> Unit) {
        if (PermisoRedLocal.falta(this)) {
            pendiente = accion
            pedirRedLocal.launch(PermisoRedLocal.PERMISO)
        } else {
            accion()
        }
    }

    private fun cargar() = conRedLocal {
        mostrarSolo(binding.cargando)
        hilo.execute {
            val resultado = fuente.obtener(polizaId)
            runOnUiThread { if (!isDestroyed) mostrar(resultado) }
        }
    }

    private fun mostrar(resultado: ResultadoPoliza) {
        when (resultado) {
            is ResultadoPoliza.Encontrada -> mostrarPoliza(resultado.poliza)
            ResultadoPoliza.NoEncontrada -> {
                mostrarError(R.string.detalle_no_encontrada)
                binding.reintentar.visibility = View.GONE
            }
            ResultadoPoliza.NoDisponible -> mostrarError(R.string.polizas_error_no_disponible)
            ResultadoPoliza.SinConexion -> mostrarError(R.string.polizas_error_sin_conexion)
            ResultadoPoliza.NoAutenticado -> iniciarSesion()
        }
    }

    private fun iniciarSesion() {
        SesionCliente.cerrar()
        pedirSesion.launch(InicioSesionActivity.intent(this))
    }

    private fun mostrarPoliza(p: Poliza) {
        poliza = p
        val locale = FormatoCotizacion.localeDe(resources.configuration.locales[0])
        binding.producto.text = TextosPoliza.producto(this, p.ramo)
        binding.numero.text = TextosPoliza.numero(this, p)
        binding.estado.setText(TextosPoliza.estado(p.estado))
        binding.tomador.text = getString(
            R.string.detalle_tomador_valor,
            "${p.tomador.nombres} ${p.tomador.apellidos}",
            p.tomador.documentoEnmascarado,
        )
        binding.sumaAsegurada.text = FormatoPoliza.pesos(p.sumaAsegurada, p.moneda, locale)
        val prima = FormatoPoliza.pesos(p.prima, p.moneda, locale)
        binding.prima.text = if (p.periodicidad == MENSUAL) getString(R.string.detalle_prima_mensual, prima) else prima
        mostrarFila(binding.filaBeneficiario, binding.beneficiario, p.beneficiario)
        binding.vigencia.text = FormatoPoliza.vigencia(p.vigenciaDesde, p.vigenciaHasta, locale)
        mostrarFila(
            binding.filaProximoCobro,
            binding.proximoCobro,
            p.proximoCobro?.let {
                getString(
                    R.string.detalle_proximo_cobro_valor,
                    FormatoPoliza.fecha(it.fecha, locale),
                    FormatoPoliza.pesos(it.valor, it.moneda, locale),
                )
            },
        )
        mostrarPagos(p.pagos, locale)
        binding.seccionCertificado.visibility = if (p.certificado == null) View.GONE else View.VISIBLE
        binding.certificado.text = p.certificado?.let {
            getString(R.string.detalle_certificado, FormatoPoliza.fechaHora(it.firmadaEn, locale), it.referencia, it.huella)
        }
        binding.mensajeDocumento.visibility = View.GONE
        mostrarSolo(binding.contenido)
    }

    private fun mostrarFila(fila: View, valor: TextView, texto: String?) {
        valor.text = texto
        fila.visibility = if (texto == null) View.GONE else View.VISIBLE
    }

    private fun mostrarPagos(pagos: List<PagoPoliza>, locale: Locale) {
        binding.pagos.removeAllViews()
        pagos.forEach { pago ->
            val fila = TextView(this)
            fila.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            val monto = FormatoPoliza.pesos(pago.monto, pago.moneda, locale)
            val linea = getString(R.string.detalle_pago, FormatoPoliza.fecha(pago.fecha, locale), monto)
            fila.text = if (pago.medio.isEmpty()) linea else "$linea\n${pago.medio}"
            fila.setPadding(0, ESPACIO_PAGO, 0, ESPACIO_PAGO)
            binding.pagos.addView(fila)
        }
        binding.sinPagos.visibility = if (pagos.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Criterio 3: descarga el PDF de la póliza (carátula, coberturas, exclusiones y versión de las
     * condiciones generales) fuera del hilo principal y lo abre con el visor del dispositivo.
     */
    private fun descargar() {
        val p = poliza ?: return
        if (descargando) return
        conRedLocal {
            mostrarDescargando(true)
            hilo.execute {
                val resultado = fuente.descargar(p.polizaId)
                runOnUiThread {
                    if (!isDestroyed) {
                        mostrarDescargando(false)
                        mostrarDocumento(p, resultado)
                    }
                }
            }
        }
    }

    private fun mostrarDescargando(activo: Boolean) {
        descargando = activo
        binding.descargarPoliza.isEnabled = !activo
        if (activo) mensajeDocumento(R.string.detalle_descargando)
    }

    private fun mostrarDocumento(p: Poliza, resultado: ResultadoDocumento) {
        when (resultado) {
            is ResultadoDocumento.Descargado -> abrir(guardar(p, resultado.pdf))
            ResultadoDocumento.NoDisponibleAun -> mensajeDocumento(R.string.detalle_documento_no_disponible_aun)
            ResultadoDocumento.NoDisponible -> mensajeDocumento(R.string.detalle_documento_error)
            ResultadoDocumento.SinConexion -> mensajeDocumento(R.string.polizas_error_sin_conexion)
            ResultadoDocumento.NoAutenticado -> iniciarSesion()
        }
    }

    /**
     * En la caché privada de la app (no en Descargas): son datos personales, sin respaldo en la
     * nube (caso 6.4). El nombre es el número de la póliza; una descarga nueva lo reemplaza.
     */
    @VisibleForTesting
    internal fun guardar(p: Poliza, pdf: ByteArray): File {
        val carpeta = File(cacheDir, CARPETA_DOCUMENTOS).apply { mkdirs() }
        val nombre = (p.numero ?: p.polizaId).replace(Regex("[^A-Za-z0-9-]"), "_")
        return File(carpeta, "$nombre.pdf").apply { writeBytes(pdf) }
    }

    private fun abrir(archivo: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName$AUTORIDAD", archivo)
        val ver = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, TIPO_PDF)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(ver)
            binding.mensajeDocumento.visibility = View.GONE
        } catch (_: ActivityNotFoundException) {
            mensajeDocumento(R.string.detalle_sin_visor)
        }
    }

    private fun mensajeDocumento(@StringRes mensaje: Int) {
        binding.mensajeDocumento.setText(mensaje)
        binding.mensajeDocumento.visibility = View.VISIBLE
    }

    private fun mostrarError(@StringRes mensaje: Int) {
        binding.mensajeError.setText(mensaje)
        binding.reintentar.visibility = View.VISIBLE
        mostrarSolo(binding.error)
    }

    private fun mostrarSolo(vista: View) {
        listOf(binding.cargando, binding.error, binding.contenido).forEach {
            it.visibility = if (it === vista) View.VISIBLE else View.GONE
        }
    }

    companion object {
        private const val EXTRA_POLIZA_ID = "co.solventa.app.extra.POLIZA_ID"
        private const val MENSUAL = "MENSUAL"
        private const val TIPO_PDF = "application/pdf"
        private const val CARPETA_DOCUMENTOS = "documentos"

        /** La del `<provider>` del manifiesto, después del id de la app. */
        private const val AUTORIDAD = ".documentos"
        private const val ESPACIO_PAGO = 8

        /** Las pruebas la reemplazan antes de abrir la pantalla, porque carga al crearse. */
        @VisibleForTesting
        internal var fabricaFuente: () -> FuentePolizas = {
            ClientePolizas(AppConfig.actual().apiBaseUrl, token = SesionCliente::idToken)
        }

        fun intent(contexto: Context, polizaId: String): Intent =
            Intent(contexto, DetallePolizaActivity::class.java).putExtra(EXTRA_POLIZA_ID, polizaId)
    }
}
