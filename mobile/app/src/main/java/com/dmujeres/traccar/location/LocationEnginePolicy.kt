package com.dmujeres.traccar.location

/**
 * Tabla pura (JVM) del motor de ubicación. Caracteriza EXACTAMENTE las
 * decisiones temporales que hoy viven en el watchdog de `TrackingService`
 * (FASE 2 del prompt maestro: congelar comportamiento antes de extraer):
 *
 * - Cuándo la jornada está "sin fix fresco" (umbral dinámico por intervalo).
 * - Cuándo toca re-solicitar actualizaciones (cada 2 min sin fix).
 * - Cuándo recrear el cliente FLP (sin callbacks crudos > 10 min, máx 1/15 min).
 * - Cuándo la alerta de "sin satélites GNSS" es legítima (jornada > 5 min y
 *   sin datos GNSS o <= 1 satélite usado, con throttle de 10 min).
 *
 * NO decide ubicaciones ni fabrica coordenadas: solo tiempos.
 */
object LocationEnginePolicy {

    /** Mínimo absoluto de "fix viejo" aunque el intervalo sea diminuto. */
    const val STALE_MIN_MS = 60_000L

    /** Factor sobre el intervalo configurado para considerar un fix viejo. */
    const val FIX_STALE_INTERVAL_FACTOR = 3_000L

    /** Re-solicitar updates si no hay fix (2 min), tal como el watchdog actual. */
    const val REREGISTER_PERIOD_MS = 2 * 60_000L

    /** Sin callbacks crudos del FLP durante 10 min = cliente sospechoso. */
    const val CALLBACK_SILENCE_MS = 10 * 60_000L

    /** Recrear el cliente como máximo una vez cada 15 min. */
    const val ENGINE_REINIT_MIN_PERIOD_MS = 15 * 60_000L

    /** Sin GNSS se avisa solo tras 5 min de jornada (evita el falso positivo del arranque). */
    const val NO_GNSS_ALERT_AFTER_MS = 5 * 60_000L

    /** Throttle de la alerta GNSS: una cada 10 min. */
    const val NO_GNSS_ALERT_THROTTLE_MS = 10 * 60_000L

    /** Umbral de "fix viejo" para un intervalo dado. */
    fun staleThresholdMs(intervalSeconds: Long): Long =
        maxOf(STALE_MIN_MS, intervalSeconds.coerceAtLeast(1L) * FIX_STALE_INTERVAL_FACTOR)

    /**
     * ¿Jornada sin fix fresco? Réplica exacta del cálculo del watchdog:
     * - aún sin fix en esta corrida y > STALE_MIN_MS desde el arranque, o
     * - fix ya viejo respecto al umbral dinámico.
     */
    fun gpsWithoutFix(
        nowMs: Long,
        startedTrackingAtMs: Long,
        lastFixAtMs: Long,
        intervalSeconds: Long,
    ): Boolean {
        val fixForCurrentRun = lastFixAtMs >= startedTrackingAtMs && lastFixAtMs > 0L
        return (!fixForCurrentRun && nowMs - startedTrackingAtMs > STALE_MIN_MS) ||
            (fixForCurrentRun && nowMs - lastFixAtMs > staleThresholdMs(intervalSeconds))
    }

    /** Re-solicitud por cadencia: `now - lastReregister > 2 min` (0 = nunca → dispara). */
    fun shouldReregister(nowMs: Long, lastReregisterAtMs: Long): Boolean =
        nowMs - lastReregisterAtMs > REREGISTER_PERIOD_MS

    /**
     * Motor sospechoso: hubo callbacks alguna vez (lastCallbackAt > 0), ninguno
     * en 10 min y no se recreó en los últimos 15 min.
     */
    fun shouldReinitEngine(nowMs: Long, lastCallbackAtMs: Long, lastReinitAtMs: Long): Boolean =
        lastCallbackAtMs > 0L &&
            nowMs - lastCallbackAtMs > CALLBACK_SILENCE_MS &&
            nowMs - lastReinitAtMs > ENGINE_REINIT_MIN_PERIOD_MS

    /** Frescura de fix en reloj monotónico (mismo umbral que [staleThresholdMs]). */
    fun isFixRecent(lastFixElapsedNanos: Long, nowElapsedNanos: Long, intervalSeconds: Long): Boolean {
        if (lastFixElapsedNanos <= 0L) return false
        return nowElapsedNanos - lastFixElapsedNanos <= staleThresholdMs(intervalSeconds) * 1_000_000L
    }

    /** Aviso GNSS: solo con jornada madura, sin GNSS útil y con throttle. */
    fun noGnssAlertDue(
        nowMs: Long,
        startedTrackingAtMs: Long,
        lastNoGpsAlertAtMs: Long,
        gnssHasData: Boolean,
        gnssUsed: Int?,
    ): Boolean =
        nowMs - startedTrackingAtMs > NO_GNSS_ALERT_AFTER_MS &&
            nowMs - lastNoGpsAlertAtMs > NO_GNSS_ALERT_THROTTLE_MS &&
            (!gnssHasData || (gnssUsed ?: 0) <= 1)
}
