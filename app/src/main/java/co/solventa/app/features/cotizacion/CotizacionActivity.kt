package co.solventa.app.features.cotizacion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import co.solventa.app.R
import co.solventa.app.core.config.AppConfig
import co.solventa.app.databinding.ActivityCotizacionBinding
import com.google.android.material.textfield.TextInputLayout
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

    /** Campos que el cliente ya tocó: solo a esos se les muestra el error, no al abrir. */
    private val tocados = mutableSetOf<CampoCotizacion>()

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

        savedInstanceState?.getStringArrayList(ESTADO_TOCADOS)?.mapTo(tocados, CampoCotizacion::valueOf)
        observar(binding.monto, CampoCotizacion.MONTO)
        observar(binding.plazo, CampoCotizacion.PLAZO)
        observar(binding.ciudad, CampoCotizacion.CIUDAD)
        observar(binding.entidad, CampoCotizacion.ENTIDAD)
        binding.cotizar.setOnClickListener { cotizar() }
        binding.nuevaCotizacion.setOnClickListener { mostrarFormulario() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(ESTADO_TOCADOS, ArrayList(tocados.map { it.name }))
    }

    override fun onDestroy() {
        hilo.shutdownNow()
        super.onDestroy()
    }

    private fun datos() = DatosCotizacion(
        monto = binding.monto.text?.toString().orEmpty(),
        plazo = binding.plazo.text?.toString().orEmpty(),
        ciudad = binding.ciudad.text?.toString().orEmpty(),
        entidad = binding.entidad.text?.toString().orEmpty(),
    )

    private fun observar(texto: EditText, campo: CampoCotizacion) {
        texto.doAfterTextChanged {
            tocados += campo
            actualizar()
        }
        texto.setOnFocusChangeListener { _, conFoco ->
            if (!conFoco) {
                tocados += campo
                actualizar()
            }
        }
    }

    /**
     * Criterio 4: marca cada campo inválido (solo los que el cliente ya tocó) y habilita «Cotizar»
     * únicamente cuando los cuatro campos cumplen los rangos del contrato.
     */
    private fun actualizar() {
        val errores = ValidadorCotizacion.validar(datos())
        CampoCotizacion.entries.forEach { campo ->
            val error = errores[campo]?.takeIf { campo in tocados }
            contenedorDe(campo).error = error?.let { getString(mensajeDe(it)) }
        }
        binding.cotizar.isEnabled = errores.isEmpty() && !cotizando
        binding.mensajeError.visibility = View.GONE
    }

    /**
     * Criterio 3: la solicitud corre fuera del hilo principal. Si el núcleo espera al perfil de
     * riesgo, la pantalla sigue respondiendo y muestra el progreso; un segundo toque no repite la
     * solicitud.
     */
    private fun cotizar() {
        if (cotizando) return
        val solicitud = ValidadorCotizacion.solicitud(datos(), clienteId) ?: return
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
        binding.cotizar.isEnabled = !activo && ValidadorCotizacion.validar(datos()).isEmpty()
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

    private fun contenedorDe(campo: CampoCotizacion): TextInputLayout = when (campo) {
        CampoCotizacion.MONTO -> binding.campoMonto
        CampoCotizacion.PLAZO -> binding.campoPlazo
        CampoCotizacion.CIUDAD -> binding.campoCiudad
        CampoCotizacion.ENTIDAD -> binding.campoEntidad
    }

    @StringRes
    private fun mensajeDe(error: ErrorCotizacion): Int = when (error) {
        ErrorCotizacion.OBLIGATORIO -> R.string.cotizacion_error_obligatorio
        ErrorCotizacion.MONTO_FUERA_DE_RANGO -> R.string.cotizacion_error_monto
        ErrorCotizacion.PLAZO_FUERA_DE_RANGO -> R.string.cotizacion_error_plazo
        ErrorCotizacion.CIUDAD_INVALIDA -> R.string.cotizacion_error_ciudad
        ErrorCotizacion.ENTIDAD_INVALIDA -> R.string.cotizacion_error_entidad
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
        private const val ESTADO_TOCADOS = "tocados"
        private const val MOTIVO_SIN_CLIENTE = "SIN_CLIENTE"
        private const val MOTIVO_SIN_CONSENTIMIENTO = "SIN_CONSENTIMIENTO"

        fun intent(contexto: Context, clienteId: String? = null): Intent =
            Intent(contexto, CotizacionActivity::class.java).putExtra(EXTRA_CLIENTE_ID, clienteId)
    }
}
