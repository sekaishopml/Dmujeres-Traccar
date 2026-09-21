package com.dmujeres.traccar.sensors

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FASE 3/9: formato del tag de giro (opcional). Sin sensor no viaja nada en la
 * telemetría: nunca se inventa una lectura.
 */
class SensorCoordinatorTest {

    @Test
    fun `sin sensor no hay tag`() {
        assertEquals("", SensorCoordinator.formatGyroTag(available = false, state = "UNKNOWN"))
    }

    @Test
    fun `con sensor el tag replica el formato del servicio`() {
        assertEquals("rot=STEADY ", SensorCoordinator.formatGyroTag(available = true, state = "STEADY"))
        assertEquals("rot=ROTATING ", SensorCoordinator.formatGyroTag(available = true, state = "ROTATING"))
    }
}
