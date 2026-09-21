package com.dmujeres.traccar.sensors

import android.content.Context
import com.dmujeres.traccar.sensors.GyroSensor
import com.dmujeres.traccar.sensors.MotionSensor

/**
 * FASE 3 (§3, §9): coordinador de sensores extraído de `TrackingService`.
 * Los sensores existentes siguen siendo la única implementación (no se
 * duplican): este coordinador solo centraliza su ciclo de vida y sus etiquetas
 * de telemetría.
 *
 * Regla absoluta: los sensores NO generan latitud/longitud. Solo aportan
 * movimiento/rotación como evidencia auxiliar.
 */
class SensorCoordinator(private val context: Context) {

    fun register() {
        MotionSensor.register(context)
        GyroSensor.register(context)
    }

    fun unregister() {
        MotionSensor.unregister()
        GyroSensor.unregister()
    }

    fun maybePauseIfStationary() {
        MotionSensor.maybePauseIfStationary()
    }

    fun reRegisterOnFix() {
        MotionSensor.reRegisterOnFix(context)
    }

    fun motionStateName(): String = MotionSensor.currentState().name

    /** Tag opcional del giroscopio: "rot=STEADY " o vacío si no hay sensor. */
    fun gyroTag(): String =
        formatGyroTag(GyroSensor.available, GyroSensor.currentState().name)

    companion object {
        /** Puro (JVM): mismo formato que usaba el servicio en POSITION_*. */
        fun formatGyroTag(available: Boolean, state: String): String =
            if (available) "rot=$state " else ""
    }
}
