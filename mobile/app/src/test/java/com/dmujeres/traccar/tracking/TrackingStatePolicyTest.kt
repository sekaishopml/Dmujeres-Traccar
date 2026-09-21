package com.dmujeres.traccar.tracking

import com.dmujeres.traccar.core.TrackingState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Caracteriza la cascada del watchdog (precedencia EXACTA del bloque original
 * en `TrackingService.watchdogLoop`). Cualquier cambio de orden debe romper
 * estos tests.
 */
class TrackingStatePolicyTest {

    private fun next(
        fine: Boolean = true,
        background: Boolean = true,
        bufferFull: Boolean = false,
        gpsWithoutFix: Boolean = false,
        network: Boolean = true,
        mqttUnavailable: Boolean = false,
        pendingWithoutAck: Boolean = false,
        batteryLow: Boolean = false,
    ) = TrackingStatePolicy.next(fine, background, bufferFull, gpsWithoutFix, network, mqttUnavailable, pendingWithoutAck, batteryLow)

    @Test
    fun allHealthyIsTrackingActive() {
        assertEquals(TrackingState.TRACKING_ACTIVE, next())
    }

    @Test
    fun missingFinePermissionWinsOverEverything() {
        assertEquals(
            TrackingState.PERMISSION_MISSING,
            next(fine = false, bufferFull = true, gpsWithoutFix = true, network = false),
        )
    }

    @Test
    fun missingBackgroundPermissionAlsoBlocks() {
        assertEquals(TrackingState.PERMISSION_MISSING, next(background = false))
    }

    @Test
    fun bufferFullWinsOverGpsAndNetwork() {
        assertEquals(
            TrackingState.BUFFER_FULL,
            next(bufferFull = true, gpsWithoutFix = true, network = false, mqttUnavailable = true),
        )
    }

    @Test
    fun gpsWithoutFixWinsOverNetwork() {
        assertEquals(
            TrackingState.NO_FRESH_FIX,
            next(gpsWithoutFix = true, network = false, mqttUnavailable = true),
        )
    }

    @Test
    fun networkOfflineWinsOverMqtt() {
        assertEquals(TrackingState.NETWORK_OFFLINE, next(network = false, mqttUnavailable = true))
    }

    @Test
    fun mqttBeforePendingAckAndBattery() {
        assertEquals(
            TrackingState.MQTT_DISCONNECTED,
            next(mqttUnavailable = true, pendingWithoutAck = true, batteryLow = true),
        )
    }

    @Test
    fun pendingAckBeforeBattery() {
        assertEquals(TrackingState.PENDING_ACK_TIMEOUT, next(pendingWithoutAck = true, batteryLow = true))
    }

    @Test
    fun batteryLowIsLastBeforeActive() {
        assertEquals(TrackingState.BATTERY_LOW, next(batteryLow = true))
    }
}
