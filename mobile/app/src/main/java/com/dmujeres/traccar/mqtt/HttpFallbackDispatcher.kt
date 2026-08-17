package com.dmujeres.traccar.mqtt

import android.util.Log
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.db.PositionDao
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Plan B de envío: cuando MQTT no está disponible, envía los pendientes por HTTPS al
 * endpoint de lotes del servidor (mismo envelope, misma idempotencia).
 */
object HttpFallbackDispatcher {

    private const val TAG = "HttpFallback"
    private const val BATCH_SIZE = 50

    /** Intenta vaciar la cola por HTTP. Devuelve cuántos mensajes se confirmaron. */
    suspend fun flush(dao: PositionDao, config: AppConfig): Int {
        val pending = try {
            dao.allOrdered(BATCH_SIZE)
        } catch (e: Exception) {
            return 0
        }
        if (pending.isEmpty()) return 0

        val baseUrl = webBase(config.serverUrl)
        val requestBody = JSONArray()
        pending.forEach { requestBody.put(JSONObject(it.payload)) }

        val connection = try {
            (URL("$baseUrl/api/mobile/v1/positions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Api-Key", AppConfig.HTTP_API_KEY)
                doOutput = true
                outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo conectar al fallback HTTP", e)
            return 0
        }

        return try {
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.readText() ?: ""
            connection.disconnect()
            if (code !in 200..299) {
                Log.w(TAG, "Fallback HTTP respondió $code")
                return 0
            }
            val results = JSONObject(body).optJSONArray("results") ?: return 0
            var confirmed = 0
            val pendingById = pending.associateBy { it.messageId }
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val messageId = item.optString("messageId")
                val status = item.optString("status")
                if (messageId.isBlank()) continue
                if (status == "accepted" || status == "duplicate" || status == "rejected"
                    || status == "invalid" || status == "expired"
                ) {
                    val deleted = dao.delete(messageId)
                    if (deleted == 0) continue
                    val pendingItem = pendingById[messageId]
                    val isCurrentPosition = pendingItem?.let {
                        it.journeyId == config.journeyStartAt &&
                            runCatching { JSONObject(it.payload).optString("type") == "position" }
                                .getOrDefault(false)
                    } == true
                    if ((status == "accepted" || status == "duplicate")
                        && config.journeyStartAt > 0L
                        && isCurrentPosition
                    ) {
                        config.recordJourneyConfirmed(pendingItem?.journeyId ?: 0L)
                    }
                    confirmed += 1
                }
            }
            confirmed
        } catch (e: Exception) {
            Log.w(TAG, "Fallback HTTP falló", e)
            0
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** Deriva la base web del servidor MQTT (mismo host, puerto web 8082). */
    fun webBase(serverUrl: String): String {
        val server = serverUrl.removePrefix("tcp://").removePrefix("mqtt://")
            .removePrefix("ssl://").removePrefix("mqtts://").substringBefore('/')
        val host = server.substringBefore(':')
        val uriHost = runCatching { URI(serverUrl).host }.getOrNull()
        val finalHost = if (!uriHost.isNullOrBlank()) uriHost else host
        return "http://$finalHost:${AppConfig.WEB_PORT}"
    }
}
