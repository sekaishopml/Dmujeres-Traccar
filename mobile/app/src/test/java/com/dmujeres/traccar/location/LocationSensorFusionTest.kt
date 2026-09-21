package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FASE 8: fusión de sensores. Reglas anti-dead-reckoning: la fusión solo
 * autoriza re-adquirir, nunca inventa posición; sin sensores opcionales
 * degrada con honestidad.
 */
class LocationSensorFusionTest {

    private fun input(
        fixAgeMs: Long?,
        accuracy: Double? = 10.0,
        motion: String = "UNKNOWN",
        gyro: String? = null,
        speed: Float? = null,
    ) = LocationSensorFusion.Input(fixAgeMs, accuracy, motion, gyro, speed)

    @Test
    fun `fix fresco y preciso es GPS_HEALTHY`() {
        val out = LocationSensorFusion.fuse(input(10_000L))
        assertEquals(LocationSensorFusion.GPS_HEALTHY, out.gpsHealth)
        assertFalse(out.reacquisitionRequired)
    }

    @Test
    fun `fix fresco impreciso no es healthy`() {
        val out = LocationSensorFusion.fuse(input(10_000L, accuracy = 120.0))
        assertEquals(LocationSensorFusion.GPS_DEGRADED, out.gpsHealth)
    }

    @Test
    fun `fix viejo es GPS_LOST`() {
        val out = LocationSensorFusion.fuse(input(10 * 60_000L))
        assertEquals(LocationSensorFusion.GPS_LOST, out.gpsHealth)
        assertFalse(out.reacquisitionRequired)
    }

    @Test
    fun `gps perdido con movimiento exige readquisicion sin inventar posicion`() {
        val out = LocationSensorFusion.fuse(
            input(10 * 60_000L, motion = "MOVING"),
        )
        assertEquals(LocationSensorFusion.GPS_LOST, out.gpsHealth)
        assertEquals(LocationSensorFusion.MOVEMENT_LIKELY, out.movement)
        assertTrue(out.reacquisitionRequired)
        assertEquals("stale_fix_with_movement", out.reason)
    }

    @Test
    fun `sin fix y con giroscopio rotando tambien exige readquisicion`() {
        val out = LocationSensorFusion.fuse(input(null, motion = "UNKNOWN", gyro = "ROTATING"))
        assertEquals(LocationSensorFusion.GPS_LOST, out.gpsHealth)
        assertTrue(out.reacquisitionRequired)
        assertEquals("no_fix_with_movement", out.reason)
    }

    @Test
    fun `quieto con gps perdido no dispara readquisicion agresiva`() {
        val out = LocationSensorFusion.fuse(
            input(10 * 60_000L, motion = "STATIONARY", gyro = "STEADY", speed = 0.0f),
        )
        assertEquals(LocationSensorFusion.STATIONARY_LIKELY, out.movement)
        assertFalse(out.reacquisitionRequired)
        assertEquals("gps_lost_stationary", out.reason)
    }

    @Test
    fun `giroscopio ausente degrada usando acelerometro y velocidad`() {
        val out = LocationSensorFusion.fuse(input(1_000L, motion = "MOVING", gyro = null))
        assertEquals(LocationSensorFusion.MOVEMENT_LIKELY, out.movement)
        val still = LocationSensorFusion.fuse(input(1_000L, motion = "STATIONARY", gyro = null, speed = 0f))
        assertEquals(LocationSensorFusion.STATIONARY_LIKELY, still.movement)
    }

    @Test
    fun `sin evidencia es UNKNOWN y no autoriza nada`() {
        val out = LocationSensorFusion.fuse(input(10 * 60_000L, motion = "UNKNOWN"))
        assertEquals(LocationSensorFusion.MOTION_UNKNOWN, out.movement)
        assertFalse(out.reacquisitionRequired)
        assertEquals("insufficient_evidence", out.reason)
    }

    @Test
    fun `fromTelemetry calcula la edad y respeta giroscopio no disponible`() {
        val out = LocationSensorFusion.fromTelemetry(
            lastFixAtMs = 1_000_000L,
            nowMs = 1_000_000L + 10 * 60_000L,
            accuracyM = 10.0,
            motionState = "UNKNOWN",
            gyroAvailable = false,
            gyroState = "ROTATING",
            speedMps = null,
        )
        assertEquals(LocationSensorFusion.GPS_LOST, out.gpsHealth)
        assertEquals(LocationSensorFusion.MOTION_UNKNOWN, out.movement)
        assertFalse(out.reacquisitionRequired)
    }
}
