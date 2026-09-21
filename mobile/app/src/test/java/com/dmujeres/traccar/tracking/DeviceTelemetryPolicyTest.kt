package com.dmujeres.traccar.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Caracteriza el nivel de señal 0-4 y el umbral de batería baja. */
class DeviceTelemetryPolicyTest {

    @Test
    fun realStrengthIsClampedTo0To4() {
        assertEquals(4, DeviceTelemetryPolicy.signalLevel(4, 0L))
        assertEquals(2, DeviceTelemetryPolicy.signalLevel(2, 0L))
        assertEquals(4, DeviceTelemetryPolicy.signalLevel(9, 0L))
        assertEquals(0, DeviceTelemetryPolicy.signalLevel(-9, 0L))
        assertEquals(0, DeviceTelemetryPolicy.signalLevel(0, 0L))
    }

    @Test
    fun unknownStrengthFallsBackToBandwidthBuckets() {
        assertEquals(0, DeviceTelemetryPolicy.signalLevel(-1, 0L))
        assertEquals(0, DeviceTelemetryPolicy.signalLevel(-1, 300L))
        assertEquals(1, DeviceTelemetryPolicy.signalLevel(-1, 301L))
        assertEquals(1, DeviceTelemetryPolicy.signalLevel(-1, 1_000L))
        assertEquals(2, DeviceTelemetryPolicy.signalLevel(-1, 1_001L))
        assertEquals(2, DeviceTelemetryPolicy.signalLevel(-1, 3_000L))
        assertEquals(3, DeviceTelemetryPolicy.signalLevel(-1, 3_001L))
        assertEquals(3, DeviceTelemetryPolicy.signalLevel(-1, 10_000L))
        assertEquals(4, DeviceTelemetryPolicy.signalLevel(-1, 10_001L))
    }

    @Test
    fun batteryLowBoundaries() {
        assertFalse(DeviceTelemetryPolicy.isBatteryLow(0))
        assertTrue(DeviceTelemetryPolicy.isBatteryLow(1))
        assertTrue(DeviceTelemetryPolicy.isBatteryLow(20))
        assertFalse(DeviceTelemetryPolicy.isBatteryLow(21))
        assertFalse(DeviceTelemetryPolicy.isBatteryLow(-1))
    }
}
