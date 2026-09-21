package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5 §27: INVARIANTE — acelerómetro + giroscopio + GPS perdido NO producen
 * coordenadas. El resultado es únicamente MOVEMENT_LIKELY + necesidad de
 * re-adquisición. (La fusión nunca tiene vía para crear lat/lon.)
 */
class NoSyntheticLocationTest {

    private val fusionInput = LocationSensorFusion.Input(
        fixAgeMs = null, // GPS perdido
        accuracyM = null,
        motionState = "MOVING", // acelerómetro indica movimiento
        gyroState = "ROTATING", // giroscopio rota
        speedMps = null,
    )

    @Test
    fun `fusión produce solo estado y banderas, nunca lat o lon`() {
        val output = LocationSensorFusion.fuse(fusionInput)
        assertEquals(LocationSensorFusion.GPS_LOST, output.gpsHealth)
        assertEquals(LocationSensorFusion.MOVEMENT_LIKELY, output.movement)
        assertTrue(output.reacquisitionRequired)
        // Sin campos de coordenadas en la salida (data class explícita):
        val fields = LocationSensorFusion.Output::class.java.declaredFields.map { it.name }
        assertFalse("Output no debe tener latitude", fields.any { it.lowercase().contains("latit") })
        assertFalse("Output no debe tener longitude", fields.any { it.lowercase().contains("longit") })
    }

    @Test
    fun `la política de rescate tampoco produce coordenadas`() {
        val input = MovementRescuePolicy.Input(
            journeyActive = true,
            fixAgeMs = null, // GPS perdido
            sensorMoving = true, // acelerómetro MOVING
            displacementM = null,
            rescueActive = false,
            movementStreak = 0,
        )
        val decision = MovementRescuePolicy.decide(MovementRescuePolicy.RescueState.NORMAL, input)
        assertEquals(MovementRescuePolicy.RescueState.RESCUE, decision.state)
        assertTrue(decision.rescueRequested)
        // Decision/Input no declaran campos de coordenadas:
        val decisionFields = MovementRescuePolicy.Decision::class.java.declaredFields.map { it.name }
        val inputFields = MovementRescuePolicy.Input::class.java.declaredFields.map { it.name }
        assertTrue(decisionFields.none { it.lowercase().contains("lat") || it.lowercase().contains("lon") })
        assertTrue(
            inputFields.none { it.lowercase().contains("latitude") || it.lowercase().contains("longitude") },
        )
    }
}
