package com.dmujeres.traccar.health

/**
 * F0 (prompt 90 %): embudo de captura por bucket de 5 min.
 *
 * Objetivo: que la métrica de cobertura NO se calcule sobre los propios fixes
 * (un viaje sin datos daba 100 % de cobertura con 1 punto). Aquí se reporta,
 * por bucket: fixes recibidos → rechazados por motivo → encolados → con ACK, y
 * los SEGUNDOS EN MOVIMIENTO medidos por sensor (denominador independiente).
 *
 * Todo son deltas por bucket (los contadores de la app son acumulados), con
 * clamp a 0 si la app se reinició a mitad de ventana.
 *
 * Puro (JVM): testeable sin Android.
 */
object HealthFunnelPolicy {

    /** Contadores acumulados de la app al momento de leer. */
    data class Counters(
        val received: Long,
        val rejected: Long,
        val rejectedByReason: Map<String, Long>,
        val enqueued: Long,
        val acked: Long,
    )

    /** Deltas del bucket (lo que se sube). */
    data class Delta(
        val received: Long,
        val rejected: Long,
        val rejectedByReason: Map<String, Long>,
        val enqueued: Long,
        val acked: Long,
        val movingSeconds: Long,
        val windowSeconds: Long,
    )

    /** Parsea "accuracy:3|implied:10" (formato de AppConfig.rejectBreakdown). */
    fun parseBreakdown(compact: String?): Map<String, Long> {
        if (compact.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, Long>()
        compact.split('|').forEach { pair ->
            val parts = pair.split(':')
            if (parts.size == 2) {
                val value = parts[1].trim().toLongOrNull()
                if (value != null && value > 0) {
                    result[parts[0].trim()] = value
                }
            }
        }
        return result
    }

    /** Delta entre dos lecturas; nunca negativo (reinicio → 0, no resta). */
    fun delta(
        previous: Counters?,
        current: Counters,
        movingSeconds: Long,
        windowSeconds: Long,
    ): Delta {
        fun diff(now: Long, before: Long?): Long =
            if (before == null || now < before) 0L else now - before
        val reasons = LinkedHashMap<String, Long>()
        current.rejectedByReason.forEach { (reason, total) ->
            val diffValue = diff(total, previous?.rejectedByReason?.get(reason))
            if (diffValue > 0) reasons[reason] = diffValue
        }
        return Delta(
            received = diff(current.received, previous?.received),
            rejected = diff(current.rejected, previous?.rejected),
            rejectedByReason = reasons,
            enqueued = diff(current.enqueued, previous?.enqueued),
            acked = diff(current.acked, previous?.acked),
            movingSeconds = movingSeconds.coerceAtLeast(0L),
            windowSeconds = windowSeconds.coerceAtLeast(0L),
        )
    }

    /** JSON compacto y estable (viaja en el snapshot de salud). */
    fun toJson(delta: Delta): String {
        val reasons = delta.rejectedByReason.entries
            .joinToString(",") { "\"${it.key}\":${it.value}" }
        return buildString {
            append("{\"rx\":").append(delta.received)
            append(",\"rj\":").append(delta.rejected)
            append(",\"rr\":{").append(reasons).append('}')
            append(",\"en\":").append(delta.enqueued)
            append(",\"ak\":").append(delta.acked)
            append(",\"mv\":").append(delta.movingSeconds)
            append(",\"w\":").append(delta.windowSeconds)
            append('}')
        }
    }
}
