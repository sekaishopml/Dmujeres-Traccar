package org.traccar.client

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Canal DMujeres del cliente de respaldo (plan B).
 *
 * El trazado va por el protocolo OsmAnd del cliente oficial (puerto 5055);
 * este objeto cubre lo que el cliente oficial no trae y el servidor sí espera:
 * - registro del token FCM (recuperación por push),
 * - eventos de jornada (inicio/fin) para el reporte del panel,
 * - ack de recuperación,
 * - consulta de actualización OTA.
 *
 * Todo corre en hilos propios: nunca bloquea la UI ni el servicio.
 */
object DmujeresApi {

    private const val TAG = "DmujeresApi"
    const val KEY_JOURNEY_ID = "journeyId"
    const val KEY_JOURNEY_OPEN = "journeyOpen"
    private const val CLIENT = "dmujeres-traccar"

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    private fun deviceId(context: Context): String =
        prefs(context).getString(MainFragment.KEY_DEVICE, "").orEmpty()

    /** Base web (999) derivada de la URL OsmAnd configurada (5055). */
    fun webBase(context: Context): String {
        val url = prefs(context).getString(MainFragment.KEY_URL, "").orEmpty()
        return url.replace(":5055", ":999").trimEnd('/')
    }

    private fun post(context: Context, path: String, body: JSONObject, onDone: ((Boolean) -> Unit)? = null) {
        val base = webBase(context)
        val device = deviceId(context)
        if (base.isBlank() || device.isBlank()) {
            Log.w(TAG, "Sin URL o id configurado; se omite $path")
            onDone?.invoke(false)
            return
        }
        Thread {
            var ok = false
            try {
                val connection = URL(base + path).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("X-Api-Key", BuildConfig.MOBILE_HTTP_API_KEY)
                connection.setRequestProperty("X-Device-Id", device)
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                ok = connection.responseCode in 200..299
                if (!ok) Log.w(TAG, "$path respondió ${connection.responseCode}")
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "POST $path falló", e)
            }
            onDone?.invoke(ok)
        }.start()
    }

    /** Registra el token FCM del equipo (endpoint compartido con la app nativa). */
    fun registerFcmToken(context: Context, token: String) {
        if (token.isBlank()) return
        post(
            context,
            "/api/mobile/v1/fcm-token",
            JSONObject().put("fcmToken", token).put("appVersion", BuildConfig.VERSION_NAME),
        )
    }

    /** Inicio de jornada: id = epoch ms, evento mobileJourneyStarted en el panel. */
    fun journeyStarted(context: Context) {
        val journeyId = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_JOURNEY_ID, journeyId)
            .putBoolean(KEY_JOURNEY_OPEN, true)
            .apply()
        post(
            context,
            "/api/mobile/v1/journey",
            JSONObject()
                .put("deviceId", deviceId(context))
                .put("action", "start")
                .put("journeyId", journeyId)
                .put("client", CLIENT),
        )
    }

    /** Fin de jornada: cierra la jornada abierta (si la hay). */
    fun journeyEnded(context: Context) {
        val open = prefs(context).getBoolean(KEY_JOURNEY_OPEN, false)
        val journeyId = prefs(context).getLong(KEY_JOURNEY_ID, System.currentTimeMillis())
        prefs(context).edit().putBoolean(KEY_JOURNEY_OPEN, false).apply()
        if (!open) return
        post(
            context,
            "/api/mobile/v1/journey",
            JSONObject()
                .put("deviceId", deviceId(context))
                .put("action", "stop")
                .put("journeyId", journeyId)
                .put("client", CLIENT),
        )
    }

    /** Acuse del push de recuperación (el servidor audita en tc_recovery_event). */
    fun recoveryAck(context: Context, attemptId: String, stage: String) {
        if (attemptId.isBlank()) return
        post(
            context,
            "/api/mobile/v1/recovery-ack",
            JSONObject()
                .put("recoveryAttemptId", attemptId)
                .put("stage", stage)
                .put("priority", "high")
                .put("reason", "fcm"),
        )
    }

    /**
     * Consulta OTA. [onUpdate] recibe (versionCode, url, sha256) solo si hay una
     * versión mayor disponible para este dispositivo.
     */
    fun checkOta(context: Context, onUpdate: (Int, String, String) -> Unit) {
        val base = webBase(context)
        val device = deviceId(context)
        if (base.isBlank() || device.isBlank()) return
        Thread {
            try {
                val url = URL("$base/api/mobile/v1/ota?deviceId=$device&versionCode=${BuildConfig.VERSION_CODE}")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("X-Api-Key", BuildConfig.MOBILE_HTTP_API_KEY)
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val json = JSONObject(body)
                val code = json.optInt("versionCode", 0)
                val apkUrl = json.optString("url")
                val sha = json.optString("sha256")
                if (code > BuildConfig.VERSION_CODE && apkUrl.isNotBlank()) {
                    onUpdate(code, apkUrl, sha)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Consulta OTA falló", e)
            }
        }.start()
    }
}
