package com.dmujeres.traccar.config

import android.content.Context
import android.content.SharedPreferences

/**
 * Configuración persistida del dispositivo. Sin secretos en el código: servidor y
 * credenciales opcionales se guardan en SharedPreferences (privadas de la app).
 */
class AppConfig(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dmj_tracking", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SERVER, value.trim()).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
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

    var intervalSeconds: Long
        get() = prefs.getLong(KEY_INTERVAL, 10L)
        set(value) = prefs.edit().putLong(KEY_INTERVAL, value).apply()

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

    companion object {
        private const val KEY_SERVER = "server_url"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TRACKING = "tracking_enabled"
        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_INTERVAL = "interval_seconds"
    }
}
