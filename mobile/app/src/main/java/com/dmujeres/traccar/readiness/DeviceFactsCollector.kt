package com.dmujeres.traccar.readiness

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.oem.SetupChecklistPolicy
import com.dmujeres.traccar.oem.VendorSettings
import com.dmujeres.traccar.recovery.FreezeEvidence

/**
 * Recolecta los hechos REALES del dispositivo para la puesta a punto (R7).
 * Solo lecturas de API estándar: ninguna pantalla OEM se puede leer.
 */
object DeviceFactsCollector {

    fun collect(context: Context): SetupChecklistPolicy.Facts {
        fun granted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        val batteryExempt = runCatching {
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)

        val exactAlarms = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching {
            context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        }.getOrDefault(false)

        val canInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || runCatching {
            context.packageManager.canRequestPackageInstalls()
        }.getOrDefault(false)

        return SetupChecklistPolicy.Facts(
            fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocation = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS),
            batteryExempt = batteryExempt,
            exactAlarmsAvailable = exactAlarms,
            canInstallUpdates = canInstall,
            freezeSignals = FreezeEvidence.freezeCount(context),
            vendor = VendorSettings.currentVendor(),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}
