package com.dmujeres.traccar.data

import android.content.Context
import android.util.Log
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.core.MqttServerNormalizer
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.dmujeres.traccar.core.MobileProtocol

/**
 * Descarga la configuración que el administrador definió en el panel
 * (/settings/device): política de buffer, tiempo de espera y reintentos máximos.
 * Se aplica al iniciar sesión; si el servidor no responde se conservan los
 * valores por defecto (la app sigue funcionando igual).
 */
object RemoteConfig {

    private const val TAG = "RemoteConfig"

    /** Devuelve true si se obtuvo y aplicó la configuración del servidor. */
    suspend fun fetch(context: Context): Boolean {
        val config = AppConfig(context)
        if (config.username.isBlank() || config.password.isBlank()) return false
        val base = MqttServerNormalizer.webBase(config.serverUrl, MobileProtocol.WEB_PORT)

        return try {
            val connection = URL("$base${MobileProtocol.PATH_CONFIG}").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("X-Api-Key", AppConfig.HTTP_API_KEY)
            connection.setRequestProperty("X-Device-Id", config.username)
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            connection.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "Config remota respondió $code")
                return false
            }
            val json = JSONObject(body)
            config.applyRemote(
                interval = json.optLong("intervalSeconds", -1).takeIf { it > 0 },
                bufferMax = json.optInt("bufferMax", -1).takeIf { it > 0 },
                bufferPolicy = json.optString("bufferPolicy").takeIf { it.isNotBlank() },
                ackTimeout = json.optInt("ackTimeoutSeconds", -1).takeIf { it > 0 },
                maxRetries = json.optInt("maxRetries", -1).takeIf { it > 0 },
                maxImpliedSpeedMps = json.optDouble("filter_max_speed_mps", -1.0).takeIf { it > 0 }?.toFloat()
                    ?: json.optDouble("filterMaxSpeedMps", -1.0).takeIf { it > 0 }?.toFloat()
                    ?: json.optDouble("maxImpliedSpeedMps", -1.0).takeIf { it > 0 }?.toFloat(),
                consistentSpeedMps = json.optDouble("filter_consistent_speed_mps", -1.0).takeIf { it > 0 }?.toFloat()
                    ?: json.optDouble("filterConsistentSpeedMps", -1.0).takeIf { it > 0 }?.toFloat()
                    ?: json.optDouble("consistentSpeedMps", -1.0).takeIf { it > 0 }?.toFloat(),
                accuracyBadM = json.optDouble("filter_accuracy_bad_m", -1.0).takeIf { it > 0 }?.toFloat()
                    ?: json.optDouble("filterAccuracyBadM", -1.0).takeIf { it > 0 }?.toFloat()
                    ?: json.optDouble("accuracyBadM", -1.0).takeIf { it > 0 }?.toFloat(),
                accuracyGoodM = json.optDouble("filter_accuracy_good_m", -1.0).takeIf { it > 0 }?.toFloat()
                    ?: json.optDouble("filterAccuracyGoodM", -1.0).takeIf { it > 0 }?.toFloat()
                    ?: json.optDouble("accuracyGoodM", -1.0).takeIf { it > 0 }?.toFloat(),
                // Fase L1: booleanos con patrón "ausente != false" y longs > 0.
                l1PendingIntentEnabled = if (json.has("l1_pending_intent_enabled") || json.has("l1PendingIntentEnabled")) {
                    json.optBoolean("l1_pending_intent_enabled", json.optBoolean("l1PendingIntentEnabled", false))
                } else null,
                storeAllEnabled = if (json.has("store_all_enabled") || json.has("storeAllEnabled")) {
                    json.optBoolean("store_all_enabled", json.optBoolean("storeAllEnabled", false))
                } else null,
                l1MaxUpdateDelayMs = json.optLong("l1_max_update_delay_ms", -1).takeIf { it > 0 }
                    ?: json.optLong("l1MaxUpdateDelayMs", -1).takeIf { it > 0 },
                minIntervalSeconds = json.optLong("min_interval_seconds", -1).takeIf { it > 0 }
                    ?: json.optLong("minIntervalSeconds", -1).takeIf { it > 0 },
            )
            Log.i(TAG, "Config remota aplicada para ${config.username}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo obtener la config remota", e)
            false
        }
    }
}