package com.dmujeres.traccar.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Build

/**
 * Capacidades de hardware del dispositivo (Fase 2, SOLO informativo: no
 * cambia captura ni decisiones). Datos de Build + SensorManager +
 * LocationManager, todos legibles sin permisos.
 *
 * dualSimProbable queda en false SIEMPRE: detectar dual-SIM real requiere
 * READ_PHONE_STATE (SubscriptionManager / SmsManager); sin ese permiso no hay
 * lectura honesta, así que no se infiere (mejor "no se sabe" que inventar un
 * probable).
 */
data class DeviceCaps(
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val androidRelease: String,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasRotationVector: Boolean,
    val hasGnss: Boolean,
    val dualSimProbable: Boolean = false,
) {

    /** Tipos de sensor presentes (entrada de [capabilitiesFrom]). */
    fun sensorTypes(): Set<Int> = buildSet {
        if (hasAccelerometer) add(Sensor.TYPE_ACCELEROMETER)
        if (hasGyroscope) add(Sensor.TYPE_GYROSCOPE)
        if (hasRotationVector) add(Sensor.TYPE_ROTATION_VECTOR)
    }

    /** Mapa compacto "capacidad=AVAILABLE|NOT_AVAILABLE" para diagnóstico. */
    fun capabilities(): Map<String, String> =
        DeviceCaps.capabilitiesFrom(sensorTypes(), manufacturer, model, sdkInt)

    companion object {

        const val CAP_AVAILABLE = "AVAILABLE"
        const val CAP_NOT_AVAILABLE = "NOT_AVAILABLE"
        const val CAP_ACCELEROMETER = "ACCELEROMETER"
        const val CAP_GYROSCOPE = "GYROSCOPE"
        const val CAP_ROTATION_VECTOR = "ROTATION_VECTOR"

        /**
         * Puro (JVM): traduce el conjunto de tipos de sensor presentes a
         * etiquetas AVAILABLE / NOT_AVAILABLE por capacidad, más la identidad
         * del equipo. Los valores de Sensor.TYPE_* van inline (constantes de
         * compilación), por lo que es testeable sin android.jar real.
         */
        fun capabilitiesFrom(
            sensorTypes: Set<Int>,
            manufacturer: String,
            model: String,
            sdk: Int,
        ): Map<String, String> = mapOf(
            CAP_ACCELEROMETER to
                (if (Sensor.TYPE_ACCELEROMETER in sensorTypes) CAP_AVAILABLE else CAP_NOT_AVAILABLE),
            CAP_GYROSCOPE to
                (if (Sensor.TYPE_GYROSCOPE in sensorTypes) CAP_AVAILABLE else CAP_NOT_AVAILABLE),
            CAP_ROTATION_VECTOR to
                (if (Sensor.TYPE_ROTATION_VECTOR in sensorTypes) CAP_AVAILABLE else CAP_NOT_AVAILABLE),
            "MANUFACTURER" to manufacturer,
            "MODEL" to model,
            "SDK_INT" to sdk.toString(),
        )

        /**
         * Lectura Android segura: nunca lanza. SensorManager ausente → los
         * sensores en false; LocationManager fallando → hasGnss=false
         * (runCatching). getDefaultSensor devuelve null sin lanzar cuando el
         * hardware no existe (caso Z2450: sin giroscopio ni rotation vector).
         */
        fun from(context: Context): DeviceCaps {
            val sensorManager = runCatching {
                context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            }.getOrNull()
            val hasAccelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
            val hasGyroscope = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
            val hasRotationVector = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null
            val hasGnss = runCatching {
                val locationManager =
                    context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                locationManager?.getProvider(LocationManager.GPS_PROVIDER) != null
            }.getOrDefault(false)
            return DeviceCaps(
                manufacturer = Build.MANUFACTURER.orEmpty(),
                model = Build.MODEL.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                androidRelease = Build.VERSION.RELEASE.orEmpty(),
                hasAccelerometer = hasAccelerometer,
                hasGyroscope = hasGyroscope,
                hasRotationVector = hasRotationVector,
                hasGnss = hasGnss,
            )
        }
    }
}
