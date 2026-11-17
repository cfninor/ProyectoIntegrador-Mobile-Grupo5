package co.solventa.app.features.contratacion

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import co.solventa.app.MainActivity
import co.solventa.app.R
import co.solventa.app.core.almacenamiento.AlmacenCifradoKeystore
import co.solventa.app.core.config.AppConfig
import co.solventa.app.core.red.PermisoRedLocal
import co.solventa.app.core.sesion.InicioSesionActivity
import co.solventa.app.core.sesion.SesionCliente
import co.solventa.app.databinding.ActivityConfirmacionBinding
import co.solventa.app.features.cotizacion.FormatoCotizacion
import co.solventa.app.features.polizas.ClientePolizas
import co.solventa.app.features.polizas.DecodificadorJwt
import co.solventa.app.features.polizas.DetallePolizaActivity
import co.solventa.app.features.polizas.EstadoPoliza
import co.solventa.app.features.polizas.FormatoPoliza
import co.solventa.app.features.polizas.FuentePolizas
import co.solventa.app.features.polizas.Poliza
import co.solventa.app.features.polizas.RepositorioBilletera
import co.solventa.app.features.polizas.ResultadoPoliza
import co.solventa.app.features.polizas.TextosPoliza
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * «¡Tu póliza fue emitida!» (SOLVENTAG5-97): el número de póliza, el producto, la cobertura, la
 * prima final, la vigencia y el estado, con «Ver mi póliza» e «Ir al inicio». Se abre al terminar
 * la contratación (SOLVENTAG5-96) y, en cualquier momento, desde «Mis pólizas».
 *
 * Lee la póliza con [RepositorioBilletera] (SOLVENTAG5-105): solo el titular la ve, porque el BFF la
 * filtra por el cliente del token, y la copia sin conexión está cifrada con la llave del Keystore
 * (BA-13). Sin red muestra el último estado conocido y lo actualiza cuando la red vuelve. Mientras la
 * póliza no está VIGENTE, muestra un estado intermedio y vuelve a consultar: ninguna petición queda
 * abierta esperando la emisión (BA-12, ADR 0007).
 */
class ConfirmacionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConfirmacionBinding

    private val fuente: FuentePolizas by lazy { fabricaFuente(this) }
    private val hilo: ExecutorService = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val polizaId: String by lazy { intent.getStringExtra(EXTRA_POLIZA_ID).orEmpty() }

    /** El último resultado en pantalla. */
    @VisibleForTesting
    internal var resultado: ResultadoPoliza? = null
        private set

    /** Mientras se muestra el inicio de sesión: al volver, lo resuelve [pedirSesion]. */
    private var esperandoSesion = false

    private val consulta = Runnable { cargar(mostrarCargando = false) }

    /** Criterio 3: al volver la red, si la pantalla mostraba la copia guardada o el error, se actualiza. */
    private val alVolverLaRed = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                val sinRed = resultado is ResultadoPoliza.DesdeCache || resultado == ResultadoPoliza.SinConexion
                if (!isDestroyed && sinRed) cargar(mostrarCargando = false)
            }
        }
    }
    private var escuchandoRed = false

    private val pedirSesion = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        esperandoSesion = false
        if (r.resultCode == RESULT_OK) cargar() else finish()
    }

    /** Solo en dev: Android 17 pide permiso para llegar al BFF local (ver src/dev/AndroidManifest). */
    private val pedirRedLocal = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) cargar() else mostrarError(R.string.error_red_local)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InicioSesionActivity.configurar()
        enableEdgeToEdge()
        binding = ActivityConfirmacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        binding.reintentar.setOnClickListener { cargar() }
        binding.verPoliza.setOnClickListener { startActivity(DetallePolizaActivity.intent(this, polizaId)) }
        binding.irAlInicio.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
    }

    override fun onStart() {
        super.onStart()
        escucharRed(true)
        when {
            esperandoSesion -> Unit
            InicioSesionActivity.faltaSesion() -> iniciarSesion()
            else -> cargar()
        }
    }

    /** En segundo plano no se consulta ni se escucha la red. */
    override fun onStop() {
        principal.removeCallbacks(consulta)
        escucharRed(false)
        super.onStop()
    }

    override fun onDestroy() {
        hilo.shutdownNow()
        super.onDestroy()
    }

    private fun escucharRed(activo: Boolean) {
        val red = getSystemService(ConnectivityManager::class.java) ?: return
        if (activo && !escuchandoRed) {
            red.registerDefaultNetworkCallback(alVolverLaRed)
            escuchandoRed = true
        } else if (!activo && escuchandoRed) {
            red.unregisterNetworkCallback(alVolverLaRed)
            escuchandoRed = false
        }
    }

    private fun iniciarSesion() {
        SesionCliente.cerrar()
        esperandoSesion = true
        pedirSesion.launch(InicioSesionActivity.intent(this))
    }

    private fun cargar(mostrarCargando: Boolean = true) {
        if (PermisoRedLocal.falta(this)) {
            pedirRedLocal.launch(PermisoRedLocal.PERMISO)
            return
        }
        principal.removeCallbacks(consulta)
        if (mostrarCargando) {
            binding.textoCargando.setText(R.string.confirmacion_cargando)
            mostrarSolo(binding.cargando)
        }
        hilo.execute {
            val nuevo = fuente.obtener(polizaId)
            runOnUiThread { if (!isDestroyed) mostrar(nuevo) }
        }
    }

    @VisibleForTesting
    internal fun mostrar(nuevo: ResultadoPoliza) {
        resultado = nuevo
        val guardadoEn = (nuevo as? ResultadoPoliza.DesdeCache)?.guardadoEn
        mostrarAviso(guardadoEn)
        when (nuevo) {
            is ResultadoPoliza.Encontrada -> mostrarPoliza(nuevo.poliza)
            is ResultadoPoliza.DesdeCache -> mostrarPoliza(nuevo.poliza)
            ResultadoPoliza.NoEncontrada -> mostrarError(R.string.confirmacion_error_no_encontrada, reintentar = false)
            ResultadoPoliza.NoDisponible -> mostrarError(R.string.confirmacion_error_no_disponible)
            ResultadoPoliza.SinConexion -> mostrarError(R.string.confirmacion_error_sin_conexion)
            ResultadoPoliza.NoAutenticado -> iniciarSesion()
        }
    }

    /** Criterio 2: la póliza todavía PENDIENTE_FIRMA no está emitida; se espera y se vuelve a consultar. */
    private fun mostrarPoliza(p: Poliza) {
        if (p.estado == EstadoPoliza.PENDIENTE_FIRMA && resultado is ResultadoPoliza.Encontrada) {
            binding.textoCargando.setText(R.string.confirmacion_emitiendo)
            mostrarSolo(binding.cargando)
            principal.postDelayed(consulta, intervaloMs)
            return
        }
        val locale = FormatoCotizacion.localeDe(resources.configuration.locales[0])
        val prima = FormatoPoliza.pesos(p.prima, p.moneda, locale)
        // La etiqueta ya dice «Número de póliza»: solo el número.
        binding.numero.text = p.numero ?: TextosPoliza.numero(this, p)
        binding.producto.text = TextosPoliza.producto(this, p.ramo)
        binding.cobertura.text =
            getString(R.string.confirmacion_cobertura_valor, FormatoPoliza.pesos(p.sumaAsegurada, p.moneda, locale))
        binding.prima.text = if (p.periodicidad == MENSUAL) getString(R.string.detalle_prima_mensual, prima) else prima
        binding.vigencia.text = FormatoPoliza.vigencia(p.vigenciaDesde, p.vigenciaHasta, locale)
        binding.estado.setText(TextosPoliza.estado(p.estado))
        mostrarSolo(binding.contenido)
    }

    private fun mostrarAviso(guardadoEn: String?) {
        if (guardadoEn != null) {
            val locale = FormatoCotizacion.localeDe(resources.configuration.locales[0])
            binding.avisoSinConexion.text =
                getString(R.string.billetera_aviso_sin_conexion, FormatoPoliza.fecha(guardadoEn, locale))
        }
        binding.avisoSinConexion.visibility = if (guardadoEn != null) View.VISIBLE else View.GONE
    }

    private fun mostrarError(@StringRes mensaje: Int, reintentar: Boolean = true) {
        binding.mensajeError.setText(mensaje)
        binding.reintentar.visibility = if (reintentar) View.VISIBLE else View.GONE
        mostrarSolo(binding.error)
    }

    private fun mostrarSolo(vista: View) {
        listOf(binding.cargando, binding.contenido, binding.error).forEach {
            it.visibility = if (it === vista) View.VISIBLE else View.GONE
        }
    }

    companion object {
        const val EXTRA_POLIZA_ID = "co.solventa.app.extra.POLIZA_ID"
        private const val MENSUAL = "MENSUAL"

        /** Cada cuánto se consulta mientras la póliza no está emitida. */
        @VisibleForTesting
        internal var intervaloMs = 3_000L

        /** Las pruebas la reemplazan antes de abrir la pantalla, porque carga al iniciar. */
        @VisibleForTesting
        internal var fabricaFuente: (Context) -> FuentePolizas = { contexto ->
            RepositorioBilletera(
                fuente = ClientePolizas(AppConfig.actual().apiBaseUrl, token = SesionCliente::idToken),
                almacen = AlmacenCifradoKeystore(contexto),
                sub = { SesionCliente.idToken()?.let(DecodificadorJwt::sub) },
            )
        }

        fun intent(contexto: Context, polizaId: String): Intent =
            Intent(contexto, ConfirmacionActivity::class.java).putExtra(EXTRA_POLIZA_ID, polizaId)
    }
}
