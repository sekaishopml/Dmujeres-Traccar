package com.dmujeres.traccar.config

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings

/**
 * Configuración persistida del dispositivo. El servidor viene preconfigurado con la IP del
 * entorno; el ID del dispositivo se genera automáticamente la primera vez (para que el
 * administrador lo agregue al panel). Todo sin secretos en el código.
 */
class AppConfig(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("dmj_tracking", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, DEFAULT_SERVER).orEmpty()
            .ifBlank { DEFAULT_SERVER }
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim().ifBlank { DEFAULT_SERVER }).apply()

    var deviceId: String
        get() {
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            return generateDeviceId()
        }
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value.trim()).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var trackingEnabled: Boolean
        get() = prefs.getBoolean(KEY_TRACKING, false)
        set(value) = prefs.edit().putBoolean(KEY_TRACKING, value).apply()

    var sequence: Long
        get() = prefs.getLong(KEY_SEQUENCE, 0L)
        set(value) = prefs.edit().putLong(KEY_SEQUENCE, value).apply()

    /** Intervalo de captura/envío de ubicación en segundos (frecuencia). */
    var intervalSeconds: Long
        get() = prefs.getLong(KEY_INTERVAL, 10L)
        set(value) = prefs.edit().putLong(KEY_INTERVAL, value.coerceIn(3, 300)).apply()

    /** Tamaño máximo de la cola offline (buffer). Si se llena, se descarta lo más antiguo. */
    var bufferMax: Int
        get() = prefs.getInt(KEY_BUFFER, 500)
        set(value) = prefs.edit().putInt(KEY_BUFFER, value.coerceIn(10, 5000)).apply()

    /** Segundos que se espera el ACK del servidor antes de reintentar. */
    var ackTimeoutSeconds: Int
        get() = prefs.getInt(KEY_ACK_TIMEOUT, 15)
        set(value) = prefs.edit().putInt(KEY_ACK_TIMEOUT, value.coerceIn(5, 60)).apply()

    /** Máximo de reintentos de un mensaje antes de descartarlo. */
    var maxRetries: Int
        get() = prefs.getInt(KEY_MAX_RETRIES, 30)
        set(value) = prefs.edit().putInt(KEY_MAX_RETRIES, value.coerceIn(3, 200)).apply()

    /** Topic de subida: dmj/v1/devices/{deviceId}/telemetry */
    fun telemetryTopic(): String = "dmj/v1/devices/$deviceId/telemetry"

    /** Topic de ACK: dmj/v1/devices/{deviceId}/ack */
    fun ackTopic(): String = "dmj/v1/devices/$deviceId/ack"

    /** Incrementa y devuelve el siguiente número de secuencia (monotónico, persistido). */
    @Synchronized
    fun nextSequence(): Long {
        sequence = sequence + 1
        return sequence
    }

    /**
     * Genera un ID de dispositivo estable y legible basado en el identificador del teléfono.
     * Se guarda la primera vez y ya no cambia: el administrador lo agrega al panel.
     */
    private fun generateDeviceId(): String {
        val androidId = runCatching {
            Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val base = androidId.filter { it.isLetterOrDigit() }
        val hash = if (base.length >= 8) base.take(8) else (base + "dmj2026").take(8)
        val id = "dmj-$hash"
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    companion object {
        /** Servidor por defecto: IP pública del entorno + puerto MQTT. */
        const val DEFAULT_SERVER = "mqtt://64.176.219.221:1883"

        private const val KEY_SERVER = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TRACKING = "tracking_enabled"
        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_INTERVAL = "interval_seconds"
        private const val KEY_BUFFER = "buffer_max"
        private const val KEY_ACK_TIMEOUT = "ack_timeout"
        private const val KEY_MAX_RETRIES = "max_retries"
    }
}
