package com.dmujeres.traccar.location

/**
 * Adquisición GPS activa: lógica pura (JVM, sin dependencias Android) para
 * poder probarla con `testDebugUnitTest`.
 *
 * Contexto: con jornada ACTIVA y GPS "encendido" vía app, el FLP pasivo puede
 * no entregar fixes (Doze/OEM, interiores, minUpdateDistance 15 m con el
 * usuario detenido). Estas políticas deciden CUÁNDO buscar activamente el fix
 * (one-shot `getCurrentLocation`) y CON QUÉ distancia mínima pedir updates,
 * sin fundir la batería.
 */
object ActivePollPolicy {

    /** Sin fix válido en > 90 s (reloj monotónico) se dispara el polling. */
    const val NO_FIX_POLL_AFTER_NANOS = 90_000_000_000L

    /** Timeout del one-shot `getCurrentLocation` (después se cancela el token). */
    const val POLL_TIMEOUT_MS = 30_000L

    /** Backoff entre intentos fallidos: 90 s → 3 min → 5 min (se resetea al recibir fix). */
    const val FIRST_RETRY_AFTER_NANOS = 90_000_000_000L
    const val SECOND_RETRY_AFTER_NANOS = 180_000_000_000L
    const val STEADY_RETRY_AFTER_NANOS = 300_000_000_000L

    /** Con batería < 15 % el polling se espacia a cada 10 min. */
    const val LOW_BATTERY_POLL_AFTER_NANOS = 600_000_000_000L
    const val LOW_BATTERY_BELOW_PCT = 15

    /** Espera exigida desde el último intento según fallos consecutivos. */
    fun retryDelayNanos(consecutiveFailures: Int): Long = when {
        consecutiveFailures <= 1 -> FIRST_RETRY_AFTER_NANOS
        consecutiveFailures == 2 -> SECOND_RETRY_AFTER_NANOS
        else -> STEADY_RETRY_AFTER_NANOS
    }

    /** Batería baja (< 15 %); <= 0 se trata como desconocida (no limita). */
    fun isLowBattery(batteryPct: Int): Boolean =
        batteryPct in 1 until LOW_BATTERY_BELOW_PCT

    /**
     * ¿Disparar un one-shot ahora? Tiempos SIEMPRE en
     * [android.os.SystemClock.elapsedRealtimeNanos] (monotónico, no wall-clock).
     *
     * @param trackingActive jornada activa y servicio capturando.
     * @param nowElapsedNanos ahora (monotónico).
     * @param lastFixElapsedNanos monotónico del último fix encolado OK (0 = ninguno en este arranque).
     * @param startElapsedNanos monotónico del inicio de la jornada (referencia si aún no hay fix).
     * @param lastPollAttemptElapsedNanos monotónico del último one-shot (0 = nunca).
     * @param consecutiveFailures intentos fallidos seguidos (0 tras recibir fix).
     * @param batteryPct 0-100 (<= 0 = desconocida).
     */
    @Suppress("ReturnCount")
    fun shouldPoll(
        trackingActive: Boolean,
        nowElapsedNanos: Long,
        lastFixElapsedNanos: Long,
        startElapsedNanos: Long,
        lastPollAttemptElapsedNanos: Long,
        consecutiveFailures: Int,
        batteryPct: Int,
    ): Boolean {
        if (!trackingActive) return false
        if (nowElapsedNanos <= 0L) return false
        val reference = if (lastFixElapsedNanos > 0L) lastFixElapsedNanos else startElapsedNanos
        if (reference <= 0L) return false
        if (nowElapsedNanos - reference <= NO_FIX_POLL_AFTER_NANOS) return false
        val required = if (isLowBattery(batteryPct)) {
            LOW_BATTERY_POLL_AFTER_NANOS
        } else {
            retryDelayNanos(consecutiveFailures)
        }
        if (lastPollAttemptElapsedNanos <= 0L) return true
        return nowElapsedNanos - lastPollAttemptElapsedNanos >= required
    }
}

/**
 * Min-distance adaptativa: 15 m solo tiene sentido en movimiento. En quietud
 * (o sin fix reciente) se pide 0 m para recibir todo lo que el FLP dé — es el
 * caso del usuario detenido en interior donde 15 m filtra todo.
 *
 * Histéresis para no re-registrar el request a cada fix: quieto → en
 * movimiento solo si speed > 5 m/s; en movimiento → quieto solo si
 * speed < 1 m/s (o se pierde la recencia del fix).
 */
object AdaptiveDistancePolicy {

    const val STATIONARY_SPEED_MPS = 1.0f
    const val MOVING_SPEED_MPS = 5.0f

    /** En quietud: recibir todo lo que el FLP dé. */
    const val DISTANCE_STATIONARY_M = 0f

    /** En movimiento: el valor urbano/peatón de [FixFilter.MIN_UPDATE_DISTANCE_M]. */
    val DISTANCE_MOVING_M: Float
        get() = FixFilter.MIN_UPDATE_DISTANCE_M

    enum class Mode { STATIONARY, MOVING }

    /**
     * @param current modo actual (histéresis en la zona 1-5 m/s: se conserva).
     * @param speedMps speed del último fix válido en m/s (null = sin dato → quieto).
     * @param hasRecentFix false si el último fix encolado ya es viejo.
     */
    fun nextMode(current: Mode, speedMps: Float?, hasRecentFix: Boolean): Mode {
        if (!hasRecentFix) return Mode.STATIONARY
        val speed = speedMps
        if (speed == null || !speed.isFinite() || speed < 0f) return Mode.STATIONARY
        return when (current) {
            Mode.STATIONARY -> if (speed > MOVING_SPEED_MPS) Mode.MOVING else Mode.STATIONARY
            Mode.MOVING -> if (speed < STATIONARY_SPEED_MPS) Mode.STATIONARY else Mode.MOVING
        }
    }

    fun distanceFor(mode: Mode): Float = when (mode) {
        Mode.STATIONARY -> DISTANCE_STATIONARY_M
        Mode.MOVING -> DISTANCE_MOVING_M
    }
}

/**
 * Resumen GNSS puro: el `GnssStatus` real (Android) se itera en el servicio y
 * aquí solo entra la lista simulada de `usedInFix(i)` por satélite, testeable
 * en JVM.
 */
object GnssSummary {

    data class Counts(val total: Int, val used: Int)

    /** [usedInFix] un flag por satélite en vista, en el orden de `GnssStatus`. */
    fun summarize(usedInFix: List<Boolean>): Counts {
        var used = 0
        usedInFix.forEach { if (it) used++ }
        return Counts(total = usedInFix.size, used = used)
    }

    /**
     * 0 satélites en vista + sin fix reciente = "bajo techo/sin cielo",
     * distinto de "GPS apagado" (eso lo dice `gpsEnabled`/`gps` en presence).
     */
    fun isSkyBlocked(totalInView: Int, hasRecentFix: Boolean): Boolean =
        totalInView == 0 && !hasRecentFix
}
