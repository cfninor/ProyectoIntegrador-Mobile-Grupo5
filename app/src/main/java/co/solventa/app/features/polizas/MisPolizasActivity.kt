package co.solventa.app.features.polizas

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import co.solventa.app.R
import co.solventa.app.core.config.AppConfig
import co.solventa.app.core.red.PermisoRedLocal
import co.solventa.app.core.sesion.InicioSesionActivity
import co.solventa.app.core.sesion.SesionCliente
import co.solventa.app.databinding.ActivityMisPolizasBinding
import co.solventa.app.databinding.ItemPolizaBinding
import co.solventa.app.features.cotizacion.CotizacionActivity
import co.solventa.app.features.cotizacion.FormatoCotizacion
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * «Mis pólizas» (SOLVENTAG5-104): la billetera con las pólizas del cliente, cada una con su
 * producto, número, estado y vigencia (criterio 1). Tocar una abre su detalle. Sin pólizas, un
 * estado vacío que lleva a cotizar (criterio 5); si el BFF no responde, un mensaje y «Reintentar».
 */
class MisPolizasActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMisPolizasBinding

    private val fuente: FuentePolizas by lazy { fabricaFuente() }
    private val hilo: ExecutorService = Executors.newSingleThreadExecutor()

    /** Mientras se muestra el inicio de sesión: al volver, lo resuelve [pedirSesion]. */
    private var esperandoSesion = false

    /** Criterio 4: las pólizas son las de la sesión del cliente. Sin sesión, no hay billetera. */
    private val pedirSesion = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { resultado ->
        esperandoSesion = false
        if (resultado.resultCode == RESULT_OK) cargar() else finish()
    }

    /** Solo en dev: Android 17 pide permiso para llegar al BFF local (ver src/dev/AndroidManifest). */
    private val pedirRedLocal = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido ->
        if (concedido) cargar() else mostrarError(R.string.error_red_local)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InicioSesionActivity.configurar()
        enableEdgeToEdge()
        binding = ActivityMisPolizasBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        binding.reintentar.setOnClickListener { cargar() }
        binding.cotizar.setOnClickListener { startActivity(CotizacionActivity.intent(this)) }
    }

    /** Se recarga al volver del detalle o de cotizar: la lista siempre es la del BFF. */
    override fun onStart() {
        super.onStart()
        when {
            esperandoSesion -> Unit
            InicioSesionActivity.faltaSesion() -> iniciarSesion()
            else -> cargar()
        }
    }

    private fun iniciarSesion() {
        SesionCliente.cerrar()
        esperandoSesion = true
        pedirSesion.launch(InicioSesionActivity.intent(this))
    }

    override fun onDestroy() {
        hilo.shutdownNow()
        super.onDestroy()
    }

    private fun cargar() {
        if (PermisoRedLocal.falta(this)) {
            pedirRedLocal.launch(PermisoRedLocal.PERMISO)
            return
        }
        mostrarSolo(binding.cargando)
        hilo.execute {
            val resultado = fuente.listar()
            runOnUiThread { if (!isDestroyed) mostrar(resultado) }
        }
    }

    private fun mostrar(resultado: ResultadoPolizas) {
        when (resultado) {
            is ResultadoPolizas.Listadas ->
                if (resultado.polizas.isEmpty()) mostrarSolo(binding.vacio) else mostrarLista(resultado.polizas)
            ResultadoPolizas.NoDisponible -> mostrarError(R.string.polizas_error_no_disponible)
            ResultadoPolizas.SinConexion -> mostrarError(R.string.polizas_error_sin_conexion)
            ResultadoPolizas.NoAutenticado -> iniciarSesion()
        }
    }

    private fun mostrarLista(polizas: List<Poliza>) {
        val locale = FormatoCotizacion.localeDe(resources.configuration.locales[0])
        binding.lista.removeAllViews()
        polizas.forEach { poliza ->
            val tarjeta = ItemPolizaBinding.inflate(layoutInflater, binding.lista, false)
            val numero = TextosPoliza.numero(this, poliza)
            tarjeta.producto.text = TextosPoliza.producto(this, poliza.ramo)
            tarjeta.estado.setText(TextosPoliza.estado(poliza.estado))
            tarjeta.numero.text = numero
            tarjeta.vigencia.text = getString(
                R.string.poliza_vigencia,
                FormatoPoliza.vigencia(poliza.vigenciaDesde, poliza.vigenciaHasta, locale),
            )
            tarjeta.root.contentDescription = getString(R.string.poliza_abrir_detalle, poliza.numero ?: numero)
            tarjeta.root.setOnClickListener {
                startActivity(DetallePolizaActivity.intent(this, poliza.polizaId))
            }
            binding.lista.addView(tarjeta.root)
        }
        mostrarSolo(binding.lista)
    }

    private fun mostrarError(@StringRes mensaje: Int) {
        binding.mensajeError.setText(mensaje)
        mostrarSolo(binding.error)
    }

    /** Uno solo de los cuatro estados de la pantalla a la vez. */
    private fun mostrarSolo(vista: View) {
        listOf(binding.cargando, binding.lista, binding.vacio, binding.error).forEach {
            it.visibility = if (it === vista) View.VISIBLE else View.GONE
        }
    }

    companion object {
        /** Las pruebas la reemplazan antes de abrir la pantalla, porque carga al iniciar. */
        @VisibleForTesting
        internal var fabricaFuente: () -> FuentePolizas = {
            ClientePolizas(AppConfig.actual().apiBaseUrl, token = SesionCliente::idToken)
        }

        fun intent(contexto: Context): Intent = Intent(contexto, MisPolizasActivity::class.java)
    }
}
