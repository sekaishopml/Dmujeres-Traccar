package com.dmujeres.traccar.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * R7: sensor de MOVIMIENTO SIGNIFICATIVO (one-shot trigger). Patrón tomado de
 * apps OSS (OwnTracks SignificantMotionSensor): al detectar el arranque de
 * movimiento despierta el pipeline y pide un fix YA, sin mantener GPS siempre
 * encendido. Ataque directo al "salto al reanudar" tras quietud.
 *
 * Es un trigger sensor: dispara UNA vez y debe re-armarse (lo hace solo).
 * Sin sensor en el equipo: available=false, sin errores.
 */
object SignificantMotion {

    private const val TAG = "SignificantMotion"

    @Volatile
    var available: Boolean = false
        private set

    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var onDetected: (() -> Unit)? = null
    private val registered = AtomicBoolean(false)

    @Volatile
    private var lastTriggerAtMs = 0L

    /** Triggers efectivos desde el registro (telemetría barata). */
    @Volatile
    var triggerCount: Int = 0
        private set

    // API 34 cambió la firma del trigger listener (TriggerEvent en vez de
    // SensorEvent): en Android ≤13 el registro falla al resolver la clase y
    // queda available=false (el llamador ya envuelve en try/catch). Todos los
    // equipos de la flota son 14+.
    private val listener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            if (event?.sensor?.type != Sensor.TYPE_SIGNIFICANT_MOTION) return
            val now = System.currentTimeMillis()
            if (SignificantMotionPolicy.shouldFire(lastTriggerAtMs, now)) {
                lastTriggerAtMs = now
                triggerCount++
                Log.i(TAG, "Movimiento significativo -> pedir fix")
                runCatching { onDetected?.invoke() }
            }
            // Trigger sensor: re-armar SIEMPRE (aunque el disparo se descarte).
            rearm()
        }
    }

    fun register(context: Context, onMotionDetected: () -> Unit) {
        if (!registered.compareAndSet(false, true)) return
        try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val smd = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            if (sm == null || smd == null) {
                available = false
                registered.set(false)
                Log.i(TAG, "Significant motion no disponible en este equipo")
                return
            }
            sensorManager = sm
            sensor = smd
            onDetected = onMotionDetected
            available = true
            sm.requestTriggerSensor(listener, smd)
        } catch (e: Exception) {
            available = false
            registered.set(false)
            Log.w(TAG, "No se pudo registrar significant motion", e)
        }
    }

    fun unregister() {
        runCatching {
            val sm = sensorManager
            val s = sensor
            if (sm != null && s != null) sm.cancelTriggerSensor(listener, s)
        }
        sensorManager = null
        sensor = null
        onDetected = null
        available = false
        registered.set(false)
    }

    private fun rearm() {
        runCatching {
            val sm = sensorManager
            val s = sensor
            if (sm != null && s != null) sm.requestTriggerSensor(listener, s)
        }
    }
}
