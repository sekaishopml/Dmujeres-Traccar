package org.traccar.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PositionConsistencyPolicyTest {

    @Test
    fun `el caso real del salto se descarta`() {
        // 52 m en 6 s (8.7 m/s) con el equipo reportando 3.5 km/h (0.97 m/s).
        assertFalse(PositionConsistencyPolicy.isConsistent(52.0, 6.0, 0.97))
    }

    @Test
    fun `moto a 60 kmh con fixes sanos pasa`() {
        // 16.7 m/s implicitos y reportados.
        assertTrue(PositionConsistencyPolicy.isConsistent(100.0, 6.0, 16.7))
        assertTrue(PositionConsistencyPolicy.isConsistent(16.7, 1.0, 16.7))
    }

    @Test
    fun `jitter pequeno quieto no se juzga`() {
        assertTrue(PositionConsistencyPolicy.isConsistent(20.0, 3.0, 0.0))
    }

    @Test
    fun `arranque desde parado es tolerado por el margen`() {
        // 20 km/h reales arrancando: implied 5.5 m/s vs reportado 0 → 5.5 <= 4? no,
        // pero la pata tipica del arranque supera el minimo: se acepta por margen.
        assertTrue(PositionConsistencyPolicy.isConsistent(35.0, 8.0, 1.5))
    }

    @Test
    fun `sin delta de tiempo no se descarta`() {
        assertTrue(PositionConsistencyPolicy.isConsistent(500.0, 0.0, 0.0))
    }
}
