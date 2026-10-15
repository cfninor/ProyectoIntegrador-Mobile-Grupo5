package co.solventa.app.features.cotizacion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
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

    private fun cotizar() {
        val solicitud = solicitud() ?: return
        binding.mensajeError.visibility = View.GONE
        hilo.execute {
            val resultado = cliente.cotizar(solicitud)
            runOnUiThread { if (!isDestroyed) mostrar(resultado) }
        }
    }

    private fun mostrar(resultado: ResultadoCotizacion) {
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
        binding.formulario.visibility = View.GONE
        binding.resultado.visibility = View.VISIBLE
    }

    private fun mostrarFormulario() {
        binding.resultado.visibility = View.GONE
        binding.formulario.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_CLIENTE_ID = "co.solventa.app.extra.CLIENTE_ID"

        fun intent(contexto: Context, clienteId: String? = null): Intent =
            Intent(contexto, CotizacionActivity::class.java).putExtra(EXTRA_CLIENTE_ID, clienteId)
    }
}
