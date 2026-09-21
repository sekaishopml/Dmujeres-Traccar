package com.dmujeres.traccar.readiness

/** Estados del sistema de readiness: sin sobrecargar significados. */
enum class CheckState { PASS, FAILED, WARNING, NOT_VERIFIABLE, CONFIRMED, INCONCLUSIVE, NOT_RUN, TEST_REQUIRED }

/** Claves de verificación pública/derivada del dispositivo. */
enum class CheckKey {
    LOCATION_ENABLED, LOCATION_PERMISSIONS, BACKGROUND_LOCATION, FGS_REQUIREMENTS,
    NOTIFICATIONS, BATTERY_COMBINED, STANDBY_BUCKET, BACKGROUND_RESTRICTED,
    OEM_CONFIG, CONTINUITY_TEST, RECOVERY_TEST
}

/** Registro auditable de un check: qué, con qué valor, de qué fuente y cuándo. */
data class CheckRecord(
    val key: CheckKey,
    val state: CheckState,
    val value: String,
    val source: String,
    val timestampMs: Long,
    val note: String,
)

/** Resultado de la prueba de CONTINUIDAD (criterio PRINCIPAL de readiness). */
data class ContinuityOutcome(
    val state: CheckState,
    val cause: String,
    val fixesDuringOff: Int,
    val screenOffMs: Long,
    val windowMs: Long,
    val frozenSeconds: Int,
    val atMs: Long,
    val note: String,
)

/** Resultado de la prueba de RECUPERACIÓN (observacional; NO entra al veredicto). */
data class RecoveryOutcome(
    val state: CheckState,
    val attempts: Int,
    val note: String,
)

/** Resultado completo del readiness del dispositivo. */
data class DeviceReadinessResult(
    val checks: Map<CheckKey, CheckRecord>,
    val oemKey: String?,
    val oemGuidePresent: Boolean,
    val oemConfirmed: Boolean,
    val continuity: ContinuityOutcome,
    val recovery: RecoveryOutcome,
    val verdict: String,
    val readyBecause: List<String>,
    val notReadyBecause: List<String>,
    val atMs: Long,
)

/**
 * Política PURA de readiness del dispositivo: evalúa checks públicos/derivados
 * más las pruebas conductuales (continuidad y recuperación) y emite un veredicto
 * auditable READY / NOT_READY con razones legibles para la UI.
 */
object DeviceReadinessPolicy {

    // Umbrales de API verificados (docs oficiales)
    const val SDK_BG_RESTRICTED = 28          // ActivityManager.isBackgroundRestricted
    const val SDK_STANDBY_RESTRICTED = 30     // STANDBY_BUCKET_RESTRICTED(45) existe desde API 30
    const val BUCKET_EXEMPTED = 5
    const val BUCKET_ACTIVE = 10
    const val BUCKET_WORKING_SET = 20
    const val BUCKET_FREQUENT = 30
    const val BUCKET_RARE = 40
    const val BUCKET_RESTRICTED = 45
    const val VERDICT_READY = "READY"
    const val VERDICT_NOT_READY = "NOT_READY"

    /** bucket: 45→FAILED; 40→WARNING; resto→PASS. */
    fun evaluateStandbyBucket(bucket: Int): CheckState = when (bucket) {
        BUCKET_RESTRICTED -> CheckState.FAILED
        BUCKET_RARE -> CheckState.WARNING
        else -> CheckState.PASS
    }

    /**
     * backgroundRestricted: false→PASS; true sin exención→FAILED;
     * true+exempt→WARNING (filosofía OEM, caso ZTE: freezer actúa solo al
     * apagar pantalla; no es fallo universal).
     */
    fun evaluateBackgroundRestricted(restricted: Boolean, batteryExempt: Boolean): CheckState =
        when {
            !restricted -> CheckState.PASS
            batteryExempt -> CheckState.WARNING
            else -> CheckState.FAILED
        }

    /**
     * Batería COMBINADA (regla de política aprobada: NUNCA isIgnoringBatteryOptimizations
     * como bloqueo universal):
     * !exempt && (bucket==45 || backgroundRestricted) → FAILED
     * !exempt && !(...)                                → WARNING
     * exempt                                           → PASS
     */
    fun evaluateBatteryCombined(exempt: Boolean, bucket: Int, backgroundRestricted: Boolean): CheckState =
        when {
            exempt -> CheckState.PASS
            bucket == BUCKET_RESTRICTED || backgroundRestricted -> CheckState.FAILED
            else -> CheckState.WARNING
        }

    /**
     * La prueba de continuidad ya no es requisito del gate (decisión de
     * producto): vive como herramienta en Diagnóstico. Se conserva la función
     * para reportar si el OEM tiene guía (valor informativo).
     */
    @Deprecated("la continuidad ya no bloquea el gate; uso informativo")
    fun continuityRequired(oemGuidePresent: Boolean): Boolean = oemGuidePresent

    /** Datos de entrada para [evaluate], recopilados por las capas Android. */
    data class Input(
        val locationEnabled: Boolean,
        val fineGranted: Boolean,
        val backgroundGranted: Boolean,    // true en API<29
        val fslGranted: Boolean,           // true en API<34
        val notificationsGranted: Boolean, // true en API<33
        val fgsRequirementsOk: Boolean,
        val batteryExempt: Boolean,
        val standbyBucket: Int,
        val backgroundRestricted: Boolean,
        val sdkInt: Int,
        val oemKey: String?,
        val oemGuidePresent: Boolean,
        val oemConfirmed: Boolean,
        val continuity: ContinuityOutcome,
        val recovery: RecoveryOutcome,
        val nowMs: Long,
    )

    /** Ejecuta todos los checks y produce el veredicto final con razones. */
    fun evaluate(input: Input): DeviceReadinessResult {
        val now = input.nowMs

        fun record(key: CheckKey, state: CheckState, value: String, source: String, note: String = "") =
            CheckRecord(key, state, value, source, now, note)

        val checks = linkedMapOf<CheckKey, CheckRecord>()

        // Checks públicos (independientes de la API salvo lo anotado)
        checks[CheckKey.LOCATION_ENABLED] = record(
            CheckKey.LOCATION_ENABLED,
            if (input.locationEnabled) CheckState.PASS else CheckState.FAILED,
            input.locationEnabled.toString(),
            "LocationManager",
        )
        checks[CheckKey.LOCATION_PERMISSIONS] = record(
            CheckKey.LOCATION_PERMISSIONS,
            if (input.fineGranted) CheckState.PASS else CheckState.FAILED,
            "fine=$input.fineGranted",
            "PermissionManager",
        )
        // API<29: el permiso de fondo viene con FINE; no es verificable por separado.
        val backgroundOk = if (input.sdkInt < 29) input.fineGranted else input.backgroundGranted
        checks[CheckKey.BACKGROUND_LOCATION] = record(
            CheckKey.BACKGROUND_LOCATION,
            if (backgroundOk) CheckState.PASS else CheckState.FAILED,
            if (input.sdkInt < 29) "merged-pre-29" else "background=$input.backgroundGranted",
            "PermissionManager",
        )
        // API<34: no existe FGS_TYPE_LOCATION obligatorio para este caso de uso.
        val fgsOk = if (input.sdkInt < 34) true else input.fgsRequirementsOk
        checks[CheckKey.FGS_REQUIREMENTS] = record(
            CheckKey.FGS_REQUIREMENTS,
            if (fgsOk) CheckState.PASS else CheckState.FAILED,
            if (input.sdkInt < 34) "n/a-pre-34" else "ok=$input.fgsRequirementsOk",
            "ForegroundServiceManager",
        )
        // API<33: el permiso de notificaciones no existe.
        val notifOk = if (input.sdkInt < 33) true else input.notificationsGranted
        checks[CheckKey.NOTIFICATIONS] = record(
            CheckKey.NOTIFICATIONS,
            if (notifOk) CheckState.PASS else CheckState.FAILED,
            if (input.sdkInt < 33) "n/a-pre-33" else "granted=$input.notificationsGranted",
            "NotificationManagerCompat",
        )

        // Checks derivados de gestión de energía
        val bucketState = evaluateStandbyBucket(input.standbyBucket)
        checks[CheckKey.STANDBY_BUCKET] = record(
            CheckKey.STANDBY_BUCKET,
            bucketState,
            input.standbyBucket.toString(),
            "UsageStatsManager",
        )
        val bgRestrictedState = evaluateBackgroundRestricted(input.backgroundRestricted, input.batteryExempt)
        checks[CheckKey.BACKGROUND_RESTRICTED] = record(
            CheckKey.BACKGROUND_RESTRICTED,
            bgRestrictedState,
            "restricted=${input.backgroundRestricted},exempt=${input.batteryExempt}",
            "ActivityManager",
        )
        val batteryState = evaluateBatteryCombined(input.batteryExempt, input.standbyBucket, input.backgroundRestricted)
        checks[CheckKey.BATTERY_COMBINED] = record(
            CheckKey.BATTERY_COMBINED,
            batteryState,
            "exempt=${input.batteryExempt},bucket=${input.standbyBucket}",
            "PowerManager",
        )

        // OEM: NOT_VERIFIABLE nunca es PASS; solo la prueba conductual lo confirma.
        val oemState = when {
            !input.oemGuidePresent -> CheckState.PASS
            !input.oemConfirmed -> CheckState.NOT_VERIFIABLE
            else -> CheckState.CONFIRMED
        }
        val oemNote = when (oemState) {
            CheckState.PASS -> "generic"
            CheckState.NOT_VERIFIABLE -> "requiere guía + prueba conductual"
            else -> "mitigated by behavior test"
        }
        checks[CheckKey.OEM_CONFIG] = record(
            CheckKey.OEM_CONFIG,
            oemState,
            input.oemKey ?: "unknown",
            "VendorCatalog",
            oemNote,
        )

        // Continuidad: criterio PRINCIPAL del veredicto.
        val continuityState = when (input.continuity.state) {
            CheckState.PASS -> CheckState.PASS
            CheckState.FAILED -> CheckState.FAILED
            CheckState.INCONCLUSIVE -> CheckState.INCONCLUSIVE
            else -> CheckState.NOT_RUN
        }
        val continuityNote = when (input.continuity.state) {
            CheckState.NOT_RUN, CheckState.TEST_REQUIRED ->
                "herramienta de diagnóstico"
            else -> input.continuity.note
        }
        checks[CheckKey.CONTINUITY_TEST] = record(
            CheckKey.CONTINUITY_TEST,
            continuityState,
            "cause=${input.continuity.cause},fixes=${input.continuity.fixesDuringOff},frozen=${input.continuity.frozenSeconds}",
            "ContinuityTestController",
            continuityNote,
        )

        // Recuperación: observacional; NO entra al veredicto (solo nota de riesgo).
        checks[CheckKey.RECOVERY_TEST] = record(
            CheckKey.RECOVERY_TEST,
            input.recovery.state,
            "attempts=${input.recovery.attempts}",
            "RecoveryJournal",
            input.recovery.note,
        )

        // FAILED_BLOCKING: lo que bloquea por sí solo el readiness.
        val failedBlocking = checks.filterValues { it.state == CheckState.FAILED }.keys.intersect(
            setOf(
                CheckKey.LOCATION_ENABLED,
                CheckKey.LOCATION_PERMISSIONS,
                CheckKey.BACKGROUND_LOCATION,
                CheckKey.FGS_REQUIREMENTS,
                CheckKey.NOTIFICATIONS,
                CheckKey.STANDBY_BUCKET,
                CheckKey.BATTERY_COMBINED,
            ),
        )

        val readyBecause = mutableListOf<String>()
        val notReadyBecause = mutableListOf<String>()

        // Etiquetas cortas legibles para la UI/diagnóstico.
        val checkLabels = mapOf(
            CheckKey.LOCATION_ENABLED to "location",
            CheckKey.LOCATION_PERMISSIONS to "permissions",
            CheckKey.BACKGROUND_LOCATION to "permissions",
            CheckKey.FGS_REQUIREMENTS to "fgs",
            CheckKey.NOTIFICATIONS to "notifications",
            CheckKey.STANDBY_BUCKET to "standby",
            CheckKey.BATTERY_COMBINED to "battery",
        )

        failedBlocking.forEach { key ->
            notReadyBecause += checkLabels[key] ?: key.name.lowercase()
        }

        // OEM pendiente de mitigar bloquea (guía del fabricante → Ajustes de
        // batería/aplicación; es la única vía de configuración manual).
        if (input.oemGuidePresent && !input.oemConfirmed) {
            notReadyBecause += "oem"
        }

        // La prueba de CONTINUIDAD ya NO participa del veredicto: vive en
        // Diagnóstico como herramienta técnica (decisión de producto). Se
        // registra su estado de forma informativa, sin bloquear READY.
        when (input.continuity.state) {
            CheckState.PASS -> readyBecause += "continuity-diag"
            CheckState.FAILED -> {} // informativo; se ve en Diagnóstico con su causa
            CheckState.INCONCLUSIVE -> {}
            else -> {}
        }

        // Veredicto final.
        val ready = notReadyBecause.isEmpty()
        if (ready) {
            if (input.locationEnabled) readyBecause += "location"
            if (input.fineGranted) readyBecause += "permissions"
            if (fgsOk) readyBecause += "fgs"
            if (notifOk) readyBecause += "notifications"
            if (batteryState != CheckState.FAILED) readyBecause += "battery"
            if (bucketState != CheckState.FAILED) readyBecause += "standby"
            if (!input.oemGuidePresent || input.oemConfirmed) readyBecause += "oem"
            // Caso C: recovery FAILED no bloquea, pero se reporta como riesgo.
            if (input.recovery.state == CheckState.FAILED) {
                readyBecause += "riesgo: recovery FAILED (respaldo degradado)"
            }
        }

        return DeviceReadinessResult(
            checks = checks,
            oemKey = input.oemKey,
            oemGuidePresent = input.oemGuidePresent,
            oemConfirmed = input.oemConfirmed,
            continuity = input.continuity,
            recovery = input.recovery,
            verdict = if (ready) VERDICT_READY else VERDICT_NOT_READY,
            readyBecause = readyBecause,
            notReadyBecause = notReadyBecause,
            atMs = now,
        )
    }
}
