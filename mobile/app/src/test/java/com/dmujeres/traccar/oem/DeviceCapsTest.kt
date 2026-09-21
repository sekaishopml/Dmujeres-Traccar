package com.dmujeres.traccar.oem

import android.hardware.Sensor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests JVM de la parte pura de DeviceCaps (capabilitiesFrom). La parte
 * Android (from(context)) no es JVM-testable (Build/SensorManager estáticos).
 */
class DeviceCapsTest {

    @Test
    fun caps1_soloAcelerometro() {
        // Caso Z2450: acelerómetro presente, sin giroscopio ni rotation vector.
        val caps = DeviceCaps.capabilitiesFrom(
            sensorTypes = setOf(Sensor.TYPE_ACCELEROMETER),
            manufacturer = "zte",
            model = "Z2450",
            sdk = 34,
        )
        assertEquals("AVAILABLE", caps["ACCELEROMETER"])
        assertEquals("NOT_AVAILABLE", caps["GYROSCOPE"])
        assertEquals("NOT_AVAILABLE", caps["ROTATION_VECTOR"])
        assertEquals("zte", caps["MANUFACTURER"])
        assertEquals("Z2450", caps["MODEL"])
        assertEquals("34", caps["SDK_INT"])
    }

    @Test
    fun caps2_todosLosSensoresPresentes() {
        val caps = DeviceCaps.capabilitiesFrom(
            sensorTypes = setOf(
                Sensor.TYPE_ACCELEROMETER,
                Sensor.TYPE_GYROSCOPE,
                Sensor.TYPE_ROTATION_VECTOR,
            ),
            manufacturer = "samsung",
            model = "SM-A155M",
            sdk = 35,
        )
        assertEquals("AVAILABLE", caps["ACCELEROMETER"])
        assertEquals("AVAILABLE", caps["GYROSCOPE"])
        assertEquals("AVAILABLE", caps["ROTATION_VECTOR"])
    }

    @Test
    fun caps3_conjuntoVacioTodoNoDisponible() {
        val caps = DeviceCaps.capabilitiesFrom(
            sensorTypes = emptySet(),
            manufacturer = "",
            model = "",
            sdk = 26,
        )
        assertEquals("NOT_AVAILABLE", caps["ACCELEROMETER"])
        assertEquals("NOT_AVAILABLE", caps["GYROSCOPE"])
        assertEquals("NOT_AVAILABLE", caps["ROTATION_VECTOR"])
    }

    @Test
    fun caps4_etiquetasEstables() {
        // Las etiquetas del contrato (AVAILABLE / NOT_AVAILABLE) son constantes
        // exactas: la fila "Dispositivo" del diagnóstico depende de ellas.
        assertEquals("AVAILABLE", DeviceCaps.CAP_AVAILABLE)
        assertEquals("NOT_AVAILABLE", DeviceCaps.CAP_NOT_AVAILABLE)
        assertEquals("ACCELEROMETER", DeviceCaps.CAP_ACCELEROMETER)
        assertEquals("GYROSCOPE", DeviceCaps.CAP_GYROSCOPE)
        assertEquals("ROTATION_VECTOR", DeviceCaps.CAP_ROTATION_VECTOR)
    }
}
