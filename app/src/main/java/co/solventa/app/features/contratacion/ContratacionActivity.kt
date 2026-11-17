package co.solventa.app.features.contratacion

import android.content.Context
import android.content.Intent
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
import androidx.core.widget.doAfterTextChanged
import co.solventa.app.R
import co.solventa.app.core.config.AppConfig
import co.solventa.app.core.red.PermisoRedLocal
import co.solventa.app.core.sesion.InicioSesionActivity
import co.solventa.app.core.sesion.SesionCliente
import co.solventa.app.databinding.ActivityContratacionBinding
import co.solventa.app.features.cotizacion.FormatoCotizacion
import co.solventa.app.features.onboarding.OnboardingActivity
import co.solventa.app.features.polizas.DecodificadorJwt
import co.solventa.app.features.polizas.FormatoPoliza
import co.solventa.app.features.pruebavida.PruebaVidaActivity
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * «Contratar» (SOLVENTAG5-96): desde la cotización vigente, el cliente acepta la oferta, sigue su
 * solicitud hasta la decisión, paga la primera prima, firma con el código que recibe y emite la
 * póliza. Cada paso lo decide [FlujoContratacion] con lo que responde el BFF; esta pantalla solo lo
 * muestra y, mientras el núcleo trabaja, vuelve a consultar cada pocos segundos (ADR 0007).
 *
 * Todas las operaciones son idempotentes en el núcleo: al volver a la pantalla (de la prueba de
 * vida, del inicio de sesión o de segundo plano), se empieza otra vez desde la cotización y se
 * llega al mismo punto en que iba el cliente.
 */
class ContratacionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityContratacionBinding

    private val flujo: FlujoContratacion by lazy { fabricaFlujo(this) }
    private val hilo: ExecutorService = Executors.newSingleThreadExecutor()
    private val principal = Handler(Looper.getMainLooper())
    private val cotizacionId: String by lazy { intent.getStringExtra(EXTRA_COTIZACION_ID).orEmpty() }

    /** El paso en pantalla. */
    @VisibleForTesting
    internal var paso: Paso? = null
        private set

    /** Mientras se muestra el inicio de sesión: al volver, lo resuelve [pedirSesion]. */
    private var esperandoSesion = false

    /** Lo que se repite al volver del inicio de sesión. */
    private var trasSesion: () -> Paso = { flujo.iniciar(cotizacionId) }

    private val pedirSesion = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { resultado ->
        esperandoSesion = false
        if (resultado.resultCode == RESULT_OK) ejecutar(trasSesion) else finish()
    }

    /** Solo en dev: Android 17 pide permiso para llegar al BFF local (ver src/dev/AndroidManifest). */
    private val pedirRedLocal = registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedido ->
        if (concedido) empezar() else mostrar(Paso.Error(1, Aviso.SIN_CONEXION) { flujo.iniciar(cotizacionId) })
    }

    private val consulta = Runnable { consultar() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InicioSesionActivity.configurar()
        enableEdgeToEdge()
        binding = ActivityContratacionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { vista, insets ->
            val barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            vista.setPadding(barras.left, barras.top, barras.right, barras.bottom)
            insets
        }

        binding.pagar.setOnClickListener { pagar() }
        binding.codigo.doAfterTextChanged {
            binding.firmar.isEnabled = it?.length == LARGO_CODIGO
        }
        binding.firmar.setOnClickListener { firmar() }
        binding.reenviar.setOnClickListener {
            (paso as? Paso.Firma)?.let { firma -> ejecutar { flujo.reenviarCodigo(firma.polizaId) } }
        }
    }

    /** Cada vez que la pantalla vuelve a verse, el recorrido se retoma desde la cotización. */
    override fun onStart() {
        super.onStart()
        if (!esperandoSesion) empezar()
    }

    /** En segundo plano no se consulta: ninguna petición queda pendiente (ADR 0007). */
    override fun onStop() {
        principal.removeCallbacks(consulta)
        super.onStop()
    }

    override fun onDestroy() {
        hilo.shutdownNow()
        super.onDestroy()
    }

    private fun empezar() {
        when {
            PermisoRedLocal.falta(this) -> pedirRedLocal.launch(PermisoRedLocal.PERMISO)
            InicioSesionActivity.faltaSesion() -> iniciarSesion { flujo.iniciar(cotizacionId) }
            else -> ejecutar { flujo.iniciar(cotizacionId) }
        }
    }

    private fun iniciarSesion(repetir: () -> Paso) {
        SesionCliente.cerrar()
        trasSesion = repetir
        esperandoSesion = true
        pedirSesion.launch(InicioSesionActivity.intent(this))
    }

    private fun ejecutar(accion: () -> Paso) {
        principal.removeCallbacks(consulta)
        mostrarCargando()
        hilo.execute {
            val siguiente = accion()
            runOnUiThread { if (!isDestroyed) mostrar(siguiente) }
        }
    }

    /** Los pasos que esperan al núcleo se vuelven a consultar; los demás esperan al cliente. */
    private fun consultar() {
        when (val actual = paso) {
            is Paso.Evaluando -> ejecutarSinCargar { flujo.consultarSolicitud(actual.solicitudId) }
            is Paso.EnRevision -> ejecutarSinCargar { flujo.consultarSolicitud(actual.solicitudId) }
            is Paso.GenerandoDocumento -> ejecutarSinCargar { flujo.consultarPoliza(actual.polizaId) }
            is Paso.PagoProcesando -> ejecutarSinCargar { flujo.consultarPago(actual.polizaId) }
            else -> Unit
        }
    }

    private fun ejecutarSinCargar(accion: () -> Paso) {
        hilo.execute {
            val siguiente = accion()
            runOnUiThread { if (!isDestroyed) mostrar(siguiente) }
        }
    }

    private fun mostrarCargando() {
        ocultarTodo()
        binding.estado.setText(R.string.contratacion_cargando)
        binding.detalle.text = ""
        binding.progreso.visibility = View.VISIBLE
    }

    private fun ocultarTodo() {
        listOf(
            binding.progreso,
            binding.grupoPago,
            binding.grupoFirma,
            binding.aviso,
            binding.accion,
            binding.accionSecundaria,
        ).forEach { it.visibility = View.GONE }
    }

    @VisibleForTesting
    internal fun mostrar(nuevo: Paso) {
        paso = nuevo
        ocultarTodo()
        binding.numeroPaso.text = getString(R.string.contratacion_paso, nuevo.numero)
        binding.avance.progress = nuevo.numero
        when (nuevo) {
            is Paso.Evaluando -> esperando(R.string.contratacion_evaluando, R.string.contratacion_evaluando_detalle, intervaloMs)
            is Paso.EnRevision -> {
                textos(R.string.contratacion_en_revision, R.string.contratacion_en_revision_detalle)
                accion(R.string.contratacion_consultar) { ejecutar { flujo.consultarSolicitud(nuevo.solicitudId) } }
                principal.postDelayed(consulta, intervaloRevisionMs)
            }
            Paso.Rechazada -> {
                textos(R.string.contratacion_rechazada, R.string.contratacion_rechazada_detalle)
                accion(R.string.contratacion_volver) { finish() }
            }
            is Paso.Ajustada -> mostrarAjustada(nuevo)
            is Paso.GenerandoDocumento ->
                esperando(R.string.contratacion_generando, R.string.contratacion_generando_detalle, intervaloMs)
            is Paso.Pago -> mostrarPago(nuevo)
            is Paso.PagoProcesando ->
                esperando(R.string.contratacion_pago_procesando, R.string.contratacion_pago_procesando_detalle, intervaloMs)
            is Paso.Firma -> mostrarFirma(nuevo)
            is Paso.Emitida -> mostrarEmitida(nuevo)
            is Paso.Kyc -> mostrarKyc(nuevo)
            Paso.OfertaVencida -> {
                textos(R.string.contratacion_oferta_vencida, R.string.contratacion_oferta_vencida_detalle)
                accion(R.string.contratacion_cotizar_de_nuevo) { finish() }
            }
            is Paso.Error -> mostrarError(nuevo)
            is Paso.IniciarSesion -> iniciarSesion(nuevo.reintentar)
        }
    }

    private fun esperando(@StringRes estado: Int, @StringRes detalle: Int, demora: Long) {
        textos(estado, detalle)
        binding.progreso.visibility = View.VISIBLE
        principal.postDelayed(consulta, demora)
    }

    private fun textos(@StringRes estado: Int, @StringRes detalle: Int) {
        binding.estado.setText(estado)
        binding.detalle.setText(detalle)
    }

    private fun accion(@StringRes texto: Int, alTocar: () -> Unit) {
        binding.accion.setText(texto)
        binding.accion.setOnClickListener { alTocar() }
        binding.accion.visibility = View.VISIBLE
    }

    private fun accionSecundaria(@StringRes texto: Int, alTocar: () -> Unit) {
        binding.accionSecundaria.setText(texto)
        binding.accionSecundaria.setOnClickListener { alTocar() }
        binding.accionSecundaria.visibility = View.VISIBLE
    }

    private fun locale(): Locale = FormatoCotizacion.localeDe(resources.configuration.locales[0])

    private fun pesos(valor: Long, moneda: String) = FormatoCotizacion.pesos(valor, moneda, locale())

    private fun mostrarAjustada(ajustada: Paso.Ajustada) {
        binding.estado.setText(R.string.contratacion_ajustada)
        binding.detalle.text = getString(
            R.string.contratacion_ajustada_detalle,
            pesos(ajustada.primaAnterior, ajustada.moneda),
            pesos(ajustada.primaNueva, ajustada.moneda),
        )
        accion(R.string.contratacion_aceptar_prima) { ejecutar { flujo.aceptarPrima(ajustada.solicitudId) } }
        accionSecundaria(R.string.contratacion_ahora_no) { finish() }
    }

    private fun mostrarPago(pago: Paso.Pago) {
        binding.estado.setText(R.string.contratacion_pago)
        binding.detalle.text = getString(R.string.contratacion_pago_detalle, pesos(pago.prima, pago.moneda))
        binding.grupoPago.visibility = View.VISIBLE
        pago.aviso?.let(::mostrarAviso)
    }

    /** La forma de la tarjeta se valida aquí para no enviar a la pasarela algo que va a rechazar. */
    private fun pagar() {
        val actual = paso as? Paso.Pago ?: return
        val numero = binding.tarjeta.text?.toString().orEmpty().filter(Char::isDigit)
        val vencimiento = binding.vencimiento.text?.toString().orEmpty().trim()
        val numeroValido = numero.length in LARGO_TARJETA
        val vencimientoValido = FORMATO_VENCIMIENTO.matches(vencimiento)
        binding.campoTarjeta.error = if (numeroValido) null else getString(R.string.contratacion_error_tarjeta)
        binding.campoVencimiento.error = if (vencimientoValido) null else getString(R.string.contratacion_error_vencimiento)
        if (numeroValido && vencimientoValido) ejecutar { flujo.pagar(actual.polizaId, numero, vencimiento) }
    }

    private fun mostrarFirma(firma: Paso.Firma) {
        binding.estado.setText(R.string.contratacion_firma)
        binding.detalle.setText(R.string.contratacion_firma_detalle)
        binding.documento.text = firma.documento?.let(::textoDocumento) ?: getString(R.string.contratacion_sin_vista_previa)
        binding.codigo.text = null
        binding.grupoFirma.visibility = View.VISIBLE
        when {
            firma.aviso == Aviso.CODIGO_INVALIDO && firma.intentosRestantes != null -> mostrarAvisoTexto(
                resources.getQuantityString(
                    R.plurals.contratacion_codigo_invalido_intentos,
                    firma.intentosRestantes,
                    firma.intentosRestantes,
                ),
            )
            firma.aviso != null -> mostrarAviso(firma.aviso)
        }
    }

    private fun firmar() {
        val actual = paso as? Paso.Firma ?: return
        val codigo = binding.codigo.text?.toString().orEmpty()
        if (codigo.length == LARGO_CODIGO) ejecutar { flujo.firmar(actual.polizaId, codigo) }
    }

    /** La vista previa con los campos de la póliza (los del enlace público del portal, R10 de la 57). */
    private fun textoDocumento(documento: DocumentoFirma): String = getString(
        R.string.contratacion_documento,
        documento.tomador,
        pesos(documento.sumaAsegurada, documento.moneda),
        pesos(documento.prima, documento.moneda),
        FormatoPoliza.fecha(documento.vigenciaDesde, locale()),
        FormatoPoliza.fecha(documento.vigenciaHasta, locale()),
        documento.coberturas.joinToString("\n") { "• $it" },
        documento.exclusiones.joinToString("\n") { "• $it" },
        documento.versionCondiciones,
        documento.huella.take(LARGO_HUELLA),
    )

    /** SOLVENTAG5-97: con la póliza emitida, la confirmación «¡Tu póliza fue emitida!». */
    private fun mostrarEmitida(emitida: Paso.Emitida) {
        binding.estado.setText(R.string.contratacion_emitida)
        binding.detalle.text = getString(R.string.contratacion_emitida_detalle, emitida.poliza.numero.orEmpty())
        startActivity(ConfirmacionActivity.intent(this, emitida.poliza.polizaId))
        finish()
    }

    /** Criterio 4: sin la identidad verificada, la app lleva a terminar el onboarding. */
    private fun mostrarKyc(kyc: Paso.Kyc) {
        binding.estado.setText(R.string.contratacion_kyc)
        if (kyc.siguientePaso == SIGUIENTE_ESPERAR_REVISION) {
            binding.detalle.setText(R.string.contratacion_kyc_en_revision)
            accion(R.string.contratacion_volver) { finish() }
            return
        }
        binding.detalle.setText(R.string.contratacion_kyc_detalle)
        accion(R.string.contratacion_terminar_onboarding) { terminarOnboarding(kyc.siguientePaso) }
        accionSecundaria(R.string.contratacion_ahora_no) { finish() }
    }

    /** El cliente del token (bloquea si renueva la sesión): por eso se lee fuera del hilo principal. */
    private fun terminarOnboarding(siguientePaso: String?) {
        hilo.execute {
            val clienteId = SesionCliente.idToken()?.let(DecodificadorJwt::clienteId)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                val destino = if (siguientePaso == SIGUIENTE_PRUEBA_VIDA && clienteId != null) {
                    PruebaVidaActivity.intent(this, clienteId)
                } else {
                    OnboardingActivity.intent(this, clienteId)
                }
                startActivity(destino)
            }
        }
    }

    private fun mostrarError(error: Paso.Error) {
        binding.estado.setText(R.string.contratacion_error)
        binding.detalle.text = ""
        mostrarAviso(error.aviso)
        val reintentar = error.reintentar
        if (reintentar != null) {
            accion(R.string.contratacion_reintentar) { ejecutar(reintentar) }
            accionSecundaria(R.string.contratacion_volver) { finish() }
        } else {
            accion(R.string.contratacion_volver) { finish() }
        }
    }

    private fun mostrarAviso(aviso: Aviso) = mostrarAvisoTexto(getString(textoDe(aviso)))

    private fun mostrarAvisoTexto(texto: String) {
        binding.aviso.text = texto
        binding.aviso.visibility = View.VISIBLE
    }

    @StringRes
    private fun textoDe(aviso: Aviso): Int = when (aviso) {
        Aviso.SIN_CONEXION -> R.string.contratacion_aviso_sin_conexion
        Aviso.NO_DISPONIBLE -> R.string.contratacion_aviso_no_disponible
        Aviso.COTIZACION_NO_ENCONTRADA -> R.string.contratacion_aviso_cotizacion_no_encontrada
        Aviso.TARJETA_NO_TOKENIZADA -> R.string.contratacion_aviso_tarjeta
        Aviso.PAGO_RECHAZADO -> R.string.contratacion_aviso_pago_rechazado
        Aviso.CODIGO_INVALIDO -> R.string.contratacion_aviso_codigo_invalido
        Aviso.CODIGO_VENCIDO -> R.string.contratacion_aviso_codigo_vencido
        Aviso.CODIGO_REENVIADO -> R.string.contratacion_aviso_codigo_reenviado
        Aviso.FIRMA_VENCIDA -> R.string.contratacion_aviso_firma_vencida
        Aviso.SIN_CONTACTO -> R.string.contratacion_aviso_sin_contacto
        Aviso.NO_EMITIBLE -> R.string.contratacion_aviso_no_emitible
    }

    companion object {
        const val EXTRA_COTIZACION_ID = "co.solventa.app.extra.COTIZACION_ID"
        private const val LARGO_CODIGO = 6
        private const val LARGO_HUELLA = 16
        private val LARGO_TARJETA = 13..19
        private val FORMATO_VENCIMIENTO = Regex("^(0[1-9]|1[0-2])/[0-9]{2}$")
        private const val SIGUIENTE_PRUEBA_VIDA = "PRUEBA_VIDA"
        private const val SIGUIENTE_ESPERAR_REVISION = "ESPERAR_REVISION"

        /** Cada cuánto se consulta mientras el núcleo evalúa, genera el documento o confirma el pago. */
        @VisibleForTesting
        internal var intervaloMs = 3_000L

        /** La revisión manual tarda más: se consulta con menos frecuencia. */
        @VisibleForTesting
        internal var intervaloRevisionMs = 15_000L

        /** Las pruebas la reemplazan antes de abrir la pantalla, porque empieza al mostrarse. */
        @VisibleForTesting
        internal var fabricaFlujo: (Context) -> FlujoContratacion = {
            val config = AppConfig.actual()
            FlujoContratacion(
                fuente = ClienteContratacion(config.apiBaseUrl, token = SesionCliente::idToken),
                pasarela = ClientePasarela(config.pasarelaUrl),
            )
        }

        fun intent(contexto: Context, cotizacionId: String): Intent =
            Intent(contexto, ContratacionActivity::class.java).putExtra(EXTRA_COTIZACION_ID, cotizacionId)
    }
}
