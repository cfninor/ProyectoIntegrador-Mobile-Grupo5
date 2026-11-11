package co.solventa.app.features.polizas

import android.content.Context
import androidx.annotation.StringRes
import co.solventa.app.R

/** Textos de la póliza que comparten «Mis pólizas» y el detalle. */
object TextosPoliza {

    private const val VIDA_HIPOTECARIO = "VIDA_HIPOTECARIO"

    /** El nombre del producto; un ramo que esta versión no conoce se muestra tal cual. */
    fun producto(contexto: Context, ramo: String): String = when (ramo) {
        VIDA_HIPOTECARIO -> contexto.getString(R.string.poliza_producto_vida_hipotecario)
        else -> ramo
    }

    @StringRes
    fun estado(estado: EstadoPoliza): Int = when (estado) {
        EstadoPoliza.VIGENTE -> R.string.poliza_estado_vigente
        EstadoPoliza.PENDIENTE_FIRMA -> R.string.poliza_estado_pendiente_firma
        EstadoPoliza.CANCELADA -> R.string.poliza_estado_cancelada
        EstadoPoliza.VENCIDA -> R.string.poliza_estado_vencida
        EstadoPoliza.DESCONOCIDO -> R.string.poliza_estado_desconocido
    }

    /** «Póliza N.º SV-VH-2026-000123», o «en proceso de emisión» si todavía no tiene número. */
    fun numero(contexto: Context, poliza: Poliza): String =
        poliza.numero?.let { contexto.getString(R.string.poliza_numero, it) }
            ?: contexto.getString(R.string.poliza_sin_numero)
}
