package org.traccar.client.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import org.traccar.client.DatabaseHelper
import org.traccar.client.DmujeresApi
import org.traccar.client.Prefs
import org.traccar.client.Position
import org.traccar.client.ProtocolFormatter
import org.traccar.client.RequestManager
import org.traccar.client.SendWakeLock
import org.traccar.client.SessionStore
import org.traccar.client.StatusActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min
import kotlin.random.Random

/**
 * Política de subida PURA (JVM, sin Android): clasificación HTTP y backoff.
 * Separada de la cola para poder probarla sin dispositivo.
 */
object UploadPolicy {

    /** Base del backoff exponencial: 5 s, ×2, tope 5 min. */
    const val BASE_BACKOFF_MS = 5_000L
    const val MAX_BACKOFF_MS = 5 * 60_000L

    /** Tamaño máximo del lote al endpoint nuevo. */
    const val MAX_BATCH = 50

    enum class HttpClass { CONFIRMED, RETRY, DEAD, PAUSED }

    /**
     * Clasificación del HTTP de la arquitectura (§8): 2xx confirmado,
     * 400/404/413 DEAD sin reintento, 401 pausa con aviso, 408/429/5xx retry.
     */
    fun classifyHttp(code: Int): HttpClass = when (code) {
        in 200..299 -> HttpClass.CONFIRMED
        400, 404, 413, 422 -> HttpClass.DEAD
        401, 403 -> HttpClass.PAUSED
        else -> HttpClass.RETRY
    }

    /** Error de red (timeout, sin conexión): siempre retry, nunca DEAD. */
    fun networkError(): HttpClass = HttpClass.RETRY

    /**
     * Backoff exponencial con jitter: base×2^fallos con tope, ±20 % aleatorio
     * para no sincronizar ráfagas de toda la flota contra el servidor.
     */
    fun backoffMs(failures: Int, jitter01: Double = Random.nextDouble()): Long {
        val grown = BASE_BACKOFF_MS shl min(failures, 10)
        val capped = min(grown, MAX_BACKOFF_MS)
        val jitter = 0.8 + 0.4 * jitter01.coerceIn(0.0, 1.0)
        return (capped * jitter).toLong().coerceAtLeast(BASE_BACKOFF_MS)
    }

    /** Resultado por evento del lote nuevo: accepted|duplicate|invalid|dead. */
    enum class EventResult { CONFIRMED, DEAD, UNKNOWN }

    fun classifyEvent(estado: String): EventResult = when (estado.lowercase()) {
        "accepted", "duplicate" -> EventResult.CONFIRMED
        "invalid", "dead" -> EventResult.DEAD
        else -> EventResult.UNKNOWN
    }

    /** Qué sigue tras procesar el `resultados` de un lote 2xx. */
    enum class BatchFollowUp { CONTINUE, BACKOFF }

    /**
     * Si el servidor confirmó o declaró algo (avance del cursor) se sigue en
     * serie con el siguiente lote; si NO confirmó nada (cuerpo vacío, estados
     * desconocidos o lote sin mención) se hace backoff: antes se seguía de
     * inmediato y se reenviaba el MISMO lote en bucle apretado contra el
     * servidor sin que el cursor avanzara.
     */
    fun followUpAfterBatch(confirmed: Int, dead: Int): BatchFollowUp =
        if (confirmed == 0 && dead == 0) BatchFollowUp.BACKOFF else BatchFollowUp.CONTINUE

    /**
     * Empareja cada entrada de `resultados` con UNA fila del lote por `seq`,
     * en orden: cada confirmación consume una sola fila.
     *
     * Hallazgo: con `associateBy` dos filas con la misma secuencia (carrera de
     * identidad) colapsaban en una sola clave: la primera fila nunca se
     * confirmaba y se reenviaba para siempre. Con colas de consumo, cada
     * mención del servidor avanza el cursor exactamente una fila; lo no
     * mencionado queda para el próximo ciclo (nunca se borra a ciegas).
     *
     * @param batch pares (rowId, seq) del lote enviado.
     * @param resultados pares (seq, estado) tal como vinieron del servidor.
     */
    data class BatchMatch(val confirmedIds: List<Long>, val deadIds: List<Long>)

    fun matchBatchResults(
        batch: List<Pair<Long, Long>>,
        resultados: List<Pair<Long, String>>,
    ): BatchMatch {
        val pending = batch.groupBy({ it.second }, { it.first })
            .mapValues { ArrayDeque(it.value) }
        val confirmed = ArrayList<Long>()
        val dead = ArrayList<Long>()
        for ((seq, estado) in resultados) {
            val row = pending[seq]?.removeFirstOrNull() ?: continue
            when (classifyEvent(estado)) {
                EventResult.CONFIRMED -> confirmed.add(row)
                EventResult.DEAD -> dead.add(row)
                EventResult.UNKNOWN -> Unit // se reintenta: no se consume
            }
        }
        return BatchMatch(confirmed, dead)
    }
}

/**
 * Cola de subida SERIAL (`read → send → ack → delete → read`, sin carreras).
 *
 * Por qué clase nueva en vez de más código en TrackingController: el
 * controlador ya mezcla captura, sensores y red; la cola es el componente SYNC
 * con su propio estado (backoff, pausa por 401, soporte de lote) y sus
 * propios tests. El controlador solo la patea (`kick()`) y recibe avisos.
 *
 * Lote nuevo `POST /api/mobile/v1/positions` (≤50) con fallback automático al
 * OsmAnd 1×1 si el servidor aún no lo tiene (404 del endpoint): así la APK
 * funciona contra ambas versiones del servidor sin flag remoto.
 */
class UploadQueue(
    context: Context,
    private val databaseHelper: DatabaseHelper,
    private val listener: Listener,
) {

    interface Listener {
        /** Un drenado confirmó eventos: la cola fluye (cierra RECOVERING). */
        fun onQueueFlowing(confirmed: Int)

        /** Eventos declarados DEAD (no reintentados, ya reportados). */
        fun onEventsDead(count: Int)

        /** 401: clave móvil inválida, cola pausada hasta aviso contrario. */
        fun onAuthPaused()
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val wakeLockEnabled: Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(Prefs.WAKELOCK, true)

    @Volatile
    private var busy = false

    @Volatile
    private var pausedAuth = false

    /**
     * Foto del material de auth al pausar (para reanudar solo cuando CAMBIE:
     * entrar de nuevo o corregir la clave; sin cambio no se reintenta el
     * mismo 401 en bucle).
     */
    @Volatile
    private var pausedToken = ""

    @Volatile
    private var pausedApiKey = ""

    @Volatile
    private var batchSupported = true

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var lastConfirmedAtMs = 0L

    /**
     * Último estado de red que reportó el controlador (la cola no observa la
     * red directamente: eso es NetworkManager vía TrackingController).
     */
    @Volatile
    private var lastOnline = true

    /** ¿Fluye la cola? (confirmó algo en los últimos 5 min). */
    fun isFlowing(nowMs: Long = System.currentTimeMillis()): Boolean =
        lastConfirmedAtMs > 0L && nowMs - lastConfirmedAtMs <= FLOWING_WINDOW_MS

    /** Patea la cola (fix nuevo, red de vuelta, refresco manual, rescate). */
    fun kick(online: Boolean) {
        lastOnline = online
        if (busy) return
        if (pausedAuth) {
            // Reanudación automática solo si el material de auth cambió desde
            // la pausa (login nuevo o clave corregida): sin cambio se sigue
            // pausado en vez de reintentar el mismo 401 en bucle.
            if (UploadAuthPolicy.shouldResumeAfterAuthChange(
                    pausedToken, pausedApiKey,
                    currentToken(), currentApiKey(),
                )
            ) {
                resume()
                Log.i(TAG, "auth cambió desde la pausa: cola reanudada")
            } else {
                return
            }
        }
        if (!online) return
        busy = true
        Thread { step() }.start()
    }

    /** Reanuda tras una pausa por 401 (el usuario ya corrigió la clave). */
    fun resume() {
        pausedAuth = false
        pausedToken = ""
        pausedApiKey = ""
        consecutiveFailures = 0
    }

    private fun currentToken(): String = SessionStore.token(appContext)

    private fun currentApiKey(): String = DmujeresApi.apiKey(appContext)

    private fun step() {
        try {
            val batch = runCatching { databaseHelper.selectPositions(UploadPolicy.MAX_BATCH) }
                .getOrDefault(emptyList())
                .filter { it.status != org.traccar.client.STATUS_DEAD }
            if (batch.isEmpty()) {
                finishOk(0)
                return
            }
            runCatching { databaseHelper.addAttempts(batch.map { it.id }) }
            if (batchSupported) {
                sendBatch(batch)
            } else {
                sendLegacyOneByOne(batch)
            }
        } catch (e: Exception) {
            Log.w(TAG, "paso de cola falló", e)
            scheduleRetry()
        }
    }

    // --- Lote nuevo ----------------------------------------------------------

    private fun sendBatch(batch: List<Position>) {
        val deviceId = deviceId()
        if (deviceId.isBlank()) {
            finishIdle()
            return
        }
        val url = DmujeresApi.webBase(appContext) + PATH_POSITIONS
        val body = JSONObject().put("eventos", JSONArray().also { array ->
            for (position in batch) array.put(eventJson(position))
        })
        if (wakeLockEnabled) SendWakeLock.acquire(appContext)
        val (code, response, hadToken) = try {
            postJson(url, deviceId, body)
        } catch (e: Exception) {
            Log.w(TAG, "lote: sin red", e)
            if (wakeLockEnabled) SendWakeLock.release()
            scheduleRetry()
            return
        }
        if (wakeLockEnabled) SendWakeLock.release()
        when (UploadPolicy.classifyHttp(code)) {
            UploadPolicy.HttpClass.CONFIRMED -> applyBatchResult(batch, response)
            UploadPolicy.HttpClass.DEAD -> {
                if (code == 404) {
                    // El servidor aún no tiene el endpoint: fallback al 1×1.
                    batchSupported = false
                    Log.i(TAG, "lote no soportado (404): fallback a OsmAnd 1×1")
                    busy = false
                    sendLegacyOneByOne(batch)
                } else {
                    Log.w(TAG, "lote DEAD ($code): no se reintenta")
                    runCatching { databaseHelper.markDead(batch.map { it.id }) }
                    listenerSafe { listener.onEventsDead(batch.size) }
                    finishOk(0)
                    kickNext()
                }
            }
            UploadPolicy.HttpClass.PAUSED -> {
                if (!UploadAuthPolicy.shouldLatchPause(code, hadToken)) {
                    // 401 con token = token muerto: se limpia la sesión y se
                    // reintenta UNA vez con la clave compartida (la app opera
                    // con ella hasta el próximo login). Si esa también falla,
                    // la siguiente vuelta pausa de verdad. Nunca se borra dato.
                    DmujeresApi.noteHttpResult(appContext, code, hadToken = true)
                    consecutiveFailures = 0
                    finishIdle()
                    kickNext()
                } else {
                    // Clave compartida inválida o 403: pausa latcheada con foto
                    // del material para reanudar solo cuando cambie (o con
                    // refresco manual). Sin foto, entrar de nuevo no reanudaba
                    // y la subida quedaba muerta toda la jornada.
                    pausedAuth = true
                    pausedToken = currentToken()
                    pausedApiKey = currentApiKey()
                    if (hadToken) {
                        // Token revocado o usuario deshabilitado: limpiar la sesión
                        // y pedir login de nuevo, sin reintentar en bucle.
                        DmujeresApi.noteHttpResult(appContext, code, hadToken = true)
                    } else {
                        Log.w(TAG, "cola pausada por 401: clave móvil inválida")
                        StatusActivity.addMessage("Clave móvil inválida (401): subida pausada")
                    }
                    listenerSafe { listener.onAuthPaused() }
                    finishIdle()
                }
            }
            UploadPolicy.HttpClass.RETRY -> scheduleRetry()
        }
    }

    private fun applyBatchResult(batch: List<Position>, response: String) {
        // Sin parseo no se borra: reintentar es seguro por idempotencia
        // (boot_id, secuencia), pero borrar a ciegas perdería datos.
        val batchPairs = batch.map { it.id to it.localSequence }
        val match: UploadPolicy.BatchMatch
        try {
            val resultados = JSONObject(response).getJSONArray("resultados")
            val entries = ArrayList<Pair<Long, String>>(resultados.length())
            for (i in 0 until resultados.length()) {
                val item = resultados.getJSONObject(i)
                entries.add(item.optLong("seq") to item.optString("estado"))
            }
            // Emparejado 1-a-1 por seq en orden (ver UploadPolicy): cada
            // mención avanza el cursor una fila; lo no mencionado se reintenta.
            match = UploadPolicy.matchBatchResults(batchPairs, entries)
        } catch (e: Exception) {
            Log.w(TAG, "lote 2xx sin cuerpo válido: se reintenta", e)
            scheduleRetry()
            return
        }
        // Sin mención = no confirmado (no se borra lo que el servidor no vio).
        runCatching { databaseHelper.deletePositions(match.confirmedIds) }
        if (match.deadIds.isNotEmpty()) {
            runCatching { databaseHelper.markDead(match.deadIds) }
            listenerSafe { listener.onEventsDead(match.deadIds.size) }
        }
        consecutiveFailures = 0
        if (match.confirmedIds.isNotEmpty()) {
            lastConfirmedAtMs = System.currentTimeMillis()
            listenerSafe { listener.onQueueFlowing(match.confirmedIds.size) }
        }
        if (UploadPolicy.followUpAfterBatch(match.confirmedIds.size, match.deadIds.size) ==
            UploadPolicy.BatchFollowUp.BACKOFF
        ) {
            // 2xx sin avance del cursor (vacío o estados desconocidos):
            // backoff en vez de reenviar el mismo lote en bucle apretado.
            scheduleRetry()
            return
        }
        finishOk(match.confirmedIds.size)
        // Serie estricta: si queda más, sigue en el mismo impulso.
        kickNext()
    }

    // --- Fallback OsmAnd 1×1 ---------------------------------------------------

    /**
     * Camino legacy contra servidores sin el endpoint nuevo: un fix por
     * petición, en serie. No clasifica HTTP (limitación heredada del
     * RequestManager booleano): éxito borra, fallo reintenta con backoff.
     */
    private fun sendLegacyOneByOne(batch: List<Position>) {
        val url = PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(Prefs.URL, "").orEmpty()
        val confirmed = ArrayList<Long>()
        var failed = false
        for (position in batch) {
            if (wakeLockEnabled) SendWakeLock.acquire(appContext)
            val ok = try {
                RequestManager.sendRequest(ProtocolFormatter.formatRequest(url, position))
            } catch (e: Exception) {
                false
            } finally {
                if (wakeLockEnabled) SendWakeLock.release()
            }
            if (ok) {
                confirmed.add(position.id)
            } else {
                failed = true
                break // serie estricta: al primer fallo se para, no se salta
            }
        }
        runCatching { databaseHelper.deletePositions(confirmed) }
        if (confirmed.isNotEmpty()) {
            consecutiveFailures = 0
            lastConfirmedAtMs = System.currentTimeMillis()
            listenerSafe { listener.onQueueFlowing(confirmed.size) }
        }
        finishOk(confirmed.size)
        if (failed) {
            scheduleRetry()
        } else {
            kickNext()
        }
    }

    // --- Utilidades ------------------------------------------------------------

    /** Serie estricta: al terminar un lote, sigue con el siguiente si hay. */
    private fun kickNext() {
        handler.post { kick(lastOnline) }
    }

    /**
     * Los avisos al controlador nunca pueden tumbar el paso de la cola: un
     * listener que lance (p. ej. UI tocada desde el hilo de subida) se
     * registra y el cursor ya avanzó (los borrados van antes). Sin esta
     * guarda, `busy` quedaba en true y la cola moría hasta reiniciar.
     */
    private inline fun listenerSafe(block: () -> Unit) {
        runCatching(block).onFailure { Log.w(TAG, "aviso al controlador falló", it) }
    }

    private fun finishOk(@Suppress("UNUSED_PARAMETER") confirmed: Int) {
        busy = false
    }

    private fun finishIdle() {
        busy = false
    }

    private fun scheduleRetry() {
        busy = false
        val delay = UploadPolicy.backoffMs(consecutiveFailures++)
        Log.i(TAG, "reintento en ${delay}ms")
        handler.postDelayed({ kick(lastOnline) }, delay)
    }

    private fun deviceId(): String =
        PreferenceManager.getDefaultSharedPreferences(appContext)
            .getString(Prefs.DEVICE, "").orEmpty().trim().lowercase()

    @Throws(Exception::class)
    private fun postJson(url: String, deviceId: String, body: JSONObject): Triple<Int, String, Boolean> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            val hadToken = DmujeresApi.setAuthHeaders(connection, appContext)
            connection.setRequestProperty("X-Device-Id", deviceId)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
            return Triple(code, text, hadToken)
        } finally {
            connection.disconnect()
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

    companion object {
        private val TAG = UploadQueue::class.java.simpleName
        const val PATH_POSITIONS = "/api/mobile/v1/positions"

        /** Ventana para considerar que la cola fluye. */
        const val FLOWING_WINDOW_MS = 5 * 60_000L
    }
}
