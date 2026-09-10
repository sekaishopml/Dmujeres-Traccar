package com.dmujeres.traccar.db

/** Regla única para mantener acotada la cola antes de insertar una posición. */
object PositionBufferPolicy {

    fun discardCount(currentCount: Int, maximum: Int): Int {
        require(maximum > 0) { "maximum must be positive" }
        return (currentCount - maximum + 1).coerceAtLeast(0)
    }
}

/**
 * Drenaje del outbox al finalizar la jornada (TrackingService.stopTracking y
 * TrackingRecoveryWorker): la cola NO se borra al cerrar, se drena por HTTP
 * hasta vaciarla o hasta el deadline. Puro (testeable en JVM, sin Android).
 *
 * Sin esto, el flush de cierre abortaba al primer intento fallido
 * (`if (flushed <= 0) return`) y el deadline de 90 s cortaba colas grandes en
 * red lenta: la ruta quedaba incompleta hasta la próxima jornada (o más).
 */
object StopDrainPolicy {

    /** Ventana de drenaje al cerrar: 4 min (una jornada de 8 h ≈ 58 lotes). */
    const val TIMEOUT_MS = 240_000L

    /** Pausa entre intentos cuando el flush no confirma nada (red caída). */
    const val RETRY_DELAY_MS = 5_000L

    fun timedOut(nowMs: Long, deadlineMs: Long): Boolean = nowMs >= deadlineMs

    /**
     * Tras un flush con progreso se reintenta de inmediato; sin progreso se
     * espera [RETRY_DELAY_MS] en vez de abortar (la red puede volver a mitad
     * de la ventana).
     */
    fun retryDelayAfter(flushed: Int): Long = if (flushed > 0) 0L else RETRY_DELAY_MS
}
