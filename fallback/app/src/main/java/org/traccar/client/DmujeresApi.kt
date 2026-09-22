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
    const val KEY_PASSWORD = "password"
    const val KEY_JOURNEY_STARTED_AT = "journeyStartedAt"
    const val KEY_JOURNEY_OPEN = "journeyOpen"
    private const val CLIENT = "dmujeres-traccar"
    private const val GITHUB_REPO = "sekaishopml/Dmujeres-Traccar"

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    private fun deviceId(context: Context): String =
        prefs(context).getString(MainFragment.KEY_DEVICE, "").orEmpty()

    /**
     * Llave del canal móvil: la contraseña que CCTV entregó (si el técnico la
     * cambió en modo avanzado) o la de fábrica embebida en el build.
     */
    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, "").orEmpty()
            .ifBlank { BuildConfig.MOBILE_HTTP_API_KEY }

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
                connection.setRequestProperty("X-Api-Key", apiKey(context))
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
            .putLong(KEY_JOURNEY_STARTED_AT, System.currentTimeMillis())
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

    fun isJourneyOpen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_JOURNEY_OPEN, false)

    /** Hora local del inicio de jornada, para el texto "desde las HH:MM". */
    fun journeyStartedAtLabel(context: Context): String {
        val startedAt = prefs(context).getLong(KEY_JOURNEY_STARTED_AT, 0L)
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return if (startedAt > 0L) format.format(java.util.Date(startedAt)) else "--:--"
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

    /** Reporte al canal de diagnósticos (crashes incluidos; ver panel). */
    fun postDiagnostics(context: Context, body: JSONObject) {
        post(context, "/api/mobile/v1/diagnostics", body)
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
     * Consulta OTA. [onUpdate] recibe (etiqueta o null, url, sha256): etiqueta
     * null = no hay versión mayor publicada. Orden: canal del servidor (rollout
     * con allowlist) y, si el puerto web no es alcanzable, releases de GitHub
     * (mismo respaldo que la app nativa: en datos móviles el 999 puede estar
     * bloqueado y sin esto el teléfono nunca vería el aviso).
     */
    fun checkOta(context: Context, onUpdate: (String?, String, String) -> Unit) {
        val base = webBase(context)
        val device = deviceId(context)
        Thread {
            if (base.isNotBlank() && device.isNotBlank()) {
                val server = tryServerOta(base, device, apiKey(context))
                if (server != null) {
                    onUpdate(server.first, server.second, server.third)
                    return@Thread
                }
            }
            val github = tryGithubRelease()
            if (github != null) {
                onUpdate(github.first, github.second, "")
            } else {
                onUpdate(null, "", "")
            }
        }.start()
    }

    private fun tryServerOta(base: String, device: String, key: String): Triple<String, String, String>? {
        return try {
            val url = URL("$base/api/mobile/v1/ota?deviceId=$device&versionCode=${BuildConfig.VERSION_CODE}")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("X-Api-Key", key)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            val code = json.optInt("versionCode", 0)
            val apkUrl = json.optString("url")
            val sha = json.optString("sha256")
            if (code > BuildConfig.VERSION_CODE && apkUrl.isNotBlank()) {
                Triple(json.optString("version", code.toString()), apkUrl, sha)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTA del servidor no disponible", e)
            null
        }
    }

    /** Releases del repo raíz: tag vX.Y.Z + asset APK. Compara por nombre. */
    private fun tryGithubRelease(): Triple<String, String, String>? {
        return try {
            val connection = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            connection.disconnect()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (!isNewer(tag)) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val url = assets.getJSONObject(i).optString("browser_download_url")
                if (url.endsWith(".apk")) return Triple(tag, url, "")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "OTA de GitHub no disponible", e)
            null
        }
    }

    /** ¿La versión publicada es mayor que la instalada? (numérica, sin downgrade). */
    private fun isNewer(candidate: String): Boolean {
        val installed = BuildConfig.VERSION_NAME.split(".")
        val published = candidate.split(".")
        for (i in 0 until maxOf(installed.size, published.size)) {
            val a = installed.getOrNull(i)?.toIntOrNull() ?: 0
            val b = published.getOrNull(i)?.toIntOrNull() ?: 0
            if (a != b) return b > a
        }
        return false
    }
}
