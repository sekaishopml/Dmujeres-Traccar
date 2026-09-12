package com.dmujeres.traccar.config

import android.content.Context
import android.content.SharedPreferences
import com.dmujeres.traccar.mqtt.MqttServerNormalizer

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
        set(value) = prefs.edit().putString(KEY_SERVER, MqttServerNormalizer.normalizeServer(value)).apply()

    /**
     * Usuario del colaborador (lo crea el administrador). Se usa como identificador
     * del dispositivo en el topic y el envelope.
     */
    var username: String
        get() = prefs.getString(KEY_USERNAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USERNAME, value.trim()).apply()

    // TODO(security): migrar password (y username) a EncryptedSharedPreferences
    // (androidx.security:security-crypto) para cifrar credenciales en reposo.
    // Requiere añadir la dependencia "androidx.security:security-crypto" y una
    // migración que copie los valores existentes y borre los de prefs en claro.
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

    /**
     * Sufijo estable del clientId MQTT (se genera una vez y se persiste). Con
     * sufijos aleatorios cada reconexión creaba una sesión nueva en EMQX y los
     * zombies acumulados consumían memoria y colas QoS1 de acks.
     */
    var mqttClientSuffix: String
        get() = prefs.getString(KEY_MQTT_SUFFIX, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_MQTT_SUFFIX, value).apply()

    /** Intervalo de captura/envío de ubicación en segundos (frecuencia). */
    var intervalSeconds: Long
        get() = prefs.getLong(KEY_INTERVAL, 10L)
        set(value) = prefs.edit().putLong(KEY_INTERVAL, value.coerceIn(3, 300)).apply()

    /**
     * Tamaño configurado de la cola offline. La EVICCIÓN real la gobierna la
     * retención dura ([com.dmujeres.traccar.db.OutboxRetentionPolicy], 100 000
     * posiciones o 7 días): este valor es el umbral configurado por el admin y
     * el tope efectivo nunca baja de la retención
     * (`effectiveMax = max(configurado, 100 000)`), de modo que instalaciones
     * con el default antiguo (5 000 ≈ 14 h) también sobreviven 24-72 h offline
     * sin migración de prefs. Default 100 000 para instalaciones nuevas.
     */
    var bufferMax: Int
        get() = prefs.getInt(KEY_BUFFER, DEFAULT_BUFFER_MAX)
        set(value) = prefs.edit().putInt(KEY_BUFFER, value.coerceIn(BUFFER_MIN, BUFFER_MAX)).apply()

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

    /**
     * Observabilidad anti "cero capturas en silencio": contadores de la jornada
     * para que el servidor distinga "GPS apagado" de "filtro mata todo".
     * - fixReceived: fixes crudos recibidos en onLocationResult.
     * - fixRejected: descartados por isValidLocation o por FixFilter.
     * - fixEnqueued: insertados OK en Room (insertWithinLimit >= 0).
     */
    var fixReceived: Long
        get() = prefs.getLong(KEY_FIX_RECEIVED, 0L)
        set(value) = prefs.edit().putLong(KEY_FIX_RECEIVED, value).apply()

    var fixRejected: Long
        get() = prefs.getLong(KEY_FIX_REJECTED, 0L)
        set(value) = prefs.edit().putLong(KEY_FIX_REJECTED, value).apply()

    var fixEnqueued: Long
        get() = prefs.getLong(KEY_FIX_ENQUEUED, 0L)
        set(value) = prefs.edit().putLong(KEY_FIX_ENQUEUED, value).apply()

    @Synchronized
    fun incFixReceived(): Long {
        val v = fixReceived + 1
        fixReceived = v
        return v
    }

    @Synchronized
    fun incFixRejected(): Long {
        val v = fixRejected + 1
        fixRejected = v
        return v
    }

    @Synchronized
    fun incFixEnqueued(): Long {
        val v = fixEnqueued + 1
        fixEnqueued = v
        return v
    }

    /** RTT medido hacia el servidor (EWMA publish→ACK en ms; -1 si aún no hay medición). */
    var mobileRttMs: Int
        get() = prefs.getInt(KEY_MOBILE_RTT_MS, -1)
        set(value) = prefs.edit().putInt(KEY_MOBILE_RTT_MS, value).apply()

    /** Métricas de la jornada actual. */
    var journeyStartAt: Long
        get() = prefs.getLong(KEY_JOURNEY_START, 0L)
        set(value) = prefs.edit().putLong(KEY_JOURNEY_START, value).apply()

    /**
     * Elapsed de jornada acumulado por TrackingService con reloj monotónico
     * (elapsedRealtime) e inmune a correcciones NTP; persistido en cada fix.
     * 0 = servicio aún no acumula (jornada nueva o legado).
     */
    var journeyElapsedMs: Long
        get() = prefs.getLong(KEY_JOURNEY_ELAPSED, 0L)
        set(value) = prefs.edit().putLong(KEY_JOURNEY_ELAPSED, value).apply()

    /**
     * Wall clock (System.currentTimeMillis) del último anclaje de
     * [journeyElapsedMs]: la UI suma ahora - este ancla, nunca ahora - inicio,
     * para que un salto de reloj no deforma la duración mostrada.
     */
    var journeyElapsedWallMs: Long
        get() = prefs.getLong(KEY_JOURNEY_ELAPSED_WALL, 0L)
        set(value) = prefs.edit().putLong(KEY_JOURNEY_ELAPSED_WALL, value).apply()

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

    /** Última comprobación de actualización (para Diagnóstico). */
    var lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** Motivo del último fallo al buscar actualización (vacío si funcionó). */
    var lastUpdateError: String
        get() = prefs.getString(KEY_LAST_UPDATE_ERROR, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_UPDATE_ERROR, value).apply()

    /** Última versión vista en el servidor (para Diagnóstico). */
    var lastUpdateLatest: String
        get() = prefs.getString(KEY_LAST_UPDATE_LATEST, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_UPDATE_LATEST, value).apply()

    /** Última causa de red detectada (NetCause.value; "ok" por defecto) para la UI. */
    var netCause: String
        get() = prefs.getString(KEY_NET_CAUSE, "ok").orEmpty().ifBlank { "ok" }
        set(value) = prefs.edit().putString(KEY_NET_CAUSE, value).apply()

    /** Última etiqueta network ("wifi"|"mobile"|"none") para distinguir wifi_lost vs sin cobertura. */
    var netLabel: String
        get() = prefs.getString(KEY_NET_LABEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_NET_LABEL, value).apply()

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

    // ---- Salud (health) para el reporte de diagnóstico -----------------------
    //
    // Contadores en BUCKET DIARIO: se guardan como "epochDay:valor" en una sola
    // clave; al leer, si el día guardado != hoy (UTC) el valor es 0 → auto-reset
    // al rodar el día, sin jobs de limpieza. Pragmático, con límites conocidos:
    // - "24h" es el día calendario UTC, no una ventana móvil real.
    // - Escritos con apply(): un kill a mitad pierde el último incremento.
    // - Un salto grande de reloj puede "saltarse" días (igual devuelve 0: fail
    //   silencioso, nunca valores inflados).
    // - anrs24h tiene helper pero NINGÚN hook todavía: detectar ANRs sin el SDK
    //   de Sentry (watchdog de hilo principal) no es fiable desde la app; se
    //   mantiene en 0 hasta que el wrapper de Sentry lo alimente.

    /** Reinicios bruscos del proceso con jornada abierta (heurística cleanShutdown). */
    var crashes24h: Int
        get() = readDailyBucket(KEY_CRASHES_24H)
        set(value) = writeDailyBucket(KEY_CRASHES_24H, value)

    /** Paso de reloj (wall vs monotónico) detectados por el watchdog. */
    var clockSteps24h: Int
        get() = readDailyBucket(KEY_CLOCK_STEPS_24H)
        set(value) = writeDailyBucket(KEY_CLOCK_STEPS_24H, value)

    /** Reconexiones MQTT completadas (connectComplete con reconnect=true). */
    var reconnects24h: Int
        get() = readDailyBucket(KEY_RECONNECTS_24H)
        set(value) = writeDailyBucket(KEY_RECONNECTS_24H, value)

    /** Recuperaciones de "crash a mitad de stop" (StuckStopPolicy START_PREFER_RECOVERY). */
    var stuckStops24h: Int
        get() = readDailyBucket(KEY_STUCK_STOPS_24H)
        set(value) = writeDailyBucket(KEY_STUCK_STOPS_24H, value)

    /** Veces que el Doppler se atascó en 0 en marcha (implícita >= 5 m/s). */
    var speedStuck24h: Int
        get() = readDailyBucket(KEY_SPEED_STUCK_24H)
        set(value) = writeDailyBucket(KEY_SPEED_STUCK_24H, value)

    /** ANRs del día (helper reservado; ver KDoc del bloque: aún sin emisor). */
    var anrs24h: Int
        get() = readDailyBucket(KEY_ANRS_24H)
        set(value) = writeDailyBucket(KEY_ANRS_24H, value)

    @Synchronized
    fun incCrash24h(): Int = incDailyBucket(KEY_CRASHES_24H)

    @Synchronized
    fun incClockStep24h(): Int = incDailyBucket(KEY_CLOCK_STEPS_24H)

    @Synchronized
    fun incReconnect24h(): Int = incDailyBucket(KEY_RECONNECTS_24H)

    @Synchronized
    fun incStuckStop24h(): Int = incDailyBucket(KEY_STUCK_STOPS_24H)

    @Synchronized
    fun incSpeedStuck24h(): Int = incDailyBucket(KEY_SPEED_STUCK_24H)

    @Synchronized
    fun incAnr24h(): Int = incDailyBucket(KEY_ANRS_24H)

    /**
     * Bandera de apagado limpio: TrackingService la pone en false al nacer
     * (servicio vivo = sin cerrar aún) y en true en ACTION_STOP. Si en el
     * próximo arranque del servicio sigue en false con jornada abierta, la
     * corrida anterior NO terminó bien → crashes24h++ (kill por OEM y reboot
     * también cuentan: es "muerte inesperada", no solo exception).
     */
    var cleanShutdown: Boolean
        get() = prefs.getBoolean(KEY_CLEAN_SHUTDOWN, true)
        set(value) = prefs.edit().putBoolean(KEY_CLEAN_SHUTDOWN, value).apply()

    /** Epoch ms del último reporte de diagnóstico ACEPTADO por el servidor. */
    var diagnosticsLastReportAt: Long
        get() = prefs.getLong(KEY_DIAG_LAST_REPORT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_DIAG_LAST_REPORT_AT, value).apply()

    /** Resultado legible del último intento de reporte (para la tarjeta Monitor). */
    var diagnosticsLastResult: String
        get() = prefs.getString(KEY_DIAG_LAST_RESULT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DIAG_LAST_RESULT, value.take(120)).apply()

    private fun readDailyBucket(key: String): Int =
        decodeDailyBucket(prefs.getString(key, null), epochDayOf(System.currentTimeMillis()))

    private fun writeDailyBucket(key: String, value: Int) {
        prefs.edit()
            .putString(key, encodeDailyBucket(epochDayOf(System.currentTimeMillis()), value.coerceAtLeast(0)))
            .apply()
    }

    private fun incDailyBucket(key: String): Int {
        val v = readDailyBucket(key) + 1
        writeDailyBucket(key, v)
        return v
    }

    /** Indica si ya se pidió el permiso de ubicación en segundo plano (para no re-pedir
     *  un diálogo que el usuario ya rechazó y llevarlo a los ajustes directamente). */
    var backgroundLocationAsked: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_LOCATION_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_LOCATION_ASKED, value).apply()

    /** versionCode con el que se completó/revalidó el onboarding por última vez. */
    var appVersionCode: Int
        get() = prefs.getInt(KEY_APP_VERSION_CODE, 0)
        set(value) = prefs.edit().putInt(KEY_APP_VERSION_CODE, value).apply()

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
        // Mínimo operativo = default local (5000 ≈ 13.8 h a 10 s): con menos, una
        // jornada larga sin señal perdía trozos de ruta por drop_oldest.
        if (bufferMax != null) this.bufferMax = clampRemoteBufferMax(bufferMax)
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

        /**
         * Default del buffer para instalaciones nuevas: cubre 72 h offline a
         * 10 s (25 920) y 7 días a 10 s (60 480) dentro de la retención dura.
         */
        const val DEFAULT_BUFFER_MAX = 100_000

        /** Piso/aceptación del ajuste local (la retención dura pone el piso real). */
        const val BUFFER_MIN = 5_000
        const val BUFFER_MAX = 100_000

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
        const val WEB_PORT = 999

        /** Ms de un día civil (UTC); base del bucket diario de los contadores de salud. */
        const val MILLIS_PER_DAY = 86_400_000L

        /** Día (UTC) de un epoch ms; floorDiv para que funcione bajo 1970. Pura. */
        fun epochDayOf(epochMs: Long): Long = Math.floorDiv(epochMs, MILLIS_PER_DAY)

        /** Formato del bucket diario: "epochDay:valor". Pura. */
        fun encodeDailyBucket(epochDay: Long, value: Int): String = "$epochDay:${value.coerceAtLeast(0)}"

        /**
         * Valor del bucket: 0 si está ausente, corrupto o es de otro día
         * (auto-reset por rollover de epochDay). Pura y testeable en JVM.
         */
        fun decodeDailyBucket(stored: String?, todayEpochDay: Long): Int {
            val parts = stored?.split(':') ?: return 0
            if (parts.size != 2) return 0
            val day = parts[0].toLongOrNull() ?: return 0
            if (day != todayEpochDay) return 0
            return parts[1].toIntOrNull()?.takeIf { it >= 0 } ?: 0
        }

        /** Servidor por defecto: IP pública del entorno + puerto MQTT. */
        const val DEFAULT_SERVER = "tcp://68.168.20.219:1883"

        private const val KEY_SERVER = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TRACKING = "tracking_enabled"
        private const val KEY_SEQUENCE = "sequence"
        private const val KEY_MQTT_SUFFIX = "mqtt_client_suffix"
        /**
         * Mínimo operativo del buffer ante config remota (≈13.8 h a 10 s): cubre
         * jornadas de 8 h sin que drop_oldest tire posiciones viejas en rutas
         * largas sin señal. Puro (testeable en JVM vía [clampRemoteBufferMax]).
         */
        const val REMOTE_BUFFER_MIN = 5000

        /**
         * Sanea el bufferMax remoto: nunca por debajo del mínimo operativo.
         * Pura, sin Android: unit-testeable en JVM.
         */
        fun clampRemoteBufferMax(requested: Int): Int = maxOf(requested, REMOTE_BUFFER_MIN)
        private const val KEY_INTERVAL = "interval_seconds"
        private const val KEY_BUFFER = "buffer_max"
        private const val KEY_ACK_TIMEOUT = "ack_timeout"
        private const val KEY_MAX_RETRIES = "max_retries"
        private const val KEY_BUFFER_POLICY = "buffer_policy"
        private const val KEY_LAST_FIX = "last_fix_at"
        private const val KEY_LAST_ENQUEUED = "last_enqueued_at"
        private const val KEY_LAST_PUBLISHED = "last_published_at"
        private const val KEY_LAST_ACK = "last_ack_at"
        private const val KEY_FIX_RECEIVED = "fix_received"
        private const val KEY_FIX_REJECTED = "fix_rejected"
        private const val KEY_FIX_ENQUEUED = "fix_enqueued"
        private const val KEY_MOBILE_RTT_MS = "mobile_rtt_ms"
        private const val KEY_JOURNEY_START = "journey_start_at"
        private const val KEY_JOURNEY_ELAPSED = "journey_elapsed_ms"
        private const val KEY_JOURNEY_ELAPSED_WALL = "journey_elapsed_wall_ms"
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
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"
        private const val KEY_LAST_UPDATE_ERROR = "last_update_error"
        private const val KEY_LAST_UPDATE_LATEST = "last_update_latest"
        private const val KEY_TRACKING_STATE = "tracking_state"
        private const val KEY_NET_CAUSE = "net_cause"
        private const val KEY_NET_LABEL = "net_label"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_CRASHES_24H = "health_crashes_24h"
        private const val KEY_CLOCK_STEPS_24H = "health_clock_steps_24h"
        private const val KEY_RECONNECTS_24H = "health_reconnects_24h"
        private const val KEY_STUCK_STOPS_24H = "health_stuck_stops_24h"
        private const val KEY_SPEED_STUCK_24H = "health_speed_stuck_24h"
        private const val KEY_ANRS_24H = "health_anrs_24h"
        private const val KEY_CLEAN_SHUTDOWN = "health_clean_shutdown"
        private const val KEY_DIAG_LAST_REPORT_AT = "diagnostics_last_report_at"
        private const val KEY_DIAG_LAST_RESULT = "diagnostics_last_result"
        private const val KEY_BACKGROUND_LOCATION_ASKED = "background_location_asked"
        private const val KEY_APP_VERSION_CODE = "app_version_code"
        private const val KEY_MAX_IMPLIED_SPEED = "filter_max_speed_mps"
        private const val KEY_ACCURACY_BAD = "filter_accuracy_bad_m"
        private const val KEY_ACCURACY_GOOD = "filter_accuracy_good_m"
        private const val KEY_CONSISTENT_SPEED = "filter_consistent_speed_mps"
    }
}
