package co.solventa.app.core.sesion

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import co.solventa.app.R
import co.solventa.app.core.config.AppConfig
import co.solventa.app.databinding.ActivityInicioSesionBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Inicio de sesión del cliente (SOLVENTAG5-104) con la misma cuenta del portal. Al iniciarla
 * guarda los tokens en [SesionCliente] y termina con `RESULT_OK`; la pantalla que la abrió sigue.
 * Con credenciales inválidas, un mensaje que no revela si la cuenta existe.
 */
class InicioSesionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityInicioSesionBinding
    private val hilo: ExecutorService = Executors.newSingleThreadExecutor()
    private var entrando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityInicioSesionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        binding.correo.doAfterTextChanged { actualizar() }
        binding.contrasena.doAfterTextChanged { actualizar() }
        binding.entrar.setOnClickListener { entrar() }
    }

    override fun onDestroy() {
        hilo.shutdownNow()
        super.onDestroy()
    }

    private fun correo() = binding.correo.text?.toString().orEmpty().trim()

    private fun contrasena() = binding.contrasena.text?.toString().orEmpty()

    /** «Iniciar sesión» solo con un correo bien formado y una contraseña. */
    private fun actualizar() {
        binding.entrar.isEnabled = !entrando && Patterns.EMAIL_ADDRESS.matcher(correo()).matches() && contrasena().isNotEmpty()
        binding.mensajeError.visibility = View.GONE
    }

    private fun entrar() {
        if (entrando) return
        val cognito = SesionCliente.cognito ?: return
        val correo = correo()
        val contrasena = contrasena()
        mostrarEntrando(true)
        hilo.execute {
            val resultado = cognito.iniciarSesion(correo, contrasena)
            runOnUiThread { if (!isDestroyed) mostrar(resultado) }
        }
    }

    private fun mostrar(resultado: ResultadoInicioSesion) {
        mostrarEntrando(false)
        when (resultado) {
            is ResultadoInicioSesion.Iniciada -> {
                SesionCliente.guardar(resultado.tokens)
                setResult(RESULT_OK)
                finish()
            }
            ResultadoInicioSesion.CredencialesInvalidas -> {
                binding.contrasena.text = null
                mostrarError(R.string.sesion_error_credenciales)
            }
            ResultadoInicioSesion.CuentaSinConfirmar -> mostrarError(R.string.sesion_error_sin_confirmar)
            ResultadoInicioSesion.NoDisponible -> mostrarError(R.string.sesion_error_no_disponible)
            ResultadoInicioSesion.SinConexion -> mostrarError(R.string.sesion_error_sin_conexion)
        }
    }

    private fun mostrarEntrando(activo: Boolean) {
        entrando = activo
        binding.progreso.visibility = if (activo) View.VISIBLE else View.GONE
        binding.campoCorreo.isEnabled = !activo
        binding.campoContrasena.isEnabled = !activo
        actualizar()
    }

    private fun mostrarError(@StringRes mensaje: Int) {
        binding.mensajeError.setText(mensaje)
        binding.mensajeError.visibility = View.VISIBLE
    }

    companion object {
        fun intent(contexto: Context): Intent = Intent(contexto, InicioSesionActivity::class.java)

        /**
         * Prepara el cliente de Cognito del ambiente para [SesionCliente], una sola vez. Sin el id
         * del cliente app-movil (local), no hace nada: el BFF usa la sesión simulada.
         */
        fun configurar(config: AppConfig = AppConfig.actual()) {
            if (config.conInicioDeSesion && SesionCliente.cognito == null) {
                SesionCliente.cognito = ClienteCognito(config.cognitoRegion, config.cognitoClienteMovilId)
            }
        }

        /** `true` si el ambiente pide sesión y todavía no hay una. */
        @VisibleForTesting
        internal var faltaSesion: () -> Boolean = {
            AppConfig.actual().conInicioDeSesion && !SesionCliente.iniciada
        }
    }
}
