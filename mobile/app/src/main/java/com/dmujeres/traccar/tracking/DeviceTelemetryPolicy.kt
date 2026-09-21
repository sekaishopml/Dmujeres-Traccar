package com.dmujeres.traccar.tracking

/**
 * Decisiones puras de telemetría de dispositivo, extraídas de
 * `TrackingService.currentSignalLevel` para poder congelarlas en JVM.
 *
 * Nivel de señal 0-4:
 * - `strength >= 0` (SDK>=29 con dato real) → se usa tal cual, acotado 0..4.
 * - `strength < 0` (desconocido o SDK<29) → buckets por ancho de banda
 *   estimado (linkDownstreamBandwidthKbps).
 */
object DeviceTelemetryPolicy {

    fun signalLevel(strength: Int, downstreamKbps: Long): Int {
        if (strength >= 0) return strength.coerceIn(0, 4)
        return when {
            downstreamKbps > 10_000L -> 4
            downstreamKbps > 3_000L -> 3
            downstreamKbps > 1_000L -> 2
            downstreamKbps > 300L -> 1
            else -> 0
        }
    }

    /** Batería baja operativa (aviso + estado): 1..20 %. */
    fun isBatteryLow(batteryPct: Int): Boolean = batteryPct in 1..20
}
