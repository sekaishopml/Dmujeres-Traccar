package com.dmujeres.traccar.tracking

import com.dmujeres.traccar.core.TrackingState

/**
 * Cascada de estado del watchdog, extraída como política pura (JVM) para
 * congelar la precedencia EXACTA del bloque original de `watchdogLoop`:
 *
 * 1. permisos FINE/BACKGROUND faltantes → PERMISSION_MISSING
 * 2. retención dura llena con STOP_CAPTURE → BUFFER_FULL
 * 3. sin fix fresco → GPS_DISABLED
 * 4. sin Internet validada → NETWORK_OFFLINE
 * 5. sesión MQTT no lista → MQTT_DISCONNECTED
 * 6. pendientes anormales sin ACK → PENDING_ACK_TIMEOUT
 * 7. batería baja (1..20) → BATTERY_LOW
 * 8. todo sano → TRACKING_ACTIVE
 *
 * Los hechos los aporta el llamador (incluida la decisión de `stopCaptureFull`
 * con `bufferPolicy`/`retentionMax`): la política no lee Android ni config.
 */
object TrackingStatePolicy {

    fun next(
        fineGranted: Boolean,
        backgroundGranted: Boolean,
        stopCaptureFull: Boolean,
        gpsWithoutFix: Boolean,
        networkAvailable: Boolean,
        mqttUnavailable: Boolean,
        pendingWithoutAck: Boolean,
        batteryLow: Boolean,
    ): TrackingState = when {
        !fineGranted || !backgroundGranted -> TrackingState.PERMISSION_MISSING
        stopCaptureFull -> TrackingState.BUFFER_FULL
        gpsWithoutFix -> TrackingState.NO_FRESH_FIX
        !networkAvailable -> TrackingState.NETWORK_OFFLINE
        mqttUnavailable -> TrackingState.MQTT_DISCONNECTED
        pendingWithoutAck -> TrackingState.PENDING_ACK_TIMEOUT
        batteryLow -> TrackingState.BATTERY_LOW
        else -> TrackingState.TRACKING_ACTIVE
    }
}
