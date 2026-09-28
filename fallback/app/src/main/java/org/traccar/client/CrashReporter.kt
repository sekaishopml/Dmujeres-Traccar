package org.traccar.client

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import org.json.JSONObject
import androidx.preference.PreferenceManager

/**
 * Reporte de crashes del plan B: escribe el stack en un archivo y lo sube al
 * canal de diagnósticos del servidor (se ve en el panel como
 * `lastDiagnostics.crash`). Así un fallo de arranque se diagnostica sin adb.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                save(context, error)
            }
            runCatching {
                report(context, error)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun save(context: Context, error: Throwable) {
        runCatching {
            File(context.filesDir, "last_crash.txt").writeText(
                stackOf(error).take(8_000),
            )
        }
    }

    /** Reporte al servidor con la misma forma del reporte de diagnósticos. */
    private fun report(context: Context, error: Throwable) {
        Thread {
            runCatching {
                val prefs = PreferenceManager.getDefaultSharedPreferences(context)
                val open = prefs.getBoolean(DmujeresApi.KEY_JOURNEY_OPEN, false)
                DmujeresApi.postDiagnostics(
                    context,
                    JSONObject()
                        .put(
                            "report",
                            JSONObject()
                                .put(
                                    "app",
                                    JSONObject()
                                        .put("versionCode", BuildConfig.VERSION_CODE)
                                        .put("versionName", BuildConfig.VERSION_NAME),
                                )
                                .put("crash", stackOf(error).take(2_000))
                                .put("journeyOpen", open),
                        ),
                )
            }
        }.start()
    }

    private fun stackOf(error: Throwable): String {
        val writer = PrintWriter(StringWriter())
        error.printStackTrace(writer)
        return writer.toString()
    }
}
