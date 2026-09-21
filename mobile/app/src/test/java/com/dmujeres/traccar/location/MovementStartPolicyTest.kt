package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3.5-C6: matriz de la política de inicio de movimiento (pura, JVM).
 * Nunca valida coordenadas: la política no produce lat/lon.
 */
class MovementStartPolicyTest {

    private fun ev(
        speed: Float? = null,
        displacement: Float? = null,
        sensor: Boolean = false,
        fresh: Boolean = true,
    ) = MovementStartPolicy.Evidence(speed, displacement, sensor, fresh)

    @Test
    fun `quieto con speed baja sigue stationary`() {
        val d = MovementStartPolicy.next(
            MovementStartPolicy.State.STATIONARY,
            ev(speed = 0.4f, displacement = 3f),
            0,
        )
        assertEquals(MovementStartPolicy.State.STATIONARY, d.state)
    }

    @Test
    fun `legado speed mayor o igual a 5 pasa a moving inmediato`() {
        val d = MovementStartPolicy.next(MovementStartPolicy.State.STATIONARY, ev(speed = 5.2f), 0)
        assertEquals(MovementStartPolicy.State.MOVING, d.state)
        assertTrue(d.reason.contains("5.0"))
    }

    @Test
    fun `arranque lento 1_5 mps requiere dos muestras`() {
        val first = MovementStartPolicy.next(
            MovementStartPolicy.State.STATIONARY, ev(speed = 1.6f), 0,
        )
        assertEquals(MovementStartPolicy.State.MOVEMENT_CANDIDATE, first.state)
        val second = MovementStartPolicy.next(
            MovementStartPolicy.State.MOVEMENT_CANDIDATE, ev(speed = 1.8f), 1,
        )
        assertEquals(MovementStartPolicy.State.MOVING, second.state)
    }

    @Test
    fun `desplazamiento real confirma al primer fix aunque la velocidad sea baja`() {
        // R8.1: desplazamiento >=15 m del ancla = evidencia geométrica dura:
        // un solo fix confirma (antes esperaba 2 muestras y la ruta arrancaba
        // 2-4 min tarde tras una parada).
        val first = MovementStartPolicy.next(
            MovementStartPolicy.State.STATIONARY, ev(speed = 0.8f, displacement = 18f), 0,
        )
        assertEquals(MovementStartPolicy.State.MOVING, first.state)
        assertTrue(first.reason.contains("desplazamiento"))
        val second = MovementStartPolicy.next(
            MovementStartPolicy.State.STATIONARY, ev(speed = 1.0f, displacement = 25f), 1,
        )
        assertEquals(MovementStartPolicy.State.MOVING, second.state)
    }

    @Test
    fun `sensor aislado es candidate y nunca moving`() {
        var d = MovementStartPolicy.next(
            MovementStartPolicy.State.STATIONARY, ev(sensor = true), 0,
        )
        assertEquals(MovementStartPolicy.State.MOVEMENT_CANDIDATE, d.state)
        // Aunque se repita, sin evidencia real no confirma.
        repeat(3) {
            d = MovementStartPolicy.next(MovementStartPolicy.State.MOVEMENT_CANDIDATE, ev(sensor = true), 0)
            assertEquals(MovementStartPolicy.State.MOVEMENT_CANDIDATE, d.state)
        }
    }

    @Test
    fun `ruido aislado no mueve`() {
        val d = MovementStartPolicy.next(
            MovementStartPolicy.State.STATIONARY, ev(speed = 0.2f, displacement = 2f), 0,
        )
        assertEquals(MovementStartPolicy.State.STATIONARY, d.state)
    }

    @Test
    fun `moving a stationary mantiene histeresis`() {
        // 3 m/s en movimiento: se mantiene (zona de histéresis 1-5).
        val keep = MovementStartPolicy.next(MovementStartPolicy.State.MOVING, ev(speed = 3f), 0)
        assertEquals(MovementStartPolicy.State.MOVING, keep.state)
        // < 1 m/s: detiene.
        val stop = MovementStartPolicy.next(MovementStartPolicy.State.MOVING, ev(speed = 0.5f), 0)
        assertEquals(MovementStartPolicy.State.STATIONARY, stop.state)
    }

    @Test
    fun `perdida de fix no inventa movimiento`() {
        val d = MovementStartPolicy.next(
            MovementStartPolicy.State.MOVEMENT_CANDIDATE,
            ev(speed = 8f, displacement = 40f, sensor = true, fresh = false),
            1,
        )
        assertEquals(MovementStartPolicy.State.STATIONARY, d.state)
        assertFalse(MovementStartPolicy.usesBurstCapture(d.state))
    }

    @Test
    fun `candidate y moving usan burst de captura`() {
        assertTrue(MovementStartPolicy.usesBurstCapture(MovementStartPolicy.State.MOVEMENT_CANDIDATE))
        assertTrue(MovementStartPolicy.usesBurstCapture(MovementStartPolicy.State.MOVING))
        assertFalse(MovementStartPolicy.usesBurstCapture(MovementStartPolicy.State.STATIONARY))
    }

    @Test
    fun `speed negativa o no finita no cuenta como evidencia`() {
        val neg = MovementStartPolicy.next(MovementStartPolicy.State.STATIONARY, ev(speed = -3f), 0)
        assertEquals(MovementStartPolicy.State.STATIONARY, neg.state)
        val nan = MovementStartPolicy.next(MovementStartPolicy.State.STATIONARY, ev(speed = Float.NaN), 0)
        assertEquals(MovementStartPolicy.State.STATIONARY, nan.state)
    }

    @Test
    fun `moving con speed nula vuelve a stationary (paridad legado)`() {
        val d = MovementStartPolicy.next(MovementStartPolicy.State.MOVING, ev(speed = null), 0)
        assertEquals(MovementStartPolicy.State.STATIONARY, d.state)
    }
}
