package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R8: disparo por ángulo (regla OR del muestreo) con guardas anti-ruido. */
class RouteSamplePolicyTest {

    @Test
    fun giroRealConVelocidadDispara() {
        assertTrue(RouteSamplePolicy.shouldForceSample(20.0, 30.0, 8f))
    }

    @Test
    fun giroMenorAlUmbralNoDispara() {
        assertFalse(RouteSamplePolicy.shouldForceSample(10.0, 30.0, 8f))
    }

    @Test
    fun pataCortaNoDispara() {
        assertFalse(RouteSamplePolicy.shouldForceSample(45.0, 3.0, 8f))
    }

    @Test
    fun sinVelocidadOSinSensorNoDispara() {
        assertFalse(RouteSamplePolicy.shouldForceSample(45.0, 30.0, null))
        assertFalse(RouteSamplePolicy.shouldForceSample(45.0, 30.0, 0.5f))
        assertFalse(RouteSamplePolicy.shouldForceSample(45.0, 30.0, Float.NaN))
    }

    @Test
    fun bearingInvalidoNoDispara() {
        assertFalse(RouteSamplePolicy.shouldForceSample(Double.NaN, 30.0, 8f))
    }

    @Test
    fun deltaSeNormalizaAlrededorDe360() {
        // 350° → 10° (delta corto) no debe disparar; 10° y 350° son el mismo giro.
        assertEquals(10.0, RouteSamplePolicy.normalizeDelta(350.0), 0.001)
        assertEquals(10.0, RouteSamplePolicy.normalizeDelta(-10.0), 0.001)
        assertFalse(RouteSamplePolicy.shouldForceSample(350.0, 30.0, 8f))
        assertTrue(RouteSamplePolicy.shouldForceSample(-20.0, 30.0, 8f))
    }
}
