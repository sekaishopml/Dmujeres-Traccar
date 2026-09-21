package com.dmujeres.traccar.oem

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * FASE 9 (§22): perfil de capacidad del dispositivo, declarativo y honesto.
 * Combina hardware ([DeviceCaps]), políticas de energía leídas del sistema y
 * el gate OEM conocido ([VendorSettings]/[OemProtection]).
 *
 * Regla: la detección es por CAPACIDAD, no por listas de fabricantes. El gate
 * OEM solo agrega guía (si existe evidencia mapeada); todo lo demás funciona
 * igual en cualquier Android.
 *
 * Estado de capacidad (nunca un score subjetivo):
 * - SUPPORTED: requisitos cumplidos, sin restricciones duras conocidas.
 * - SUPPORTED_WITH_GUIDANCE: funcional pero hay acción de usuario requerida.
 * - DEGRADED: restricción dura activa (bucket RESTRICTED / background restricted).
 * - UNSUPPORTED: sin GNSS (el tracking continuo no es posible).
 */
data class DeviceCapabilityProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdk: Int,
    val rom: String,
    val hasGnss: Boolean,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasRotationVector: Boolean,
    /** true = exento de optimización de batería (isIgnoringBatteryOptimizations). */
    val batteryOptimizationExempt: Boolean,
    val standbyBucket: Int,
    val backgroundRestricted: Boolean,
    val supportsAutostartGuide: Boolean,
    val supportsBatteryGuide: Boolean,
    val supportsVendorSettings: Boolean,
    val knownBackgroundRestriction: Boolean,
    val knownFreezerBehavior: Boolean,
) {

    fun status(): String = when {
        !hasGnss -> STATUS_UNSUPPORTED
        hardRestricted() -> STATUS_DEGRADED
        requiresGuidance() -> STATUS_SUPPORTED_WITH_GUIDANCE
        else -> STATUS_SUPPORTED
    }

    /**
     * Restricción dura con EVIDENCIA: appops de segundo plano, bucket
     * RESTRICTED o fabricante con congelamiento confirmado en campo
     * (KNOWN_FREEZER_VENDORS: ZTE cfreezer, evidencia qa-f0/frozen-list).
     * Un fabricante solo con guía de batería NO es restricción dura.
     */
    fun hardRestricted(): Boolean =
        backgroundRestricted || standbyBucket == STANDBY_RESTRICTED || knownFreezerBehavior

    fun requiresGuidance(): Boolean =
        supportsAutostartGuide || supportsBatteryGuide || !batteryOptimizationExempt

    companion object {
        const val STATUS_SUPPORTED = "SUPPORTED"
        const val STATUS_SUPPORTED_WITH_GUIDANCE = "SUPPORTED_WITH_GUIDANCE"
        const val STATUS_DEGRADED = "DEGRADED"
        const val STATUS_UNSUPPORTED = "UNSUPPORTED"

        const val STANDBY_EXEMPTED = 5
        const val STANDBY_ACTIVE = 10
        const val STANDBY_RARE = 40
        const val STANDBY_RESTRICTED = 45

        /** Grados de congelamiento OEM con evidencia real (no listas genéricas). */
        val KNOWN_FREEZER_VENDORS = setOf("zte")

        /**
         * Puro (JVM): compone el perfil desde los hechos leídos. La guía OEM
         * solo existe si [VendorSettings] mapea el fabricante.
         */
        fun from(
            manufacturer: String,
            model: String,
            device: String,
            androidVersion: String,
            sdk: Int,
            rom: String,
            caps: DeviceCaps,
            batteryOptimizationExempt: Boolean,
            standbyBucket: Int,
            backgroundRestricted: Boolean,
            vendorKey: String?,
        ): DeviceCapabilityProfile {
            val vendor = (vendorKey ?: "").lowercase()
            return DeviceCapabilityProfile(
                manufacturer = manufacturer,
                model = model,
                device = device,
                androidVersion = androidVersion,
                sdk = sdk,
                rom = rom,
                hasGnss = caps.hasGnss,
                hasAccelerometer = caps.hasAccelerometer,
                hasGyroscope = caps.hasGyroscope,
                hasRotationVector = caps.hasRotationVector,
                batteryOptimizationExempt = batteryOptimizationExempt,
                standbyBucket = standbyBucket,
                backgroundRestricted = backgroundRestricted,
                supportsAutostartGuide = vendor.isNotEmpty(),
                supportsBatteryGuide = vendor.isNotEmpty(),
                supportsVendorSettings = vendor.isNotEmpty(),
                knownBackgroundRestriction = vendor.isNotEmpty(),
                knownFreezerBehavior = vendor in KNOWN_FREEZER_VENDORS,
            )
        }

        /** Lectura Android segura (nunca lanza). */
        fun read(context: Context): DeviceCapabilityProfile {
            val caps = DeviceCaps.from(context)
            val power = runCatching {
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            }.getOrNull()
            val exempt = runCatching {
                power?.isIgnoringBatteryOptimizations(context.packageName) == true
            }.getOrDefault(false)
            val restricted = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                        ?.isBackgroundRestricted == true
                } else {
                    false
                }
            }.getOrDefault(false)
            val bucket = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager)
                        ?.appStandbyBucket ?: STANDBY_ACTIVE
                } else {
                    STANDBY_ACTIVE
                }
            }.getOrDefault(STANDBY_ACTIVE)
            return from(
                manufacturer = caps.manufacturer,
                model = caps.model,
                device = Build.DEVICE.orEmpty(),
                androidVersion = caps.androidRelease,
                sdk = caps.sdkInt,
                rom = runCatching { Build.DISPLAY.orEmpty() }.getOrDefault(""),
                caps = caps,
                batteryOptimizationExempt = exempt,
                standbyBucket = bucket,
                backgroundRestricted = restricted,
                vendorKey = VendorSettings.currentVendor(),
            )
        }
    }
}
