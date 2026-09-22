package org.traccar.client

import android.content.Context
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject

/**
 * Latido de diagnóstico del cliente de respaldo.
 *
 * Mientras el servicio está activo publica (cada 10 min y al arrancar) un
 * reporte al canal de diagnósticos del servidor: servicio activo, ubicación del
 * sistema encendida, pendientes en el buffer, jornada y URL configurada. Con
 * eso el estado del teléfono se ve en el panel (`lastDiagnostics`) sin adb y se
 * puede diagnosticar por qué no llegan posiciones.
 */
object ServiceHeartbeat {

    private const val TAG = "ServiceHeartbeat"
    private const val INTERVAL_MS = 10 * 60_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    private var appContext: Context? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            report()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    fun start(context: Context) {
        if (running) return
        running = true
        appContext = context.applicationContext
        report()
        handler.postDelayed(tick, INTERVAL_MS)
        Log.i(TAG, "latido de diagnóstico iniciado")
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        appContext = null
    }

    private fun report() {
        val context = appContext ?: return
        Thread {
            runCatching {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val locationEnabled = runCatching {
                    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                }.getOrDefault(false)
                val pending = runCatching { DatabaseHelper(context).countPositions() }.getOrDefault(-1)
                DmujeresApi.postDiagnostics(
                    context,
                    JSONObject().put(
                        "report",
                        JSONObject()
                            .put(
                                "app",
                                JSONObject()
                                    .put("versionCode", BuildConfig.VERSION_CODE)
                                    .put("versionName", BuildConfig.VERSION_NAME),
                            )
                            .put(
                                "gps",
                                JSONObject()
                                    .put("enabled", locationEnabled)
                                    .put("provider", "fused"),
                            )
                            .put("buffer", JSONObject().put("pending", pending))
                            .put("journeyOpen", prefs.getBoolean(DmujeresApi.KEY_JOURNEY_OPEN, false))
                            .put("serverUrl", prefs.getString(MainFragment.KEY_URL, "")),
                    ),
                )
            }
        }.start()
    }
}
