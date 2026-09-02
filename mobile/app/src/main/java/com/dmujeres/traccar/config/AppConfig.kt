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
     * Usuario del colaborador (lo crea el administrador). Se usa como identificador
     * del dispositivo en el topic y el envelope.
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

    /** Valor heredado usado para inicializar la secuencia durable de Room. */
    var sequence: Long
        get() = prefs.getLong(KEY_SEQUENCE, 0L)
        set(value) = prefs.edit().putLong(KEY_SEQUENCE, value).apply()

    /** Intervalo de captura/envío de ubicación en segundos (frecuencia). */
    var intervalSeconds: Long
        get() = prefs.getLong(KEY_INTERVAL, 10L)
        set(value) = prefs.edit().putLong(KEY_INTERVAL, value.coerceIn(3, 300)).apply()

    /** Tamaño máximo de la cola offline (buffer). Default 5000 ≈ 14 h a 10 s; techo 10000 ≈ 28h @10s. */
    var bufferMax: Int
        get() = prefs.getInt(KEY_BUFFER, 5000)
        set(value) = prefs.edit().putInt(KEY_BUFFER, value.coerceIn(10, 10000)).apply()

    /** Política al llenarse el buffer: descartar lo más antiguo o detener la captura. */
    var bufferPolicy: String
        get() = prefs.getString(KEY_BUFFER_POLICY, POLICY_DROP_OLDEST) ?: POLICY_DROP_OLDEST
        set(value) = prefs.edit().putString(KEY_BUFFER_POLICY, value).apply()

    /** Segundos que se espera el ACK del servidor antes de reintentar. */
    var ackTimeoutSeconds: Int
        get() = prefs.getInt(KEY_ACK_TIMEOUT, 15)
        set(value) = prefs.edit().putInt(KEY_ACK_TIMEOUT, value.coerceIn(5, 60)).apply()

    /** Último fix GPS válido recibido. */
    var lastFixAt: Long
        get() = prefs.getLong(KEY_LAST_FIX, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_FIX, value).apply()

    /** Última posición insertada correctamente en la cola Room. */
    var lastEnqueuedAt: Long
        get() = prefs.getLong(KEY_LAST_ENQUEUED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ENQUEUED, value).apply()

    /** Último publish MQTT aceptado localmente por Paho. */
    var lastPublishedAt: Long
        get() = prefs.getLong(KEY_LAST_PUBLISHED, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_PUBLISHED, value).apply()

    /** Último ACK de aplicación recibido del servidor. */
    var lastAckAt: Long
        get() = prefs.getLong(KEY_LAST_ACK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ACK, value).apply()

    /** Métricas de la jornada actual. */
    var journeyStartAt: Long
        get() = prefs.getLong(KEY_JOURNEY_START, 0L)
        set(value) = prefs.edit().putLong(KEY_JOURNEY_START, value).apply()

    var journeyDistanceM: Double
        get() = prefs.getString(KEY_JOURNEY_DISTANCE, "0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString(KEY_JOURNEY_DISTANCE, value.toString()).apply()

    var journeyPoints: Long
        get() = prefs.getLong(KEY_JOURNEY_POINTS, 0L)
        set(value) = prefs.edit().putLong(KEY_JOURNEY_POINTS, value).apply()

    /** Posiciones de la jornada confirmadas por el servidor. */
    var journeyConfirmedPoints: Long
        get() = prefs.getLong(KEY_JOURNEY_CONFIRMED_POINTS, 0L)
        set(value) = prefs.edit().putLong(KEY_JOURNEY_CONFIRMED_POINTS, value).apply()

    @Synchronized
    fun recordJourneyConfirmed(journeyId: Long) {
        if (journeyId > 0L && journeyId == journeyStartAt) {
            journeyConfirmedPoints = journeyConfirmedPoints + 1
        }
    }

    /** Último punto guardado, para continuar la distancia tras una recuperación del servicio. */
    var journeyLastLat: Double
        get() = prefs.getString(KEY_JOURNEY_LAST_LAT, "0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString(KEY_JOURNEY_LAST_LAT, value.toString()).apply()

    var journeyLastLon: Double
        get() = prefs.getString(KEY_JOURNEY_LAST_LON, "0")?.toDoubleOrNull() ?: 0.0
        set(value) = prefs.edit().putString(KEY_JOURNEY_LAST_LON, value.toString()).apply()

    var journeyHasLastLocation: Boolean
        get() = prefs.getBoolean(KEY_JOURNEY_HAS_LAST_LOCATION, false)
        set(value) = prefs.edit().putBoolean(KEY_JOURNEY_HAS_LAST_LOCATION, value).apply()

    /** Permite completar el cierre aunque la app muera justo después de pulsar finalizar. */
    var journeyStopRequested: Boolean
        get() = prefs.getBoolean(KEY_JOURNEY_STOP_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_JOURNEY_STOP_REQUESTED, value).apply()

    /** Último error al intentar auto-iniciar (para diagnóstico en pantalla). */
    var lastStartError: String
        get() = prefs.getString(KEY_LAST_START_ERROR, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_START_ERROR, value).apply()

    /** Último estado real publicado por el servicio para que la UI no dependa solo del booleano. */
    var trackingState: String
        get() = prefs.getString(KEY_TRACKING_STATE, "TRACKING_DISABLED_BY_USER").orEmpty()
        set(value) = prefs.edit().putString(KEY_TRACKING_STATE, value).apply()

    var lastSummaryNotified: String
        get() = prefs.getString(KEY_SUMMARY_NOTIFIED, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SUMMARY_NOTIFIED, value).apply()

    /** Resumen de la última jornada finalizada (para notificación diaria). */
    var lastJourneySummary: String
        get() = prefs.getString(KEY_LAST_SUMMARY, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_SUMMARY, value).apply()

    /** Máximo de reintentos de un mensaje antes de descartarlo. */
    var maxRetries: Int
        get() = prefs.getInt(KEY_MAX_RETRIES, 30)
        set(value) = prefs.edit().putInt(KEY_MAX_RETRIES, value.coerceIn(3, 200)).apply()

    /** Velocidad implicita maxima aceptable entre fixes consecutivos (m/s; 45 ≈ 162 km/h). */
    var maxImpliedSpeedMps: Float
        get() = prefs.getFloat(KEY_MAX_IMPLIED_SPEED, DEFAULT_MAX_IMPLIED_SPEED_MPS)
        set(value) = prefs.edit().putFloat(KEY_MAX_IMPLIED_SPEED, value).apply()

    /** Accuracy (m) sobre la que un fix se considera degradado si hay un fix bueno reciente. */
    var accuracyBadM: Float
        get() = prefs.getFloat(KEY_ACCURACY_BAD, DEFAULT_ACCURACY_BAD_M)
        set(value) = prefs.edit().putFloat(KEY_ACCURACY_BAD, value).apply()

    /** Accuracy (m) bajo la que un fix reciente se considera fiable. */
    var accuracyGoodM: Float
        get() = prefs.getFloat(KEY_ACCURACY_GOOD, DEFAULT_ACCURACY_GOOD_M)
        set(value) = prefs.edit().putFloat(KEY_ACCURACY_GOOD, value).apply()

    /** Velocidad sostenida (m/s) a partir de la cual se toleran picos rapidos legitimos. */
    var consistentSpeedMps: Float
        get() = prefs.getFloat(KEY_CONSISTENT_SPEED, DEFAULT_CONSISTENT_SPEED_MPS)
        set(value) = prefs.edit().putFloat(KEY_CONSISTENT_SPEED, value).apply()

    /** Onboarding completado: permisos de ubicación, notificaciones, batería y GPS aceptados. */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_DONE, value).apply()

    /** Indica si ya se pidió el permiso de ubicación en segundo plano (para no re-pedir
     *  un diálogo que el usuario ya rechazó y llevarlo a los ajustes directamente). */
    var backgroundLocationAsked: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_LOCATION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_LOCATION_ASKED, value).apply()

    /**
     * Aplica la configuración remota que el administrador definió en el panel
     * (/settings/device). Solo se aplican los valores presentes y válidos.
     * Incluye thresholds del filtro GPS (Fase A: menos agresivo y tunable).
     */
    fun applyRemote(
        interval: Long?,
        bufferMax: Int?,
        bufferPolicy: String?,
        ackTimeout: Int?,
        maxRetries: Int?,
        maxImpliedSpeedMps: Float? = null,
        consistentSpeedMps: Float? = null,
        accuracyBadM: Float? = null,
        accuracyGoodM: Float? = null,
    ) {
        if (interval != null) this.intervalSeconds = interval
        if (bufferMax != null) this.bufferMax = bufferMax
        if (bufferPolicy != null &&
            (bufferPolicy == POLICY_DROP_OLDEST || bufferPolicy == POLICY_STOP_CAPTURE)
        ) {
            this.bufferPolicy = bufferPolicy
        }
        if (ackTimeout != null) this.ackTimeoutSeconds = ackTimeout
        if (maxRetries != null) this.maxRetries = maxRetries
        if (maxImpliedSpeedMps != null && maxImpliedSpeedMps > 0f) this.maxImpliedSpeedMps = maxImpliedSpeedMps
        if (consistentSpeedMps != null && consistentSpeedMps > 0f) this.consistentSpeedMps = consistentSpeedMps
        if (accuracyBadM != null && accuracyBadM > 0f) this.accuracyBadM = accuracyBadM
        if (accuracyGoodM != null && accuracyGoodM > 0f) this.accuracyGoodM = accuracyGoodM
    }

    /** Topic de subida: dmj/v1/devices/{deviceId}/telemetry */
    fun telemetryTopic(): String = "dmj/v1/devices/$deviceId/telemetry"

    /** Topic de ACK: dmj/v1/devices/{deviceId}/ack */
    fun ackTopic(): String = "dmj/v1/devices/$deviceId/ack"

    /**
     * Genera un ID estable para el messageId basado en el usuario (no expone el ID completo).
     * El messageId real se compone en Envelope con este valor.
     */
    fun deviceHash(): String {
        val base = username.hashCode()
        return Integer.toUnsignedString(base, 16).padStart(8, '0')
    }

    companion object {
        const val POLICY_DROP_OLDEST = "drop_oldest"
        const val POLICY_STOP_CAPTURE = "stop_capture"

        /** Anti "GPS loco": se descartan saltos con velocidad implicita mayor (≈162 km/h). Fase A: 45 m/s. */
        const val DEFAULT_MAX_IMPLIED_SPEED_MPS = 45f
        /** Fix peor que esto y con un fix bueno reciente se descarta por degradado. */
        const val DEFAULT_ACCURACY_BAD_M = 80f
        /** Accuracy que hace a un fix reciente "bueno". */
        const val DEFAULT_ACCURACY_GOOD_M = 20f
        /** Velocidad sostenida para tolerar picos rapidos legitimos (≈108 km/h). Fase A: 30 m/s. */
        const val DEFAULT_CONSISTENT_SPEED_MPS = 30f

        /** Clave compartida del fallback HTTP (misma que el server; se reforzará en Fase 5). */
        const val HTTP_API_KEY = "dmj-dev-fallback-key"

        /** Puerto web del servidor para el fallback HTTP. */
        const val WEB_PORT = 8082

        /** Servidor por defecto: IP pública del entorno + puerto MQTT. */
        const val DEFAULT_SERVER = "tcp://68.168.20.219:1883"

        private const val KEY_SERVER = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TRACKING = "tracking_enabled"
        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_INTERVAL = "interval_seconds"
        private const val KEY_BUFFER = "buffer_max"
        private const val KEY_ACK_TIMEOUT = "ack_timeout"
        private const val KEY_MAX_RETRIES = "max_retries"
        private const val KEY_BUFFER_POLICY = "buffer_policy"
        private const val KEY_LAST_FIX = "last_fix_at"
        private const val KEY_LAST_ENQUEUED = "last_enqueued_at"
        private const val KEY_LAST_PUBLISHED = "last_published_at"
        private const val KEY_LAST_ACK = "last_ack_at"
        private const val KEY_JOURNEY_START = "journey_start_at"
        private const val KEY_JOURNEY_DISTANCE = "journey_distance_m"
        private const val KEY_JOURNEY_POINTS = "journey_points"
        private const val KEY_JOURNEY_CONFIRMED_POINTS = "journey_confirmed_points"
        private const val KEY_JOURNEY_LAST_LAT = "journey_last_lat"
        private const val KEY_JOURNEY_LAST_LON = "journey_last_lon"
        private const val KEY_JOURNEY_HAS_LAST_LOCATION = "journey_has_last_location"
        private const val KEY_JOURNEY_STOP_REQUESTED = "journey_stop_requested"
        private const val KEY_SUMMARY_NOTIFIED = "summary_notified"
        private const val KEY_LAST_SUMMARY = "last_journey_summary"
        private const val KEY_LAST_START_ERROR = "last_start_error"
        private const val KEY_TRACKING_STATE = "tracking_state"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_BACKGROUND_LOCATION_ASKED = "background_location_asked"
        private const val KEY_MAX_IMPLIED_SPEED = "filter_max_speed_mps"
        private const val KEY_ACCURACY_BAD = "filter_accuracy_bad_m"
        private const val KEY_ACCURACY_GOOD = "filter_accuracy_good_m"
        private const val KEY_CONSISTENT_SPEED = "filter_consistent_speed_mps"
    }
}
