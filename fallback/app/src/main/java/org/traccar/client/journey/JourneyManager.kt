package org.traccar.client.journey

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject
import org.traccar.client.BuildConfig
import org.traccar.client.DatabaseHelper
import org.traccar.client.DmujeresApi
import org.traccar.client.Prefs
import java.net.HttpURLConnection
import java.net.URL

/**
 * Jornada persistida + reconciliación cliente↔servidor (§10).
 *
 * Por qué persiste en `meta` ADEMÁS de prefs: las prefs las lee la UI
 * ([DmujeresApi] las escribe al abrir/cerrar y no se tocan por compatibilidad),
 * pero la fuente del tracking tras recreación/reboot es `meta` del SQLite
 * (misma transacción que la cola: o se guarda todo o nada se asume).
 *
 * Reconciliación al arrancar (`GET /api/mobile/v1/journey` → el servidor dice
 * si hay jornada abierta): si el servidor aún no tiene el endpoint (404) se
 * conserva el flujo actual (fallback automático, sin romper la flota).
 */
class JourneyManager(context: Context) {

    private val appContext = context.applicationContext

    data class LocalJourney(val journeyId: String, val startedAtMs: Long, val open: Boolean)

    data class RemoteJourney(val open: Boolean, val journeyId: String, val startedAtMs: Long)

    enum class ReconcileOutcome { ADOPTED_REMOTE, KEPT_LOCAL, IN_SYNC, FALLBACK_LOCAL }

    /** Jornada local: meta manda; si está vacía se hereda de prefs (migración). */
    fun local(): LocalJourney? {
        val db = runCatching { DatabaseHelper(appContext) }.getOrNull()
        val metaId = db?.getMeta(DatabaseHelper.KEY_JOURNEY_ID)
        val metaAt = db?.getMeta(DatabaseHelper.KEY_JOURNEY_STARTED_AT)?.toLongOrNull() ?: 0L
        if (!metaId.isNullOrBlank()) {
            val open = db?.getMeta(KEY_JOURNEY_OPEN_META) != "0"
            return LocalJourney(metaId, metaAt, open)
        }
        // Herencia de instalaciones previas: solo prefs.
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        if (!prefs.getBoolean(DmujeresApi.KEY_JOURNEY_OPEN, false)) return null
        val id = prefs.getLong(DmujeresApi.KEY_JOURNEY_ID, 0L)
        if (id <= 0L) return null
        return LocalJourney(id.toString(), prefs.getLong(DmujeresApi.KEY_JOURNEY_STARTED_AT, 0L), true)
    }

    /** Persiste la jornada en meta y espeja prefs (la UI existente lee prefs). */
    fun persistLocal(journeyId: String, startedAtMs: Long, open: Boolean) {
        runCatching {
            DatabaseHelper(appContext).apply {
                putMeta(DatabaseHelper.KEY_JOURNEY_ID, journeyId)
                putMeta(DatabaseHelper.KEY_JOURNEY_STARTED_AT, startedAtMs.toString())
                putMeta(KEY_JOURNEY_OPEN_META, if (open) "1" else "0")
            }
        }
        PreferenceManager.getDefaultSharedPreferences(appContext).edit()
            .putLong(DmujeresApi.KEY_JOURNEY_ID, journeyId.toLongOrNull() ?: 0L)
            .putLong(DmujeresApi.KEY_JOURNEY_STARTED_AT, startedAtMs)
            .putBoolean(DmujeresApi.KEY_JOURNEY_OPEN, open)
            .apply()
    }

    /**
     * Copia prefs→meta al arrancar el tracking: [DmujeresApi] escribe las
     * prefs al abrir/cerrar jornada y la UI las lee; meta es la fuente del
     * tracking tras recreación. Sin esto, una jornada abierta con la APK vieja
     * no aparecería en meta tras actualizar.
     */
    fun syncFromPrefs() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val open = prefs.getBoolean(DmujeresApi.KEY_JOURNEY_OPEN, false)
        val id = prefs.getLong(DmujeresApi.KEY_JOURNEY_ID, 0L)
        if (id <= 0L && !open) return
        runCatching {
            val db = DatabaseHelper(appContext)
            val metaId = db.getMeta(DatabaseHelper.KEY_JOURNEY_ID)
            if (metaId.isNullOrBlank() && id > 0L) {
                db.putMeta(DatabaseHelper.KEY_JOURNEY_ID, id.toString())
                db.putMeta(
                    DatabaseHelper.KEY_JOURNEY_STARTED_AT,
                    prefs.getLong(DmujeresApi.KEY_JOURNEY_STARTED_AT, 0L).toString(),
                )
            }
            db.putMeta(KEY_JOURNEY_OPEN_META, if (open) "1" else "0")
        }
    }

    /**
     * Reconcilia con el servidor al arrancar. No bloquea la captura: corre en
     * hilo propio y decide con [decide] (pura, testeada).
     */
    fun reconcileAtStartup(onDone: ((ReconcileOutcome) -> Unit)? = null) {
        Thread {
            val outcome = try {
                reconcileNow()
            } catch (e: Exception) {
                Log.w(TAG, "reconciliación sin red: se conserva lo local", e)
                ReconcileOutcome.FALLBACK_LOCAL
            }
            onDone?.invoke(outcome)
        }.start()
    }

    private fun reconcileNow(): ReconcileOutcome {
        val remote = fetchRemote() ?: return ReconcileOutcome.FALLBACK_LOCAL
        val outcome = decide(local(), remote)
        if (outcome == ReconcileOutcome.ADOPTED_REMOTE) {
            persistLocal(remote.journeyId, remote.startedAtMs, open = true)
            Log.i(TAG, "jornada adoptada del servidor: ${remote.journeyId}")
        }
        return outcome
    }

    companion object {
        private val TAG = JourneyManager::class.java.simpleName
        const val PATH_JOURNEY = "/api/mobile/v1/journey"
        private const val KEY_JOURNEY_OPEN_META = "journey_open"

        /**
         * Decisión pura de reconciliación: el cliente NUNCA pisa una jornada
         * local abierta (el servidor ya reconcilia cada hora); solo adopta la
         * remota si lo local está vacío/cerrado. Cerrar una jornada ajena sería
         * pérdida.
         */
        fun decide(local: LocalJourney?, remote: RemoteJourney?): ReconcileOutcome {
            if (remote == null) return ReconcileOutcome.FALLBACK_LOCAL
            if (local?.open == true) {
                return if (remote.open && remote.journeyId == local.journeyId) {
                    ReconcileOutcome.IN_SYNC
                } else {
                    ReconcileOutcome.KEPT_LOCAL
                }
            }
            return if (remote.open) ReconcileOutcome.ADOPTED_REMOTE else ReconcileOutcome.IN_SYNC
        }
    }

    /** GET /api/mobile/v1/journey → null si el servidor no lo tiene o no hay red. */
    private fun fetchRemote(): RemoteJourney? {
        val base = DmujeresApi.webBase(appContext)
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        val device = prefs.getString(Prefs.DEVICE, "").orEmpty().trim().lowercase()
        if (base.isBlank() || device.isBlank()) return null
        val connection = URL(base + PATH_JOURNEY).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            val hadToken = DmujeresApi.setAuthHeaders(connection, appContext)
            connection.setRequestProperty("X-Device-Id", device)
            if (connection.responseCode == 404) return null // servidor viejo: fallback
            if (connection.responseCode !in 200..299) {
                // 401 con token: sesión terminada, se pedirá login (sin reintento).
                DmujeresApi.noteHttpResult(appContext, connection.responseCode, hadToken)
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val estado = json.optString("estado")
            return RemoteJourney(
                open = estado.equals("abierta", ignoreCase = true) || estado.equals("open", ignoreCase = true),
                journeyId = json.optString("journeyId").ifBlank { json.optLong("journeyId", 0L).toString() },
                startedAtMs = json.optLong("inicioEn", json.optLong("startedAt", 0L)),
            )
        } catch (e: Exception) {
            Log.w(TAG, "GET journey falló", e)
            return null
        } finally {
            connection.disconnect()
        }
    }
}
