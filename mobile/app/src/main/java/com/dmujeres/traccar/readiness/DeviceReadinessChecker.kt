package com.dmujeres.traccar.readiness

import android.Manifest
import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.platform.LocationState
import com.dmujeres.traccar.oem.VendorSettings
import com.dmujeres.traccar.tracking.TrackingService

/**
 * Capa Android del DeviceReadinessGate: reúne los valores reales del sistema
 * (con fallbacks defensivos: una ROM rara nunca debe romper la evaluación) y
 * los entrega a DeviceReadinessPolicy, que es pura y decide el veredicto.
 *
 * REGLA INNEGOCIABLE del gate: CONTINUITY_FAIL + RECOVERY_PASS = NOT_READY;
 * la recuperación nunca sustituye la continuidad.
 */
object DeviceReadinessChecker {

    /** Lecturas crudas con fallbacks (para diagnóstico y para el policy input). */
    data class RawReadings(
        val locationEnabled: Boolean,
        val fineGranted: Boolean,
        val backgroundGranted: Boolean,
        val fslGranted: Boolean,
        val notificationsGranted: Boolean,
        val fgsRequirementsOk: Boolean,
        val batteryExempt: Boolean,
        val standbyBucket: Int,
        val backgroundRestricted: Boolean,
    )

    fun readRaw(context: Context): RawReadings {
        val granted: (String) -> Boolean = { permission ->
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
        }
        val pm = runCatching { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
            .getOrNull()
        val batteryExempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
        // Bucket: EXEMPTED(5) es @SystemApi pero getAppStandbyBucket lo devuelve
        // numéricamente; el default defensivo al fallar es ACTIVE(10).
        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                    as? UsageStatsManager ?: error("sin UsageStatsManager")
                usm.getAppStandbyBucket()
            }.getOrDefault(DeviceReadinessPolicy.BUCKET_ACTIVE)
        } else {
            DeviceReadinessPolicy.BUCKET_ACTIVE
        }
        val restricted = if (Build.VERSION.SDK_INT >= DeviceReadinessPolicy.SDK_BG_RESTRICTED) {
            runCatching {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                am?.isBackgroundRestricted ?: false
            }.getOrDefault(false)
        } else {
            false
        }
        return RawReadings(
            locationEnabled = com.dmujeres.traccar.platform.LocationState.isEnabled(context),
            fineGranted = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            fslGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                granted(Manifest.permission.FOREGROUND_SERVICE_LOCATION),
            notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                granted(Manifest.permission.POST_NOTIFICATIONS),
            fgsRequirementsOk = true, // el manifest declara foregroundServiceType=location (verificado en build)
            batteryExempt = batteryExempt,
            standbyBucket = bucket,
            backgroundRestricted = restricted,
        )
    }

    /** Evalúa el readiness completo del dispositivo (checks + OEM + pruebas). */
    fun evaluate(context: Context): DeviceReadinessResult {
        val appContext = context.applicationContext
        val config = AppConfig(appContext)
        val raw = readRaw(appContext)
        val vendor = VendorSettings.currentVendor()
        val guide = vendor?.let { VendorSettings.guideFor(it) }
        val oemConfirmed = config.vendorGuideDone

        val recoveryTest = RecoveryTestPolicy.evaluate(
            lastRecoveryResult = config.lastRecoveryResult,
            attemptsSinceLastSuccess = config.attemptsSinceLastSuccess,
            serviceRunning = TrackingService.isRunning,
            journeyActive = config.trackingEnabled,
        )
        val recoveryOutcome = RecoveryOutcome(
            state = when (recoveryTest.state) {
                "PASS" -> CheckState.PASS
                "FAILED" -> CheckState.FAILED
                else -> CheckState.NOT_RUN
            },
            attempts = recoveryTest.attempts,
            note = recoveryTest.note,
        )

        val continuityOutcome = if (config.continuityRunning) {
            ContinuityOutcome(
                state = CheckState.TEST_REQUIRED,
                cause = ContinuityTestPolicy.Cause.NONE.name,
                fixesDuringOff = config.continuityFixesDuringOff,
                screenOffMs = config.continuityScreenOffAt,
                windowMs = 0L,
                frozenSeconds = config.continuityFrozenSeconds,
                atMs = 0L,
                note = "prueba en curso",
            )
        } else {
            ContinuityOutcome(
                state = when (config.continuityState) {
                    "PASS" -> CheckState.PASS
                    "FAILED" -> CheckState.FAILED
                    "INCONCLUSIVE" -> CheckState.INCONCLUSIVE
                    else -> CheckState.NOT_RUN
                },
                cause = config.continuityCause,
                fixesDuringOff = config.continuityFixesDuringOff,
                screenOffMs = 0L,
                windowMs = 0L,
                frozenSeconds = config.continuityFrozenSeconds,
                atMs = config.continuityAt,
                note = "",
            )
        }

        val input = DeviceReadinessPolicy.Input(
            locationEnabled = raw.locationEnabled,
            fineGranted = raw.fineGranted,
            backgroundGranted = raw.backgroundGranted,
            fslGranted = raw.fslGranted,
            notificationsGranted = raw.notificationsGranted,
            fgsRequirementsOk = raw.fgsRequirementsOk,
            batteryExempt = raw.batteryExempt,
            standbyBucket = raw.standbyBucket,
            backgroundRestricted = raw.backgroundRestricted,
            sdkInt = Build.VERSION.SDK_INT,
            oemKey = vendor,
            oemGuidePresent = guide != null,
            oemConfirmed = oemConfirmed,
            continuity = continuityOutcome,
            recovery = recoveryOutcome,
            nowMs = System.currentTimeMillis(),
        )
        return DeviceReadinessPolicy.evaluate(input)
    }
}

private typealias PackageManager = android.content.pm.PackageManager
