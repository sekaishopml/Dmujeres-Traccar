package com.dmujeres.traccar.config

import android.content.Context
import android.content.SharedPreferences

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
        set(value) = prefs.edit().putString(KEY_SERVER, normalizeServer(value)).apply()

    private fun normalizeServer(value: String): String {
        var server = value.trim().trimEnd('/')
        var secure = false
        if (server.startsWith("mqtts://")) { server = server.removePrefix("mqtts://"); secure = true }
        if (server.startsWith("ssl://")) { server = server.removePrefix("ssl://"); secure = true }
        if (server.startsWith("mqtt://")) server = server.removePrefix("mqtt://")
        if (server.startsWith("http://")) server = server.removePrefix("http://")
        if (server.startsWith("https://")) { server = server.removePrefix("https://"); secure = true }
        if (server.contains("://")) return server
        val hostPort = server.substringBefore('/')
        val hasPort = hostPort.contains(':')
        val defaultPort = if (secure) ":8883" else ":1883"
        return (if (secure) "ssl://" else "tcp://") + hostPort + if (hasPort) "" else defaultPort
    }

    /**
     * Usuario del colaborador (lo crea el administrador). El usuario ES el identificador
     * del dispositivo: el topic y el envelope usan este nombre.
     */
    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    /** Identificador del dispositivo para topics/envelope: derivado del usuario. */
    val deviceId: String
        get() = username.filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

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
     * Genera un ID estable para el messageId basado en el usuario (no expone el ID completo).
     * El messageId real se compone en Envelope con este valor.
     */
    fun deviceHash(): String {
        val base = username.hashCode()
        return Integer.toUnsignedString(base, 16).padStart(8, '0')
    }

    companion object {
        /** Servidor por defecto: IP pública del entorno + puerto MQTT. */
        const val DEFAULT_SERVER = "tcp://64.176.219.221:1883"

        private const val KEY_SERVER = "server_url"
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
