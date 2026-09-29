package org.traccar.client

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.traccar.client.journey.JourneyManager
import org.traccar.client.sync.UploadPolicy
import org.traccar.client.sync.UploadQueue
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cierre limpio de sesión (solo debug, flota intacta).
 *
 * `cerrarSesionLimpia()` es suspend: corre en IO sin bloquear el hilo
 * principal, con tope aproximado de 30 s ([SessionClosePlan.TIMEOUT_MS]) y
 * progreso en UI vía [onProgress] (se invoca en el hilo del cierre: la UI
 * debe volver al principal). Pasos:
 *
 * a. Congela la captura nueva ([TrackingController.captureFrozen]).
 * b. Vacía la cola PENDING en lotes del endpoint actual hasta vaciar o
 *    agotar el tope; ante 401 con token se deja de intentar y se sigue al
 *    paso c con lo que quede (lo no enviado queda en SQLite).
 * c. Finaliza la jornada abierta local por el canal actual; si el servidor
 *    ya la tenía cerrada se toma como cerrada sin error. Lo local SIEMPRE
 *    queda cerrado (nunca dos abiertas para la próxima sesión: al entrar de
 *    nuevo se reconcilia con GET /journey).
 * d. Limpia sesión (token, usuario, marcas y usuario residual) y la UI abre
 *    `LoginActivity`. La clave compartida NO se toca.
 *
 * Regla de oro: lo no enviado NUNCA se borra en silencio; si la red falló,
 * el informe trae `remaining` y la UI avisa que se enviará al entrar de
 * nuevo (las filas siguen en SQLite y drenan primero: son las más viejas).
 */
object SessionCloser {

    private val TAG = SessionCloser::class.java.simpleName

    /** POST de jornada (mismo canal que [DmujeresApi.journeyEnded]). */
    private const val CLIENT = "dmujeres-traccar"

    /**
     * Cierre limpio con tope y progreso. Nunca lanza: ante un fallo
     * inesperado devuelve el informe con la sesión intacta.
     */
    suspend fun cerrarSesionLimpia(
        context: Context,
        onProgress: (enviadas: Int, restantes: Int) -> Unit = { _, _ -> },
    ): SessionClosePlan.CloseReport = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        // a. Congelar la captura nueva (no aceptar más fixes a la cola).
        TrackingController.captureFrozen = true
        try {
            var sent = 0
            val driver = object : SessionClosePlan.Driver {
                override fun pendingCount(): Int =
                    runCatching { DatabaseHelper(app).countPositions() }.getOrDefault(-1)

                override fun sendBatch(maxBatch: Int): SessionClosePlan.SendResult =
                    flushOneBatch(app, maxBatch)

                override fun endJourney(): SessionClosePlan.JourneyOutcome =
                    endJourneySync(app)

                override fun clearSession() = clearSessionLocal(app)

                override fun nowMs(): Long = System.currentTimeMillis()
            }
            SessionClosePlan.run(
                driver,
                onBatchConfirmed = { confirmed ->
                    sent += confirmed
                    val remaining = runCatching { DatabaseHelper(app).countPositions() }
                        .getOrDefault(-1)
                    runCatching { onProgress(sent, remaining) }
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "cierre interrumpido, sesión intacta", e)
            SessionClosePlan.CloseReport(
                sent = 0,
                remaining = runCatching { DatabaseHelper(app).countPositions() }.getOrDefault(-1),
                authFailed = false,
                journey = SessionClosePlan.JourneyOutcome.OFFLINE,
                sessionCleared = false,
            )
        } finally {
            // La captura siempre se reanuda (con la clave compartida o la
            // próxima sesión): el cierre no deja el tracking congelado.
            TrackingController.captureFrozen = false
        }
    }

    // ── b. Vaciar la cola ────────────────────────────────────────────────

    /**
     * Envía UN lote por el endpoint actual. No borra lo no confirmado:
     * solo lo confirmado por el servidor sale del SQLite.
     */
    private fun flushOneBatch(app: Context, maxBatch: Int): SessionClosePlan.SendResult {
        val db = runCatching { DatabaseHelper(app) }.getOrNull()
            ?: return SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.RETRY_LATER)
        val batch = runCatching { db.selectPositions(maxBatch) }
            .getOrDefault(emptyList())
            .filter { it.status != STATUS_DEAD }
        if (batch.isEmpty()) return SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.EMPTY)
        runCatching { db.addAttempts(batch.map { it.id }) }
        val deviceId = PreferenceManager.getDefaultSharedPreferences(app)
            .getString(Prefs.DEVICE, "").orEmpty().trim().lowercase()
        if (deviceId.isBlank()) {
            // Sin identidad no se envía nada: se conserva para la próxima sesión.
            return SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.RETRY_LATER)
        }
        val (code, body, hadToken) = runCatching { postBatch(app, deviceId, batch) }.getOrNull()
            ?: return SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.RETRY_LATER)
        return when (UploadPolicy.classifyHttp(code)) {
            UploadPolicy.HttpClass.CONFIRMED -> applyBatchResult(db, batch, body)
            UploadPolicy.HttpClass.DEAD -> {
                if (code == 404) {
                    // Servidor sin el endpoint nuevo: fallback OsmAnd 1×1.
                    flushLegacyOneByOne(app, db, batch)
                } else {
                    // El servidor lo rechaza (400/413/422): no se reintenta;
                    // queda contado en meta (sin borrado silencioso).
                    runCatching { db.markDead(batch.map { it.id }) }
                    SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.SENT)
                }
            }
            UploadPolicy.HttpClass.PAUSED -> {
                if (hadToken) {
                    // Token muerto a mitad: se limpia la marca de sesión aquí
                    // (el paso d la deja en limpio definitivo) y se sigue al
                    // fin con lo que quede, sin perder datos.
                    DmujeresApi.noteHttpResult(app, code, hadToken = true)
                } else {
                    Log.w(TAG, "cierre pausado por 401 sin token")
                    StatusActivity.addMessage("Clave móvil inválida (401): subida pausada")
                }
                SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.AUTH_PAUSED)
            }
            UploadPolicy.HttpClass.RETRY ->
                SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.RETRY_LATER)
        }
    }

    @Throws(Exception::class)
    private fun postBatch(
        app: Context,
        deviceId: String,
        batch: List<Position>,
    ): Triple<Int, String, Boolean> {
        val connection = URL(DmujeresApi.webBase(app) + UploadQueue.PATH_POSITIONS)
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            // Timeouts más cortos que la cola (15 s): el cierre tiene tope ~30 s.
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val hadToken = DmujeresApi.setAuthHeaders(connection, app)
            connection.setRequestProperty("X-Device-Id", deviceId)
            val body = JSONObject().put("eventos", JSONArray().also { array ->
                for (position in batch) array.put(eventJson(position))
            })
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }
                .getOrDefault("")
            return Triple(code, text, hadToken)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Aplica el `resultados` del lote: borra SOLO lo confirmado (accepted o
     * duplicate) y marca DEAD lo rechazado. Sin mención o sin cuerpo válido
     * no se borra nada (se reintenta por idempotencia).
     */
    private fun applyBatchResult(
        db: DatabaseHelper,
        batch: List<Position>,
        response: String,
    ): SessionClosePlan.SendResult {
        val batchPairs = batch.map { it.id to it.localSequence }
        val match: UploadPolicy.BatchMatch
        try {
            val resultados = JSONObject(response).getJSONArray("resultados")
            val entries = ArrayList<Pair<Long, String>>(resultados.length())
            for (i in 0 until resultados.length()) {
                val item = resultados.getJSONObject(i)
                entries.add(item.optLong("seq") to item.optString("estado"))
            }
            // Mismo emparejado 1-a-1 que la cola (ver UploadPolicy): cada
            // mención consume una sola fila, en orden.
            match = UploadPolicy.matchBatchResults(batchPairs, entries)
        } catch (e: Exception) {
            Log.w(TAG, "lote 2xx sin cuerpo válido: se conserva", e)
            return SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.RETRY_LATER)
        }
        val confirmed = match.confirmedIds
        val dead = match.deadIds
        runCatching { db.deletePositions(confirmed) }
        if (dead.isNotEmpty()) runCatching { db.markDead(dead) }
        if (confirmed.isEmpty() && dead.isEmpty()) {
            return SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.RETRY_LATER)
        }
        return SessionClosePlan.SendResult(confirmed.size, SessionClosePlan.BatchOutcome.SENT)
    }

    /** Fallback OsmAnd 1×1 en serie (primer fallo para, sin saltos). */
    private fun flushLegacyOneByOne(
        app: Context,
        db: DatabaseHelper,
        batch: List<Position>,
    ): SessionClosePlan.SendResult {
        val url = PreferenceManager.getDefaultSharedPreferences(app)
            .getString(Prefs.URL, "").orEmpty()
        val confirmed = ArrayList<Long>()
        var failed = false
        for (position in batch) {
            val ok = runCatching {
                RequestManager.sendRequest(ProtocolFormatter.formatRequest(url, position))
            }.getOrDefault(false)
            if (ok) {
                confirmed.add(position.id)
            } else {
                failed = true
                break
            }
        }
        runCatching { db.deletePositions(confirmed) }
        return if (confirmed.isEmpty() && failed) {
            SessionClosePlan.SendResult(0, SessionClosePlan.BatchOutcome.RETRY_LATER)
        } else {
            SessionClosePlan.SendResult(confirmed.size, SessionClosePlan.BatchOutcome.SENT)
        }
    }

    private fun eventJson(position: Position): JSONObject = JSONObject()
        .put("bootId", position.bootId)
        .put("seq", position.localSequence)
        .put("journeyId", position.journeyId)
        .put("capturedAt", position.time.time)
        .put("lat", position.latitude)
        .put("lon", position.longitude)
        .put("alt", position.altitude)
        .put("speed", position.speed)
        .put("bearing", position.course)
        .put("accuracy", position.accuracy)
        .put("battery", position.battery)
        .put("charging", position.charging)
        .put("mock", position.mock)
        .put("provider", position.provider)
        .put("movementState", position.movementState)

    // ── c. Finalizar la jornada ──────────────────────────────────────────

    /**
     * Cierra la jornada abierta local por el canal actual. Lo local SIEMPRE
     * queda cerrado (prefs + meta) para que la próxima sesión reconcilie con
     * GET /journey sin duplicar abiertas; el informe dice si el servidor lo
     * confirmó. Sin jornada abierta local no hay llamada (sin error).
     */
    private fun endJourneySync(app: Context): SessionClosePlan.JourneyOutcome {
        val manager = JourneyManager(app)
        val local = runCatching { manager.local() }.getOrNull()
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val openPrefs = prefs.getBoolean(DmujeresApi.KEY_JOURNEY_OPEN, false)
        if (local?.open != true && !openPrefs) {
            return SessionClosePlan.JourneyOutcome.ALREADY_CLOSED
        }
        val journeyId = local?.journeyId?.toLongOrNull()
            ?: prefs.getLong(DmujeresApi.KEY_JOURNEY_ID, System.currentTimeMillis())
        val startedAt = if (local != null) local.startedAtMs
        else prefs.getLong(DmujeresApi.KEY_JOURNEY_STARTED_AT, 0L)
        // Cierre local primero: la próxima sesión parte de "cerrada" y adopta
        // lo que diga el servidor (nunca dos abiertas locales).
        prefs.edit().putBoolean(DmujeresApi.KEY_JOURNEY_OPEN, false).apply()
        val metaId = local?.journeyId?.ifBlank { null } ?: journeyId.toString()
        if (metaId.isNotBlank()) {
            runCatching { manager.persistLocal(metaId, startedAt, open = false) }
        }
        return try {
            postJourneyStop(app, journeyId)
        } catch (e: Exception) {
            Log.w(TAG, "fin de jornada sin red: queda cerrada en el teléfono", e)
            SessionClosePlan.JourneyOutcome.OFFLINE
        }
    }

    @Throws(Exception::class)
    private fun postJourneyStop(app: Context, journeyId: Long): SessionClosePlan.JourneyOutcome {
        val base = DmujeresApi.webBase(app)
        val device = PreferenceManager.getDefaultSharedPreferences(app)
            .getString(Prefs.DEVICE, "").orEmpty().trim().lowercase()
        if (base.isBlank() || device.isBlank()) {
            return SessionClosePlan.JourneyOutcome.OFFLINE
        }
        val connection = URL(base + JourneyManager.PATH_JOURNEY).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            DmujeresApi.setAuthHeaders(connection, app)
            connection.setRequestProperty("X-Device-Id", device)
            val body = JSONObject()
                .put("deviceId", device)
                .put("action", "stop")
                .put("journeyId", journeyId)
                .put("client", CLIENT)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            return when (val code = connection.responseCode) {
                in 200..299 -> {
                    StatusActivity.addMessage(app.getString(R.string.journey_ended_toast))
                    SessionClosePlan.JourneyOutcome.CLOSED
                }
                // El servidor ya la tenía cerrada: sin error.
                400, 404, 409, 410, 422 -> SessionClosePlan.JourneyOutcome.ALREADY_CLOSED
                401, 403 -> SessionClosePlan.JourneyOutcome.AUTH_FAILED
                else -> {
                    Log.w(TAG, "fin de jornada respondió $code")
                    SessionClosePlan.JourneyOutcome.OFFLINE
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    // ── d. Limpiar la sesión ─────────────────────────────────────────────

    /**
     * Limpia token, usuario y marcas (UI y diagnóstico sin residual).
     * La identidad del equipo (Prefs.DEVICE, p. ej. "macias") y la clave
     * compartida NO se tocan: la persona cambia, el equipo sigue siendo el
     * mismo. Las filas no enviadas quedan en SQLite para el próximo drenado.
     */
    private fun clearSessionLocal(app: Context) {
        SessionStore.clear(app)
        Log.i(TAG, "sesión finalizada (cierre limpio)")
    }
}
