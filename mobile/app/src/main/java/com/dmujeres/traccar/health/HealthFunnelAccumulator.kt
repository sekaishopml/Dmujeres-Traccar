package com.dmujeres.traccar.health

/**
 * F0: acumulador del embudo por bucket. El watchdog lo "tickea" cada 30 s con
 * el estado de movimiento del sensor; el heartbeat de 5 min lo consume y el
 * delta viaja en el snapshot.
 *
 * Hilos: watchdog (tick) y heartbeat (consume) van en el mismo scope del
 * servicio, pero se sincroniza por seguridad.
 */
class HealthFunnelAccumulator {

    private var previous: HealthFunnelPolicy.Counters? = null
    private var movingSeconds: Long = 0L
    private var lastTickAtMs: Long = 0L

    /** Suma segundos en movimiento desde el tick anterior (máx. 120 s por tick). */
    @Synchronized
    fun tick(moving: Boolean, nowMs: Long) {
        if (lastTickAtMs > 0L && moving) {
            val elapsed = ((nowMs - lastTickAtMs) / 1000L).coerceIn(0L, 120L)
            movingSeconds += elapsed
        }
        lastTickAtMs = nowMs
    }

    /** Devuelve el delta del bucket y reinicia la ventana. */
    @Synchronized
    fun consume(
        counters: HealthFunnelPolicy.Counters,
        windowSeconds: Long,
    ): HealthFunnelPolicy.Delta {
        val delta = HealthFunnelPolicy.delta(previous, counters, movingSeconds, windowSeconds)
        previous = counters
        movingSeconds = 0L
        return delta
    }
}
