package com.dmujeres.traccar.health

import android.content.Context
import android.os.SystemClock
import com.dmujeres.traccar.DmujeresApp
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.data.HealthSnapshot
import com.dmujeres.traccar.core.TrackingState

/**
 * FASE 3/4 (§3, §21): monitor de salud extraído de `TrackingService`.
 * Persiste el heartbeat local (evidencia de proceso vivo sin tráfico de red)
 * y expone la clasificación de estado de [TrackingHealthPolicy].
 *
 * Retención: ~24 h (288 snapshots a 5 min). Idempotencia por
 * (sessionId, wallBucket). El envío al servidor NO ocurre aquí (canal
 * existente de telemetría/diagnóstico).
 */
class TrackingHealthMonitor(
    private val context: Context,
    private val config: AppConfig,
    private val fgsRunning: () -> Boolean,
    private val outboxCount: suspend () -> Int,
) {

    /** Clasifica las capas actuales con la política pura. */
    fun state(layers: TrackingHealthPolicy.Layers): String = TrackingHealthPolicy.evaluate(layers)

    /** Causa probable (diagnóstico, nunca para la UI del colaborador). */
    fun probableCause(layers: TrackingHealthPolicy.Layers): String =
        TrackingHealthPolicy.probableCause(layers)

    /**
     * Persiste un snapshot HEARTBEAT en Room (CAPA 5). No lanza: cualquier
     * fallo de DB se registra y sigue (la salud nunca tumba el tracking).
     */
    suspend fun persistHeartbeat(motion: String, network: String, healthState: String) {
        // F0: el delta del embudo viaja en el snapshot (denominador
        // independiente para la cobertura: segundos en movimiento del sensor).
        val funnelJson = runCatching {
            val counters = HealthFunnelPolicy.Counters(
                received = config.fixReceived,
                rejected = config.fixRejected,
                rejectedByReason = HealthFunnelPolicy.parseBreakdown(config.rejectBreakdown()),
                enqueued = config.fixEnqueued,
                acked = config.ackTotal,
            )
            HealthFunnelPolicy.toJson(
                funnel.consume(counters, windowSeconds = SNAPSHOT_PERIOD_MS / 1000L),
            )
        }.getOrDefault("")
        persistEvent(EVENT_HEARTBEAT, "", motion, network, healthState, funnelJson)
    }

    /**
     * F0: acumulador del embudo por bucket. El watchdog lo tickea cada 30 s
     * con el movimiento del sensor (bajo costo, sin I/O).
     */
    private val funnel = HealthFunnelAccumulator()

    fun tickFunnel(moving: Boolean, nowMs: Long) {
        runCatching { funnel.tick(moving, nowMs) }
    }

    /** Transición de estado del servicio (cambio real, no cada refresh). */
    suspend fun persistTransition(state: TrackingState, motion: String, network: String, healthState: String) {
        persistEvent(EVENT_STATE_CHANGE, state.name, motion, network, healthState)
    }

    /** Evento crítico (recuperación confirmada, cuarentena, etc.). */
    suspend fun persistCritical(reason: String, motion: String, network: String, healthState: String) {
        persistEvent(EVENT_CRITICAL, reason, motion, network, healthState)
    }

    /** Persiste un snapshot con el tipo de evento pedido (idempotente por bucket). */
    suspend fun persistEvent(
        eventType: String,
        reason: String,
        motion: String,
        network: String,
        healthState: String,
        funnelJson: String = "",
    ) {
        runCatching {
            val dao = (context.applicationContext as DmujeresApp).database.healthSnapshotDao()
            val now = System.currentTimeMillis()
            val snapshot = HealthSnapshot(
                sessionId = config.sessionId,
                deviceId = config.deviceId,
                wallMs = now,
                wallBucket = bucketOf(now),
                elapsedMs = SystemClock.elapsedRealtime(),
                eventType = eventType,
                reason = reason,
                fgs = fgsRunning(),
                motion = motion,
                network = network,
                outbox = runCatching { outboxCount() }.getOrDefault(0),
                healthState = healthState,
                funnel = funnelJson,
            )
            dao.insert(snapshot)
            runCatching { dao.deleteBefore(now - RETENTION_MS) }
        }
    }

    companion object {
        /** Snapshot periódico cada 5 min (12/h). */
        const val SNAPSHOT_PERIOD_MS = 5L * 60_000L

        /** Retención local de snapshots: 24 h. */
        const val RETENTION_MS = 24L * 3_600_000L

        const val EVENT_HEARTBEAT = "HEARTBEAT"
        const val EVENT_STATE_CHANGE = "STATE_CHANGE"
        const val EVENT_CRITICAL = "CRITICAL"

        /** Bucket de idempotencia: minuto de pared del snapshot. Puro. */
        fun bucketOf(wallMs: Long): Long = wallMs / 60_000L
    }
}
