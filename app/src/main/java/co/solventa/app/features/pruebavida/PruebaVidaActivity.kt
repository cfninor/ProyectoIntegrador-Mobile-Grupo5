package co.solventa.app.features.pruebavida

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import co.solventa.app.R
import co.solventa.app.core.config.AppConfig
import co.solventa.app.core.red.PermisoRedLocal
import co.solventa.app.databinding.ActivityPruebaVidaBinding
import co.solventa.app.features.cotizacion.CotizacionActivity
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pantalla «Prueba de vida» (SOLVENTAG5-90): guía la captura de 3 cuadros con CameraX (D5),
 * pide al núcleo el gesto a realizar y muestra el resultado del proveedor KYC. Ningún cuadro se
 * escribe en el dispositivo (D2): vive en memoria hasta que sale en la petición.
 */
class PruebaVidaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPruebaVidaBinding

    /** Las pruebas lo fijan en [clienteDePrueba] antes de lanzar la actividad: a diferencia de
     * `OnboardingActivity`/`CotizacionActivity`, aquí `onCreate` ya dispara la petición, así que
     * reemplazarlo después del lanzamiento sería tarde. */
    @VisibleForTesting
    internal var cliente = clienteDePrueba ?: ClientePruebaVida(AppConfig.actual().apiBaseUrl)
    private val hilo: ExecutorService = Executors.newSingleThreadExecutor()

    private val clienteId: String by lazy { intent.getStringExtra(EXTRA_CLIENTE_ID).orEmpty() }
    private var pruebaVidaId: String? = null
    private var proveedorCamara: ProcessCameraProvider? = null
    private var captura: ImageCapture? = null
    private var capturando = false

    /** El rechazo que se ve ahora, para poder redibujar sus dos acciones si «Solicitar
     * revisión manual» falla (R6 de la SOLVENTAG5-91). */
    private var rechazoActual: ResultadoCapturaPruebaVida.Resuelta? = null

    private val pedirCamara = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido -> if (concedido) comenzar() else mostrarFinal(
        R.string.prueba_vida_error_titulo,
        R.string.prueba_vida_error_permiso_camara,
        accion = AccionFinal.FINALIZAR,
    ) }

    /** Solo en dev: Android 17 pide permiso para llegar al BFF local (ver src/dev/AndroidManifest). */
    private val pedirRedLocal = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { concedido -> if (concedido) iniciar() else mostrarFinal(
        R.string.prueba_vida_error_titulo,
        R.string.error_red_local,
        accion = AccionFinal.REINTENTAR,
    ) }

    private enum class AccionFinal { REINTENTAR, FINALIZAR }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPruebaVidaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        binding.capturar.setOnClickListener { capturar() }
        comenzar()
    }

    override fun onDestroy() {
        proveedorCamara?.unbindAll()
        hilo.shutdownNow()
        super.onDestroy()
    }

    private fun comenzar() {
        if (faltaPermisoCamara()) {
            pedirCamara.launch(Manifest.permission.CAMERA)
            return
        }
        iniciar()
    }

    private fun faltaPermisoCamara() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED

    /** Criterio 1 y 2: pide el gesto a realizar y abre la cámara para guiar la captura. */
    private fun iniciar() {
        if (PermisoRedLocal.falta(this)) {
            pedirRedLocal.launch(PermisoRedLocal.PERMISO)
            return
        }
        mostrarCargando(R.string.prueba_vida_cargando)
        hilo.execute {
            val resultado = cliente.iniciar(clienteId)
            runOnUiThread { if (!isDestroyed) mostrarIniciado(resultado) }
        }
    }

    private fun mostrarIniciado(resultado: ResultadoIniciarPruebaVida) {
        when (resultado) {
            is ResultadoIniciarPruebaVida.Iniciada -> {
                pruebaVidaId = resultado.pruebaVidaId
                mostrarCamara(resultado)
            }
            ResultadoIniciarPruebaVida.ClienteNoEncontrado -> mostrarFinal(
                R.string.prueba_vida_error_titulo,
                R.string.prueba_vida_error_cliente_no_encontrado,
                accion = AccionFinal.FINALIZAR,
            )
            ResultadoIniciarPruebaVida.SinIntentos -> mostrarFinal(
                R.string.prueba_vida_error_titulo,
                R.string.prueba_vida_error_sin_intentos,
                accion = AccionFinal.FINALIZAR,
            )
            // R4: no hace falta repetir la prueba. El 409 no trae el siguiente paso, así que se
            // consulta (R7, D6): la app nunca lo deduce del resultado.
            ResultadoIniciarPruebaVida.KycYaVerificado -> consultarYNavegar()
            ResultadoIniciarPruebaVida.NoDisponible -> mostrarFinal(
                R.string.prueba_vida_error_titulo,
                R.string.prueba_vida_error_no_disponible,
                accion = AccionFinal.REINTENTAR,
            )
            ResultadoIniciarPruebaVida.SinConexion -> mostrarFinal(
                R.string.prueba_vida_error_titulo,
                R.string.prueba_vida_error_sin_conexion,
                accion = AccionFinal.REINTENTAR,
            )
        }
    }

    /** D6: consulta el estado del onboarding y navega según `siguientePaso` (R7). */
    private fun consultarYNavegar() {
        mostrarCargando(R.string.prueba_vida_cargando)
        hilo.execute {
            val estado = cliente.consultarOnboarding(clienteId)
            runOnUiThread { if (!isDestroyed) manejarEstadoOnboarding(estado) }
        }
    }

    /** `@VisibleForTesting`: así las pruebas cubren SinConexion sin tener que cronometrar un
     * corte de red justo entre el 409 de `iniciar` y esta segunda consulta. */
    @VisibleForTesting
    internal fun manejarEstadoOnboarding(estado: ResultadoConsultarOnboarding) {
        when (estado) {
            is ResultadoConsultarOnboarding.Obtenido -> irASiguientePaso(estado.siguientePaso)
            ResultadoConsultarOnboarding.ClienteNoEncontrado -> mostrarFinal(
                R.string.prueba_vida_error_titulo,
                R.string.prueba_vida_error_cliente_no_encontrado,
                accion = AccionFinal.FINALIZAR,
            )
            ResultadoConsultarOnboarding.NoDisponible -> mostrarFinal(
                R.string.prueba_vida_error_titulo,
                R.string.prueba_vida_error_no_disponible,
                accion = AccionFinal.REINTENTAR,
            )
            ResultadoConsultarOnboarding.SinConexion -> mostrarFinal(
                R.string.prueba_vida_error_titulo,
                R.string.prueba_vida_error_sin_conexion,
                accion = AccionFinal.REINTENTAR,
            )
        }
    }

    /** R7: la app navega según `siguientePaso`, nunca lo deduce. COTIZAR es el único que sale
     * de esta pantalla; cualquier otro (incluido uno que la app no conozca todavía, ya resuelto
     * como ESPERAR_REVISION por el cliente HTTP) se queda aquí, en la pantalla neutra. */
    private fun irASiguientePaso(paso: SiguientePaso) {
        when (paso) {
            SiguientePaso.COTIZAR -> {
                startActivity(CotizacionActivity.intent(this, clienteId))
                finish()
            }
            SiguientePaso.ESPERAR_REVISION, SiguientePaso.PRUEBA_VIDA -> mostrarEnRevision()
        }
    }

    private fun mostrarCamara(iniciada: ResultadoIniciarPruebaVida.Iniciada) {
        binding.cargando.visibility = View.GONE
        binding.resultado.visibility = View.GONE
        binding.mensajeError.visibility = View.GONE
        binding.gesto.setText(nombreDeGesto(iniciada.gesto))
        binding.intento.text = getString(R.string.prueba_vida_intento, iniciada.intento, iniciada.intentosRestantes)
        binding.capturar.isEnabled = true
        binding.camara.visibility = View.VISIBLE
        iniciarCamaraX()
    }

    private fun iniciarCamaraX() {
        val futuro = ProcessCameraProvider.getInstance(this)
        futuro.addListener(
            {
                try {
                    val proveedor = futuro.get()
                    val vistaPrevia = Preview.Builder().build()
                        .also { it.surfaceProvider = binding.vistaPrevia.surfaceProvider }
                    val nuevaCaptura = ImageCapture.Builder().build()
                    proveedor.unbindAll()
                    proveedor.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, vistaPrevia, nuevaCaptura)
                    proveedorCamara = proveedor
                    captura = nuevaCaptura
                } catch (_: Exception) {
                    binding.mensajeError.setText(R.string.prueba_vida_error_camara)
                    binding.mensajeError.visibility = View.VISIBLE
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    /** Criterio 1: captura los `CUADROS_POR_INTENTO` cuadros que pide el contrato (3 a 5), sin
     * escribir ninguno en el dispositivo. */
    private fun capturar() {
        val capturaActual = captura
        if (capturando || capturaActual == null) return
        capturando = true
        binding.capturar.isEnabled = false
        binding.mensajeError.visibility = View.GONE
        capturarSiguiente(capturaActual, mutableListOf())
    }

    /** `@VisibleForTesting`: cuando `acumulado` ya llega completo (el caso al que converge
     * [agregarCuadroYSeguir]) no toca `capturaActual`, así que las pruebas pueden ejercitar
     * ese camino con cualquier `ImageCapture`, sin CameraX real corriendo. */
    @VisibleForTesting
    internal fun capturarSiguiente(capturaActual: ImageCapture, acumulado: MutableList<ByteArray>) {
        if (acumulado.size >= CUADROS_POR_INTENTO) {
            capturando = false
            enviarCapturas(acumulado)
            return
        }
        capturaActual.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imagen: ImageProxy) {
                    val bytes = imagen.jpegBytes()
                    imagen.close()
                    agregarCuadroYSeguir(bytes, capturaActual, acumulado)
                }

                override fun onError(excepcion: ImageCaptureException) {
                    manejarErrorCaptura()
                }
            },
        )
    }

    /** Separado de `onCaptureSuccess` para no depender de un `ImageProxy` real en las pruebas:
     * recibe los bytes ya extraídos. */
    @VisibleForTesting
    internal fun agregarCuadroYSeguir(bytes: ByteArray, capturaActual: ImageCapture, acumulado: MutableList<ByteArray>) {
        acumulado += bytes
        capturarSiguiente(capturaActual, acumulado)
    }

    private fun ImageProxy.jpegBytes(): ByteArray {
        val buffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return bytes
    }

    @VisibleForTesting
    internal fun manejarErrorCaptura() {
        capturando = false
        binding.capturar.isEnabled = true
        binding.mensajeError.setText(R.string.prueba_vida_error_captura)
        binding.mensajeError.visibility = View.VISIBLE
    }

    /** Envía los cuadros ya capturados; separado de [capturar] para que las pruebas lo ejerciten
     * sin depender de CameraX. */
    @VisibleForTesting
    internal fun enviarCapturas(cuadros: List<ByteArray>) {
        val idActual = pruebaVidaId ?: return
        mostrarCargando(R.string.prueba_vida_enviando)
        hilo.execute {
            val resultado = cliente.registrarCapturas(clienteId, idActual, cuadros)
            runOnUiThread { if (!isDestroyed) mostrarResultadoCapturas(resultado) }
        }
    }

    private fun mostrarResultadoCapturas(resultado: ResultadoCapturaPruebaVida) {
        when (resultado) {
            is ResultadoCapturaPruebaVida.Resuelta -> mostrarResuelta(resultado)
            ResultadoCapturaPruebaVida.RecursoNoEncontrado -> mostrarFinal(
                R.string.prueba_vida_error_titulo,
                R.string.prueba_vida_error_cliente_no_encontrado,
                accion = AccionFinal.FINALIZAR,
            )
            ResultadoCapturaPruebaVida.IntentoNoDisponible -> mostrarFinal(
                R.string.prueba_vida_error_titulo,
                R.string.prueba_vida_error_intento_vencido,
                accion = AccionFinal.REINTENTAR,
            )
            ResultadoCapturaPruebaVida.NoDisponible -> volverACamaraConError(R.string.prueba_vida_error_envio)
            ResultadoCapturaPruebaVida.SinConexion -> volverACamaraConError(R.string.prueba_vida_error_sin_conexion)
        }
    }

    /** R7: la pantalla sigue `siguientePaso`, no `resultado` (que solo decide el mensaje, R8). */
    private fun mostrarResuelta(resuelta: ResultadoCapturaPruebaVida.Resuelta) {
        when (resuelta.siguientePaso) {
            SiguientePaso.COTIZAR -> {
                mostrarFinal(
                    R.string.prueba_vida_aprobada_titulo,
                    R.string.prueba_vida_aprobada_mensaje,
                    accion = AccionFinal.FINALIZAR,
                )
                startActivity(CotizacionActivity.intent(this, clienteId))
                finish()
            }
            SiguientePaso.PRUEBA_VIDA -> mostrarRechazoConOpciones(resuelta)
            SiguientePaso.ESPERAR_REVISION -> mostrarEnRevision()
        }
    }

    /** R8: el motivo (o su ausencia, si venció) y las dos acciones: reintentar o pedir
     * revisión manual (R6). */
    private fun mostrarRechazoConOpciones(resuelta: ResultadoCapturaPruebaVida.Resuelta, @StringRes error: Int? = null) {
        rechazoActual = resuelta
        proveedorCamara?.unbindAll()
        binding.cargando.visibility = View.GONE
        binding.camara.visibility = View.GONE
        binding.resultadoTitulo.setText(R.string.prueba_vida_rechazada_titulo)
        binding.resultadoMensaje.setText(mensajeDeMotivo(resuelta.motivo))
        binding.resultadoIntentos.text = getString(R.string.prueba_vida_intentos_restantes, resuelta.intentosRestantes)
        binding.resultadoIntentos.visibility = View.VISIBLE
        if (error != null) {
            binding.resultadoError.setText(error)
            binding.resultadoError.visibility = View.VISIBLE
        } else {
            binding.resultadoError.visibility = View.GONE
        }
        binding.resultadoAccion.setText(R.string.prueba_vida_reintentar)
        binding.resultadoAccion.setOnClickListener { comenzar() }
        binding.resultadoAccionSecundaria.visibility = View.VISIBLE
        binding.resultadoAccionSecundaria.isEnabled = true
        binding.resultadoAccionSecundaria.setOnClickListener { solicitarRevisionManual() }
        binding.resultado.visibility = View.VISIBLE
    }

    /** Resultado automático (tercer rechazo) o revisión manual voluntaria aceptada: misma
     * pantalla, sin botón de reintento (R6, flujo de «Referencia común»). */
    private fun mostrarEnRevision() = mostrarFinal(
        R.string.prueba_vida_revision_manual_titulo,
        R.string.prueba_vida_revision_manual_mensaje,
        accion = AccionFinal.FINALIZAR,
    )

    private fun solicitarRevisionManual() {
        mostrarCargando(R.string.prueba_vida_enviando)
        hilo.execute {
            val resultado = cliente.solicitarRevisionManual(clienteId)
            runOnUiThread { if (!isDestroyed) manejarRevisionManual(resultado) }
        }
    }

    private fun manejarRevisionManual(resultado: ResultadoSolicitarRevisionManual) {
        when (resultado) {
            is ResultadoSolicitarRevisionManual.Aceptada -> mostrarEnRevision()
            else -> rechazoActual?.let { mostrarRechazoConOpciones(it, error = R.string.prueba_vida_error_revision_manual) }
        }
    }

    /** El intento no se consumió (D2): se queda en la cámara para reintentar la captura con el
     * mismo gesto, sin volver a pedirlo al núcleo. */
    private fun volverACamaraConError(@StringRes mensaje: Int) {
        binding.cargando.visibility = View.GONE
        binding.resultado.visibility = View.GONE
        binding.camara.visibility = View.VISIBLE
        binding.mensajeError.setText(mensaje)
        binding.mensajeError.visibility = View.VISIBLE
        binding.capturar.isEnabled = true
    }

    private fun mostrarCargando(@StringRes mensaje: Int) {
        binding.camara.visibility = View.GONE
        binding.resultado.visibility = View.GONE
        binding.mensajeCargando.setText(mensaje)
        binding.cargando.visibility = View.VISIBLE
    }

    private fun mostrarFinal(
        @StringRes titulo: Int,
        @StringRes mensaje: Int,
        accion: AccionFinal,
    ) {
        rechazoActual = null
        proveedorCamara?.unbindAll()
        binding.cargando.visibility = View.GONE
        binding.camara.visibility = View.GONE
        binding.resultadoTitulo.setText(titulo)
        binding.resultadoMensaje.setText(mensaje)
        binding.resultadoIntentos.visibility = View.GONE
        binding.resultadoError.visibility = View.GONE
        binding.resultadoAccionSecundaria.visibility = View.GONE
        binding.resultadoAccion.setText(
            if (accion == AccionFinal.REINTENTAR) R.string.prueba_vida_reintentar else R.string.prueba_vida_finalizar,
        )
        binding.resultadoAccion.setOnClickListener { if (accion == AccionFinal.REINTENTAR) comenzar() else finish() }
        binding.resultado.visibility = View.VISIBLE
    }

    @StringRes
    private fun nombreDeGesto(gesto: Gesto): Int = when (gesto) {
        Gesto.PARPADEAR -> R.string.prueba_vida_gesto_parpadear
        Gesto.GIRAR_IZQUIERDA -> R.string.prueba_vida_gesto_girar_izquierda
        Gesto.GIRAR_DERECHA -> R.string.prueba_vida_gesto_girar_derecha
        Gesto.SONREIR -> R.string.prueba_vida_gesto_sonreir
    }

    @StringRes
    private fun mensajeDeMotivo(motivo: MotivoPruebaVida?): Int = when (motivo) {
        MotivoPruebaVida.ROSTRO_NO_COINCIDE -> R.string.prueba_vida_motivo_rostro_no_coincide
        MotivoPruebaVida.SUPLANTACION -> R.string.prueba_vida_motivo_suplantacion
        MotivoPruebaVida.GESTO_NO_DETECTADO -> R.string.prueba_vida_motivo_gesto_no_detectado
        MotivoPruebaVida.CALIDAD_INSUFICIENTE -> R.string.prueba_vida_motivo_calidad_insuficiente
        null -> R.string.prueba_vida_rechazada_mensaje_generico
    }

    companion object {
        const val EXTRA_CLIENTE_ID = "co.solventa.app.extra.CLIENTE_ID"
        private const val CUADROS_POR_INTENTO = 3

        /** El cliente que usará la próxima actividad que se cree; solo lo fijan las pruebas. */
        @VisibleForTesting
        internal var clienteDePrueba: ClientePruebaVida? = null

        fun intent(contexto: Context, clienteId: String): Intent =
            Intent(contexto, PruebaVidaActivity::class.java).putExtra(EXTRA_CLIENTE_ID, clienteId)
    }
}
