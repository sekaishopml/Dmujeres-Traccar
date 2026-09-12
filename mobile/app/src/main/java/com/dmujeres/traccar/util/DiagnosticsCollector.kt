package com.dmujeres.traccar.util

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.mqtt.MqttStatus
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Insumos del snapshot de diagnóstico: TODOS primitivos (sin Android) para que
 * [DiagnosticsCollector.compact] sea unit-testable en JVM. Lo construye el
 * wrapper [DiagnosticsCollector.sourcesFrom] leyendo estado vivo.
 */
data class DiagnosticsSources(
    val versionCode: Int,
    val versionName: String,
    val journeyActive: Boolean,
    val journeyElapsedMs: Long,
    val journeyStartAt: Long,
    /** Pendientes en Room; <0 = "no consultado" → se omite (nunca tocar la DB desde la UI). */
    val pendingCount: Int,
    val bufferMax: Int,
    val bufferPolicy: String,
    val mqttStatus: String,
    val lastAckAt: Long,
    val lastFixAt: Long,
    val reconnects24h: Int,
    val netCause: String,
    val cellular: Boolean,
    val airplane: Boolean,
    val battery: Int,
    val batteryExempt: Boolean,
    /** Ms sin actividad de captura (proxy: now - max(lastFixAt, lastEnqueuedAt)); 0 si nunca. */
    val idleMs: Long,
    val crashes24h: Int,
    val anrs24h: Int,
    val stuckStops24h: Int,
    val clockSteps24h: Int,
    val speedStuck24h: Int,
    val lastStartError: String,
)

/**
 * Construye el snapshot de diagnóstico que consume [DiagnosticsReporter].
 *
 * Reparte el trabajo en dos mitades:
 * - [compact] + [toJson]: puros (solo org.json), whitelist EXACTO del contrato
 *   `POST /api/mobile/v1/diagnostics`. Cualquier campo fuera del esquema NO se
 *   genera; el servidor además descartaría lo que sobre (y recorta hasta 10 KB).
 * - [sourcesFrom]: wrapper delgado que lee estado Android (AppConfig,
 *   MqttStatus, BatteryManager, PowerManager, snapshot de red del util NetCause).
 *   La cuenta de pendientes ENTRa por parámetro: quien llama ya la tiene o la
 *   consulta fuera del hilo de UI.
 *
 * `deviceId` numérico (db id) se OMITE: el cliente solo conoce su uniqueId, que
 * viaja en la cabecera X-Device-Id; el servidor rechaza (403) cualquier body
 * deviceId que no coincida, así que no incluirlo es la opción segura.
 */
object DiagnosticsCollector {

    /** Salte wall-vs-monótono por encima del cual se considera paso de reloj. */
    const val CLOCK_STEP_THRESHOLD_MS = 60_000L

    /**
     * Detector puro de pasos de reloj: divergencia entre el delta del reloj de
     * pared y el del monotónico en la misma ventana > 60 s (NTP, cambio de zona,
     * ajuste manual). Usado por el watchdog de TrackingService cada 30 s.
     */
    fun clockStepDelta(wallDiffMs: Long, monoDiffMs: Long): Boolean =
        abs(wallDiffMs - monoDiffMs) > CLOCK_STEP_THRESHOLD_MS

    /**
     * Duración de jornada para el snapshot: reutiliza el reloj honesto de la UI
     * ([JourneyFormatter.displayElapsedMs]) — nunca `now - startAt` bruto.
     */
    fun journeyElapsedMs(
        persistedElapsedMs: Long,
        persistedWallMs: Long,
        journeyStartAt: Long,
        nowMs: Long,
    ): Long = JourneyFormatter.displayElapsedMs(
        persistedElapsedMs = persistedElapsedMs,
        persistedWallMs = persistedWallMs,
        journeyStartWallMs = journeyStartAt,
        nowWallMs = nowMs,
    )

    /** Mapeo puro al whitelist del servidor. Nulls y cadenas en blanco se omiten. */
    fun compact(s: DiagnosticsSources): Map<String, Any?> = mapOf(
        "app" to buildMap {
            put("versionCode", s.versionCode)
            nonBlank(s.versionName)?.let { put("versionName", it) }
        },
        "journey" to buildMap {
            put("active", s.journeyActive)
            put("elapsedMs", s.journeyElapsedMs.coerceAtLeast(0L))
            put("startAt", s.journeyStartAt.coerceAtLeast(0L))
        },
        "buffer" to buildMap {
            if (s.pendingCount >= 0) put("pending", s.pendingCount)
            put("max", s.bufferMax)
            nonBlank(s.bufferPolicy)?.let { put("policy", it) }
        },
        "mqtt" to buildMap {
            nonBlank(s.mqttStatus)?.let { put("status", it) }
            put("lastAckAt", s.lastAckAt.coerceAtLeast(0L))
            put("lastFixAt", s.lastFixAt.coerceAtLeast(0L))
            put("reconnects", s.reconnects24h.coerceAtLeast(0))
        },
        "net" to buildMap {
            nonBlank(s.netCause)?.let { put("cause", it) }
            put("cellular", s.cellular)
            put("airplane", s.airplane)
        },
        "power" to buildMap {
            put("battery", s.battery.coerceIn(-1, 100))
            put("exempt", s.batteryExempt)
            put("idleMs", s.idleMs.coerceAtLeast(0L))
        },
        "health" to buildMap {
            put("crashes24h", s.crashes24h.coerceAtLeast(0))
            put("anrs24h", s.anrs24h.coerceAtLeast(0))
            put("stuckStops", s.stuckStops24h.coerceAtLeast(0))
            put("clockSteps24h", s.clockSteps24h.coerceAtLeast(0))
            put("speedStuck24h", s.speedStuck24h.coerceAtLeast(0))
            nonBlank(s.lastStartError)?.let { put("lastStartError", it.take(64)) }
        },
    )

    /**
     * Body del contrato: `{"ts": <epoch ms>, "report": {...}}`. `ts` default =
     * reloj actual; el servidor lo conserva tal cual si viene y lo normaliza a
     * epoch ms del suyo si falta.
     */
    fun toJson(report: Map<String, Any?>, ts: Long = System.currentTimeMillis()): String {
        val root = JSONObject()
        root.put("ts", ts)
        root.put("report", jsonObject(report))
        return root.toString()
    }

    /** Snapshot completo listo para [toJson]: estado Android + pendientes (por parámetro). */
    fun collect(context: Context, config: AppConfig, pendingCount: Int): Map<String, Any?> =
        compact(sourcesFrom(context, config, pendingCount))

    /** Lee estado Android (barato: prefs + BatteryManager/PowerManager/ConnectivityManager). */
    fun sourcesFrom(
        context: Context,
        config: AppConfig,
        pendingCount: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): DiagnosticsSources {
        val battery = runCatching {
            (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(-1)
        val exempt = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
        // Foto de red vía el mismo snapshot util que alimenta NetCause (cellular/avión).
        val shot = runCatching { snapshot(context, config.netLabel, config.lastDataEnabled) }.getOrNull()
        // Idle: tiempo sin capturar/enviar; 0 si nunca hubo actividad (arranque limpio).
        val idleAnchor = maxOf(config.lastFixAt, config.lastEnqueuedAt)
        return DiagnosticsSources(
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            journeyActive = config.trackingEnabled && config.journeyStartAt > 0L,
            journeyElapsedMs = journeyElapsedMs(
                persistedElapsedMs = config.journeyElapsedMs,
                persistedWallMs = config.journeyElapsedWallMs,
                journeyStartAt = config.journeyStartAt,
                nowMs = nowMs,
            ),
            journeyStartAt = config.journeyStartAt,
            pendingCount = pendingCount,
            bufferMax = config.bufferMax,
            bufferPolicy = config.bufferPolicy,
            mqttStatus = MqttStatus.status,
            lastAckAt = config.lastAckAt,
            lastFixAt = config.lastFixAt,
            reconnects24h = runCatching { config.reconnects24h }.getOrDefault(0),
            netCause = config.netCause,
            cellular = shot?.hasCellTransport == true,
            airplane = shot?.airplane == true,
            battery = battery,
            batteryExempt = exempt,
            idleMs = if (idleAnchor > 0L) (nowMs - idleAnchor).coerceAtLeast(0L) else 0L,
            crashes24h = runCatching { config.crashes24h }.getOrDefault(0),
            anrs24h = runCatching { config.anrs24h }.getOrDefault(0),
            stuckStops24h = runCatching { config.stuckStops24h }.getOrDefault(0),
            clockSteps24h = runCatching { config.clockSteps24h }.getOrDefault(0),
            speedStuck24h = runCatching { config.speedStuck24h }.getOrDefault(0),
            lastStartError = config.lastStartError,
        )
    }

    private fun nonBlank(value: String): String? = value.trim().takeIf { it.isNotEmpty() }

    private fun jsonObject(map: Map<*, *>): JSONObject {
        val obj = JSONObject()
        map.forEach { (key, value) ->
            val json = toJsonValue(value)
            if (key is String && json != null) obj.put(key, json)
        }
        return obj
    }

    private fun toJsonValue(value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> jsonObject(value)
        is List<*> -> JSONArray().also { arr ->
            value.forEach { arr.put(toJsonValue(it) ?: JSONObject.NULL) }
        }
        is Number -> value
        is Boolean -> value
        is String -> value
        else -> value.toString()
    }
}
