package org.traccar.client

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Configuración remota del cliente de respaldo.
 *
 * El servidor expone /api/mobile/v1/config con los ajustes del dispositivo
 * (intervalo, filtros de captura, precisión y búfer). Se consulta al abrir el
 * home, en cada latido de diagnóstico y desde el refresco manual; si algo
 * cambió se reinicia el servicio para que tome los valores nuevos.
 */
object RemoteConfig {

    private const val TAG = "RemoteConfig"
    private val ACCURACIES = setOf("high", "medium", "low")

    /** Consulta la config y aplica a prefs solo valores válidos. */
    fun refresh(context: Context, onApplied: (Boolean) -> Unit) {
        val base = DmujeresApi.webBase(context)
        val device = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(Prefs.DEVICE, "").orEmpty().trim().lowercase()
        if (base.isBlank() || device.isBlank()) {
            onApplied(false)
            return
        }
        Thread {
            var changed = false
            try {
                val connection = URL("$base/api/mobile/v1/config").openConnection() as HttpURLConnection
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                connection.setRequestProperty("X-Api-Key", DmujeresApi.apiKey(context))
                connection.setRequestProperty("X-Device-Id", device)
                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    ""
                }
                connection.disconnect()
                if (code in 200..299) {
                    changed = apply(context, JSONObject(body))
                } else {
                    Log.w(TAG, "config respondió $code")
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo consultar la configuración remota", e)
            }
            onApplied(changed)
        }.start()
    }

    /** Consulta la config y, si cambió, reinicia el servicio para aplicarla. */
    fun applyAndRestartIfChanged(context: Context) {
        refresh(context) { applied ->
            if (applied) restartService(context)
        }
    }

    /** Detiene y arranca de nuevo el servicio (solo si ya está corriendo). */
    fun restartService(context: Context) {
        val app = context.applicationContext
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            // Guardas contra la carrera clásica de Android: si se reinicia
            // mientras el servicio está arrancando (o parado), un
            // startForegroundService se queda sin su startForeground y el
            // sistema mata la app. Se espera al onDestroy antes de arrancar.
            if (!TrackingService.isRunning) return@post
            runCatching {
                app.stopService(Intent(app, TrackingService::class.java))
                handler.postDelayed({
                    runCatching {
                        ContextCompat.startForegroundService(
                            app, Intent(app, TrackingService::class.java),
                        )
                    }.onFailure { Log.w(TAG, "No se pudo arrancar el servicio", it) }
                }, 700)
            }.onFailure {
                // P. ej. Android 12+ puede rechazar el arranque en segundo plano:
                // se registra sin tumbar el proceso.
                Log.w(TAG, "No se pudo reiniciar el servicio", it)
            }
        }
    }

    /** Aplica el JSON a las prefs. Devuelve true solo si algún valor cambió. */
    private fun apply(context: Context, json: JSONObject): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val editor = prefs.edit()
        var changed = false

        fun putString(key: String, current: String, value: String) {
            if (current != value) {
                editor.putString(key, value)
                changed = true
            }
        }

        val interval = json.optLong("intervalSeconds", -1L)
        if (interval >= 3) {
            putString(Prefs.INTERVAL, prefs.getString(Prefs.INTERVAL, "60").orEmpty(), interval.toString())
        }
        val distance = json.optLong("distanceMeters", -1L)
        if (distance >= 0) {
            putString(Prefs.DISTANCE, prefs.getString(Prefs.DISTANCE, "10").orEmpty(), distance.toString())
        }
        val angle = json.optLong("angleDegrees", -1L)
        if (angle in 0..180) {
            putString(Prefs.ANGLE, prefs.getString(Prefs.ANGLE, "15").orEmpty(), angle.toString())
        }
        val accuracy = json.optString("accuracy")
        if (accuracy in ACCURACIES) {
            putString(Prefs.ACCURACY, prefs.getString(Prefs.ACCURACY, "medium").orEmpty(), accuracy)
        }
        val buffer = json.opt("bufferEnabled")
        if (buffer is Boolean && prefs.getBoolean(Prefs.BUFFER, true) != buffer) {
            editor.putBoolean(Prefs.BUFFER, buffer)
            changed = true
        }
        if (changed) editor.apply()
        return changed
    }
}
