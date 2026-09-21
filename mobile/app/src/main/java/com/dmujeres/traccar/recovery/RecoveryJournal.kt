package com.dmujeres.traccar.recovery

import com.dmujeres.traccar.core.RecoveryOutcome

/**
 * Estados del pipeline de recuperación (Fase 6, verificación HONESTA):
 * - TRACKING_ACTIVE: nada que recuperar (jornada inactiva o servicio vivo).
 * - RECOVERY_PENDING: intento de recuperación lanzado hace < 20 min y aún sin
 *   confirmar (el arranque del FGS es asíncrono: no se sabe todavía).
 * - RECOVERY_BLOCKED_BY_OEM: el último intento verificado NO consolidó (el
 *   servicio siguió muerto en el fire siguiente): posible bloqueo del OEM.
 * - SERVICE_MISSING: jornada activa, servicio muerto y sin intento reciente
 *   verificado (también cubre "el OEM saltó la alarma del guardián").
 */
enum class RecoveryStatus { TRACKING_ACTIVE, SERVICE_MISSING, RECOVERY_PENDING, RECOVERY_BLOCKED_BY_OEM }

/** Veredicto + razón legible (para diagnóstico/logs). Puro. */
data class RecoveryState(
    val status: RecoveryStatus,
    val reason: String,
)

/**
 * Diario de recuperación del guardián (SessionKeeper). Políticas PURAS (JVM);
 * la persistencia vive en AppConfig (claves recovery*).
 *
 * Valores de veredicto persistidos (AppConfig.lastRecoveryResult):
 * - "ok": el servicio confirmó estar vivo tras el intento (watchdog o fire).
 * - "blocked": el intento no consolidó; posible bloqueo del OEM.
 * - "ok-pending": intento lanzado, resultado desconocido hasta verificación
 *   (el receiver NO puede saber si el FGS consolidó: onStartCommand es async).
 */
object RecoveryJournal {

    const val RESULT_OK = RecoveryOutcome.OK
    const val RESULT_BLOCKED = RecoveryOutcome.BLOCKED
    const val RESULT_PENDING = RecoveryOutcome.PENDING
    const val RESULT_UNKNOWN = RecoveryOutcome.UNKNOWN

    /**
     * Histéresis: un intento se considera "en curso" hasta 20 min. Ventana
     * mayor que el período del guardián (15 min) para no marcar SERVICE_MISSING
     * entre fires normales, y acotada para no esconder muertes indefinidamente.
     */
    const val HISTERESIS_MS = 20L * 60_000L

    /**
     * Clasificación del estado de recuperación. Reglas (en orden):
     * 1. !journeyActive → TRACKING_ACTIVE: jornada cerrada, nada que recuperar,
     *    sin riesgo (el caller muestra "INACTIVO" como string, no este enum).
     * 2. journeyActive && isRunning → TRACKING_ACTIVE: servicio vivo.
     * 3. journeyActive && !isRunning && lastOutcome == "blocked" →
     *    RECOVERY_BLOCKED_BY_OEM: el último intento VERIFICADO falló. Gana
     *    sobre la frescura del intento: la evidencia de bloqueo persiste
     *    hasta que el servicio vuelva a confirmarse vivo.
     * 4. journeyActive && !isRunning && intento reciente (< HISTERESIS_MS) →
     *    RECOVERY_PENDING: aún puede consolidar.
     * 5. else → SERVICE_MISSING: servicio muerto sin intento reciente ni
     *    veredicto de bloqueo.
     */
    fun classifyRecovery(
        journeyActive: Boolean,
        isRunning: Boolean,
        attempts24h: Int,
        lastOutcome: String?,
        nowMs: Long,
        lastRecoveryAtMs: Long,
    ): RecoveryState = when {
        !journeyActive ->
            RecoveryState(RecoveryStatus.TRACKING_ACTIVE, "INACTIVO (nada que recuperar)")
        isRunning ->
            RecoveryState(RecoveryStatus.TRACKING_ACTIVE, "SERVICIO VIVO")
        lastOutcome == RESULT_BLOCKED ->
            RecoveryState(
                RecoveryStatus.RECOVERY_BLOCKED_BY_OEM,
                "último intento: blocked (posible OEM)",
            )
        lastRecoveryAtMs > 0L && nowMs >= lastRecoveryAtMs &&
            nowMs - lastRecoveryAtMs < HISTERESIS_MS ->
            RecoveryState(
                RecoveryStatus.RECOVERY_PENDING,
                "intento hace ${(nowMs - lastRecoveryAtMs) / 60_000L} min, sin confirmar",
            )
        else ->
            RecoveryState(
                RecoveryStatus.SERVICE_MISSING,
                "servicio muerto sin intento reciente (intentos 24h: $attempts24h)",
            )
    }

    /** Veredicto que este fire emite sobre el intento del fire anterior. */
    enum class PreviousVerdict { SUCCESS, BLOCKED, NONE }

    /**
     * Decide qué fue del intento ANTERIOR del guardián, ahora que se sabe si el
     * servicio está vivo (histéresis honesta: el resultado del arranque async
     * solo se conoce en el fire siguiente o en el watchdog del servicio):
     * - Servicio vivo con intento aún sin confirmar ("ok-pending" o un
     *   "blocked" viejo que ya no aplica) → SUCCESS.
     * - Servicio muerto con intento pendiente de hace <= 20 min → BLOCKED
     *   (el FGS no consolidó: posible OEM).
     * - Cualquier otra combinación → NONE (sin veredicto nuevo; p. ej.
     *   "blocked" con servicio muerto ya está marcado, no se repite el log).
     */
    fun verdictForPreviousAttempt(
        trackingEnabled: Boolean,
        isRunning: Boolean,
        nowMs: Long,
        lastAttemptAtMs: Long,
        lastResult: String?,
    ): PreviousVerdict = when {
        !trackingEnabled || lastAttemptAtMs <= 0L -> PreviousVerdict.NONE
        isRunning && !lastResult.isNullOrBlank() && lastResult != RESULT_OK ->
            PreviousVerdict.SUCCESS
        !isRunning && lastResult == RESULT_PENDING &&
            nowMs - lastAttemptAtMs <= HISTERESIS_MS -> PreviousVerdict.BLOCKED
        else -> PreviousVerdict.NONE
    }

    /**
     * Confirmación del watchdog (TrackingService, tick 30 s): servicio vivo +
     * intento sin confirmar → escribir "ok" UNA vez. El marker persistido
     * (AppConfig.recoveryConfirmAt, KEY_RECOVERY_CONFIRM_AT) evita repetir:
     * se confirma solo si confirmAt es anterior al último intento.
     */
    fun shouldConfirmOnServiceAlive(
        trackingEnabled: Boolean,
        serviceRunning: Boolean,
        lastRecoveryAtMs: Long,
        confirmAtMs: Long,
    ): Boolean =
        trackingEnabled && serviceRunning && lastRecoveryAtMs > 0L && confirmAtMs < lastRecoveryAtMs

    /**
     * Valor que debe quedar persistido al registrar un intento: un
     * "ok-pending" nuevo NO borra un veredicto "blocked" previo (es la
     * evidencia más informativa y el próximo fire la re-evaluará); cualquier
     * otro caso escribe el nuevo valor tal cual.
     */
    fun resultAfterAttempt(previousResult: String?, newResult: String): String =
        RecoveryOutcome.afterAttempt(previousResult, newResult)

    // ==== F0: eventos finos de recovery + motivos (sin romper callers) ======
    //
    // La API existente (RESULT_*, classifyRecovery, verdictForPreviousAttempt,
    // incRecoveryAttempt) NO cambia: SessionKeeper y Diagnostics siguen
    // funcionando igual. Aquí se agregan SOLO las piezas que F2 (FCM) y el
    // watchdog server-side necesitan para registrar la evidencia fina.

    /** Eventos del ciclo de recuperación (persistence-friendly). */
    enum class RecoveryEventType {
        RECOVERY_ATTEMPT,
        RECOVERY_RECEIVED,
        RECOVERY_VALIDATED,
        RECOVERY_SUCCESS,
        RECOVERY_FAILED,
        RECOVERY_BLOCKED,
    }

    /** Motivos honestos del resultado. FCM_DEPRIORITIZED llega desde F2. */
    enum class RecoveryReason {
        PROCESS_DEAD,
        FGS_DEAD,
        NO_CALLBACK,
        NETWORK_DOWN,
        SERVER_SILENT,
        OEM_FREEZE,
        FCM_DEPRIORITIZED,
        PERMISSION,
        SECURITY_EXCEPTION,
        UNKNOWN,
    }

    /**
     * Registra un evento de recovery como línea auditable (eventType + reason).
     * F0: los eventos SE integran con RecoveryJournal cuando F2/F1 los
     * alimentan; el formato y motivo ya quedan normalizados aquí.
     */
    fun formatRecoveryEvent(
        eventType: RecoveryEventType,
        reason: RecoveryReason,
        sessionId: String,
        attemptId: String,
        atMs: Long,
    ): String = "eventType=$eventType reason=$reason sessionId=$sessionId " +
        "attemptId=$attemptId at=$atMs payloadVersion=1"

    /** Resultados compatibles con incRecoveryAttempt existente. */
    fun resultFor(eventType: RecoveryEventType): String = when (eventType) {
        RecoveryEventType.RECOVERY_SUCCESS -> RESULT_OK
        RecoveryEventType.RECOVERY_BLOCKED, RecoveryEventType.RECOVERY_FAILED -> RESULT_BLOCKED
        else -> RESULT_PENDING
    }
}
