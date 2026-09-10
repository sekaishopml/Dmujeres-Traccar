package com.dmujeres.traccar.util

import android.content.Context
import android.util.Log
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.mqtt.MqttServerNormalizer
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Envía diagnósticos a `POST {base}/api/mobile/v1/diagnostics` (204 = ok,
 * rate-limit del servidor 20 s/dispositivo). Mismo canal de autenticación que
 * [com.dmujeres.traccar.mqtt.HttpFallbackDispatcher] / RemoteConfig: base web
 * derivada del servidor MQTT ([MqttServerNormalizer.webBase]), la api key
 * compartida [AppConfig.HTTP_API_KEY] en X-Api-Key y el uniqueId del
 * dispositivo ([AppConfig.username]) en X-Device-Id — sin duplicar manejo de
 * secretos ni añadir dependencias (HttpURLConnection, como el resto del plan B).
 *
 * - El snapshot (prefs + BatteryManager/PowerManager/red) se construye en el
 *   hilo llamador (barato, sin DB: pendingCount entra por parámetro); SOLO el
 *   POST viaja en un executor mono-hilo: nunca bloquea hilos del servicio.
 * - Throttle cliente [THROTTLE_MS] para toda reason, salvo las de [FORCE_REASONS]
 *   (fronteras de jornada, crash-boot y manual).
 * - Fallos: silencio total para el usuario + breadcrumb de Sentry; persiste
 *   [AppConfig.diagnosticsLastReportAt]/[AppConfig.diagnosticsLastResult] solo
 *   cuando el servidor aceptó (los reintentos los gobierna el throttle).
 */
object DiagnosticsReporter {

    private const val TAG = "DiagnosticsReporter"

    /** Throttle cliente, independiente del reason (server throttlea 20 s; margamos 90 s). */
    const val THROTTLE_MS = 90_000L

    /** Reporte periódico con jornada activa (lo chequea TrackingService por fix/watchdog). */
    const val PERIODIC_MS = 60L * 60_000L

    /** Reasons que saltan el throttle cliente: siempre se envían. */
    val FORCE_REASONS: Set<String> = setOf("journey_start", "journey_stop", "crash_boot", "manual")

    /** Executor mono-hilo daemon: si el proceso muere antes del POST, lo reintenta el próximo arranque. */
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "diagnostics-report").apply { isDaemon = true }
    }

    /** Último intento (construido y encolado) en este proceso; el config trae el último exitoso. */
    private val lastAttemptMs = AtomicLong(0L)

    /**
     * Decisión pura del throttle cliente: las reasons de [FORCE_REASONS] siempre
     * pasan; cualquier otra espera [THROTTLE_MS] desde [lastReportAtMs].
     */
    fun shouldSend(
        reason: String,
        lastReportAtMs: Long,
        nowMs: Long,
        forceReasons: Set<String> = FORCE_REASONS,
        throttleMs: Long = THROTTLE_MS,
    ): Boolean = reason in forceReasons || nowMs - lastReportAtMs >= throttleMs

    /**
     * One-shot de diagnóstico. `pendingCount = -1` = "no consultado" (se omite
     * en el body). Nunca lanza: todo va envuelto en runCatching.
     */
    fun report(context: Context, reason: String, pendingCount: Int = -1) {
        val appContext = context.applicationContext ?: context
        runCatching {
            val config = AppConfig(appContext)
            val now = System.currentTimeMillis()
            val last = maxOf(lastAttemptMs.get(), config.diagnosticsLastReportAt)
            if (!shouldSend(reason, last, now)) return
            val body = DiagnosticsCollector.toJson(
                DiagnosticsCollector.collect(appContext, config, pendingCount),
                ts = now,
            )
            lastAttemptMs.set(now)
            executor.execute {
                runCatching { post(config, reason, body) }
                    .onFailure { error ->
                        // Fallo: silencio para el usuario, solo log + breadcrumb.
                        Log.d(TAG, "Fallo reporte $reason", error)
                        SentryLog.breadcrumb("diag", "report", "$reason falló: ${error.javaClass.simpleName}")
                        runCatching {
                            config.diagnosticsLastResult = "$reason: fallo ${error.javaClass.simpleName}"
                        }
                    }
            }
        }
    }

    /** POST con la misma plomería que HttpFallbackDispatcher. 204/2xx = ok. */
    private fun post(config: AppConfig, reason: String, body: String) {
        val base = MqttServerNormalizer.webBase(config.serverUrl, AppConfig.WEB_PORT)
        val connection = (URL("$base/api/mobile/v1/diagnostics").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Api-Key", AppConfig.HTTP_API_KEY)
            setRequestProperty("X-Device-Id", config.username)
            doOutput = true
            outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = connection.responseCode
            if (code in 200..299) {
                Log.i(TAG, "Reporte $reason aceptado ($code)")
                config.diagnosticsLastReportAt = System.currentTimeMillis()
                config.diagnosticsLastResult = "$reason: ok ($code)"
            } else {
                Log.w(TAG, "Reporte $reason respondió $code")
                config.diagnosticsLastResult = "$reason: http $code"
                // Sin toast, sin crash: solo breadcrumb para el post-mortem en Sentry.
                SentryLog.breadcrumb("diag", "report", "$reason http $code")
            }
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
