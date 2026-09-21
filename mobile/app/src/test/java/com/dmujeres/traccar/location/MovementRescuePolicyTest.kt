package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R5 §26: matriz de la máquina de rescate (pura, sin Android). */
class MovementRescuePolicyTest {

    private fun input(
        journey: Boolean = true,
        fixAge: Long? = 0L,
        sensorMoving: Boolean = false,
        displacement: Float? = null,
        rescueActive: Boolean = false,
        streak: Int = 0,
        lastRescueAtMs: Long? = null,
        nowMs: Long = 0L,
    ) = MovementRescuePolicy.Input(
        journeyActive = journey,
        fixAgeMs = fixAge,
        sensorMoving = sensorMoving,
        displacementM = displacement,
        rescueActive = rescueActive,
        movementStreak = streak,
        lastRescueAtMs = lastRescueAtMs,
        nowMs = nowMs,
    )

    /** A: GPS healthy + stationary → NORMAL */
    @Test
    fun `A gps healthy y stationary es NORMAL`() {
        val d = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.NORMAL, input(fixAge = 10_000L))
        assertEquals(MovementRescuePolicy.RescueState.NORMAL, d.state)
        assertFalse(d.rescueRequested)
    }

    /** B: GPS healthy + moving (sensor) → NORMAL con fix fresco. */
    @Test
    fun `B gps healthy con sensor moving sigue NORMAL`() {
        val d = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.NORMAL, input(fixAge = 10_000L, sensorMoving = true))
        assertEquals(MovementRescuePolicy.RescueState.NORMAL, d.state)
    }

    /** C: GPS stale + stationary → GPS_DEGRADED sin rescate. */
    @Test
    fun `C gps stale y stationary es DEGRADED sin rescate`() {
        val d = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.NORMAL, input(fixAge = 70_000L))
        assertEquals(MovementRescuePolicy.RescueState.GPS_DEGRADED, d.state)
        assertFalse(d.rescueRequested)
    }

    /** D: GPS stale + moving → candidato 1/2. */
    @Test
    fun `D gps stale y moving es candidato`() {
        val d = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.NORMAL, input(fixAge = 70_000L, sensorMoving = true))
        assertEquals(MovementRescuePolicy.RescueState.MOVEMENT_CANDIDATE, d.state)
        assertFalse(d.rescueRequested)
    }

    /** E: GPS lost + stationary → GPS_LOST sin burst. */
    @Test
    fun `E gps lost y stationary es LOST sin rescate`() {
        val d = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.NORMAL, input(fixAge = null))
        assertEquals(MovementRescuePolicy.RescueState.GPS_LOST, d.state)
        assertFalse(d.rescueRequested)
    }

    /** F: GPS lost + moving → RESCUE inmediato. */
    @Test
    fun `F gps lost y moving activa rescate`() {
        val d = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.NORMAL, input(fixAge = null, sensorMoving = true))
        assertEquals(MovementRescuePolicy.RescueState.RESCUE, d.state)
        assertTrue(d.rescueRequested)
    }

    /** G: una sola muestra no confirma. */
    @Test
    fun `G degraded con una muestra de movimiento no rescata`() {
        val d = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.NORMAL, input(fixAge = 70_000L, sensorMoving = true, streak = 0))
        assertEquals(MovementRescuePolicy.RescueState.MOVEMENT_CANDIDATE, d.state)
    }

    /** H: segunda muestra consecutiva confirma. */
    @Test
    fun `H segunda muestra confirma rescate`() {
        val d = MovementRescuePolicy.decide(
            MovementRescuePolicy.RescueState.GPS_DEGRADED,
            input(fixAge = 70_000L, sensorMoving = true, streak = 1),
        )
        assertEquals(MovementRescuePolicy.RescueState.RESCUE, d.state)
        assertTrue(d.rescueRequested)
    }

    @Test
    fun `desplazamiento real de 15m cuenta como evidencia`() {
        val d = MovementRescuePolicy.decide(
            MovementRescuePolicy.RescueState.GPS_DEGRADED,
            input(fixAge = 70_000L, displacement = 15f, streak = 1),
        )
        assertEquals(MovementRescuePolicy.RescueState.RESCUE, d.state)
    }

    /** I-K: el rescue en curso no re-dispara; fix real → FIX_RECOVERED → NORMAL. */
    @Test
    fun `rescue en curso no re-pide y fix real recupera`() {
        val inCourse = MovementRescuePolicy.decide(
            MovementRescuePolicy.RescueState.RESCUE,
            input(fixAge = 200_000L, sensorMoving = true, rescueActive = true),
        )
        assertEquals(MovementRescuePolicy.RescueState.RESCUE, inCourse.state)
        assertFalse(inCourse.rescueRequested)

        val fixed = MovementRescuePolicy.decide(
            MovementRescuePolicy.RescueState.RESCUE,
            input(fixAge = 5_000L, sensorMoving = true),
        )
        assertEquals(MovementRescuePolicy.RescueState.FIX_RECOVERED, fixed.state)
        assertFalse(fixed.rescueRequested)

        val back = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.FIX_RECOVERED, input(fixAge = 5_000L))
        assertEquals(MovementRescuePolicy.RescueState.NORMAL, back.state)
    }

    /** J: cooldown — dentro de la ventana de 5 min no vuelve a rescatar. */
    @Test
    fun `cooldown bloquea rescate repetido`() {
        val now = 10 * 60 * 1000L
        val d = MovementRescuePolicy.decide(
            MovementRescuePolicy.RescueState.GPS_LOST,
            input(fixAge = null, sensorMoving = true, lastRescueAtMs = now - 60_000L, nowMs = now),
        )
        assertFalse(d.rescueRequested)
        // Fuera del cooldown vuelve a pedirlo.
        val after = MovementRescuePolicy.decide(
            MovementRescuePolicy.RescueState.GPS_LOST,
            input(fixAge = null, sensorMoving = true, lastRescueAtMs = now - 6 * 60_000L, nowMs = now),
        )
        assertTrue(after.rescueRequested)
    }

    /** M: sin jornada nunca rescata. */
    @Test
    fun `sin jornada nunca entra en rescate`() {
        val d = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.NORMAL, input(journey = false, fixAge = null, sensorMoving = true))
        assertFalse(d.rescueRequested)
    }
}
