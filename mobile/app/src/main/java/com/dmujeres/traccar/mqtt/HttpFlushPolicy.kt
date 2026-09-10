package com.dmujeres.traccar.mqtt

/**
 * Decisión pura de cuándo el plan B HTTP debe intentar drenar la cola offline.
 *
 * El watchdog ([com.dmujeres.traccar.location.TrackingService]) la usa para no depender
 * solo del último ACK: cualquier ACK de aplicación (incluidas las presencias de
 * heartbeat, que se confirman cada ~1 min sin fix de GPS) refresca `lastAckAt`, de modo
 * que mirar solo "¿hay ACK reciente?" oculta un backlog de *posiciones* que envejece
 * mientras MQTT sigue "conectado" a nivel TCP pero el consumer no las confirma.
 * Sin esta tercera condición, el fallback casi nunca disparaba en ese caso y la cola
 * crecía (o se estabilizaba en miles) sin ayuda.
 *
 * Sin dependencias Android: unit-testeable en JVM.
 */
object HttpFlushPolicy {

    /** Sin ACK de aplicación en este tiempo, MQTT se considera atascado aunque siga "conectado". */
    const val STUCK_WITHOUT_ACK_MS = 2 * 60_000L

    /** Antigüedad del pendiente más viejo que dispara ayuda HTTP aunque haya ACKs recientes. */
    const val BACKLOG_AGE_MS = 10 * 60_000L

    /**
     * @param pendingCount filas en `pending_positions`.
     * @param mqttDelivering true si hay red validada Y el cliente MQTT está listo
     * (conectado + suscrito al ACK). Equivale a `!connectionUnavailable` del watchdog.
     * @param lastAckAt [com.dmujeres.traccar.config.AppConfig.lastAckAt].
     * @param oldestPendingAt `MIN(enqueuedAt)` de la cola, o null si se desconoce
     * (filas legacy con `enqueuedAt = 0`: no se puede probar que sean frescas).
     */
    fun shouldFlush(
        pendingCount: Int,
        mqttDelivering: Boolean,
        lastAckAt: Long,
        oldestPendingAt: Long?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (pendingCount <= 0) return false
        if (!mqttDelivering) return true
        if (lastAckAt <= 0L || now - lastAckAt > STUCK_WITHOUT_ACK_MS) return true
        val oldest = oldestPendingAt?.takeIf { it > 0L } ?: return true
        return now - oldest > BACKLOG_AGE_MS
    }
}
