package com.dmujeres.traccar.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Permisos críticos para que el rastreo sobreviva a una actualización OTA.
 * Si la app se actualiza "en vivo" (el instalador conserva los datos, así que
 * `onboardingDone` sigue en true), capas OEM como ZTE/Mifavor o MIUI/Xiaomi
 * suelen REVOCAR los permisos especiales (exención de batería, avisos,
 * ubicación en segundo plano, FOREGROUND_SERVICE_LOCATION). Como el
 * onboarding es de una sola vez, sin esta auditoría la pérdida pasa en
 * silencio y la calidad del rastreo se degrada sin que la colaboradora lo
 * note.
 */
enum class CriticalPermission { NOTIFICATIONS, BATTERY_EXEMPT, FINE_LOCATION, BACKGROUND_LOCATION, FSL }

/** Estado de los permisos críticos: los concedidos + la lista derivada de faltantes. */
data class PermissionStatus(val granted: Set<CriticalPermission>) {

    /** Faltantes en un orden estable (el de [ORDER]), para mapear y mostrar siempre igual. */
    val missing: List<CriticalPermission> get() = ORDER.filter { it !in granted }

    companion object {
        /** Orden canónico de los permisos críticos. */
        val ORDER: List<CriticalPermission> = listOf(
            CriticalPermission.NOTIFICATIONS,
            CriticalPermission.BATTERY_EXEMPT,
            CriticalPermission.FINE_LOCATION,
            CriticalPermission.BACKGROUND_LOCATION,
            CriticalPermission.FSL,
        )
    }
}

/**
 * Auditoría post-OTA de permisos críticos y su reparación vía onboarding.
 * [repairStepsFor] es pura (JVM, sin Android) y testeable en unit tests.
 */
object PermissionHealth {

    /** Estado actual de los permisos críticos según la API del sistema. */
    fun check(context: Context): PermissionStatus {
        val granted = mutableSetOf<CriticalPermission>()
        // Avisos: solo existe el permiso runtime desde API 33; antes siempre ok.
        val notificationsOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (notificationsOk) granted += CriticalPermission.NOTIFICATIONS
        // Exención de batería: la app declara REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm != null && pm.isIgnoringBatteryOptimizations(context.packageName)) {
            granted += CriticalPermission.BATTERY_EXEMPT
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            granted += CriticalPermission.FINE_LOCATION
        }
        // Ubicación en segundo plano: permiso runtime solo desde API 29.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            granted += CriticalPermission.BACKGROUND_LOCATION
        }
        // FOREGROUND_SERVICE_LOCATION: runtime solo desde API 34.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.FOREGROUND_SERVICE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            granted += CriticalPermission.FSL
        }
        return PermissionStatus(granted.toSet())
    }

    /**
     * Pasos del onboarding que REQUERIRÍAN los permisos faltantes, deduplicados
     * y ordenados de menor a mayor. En OnboardingActivity el paso 0 (ubicación)
     * pide FINE + BACKGROUND + FSL (Android 14+); el 1 pide avisos y el 2 la
     * exención de batería (el 3 es el interruptor GPS del sistema, no un
     * permiso). Vacío si no falta nada. Pura para tests JVM.
     */
    fun repairStepsFor(missing: List<CriticalPermission>): List<Int> =
        missing.map { stepIndexFor(it) }.distinct().sorted()

    private fun stepIndexFor(permission: CriticalPermission): Int = when (permission) {
        CriticalPermission.FINE_LOCATION,
        CriticalPermission.BACKGROUND_LOCATION,
        CriticalPermission.FSL,
        -> 0
        CriticalPermission.NOTIFICATIONS -> 1
        CriticalPermission.BATTERY_EXEMPT -> 2
    }
}
