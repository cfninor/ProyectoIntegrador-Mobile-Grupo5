package co.solventa.app.features.cotizacion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import co.solventa.app.R
import co.solventa.app.core.config.AppConfig
import co.solventa.app.databinding.ActivityCotizacionBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pantalla «Cotizar» (SOLVENTAG5-95): el cliente ingresa los datos de su crédito hipotecario y
 * ve la prima mensual que calcula el núcleo, con su vigencia y el número de cotización.
 */
class CotizacionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCotizacionBinding

    /** Las pruebas lo reemplazan por un cliente que apunta a un BFF de prueba. */
    @VisibleForTesting
    internal var cliente = ClienteCotizacion(AppConfig.actual().apiBaseUrl)
    private val hilo: ExecutorService = Executors.newSingleThreadExecutor()

    /** El del onboarding, si el cliente viene de ahí; sin él la prima usa el perfil por defecto. */
    private val clienteId: String? by lazy { intent.getStringExtra(EXTRA_CLIENTE_ID) }
    private var cotizando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCotizacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        binding.cotizar.setOnClickListener { cotizar() }
        binding.nuevaCotizacion.setOnClickListener { mostrarFormulario() }
    }

    override fun onDestroy() {
        hilo.shutdownNow()
        super.onDestroy()
    }

    private fun solicitud(): SolicitudCotizacion? {
        val monto = binding.monto.text?.toString()?.trim()?.toLongOrNull() ?: return null
        val plazo = binding.plazo.text?.toString()?.trim()?.toIntOrNull() ?: return null
        val ciudad = binding.ciudad.text?.toString()?.trim().orEmpty()
        val entidad = binding.entidad.text?.toString()?.trim().orEmpty()
        if (ciudad.isEmpty() || entidad.isEmpty()) return null
        return SolicitudCotizacion(monto, plazo, ciudad, entidad, clienteId)
    }

    /**
     * Criterio 3: la solicitud corre fuera del hilo principal. Si el núcleo espera al perfil de
     * riesgo, la pantalla sigue respondiendo y muestra el progreso; un segundo toque no repite la
     * solicitud.
     */
    private fun cotizar() {
        if (cotizando) return
        val solicitud = solicitud() ?: return
        binding.mensajeError.visibility = View.GONE
        mostrarCotizando(true)
        hilo.execute {
            val resultado = cliente.cotizar(solicitud)
            runOnUiThread { if (!isDestroyed) mostrar(resultado) }
        }
    }

    private fun mostrarCotizando(activo: Boolean) {
        cotizando = activo
        binding.progreso.visibility = if (activo) View.VISIBLE else View.GONE
        binding.cotizar.isEnabled = !activo
        listOf(binding.campoMonto, binding.campoPlazo, binding.campoCiudad, binding.campoEntidad)
            .forEach { it.isEnabled = !activo }
    }

    private fun mostrar(resultado: ResultadoCotizacion) {
        mostrarCotizando(false)
        when (resultado) {
            is ResultadoCotizacion.Cotizada -> mostrarCotizacion(resultado.cotizacion)
            ResultadoCotizacion.NoDisponible -> {
                binding.mensajeError.setText(R.string.cotizacion_error_general)
                binding.mensajeError.visibility = View.VISIBLE
            }
        }
    }

    /** Criterio 2: la prima estimada en pantalla, con su vigencia y el número de cotización. */
    private fun mostrarCotizacion(cotizacion: Cotizacion) {
        binding.prima.text = FormatoCotizacion.pesos(cotizacion.prima, cotizacion.moneda)
        binding.vigencia.text = getString(
            R.string.cotizacion_resultado_vigencia,
            FormatoCotizacion.fecha(cotizacion.vigenteHasta),
        )
        binding.numeroCotizacion.text = getString(
            R.string.cotizacion_resultado_numero,
            cotizacion.cotizacionId.substringBefore('-').uppercase(),
        )
        // Si el perfil de riesgo no llegó (BA-01, BA-02), el núcleo cotiza con el perfil por
        // defecto en vez de dejar esperando al cliente; la pantalla explica por qué.
        val aviso = if (cotizacion.calculadaSinPerfil) avisoSinPerfil(cotizacion.motivoSinPerfil) else null
        binding.avisoSinPerfil.text = aviso?.let(::getString)
        binding.avisoSinPerfil.visibility = if (aviso == null) View.GONE else View.VISIBLE
        binding.formulario.visibility = View.GONE
        binding.resultado.visibility = View.VISIBLE
    }

    @StringRes
    private fun avisoSinPerfil(motivo: String?): Int = when (motivo) {
        MOTIVO_SIN_CLIENTE -> R.string.cotizacion_sin_perfil_sin_cliente
        MOTIVO_SIN_CONSENTIMIENTO -> R.string.cotizacion_sin_perfil_sin_consentimiento
        else -> R.string.cotizacion_sin_perfil_no_disponible
    }

    private fun mostrarFormulario() {
        binding.resultado.visibility = View.GONE
        binding.formulario.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_CLIENTE_ID = "co.solventa.app.extra.CLIENTE_ID"
        private const val MOTIVO_SIN_CLIENTE = "SIN_CLIENTE"
        private const val MOTIVO_SIN_CONSENTIMIENTO = "SIN_CONSENTIMIENTO"

        fun intent(contexto: Context, clienteId: String? = null): Intent =
            Intent(contexto, CotizacionActivity::class.java).putExtra(EXTRA_CLIENTE_ID, clienteId)
    }
}
