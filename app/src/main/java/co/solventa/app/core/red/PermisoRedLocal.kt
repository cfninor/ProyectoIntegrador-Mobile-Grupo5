package co.solventa.app.core.red

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Permiso de red local de Android 17 (`ACCESS_LOCAL_NETWORK`). Solo lo declara el flavor `dev`,
 * para llegar al BFF móvil del docker-compose (10.0.2.2); QA y PROD usan HTTPS público y no lo
 * necesitan. Toda pantalla que llame al BFF lo pide antes del primer envío.
 */
object PermisoRedLocal {

    const val PERMISO = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val ANDROID_17 = 37

    /** `true` si el flavor declara el permiso y todavía no está concedido. */
    fun falta(contexto: Context): Boolean {
        if (Build.VERSION.SDK_INT < ANDROID_17) return false
        val declarados = contexto.packageManager
            .getPackageInfo(
                contexto.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
            .requestedPermissions.orEmpty()
        return PERMISO in declarados &&
            contexto.checkSelfPermission(PERMISO) != PackageManager.PERMISSION_GRANTED
    }
}
