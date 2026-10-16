package co.solventa.app.features.onboarding

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import co.solventa.app.R
import co.solventa.app.core.config.AppConfig
import co.solventa.app.core.red.PermisoRedLocal
import co.solventa.app.databinding.ActivityOnboardingBinding
import co.solventa.app.features.cotizacion.CotizacionActivity
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pantalla «Iniciar onboarding» (SOLVENTAG5-89): el cliente ingresa sus datos básicos, la app
 * valida el formato de cada campo y solo deja continuar cuando los seis campos son válidos; luego pide al BFF
 * móvil crear el Cliente en estado «onboarding en curso».
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    /** Las pruebas lo reemplazan por un cliente que apunta a un BFF de prueba. */
    @VisibleForTesting
    internal var cliente = ClienteOnboarding(AppConfig.actual().apiBaseUrl)
    private val hilo: ExecutorService = Executors.newSingleThreadExecutor()

    /** Campos que el cliente ya tocó: solo a esos se les muestra el error, no al abrir. */
    private val tocados = mutableSetOf<Campo>()
    private var tipoDocumento: TipoDocumento? = null
    private var enviando = false

    /** Solo en dev: Android 17 pide permiso para llegar al BFF local (ver src/dev/AndroidManifest). */
    private val pedirRedLocal = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) enviar() else mostrarError(R.string.error_red_local)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        savedInstanceState?.let { estado ->
            tipoDocumento = estado.getString(ESTADO_TIPO)?.let(TipoDocumento::valueOf)
            estado.getStringArrayList(ESTADO_TOCADOS)?.mapTo(tocados, Campo::valueOf)
        }
        configurarTipoDocumento()
        observar(binding.numeroDocumento, Campo.NUMERO_DOCUMENTO)
        observar(binding.nombres, Campo.NOMBRES)
        observar(binding.apellidos, Campo.APELLIDOS)
        observar(binding.correo, Campo.CORREO)
        observar(binding.celular, Campo.CELULAR)
        binding.continuar.setOnClickListener { enviar() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(ESTADO_TIPO, tipoDocumento?.name)
        outState.putStringArrayList(ESTADO_TOCADOS, ArrayList(tocados.map { it.name }))
    }

    override fun onDestroy() {
        hilo.shutdownNow()
        super.onDestroy()
    }

    private fun configurarTipoDocumento() {
        val opciones = TipoDocumento.entries.map { getString(nombreDe(it)) }
        binding.tipoDocumento.setSimpleItems(opciones.toTypedArray())
        binding.tipoDocumento.setOnItemClickListener { _, _, posicion, _ ->
            tipoDocumento = TipoDocumento.entries[posicion]
            tocados += Campo.TIPO_DOCUMENTO
            actualizar()
        }
    }

    private fun observar(texto: EditText, campo: Campo) {
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

    private fun datos() = DatosBasicos(
        tipoDocumento = tipoDocumento,
        numeroDocumento = binding.numeroDocumento.text?.toString().orEmpty(),
        nombres = binding.nombres.text?.toString().orEmpty(),
        apellidos = binding.apellidos.text?.toString().orEmpty(),
        correo = binding.correo.text?.toString().orEmpty(),
        celular = binding.celular.text?.toString().orEmpty(),
    )

    /** Criterio 2: marca los campos inválidos y habilita «Continuar» solo si los campos son válidos. */
    private fun actualizar() {
        val errores = ValidadorOnboarding.validar(datos())
        Campo.entries.forEach { campo ->
            val error = errores[campo]?.takeIf { campo in tocados }
            contenedorDe(campo).error = error?.let { getString(mensajeDe(it)) }
        }
        binding.continuar.isEnabled = errores.isEmpty() && !enviando
        binding.mensajeError.visibility = View.GONE
    }

    private fun enviar() {
        val datos = datos()
        if (ValidadorOnboarding.validar(datos).isNotEmpty()) return
        if (PermisoRedLocal.falta(this)) {
            pedirRedLocal.launch(PermisoRedLocal.PERMISO)
            return
        }
        mostrarEnviando(true)
        hilo.execute {
            val resultado = cliente.iniciar(datos)
            runOnUiThread { if (!isDestroyed) mostrar(resultado) }
        }
    }

    private fun mostrar(resultado: ResultadoOnboarding) {
        mostrarEnviando(false)
        when (resultado) {
            is ResultadoOnboarding.Iniciado -> {
                binding.formulario.visibility = View.GONE
                binding.exito.visibility = View.VISIBLE
                // Con el clienteId, el núcleo cotiza con el perfil de riesgo del cliente.
                binding.cotizarSeguro.setOnClickListener {
                    startActivity(CotizacionActivity.intent(this, resultado.clienteId))
                }
            }
            is ResultadoOnboarding.Invalido -> {
                resultado.campos.forEach { campo ->
                    contenedorDe(campo).error = getString(R.string.onboarding_error_formato)
                }
                mostrarError(R.string.onboarding_error_formato)
            }
            ResultadoOnboarding.NoDisponible -> mostrarError(R.string.onboarding_error_no_disponible)
            ResultadoOnboarding.SinConexion -> mostrarError(R.string.onboarding_error_sin_conexion)
        }
    }

    private fun mostrarEnviando(activo: Boolean) {
        enviando = activo
        binding.progreso.visibility = if (activo) View.VISIBLE else View.GONE
        binding.continuar.isEnabled = !activo && ValidadorOnboarding.validar(datos()).isEmpty()
        listOf(
            binding.campoTipoDocumento, binding.campoNumeroDocumento, binding.campoNombres,
            binding.campoApellidos, binding.campoCorreo, binding.campoCelular,
        ).forEach { it.isEnabled = !activo }
    }

    private fun mostrarError(@StringRes mensaje: Int) {
        binding.mensajeError.setText(mensaje)
        binding.mensajeError.visibility = View.VISIBLE
    }

    private fun contenedorDe(campo: Campo): TextInputLayout = when (campo) {
        Campo.TIPO_DOCUMENTO -> binding.campoTipoDocumento
        Campo.NUMERO_DOCUMENTO -> binding.campoNumeroDocumento
        Campo.NOMBRES -> binding.campoNombres
        Campo.APELLIDOS -> binding.campoApellidos
        Campo.CORREO -> binding.campoCorreo
        Campo.CELULAR -> binding.campoCelular
    }

    @StringRes
    private fun nombreDe(tipo: TipoDocumento): Int = when (tipo) {
        TipoDocumento.CC -> R.string.onboarding_tipo_cc
        TipoDocumento.CE -> R.string.onboarding_tipo_ce
        TipoDocumento.PA -> R.string.onboarding_tipo_pa
        TipoDocumento.PPT -> R.string.onboarding_tipo_ppt
    }

    @StringRes
    private fun mensajeDe(error: ErrorCampo): Int = when (error) {
        ErrorCampo.OBLIGATORIO -> R.string.onboarding_error_obligatorio
        ErrorCampo.DOCUMENTO_INVALIDO -> R.string.onboarding_error_documento
        ErrorCampo.PASAPORTE_INVALIDO -> R.string.onboarding_error_pasaporte
        ErrorCampo.NOMBRE_INVALIDO -> R.string.onboarding_error_nombre
        ErrorCampo.CORREO_INVALIDO -> R.string.onboarding_error_correo
        ErrorCampo.CELULAR_INVALIDO -> R.string.onboarding_error_celular
    }

    private companion object {
        const val ESTADO_TIPO = "tipoDocumento"
        const val ESTADO_TOCADOS = "tocados"
    }
}
