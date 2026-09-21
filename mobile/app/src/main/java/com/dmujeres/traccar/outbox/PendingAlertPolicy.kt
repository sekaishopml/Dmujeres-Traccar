package com.dmujeres.traccar.outbox

/**
 * Decisión pura de cuándo los pendientes son ANORMALES (aviso rojo / alerta)
 * frente a un backlog leve y sano ("Sincronizando N…").
 *
 * Contexto: el dispatch MQTT es estrictamente secuencial (1 mensaje en vuelo,
 * `ackTimeout` 15 s por mensaje + 200 ms entre envíos) mientras la captura es
 * cada 10 s. Con la latencia normal del consumer (MQTT → Java → PG → ACK) casi
 * siempre hay >= 1 pendiente en la cola aunque todo funcione, así que pintar de
 * rojo o alertar por tener 1-5 pendientes recientes y sanos es casi permanente
 * y esconde los atascos reales. Este predicado solo marca anormal lo que un
 * pipeline sano no produce.
 *
 * Sin dependencias Android: unit-testeable en JVM (ver PendingAlertPolicyTest).
 */
object PendingAlertPolicy {

    /** Más pendientes que esto es anormal aunque sean jóvenes (acumulación rápida). */
    const val ABNORMAL_COUNT = 30

    /**
     * Antigüedad del pendiente más viejo que marca atasco.
     * Igual que el umbral del watchdog (10 min): un pipeline sano drena mucho antes.
     */
    const val ABNORMAL_AGE_MS = 10 * 60_000L

    /**
     * Sin ACK de aplicación en este tiempo, con cola > 0, se considera atascado
     * aunque el pendiente más viejo aún sea joven. Igual que
     * [HttpFlushPolicy.STUCK_WITHOUT_ACK_MS] (el plan B HTTP ya usa 2 min).
     */
    const val STUCK_WITHOUT_ACK_MS = 2 * 60_000L

    /**
     * @param pendingCount filas en `pending_positions`.
     * @param oldestPendingAt `MIN(enqueuedAt)` de la cola, o null si se desconoce
     * (filas legacy con `enqueuedAt = 0`: no se puede probar que sean frescas).
     * @param lastAckAt [com.dmujeres.traccar.config.AppConfig.lastAckAt]
     * (0 = aún sin ningún ACK, p. ej. arranque: no alarma por sí solo).
     */
    fun isAbnormal(
        pendingCount: Int,
        oldestPendingAt: Long?,
        lastAckAt: Long,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (pendingCount <= 0) return false
        if (pendingCount > ABNORMAL_COUNT) return true
        if (lastAckAt > 0L && now - lastAckAt > STUCK_WITHOUT_ACK_MS) return true
        // Sin edad conocida no se puede probar que la cola sea fresca: como el
        // watchdog y HttpFlushPolicy, se trata como anormal (solo ocurre con
        // filas legacy; las filas nuevas siempre traen enqueuedAt).
        val oldest = oldestPendingAt?.takeIf { it > 0L } ?: return true
        return now - oldest > ABNORMAL_AGE_MS
    }
}
