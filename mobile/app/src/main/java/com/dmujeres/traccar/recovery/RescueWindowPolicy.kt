package com.dmujeres.traccar.recovery

/**
 * R8: política pura (JVM) de la ventana de rescate del probe FCM.
 *
 * Problema real (joseph, 19-sep): 21 despertares FCM con FGS activo y solo 2
 * posiciones — el proceso despertaba pero el GPS no enganchaba antes de que el
 * sistema lo volviera a dormir. La ventana mantiene CPU despierta durante los
 * rechecks del ACK y el enganche típico del GPS.
 *
 * LÍMITE de batería explícito: la ventana NUNCA supera [MAX_WINDOW_MS]; es un
 * wakelock acotado con timeout nativo (se libera solo al vencer).
 */
object RescueWindowPolicy {

    /** Recheck FCM a 6 s + recheck GPS a 30 s (ver FcmRecoveryMessagingService). */
    const val RECHECKS_MS = 36_000L

    /** Enganche GPS típico tras dormir (frio: 30–60 s). */
    const val GPS_WARMUP_MS = 45_000L

    /** Margen para el ACK al servidor. */
    const val MARGIN_MS = 9_000L

    /** Ventana efectiva: 90 s. */
    const val WINDOW_MS = RECHECKS_MS + GPS_WARMUP_MS + MARGIN_MS

    /**
     * Ventana del GUARDIÁN por alarma (cada 2 min con jornada activa): el GPS
     * ya está caliente, 45 s bastan para un fix; no es la ventana de 90 s del
     * despertar FCM (cold start). Duty ≈ 37 % solo con jornada activa.
     */
    const val KEEPER_WINDOW_MS = 45_000L

    /**
     * R9: ventana en MOVIMIENTO (jornada activa, sin red): mantiene CPU para
     * trazar el tramo aunque el OEM congele y no haya internet (FCM no llega).
     * Acotada: se renueva solo con fixes aceptados.
     */
    const val MOVING_WINDOW_MS = 150_000L

    /** Cota dura de batería: jamás una ventana mayor a 3 min. */
    const val MAX_WINDOW_MS = 180_000L

    /** ¿La ventana está dentro de la cota? (guarda de regresión) */
    fun isBounded(windowMs: Long = WINDOW_MS): Boolean = windowMs in 1..MAX_WINDOW_MS

    /** Solo un probe VÁLIDO abre ventana; los rechazados no gastan batería. */
    fun shouldOpen(validProbe: Boolean): Boolean = validProbe
}
