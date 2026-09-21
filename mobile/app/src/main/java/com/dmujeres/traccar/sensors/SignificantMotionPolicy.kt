package com.dmujeres.traccar.sensors

/**
 * Política pura (JVM) del sensor de movimiento significativo: limita la
 * frecuencia de disparos para no pedir fixes de más (batería).
 */
object SignificantMotionPolicy {

    /** Mínimo entre disparos aceptados (el sensor puede disparar seguido). */
    const val COOLDOWN_MS = 30_000L

    /** ¿Se debe actuar con este disparo? (primero siempre) */
    fun shouldFire(lastTriggerAtMs: Long, nowMs: Long, cooldownMs: Long = COOLDOWN_MS): Boolean =
        lastTriggerAtMs <= 0L || nowMs - lastTriggerAtMs >= cooldownMs
}
