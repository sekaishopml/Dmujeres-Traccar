package com.dmujeres.traccar.health

import android.util.Log
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.data.HealthSnapshot
import com.dmujeres.traccar.data.HealthSnapshotDao
import com.dmujeres.traccar.core.MqttServerNormalizer
import com.dmujeres.traccar.oem.DeviceCapabilityProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.dmujeres.traccar.core.MobileProtocol

/**
 * FASE 7 (§21): sube los snapshots de salud locales al servidor por el canal
 * HTTP existente (misma autenticación X-Api-Key + X-Device-Id que posiciones).
 *
 * Honestidad del ACK: un snapshot solo se marca como subido cuando el servidor
 * responde 2xx. Rechazos permanentes del contrato (400/403/413) se marcan como
 * procesados para no reintentar en loop infinito (el servidor ya los juzgó);
 * 401/5xx/red se conservan para el próximo ciclo. Nunca se borra evidencia:
 * la retención local de 24 h la maneja el monitor.
 */
object HealthUploader {

    private const val TAG = "HealthUploader"

    /** Tope por lote (contrato server MAX_ITEMS=300; el móvil usa menos). */
    const val MAX_BATCH = 100

    data class Result(val httpStatus: Int, val uploaded: Int, val attempted: Int, val permanent: Boolean)

    /**
     * Cuerpo JSON del lote. Puro (org.json): testeable en JVM.
     * Sin coordenadas, sin PII, sin tokens.
     */
    fun buildBody(snapshots: List<HealthSnapshot>, profile: DeviceCapabilityProfile, appVersion: String): String {
        val device = JSONObject()
            .put("manufacturer", profile.manufacturer)
            .put("model", profile.model)
            .put("androidVersion", profile.androidVersion)
            .put("androidSdk", profile.sdk.toString())
            .put("rom", profile.rom)
            .put("standbyBucket", profile.standbyBucket)
            .put("backgroundRestricted", profile.backgroundRestricted)
            .put("batteryOptimized", !profile.batteryOptimizationExempt)
            .put("capabilityStatus", profile.status())
            .put("appVersion", appVersion)
        val array = JSONArray()
        snapshots.forEach { snapshot ->
            array.put(
                JSONObject()
                    .put("sessionId", snapshot.sessionId)
                    .put("wallMs", snapshot.wallMs)
                    .put("wallBucket", snapshot.wallBucket)
                    .put("elapsedMs", snapshot.elapsedMs)
                    .put("eventType", snapshot.eventType)
                    .put("reason", snapshot.reason)
                    .put("fgs", snapshot.fgs)
                    .put("motion", snapshot.motion)
                    .put("network", snapshot.network)
                    .put("outbox", snapshot.outbox)
                    .put("healthState", snapshot.healthState)
                    .put("funnel", snapshot.funnel),
            )
        }
        return JSONObject()
            .put("device", device)
            .put("snapshots", array)
            .toString()
    }

    /**
     * Sube pendientes y marca aceptados. `nowMs`/`profile` inyectables para
     * pruebas; en producción el llamador pasa los valores vivos.
     */
    suspend fun uploadPending(
        dao: HealthSnapshotDao,
        config: AppConfig,
        profile: DeviceCapabilityProfile,
        webBaseUrl: String = MqttServerNormalizer.webBase(config.serverUrl, MobileProtocol.WEB_PORT),
        apiKey: String = AppConfig.HTTP_API_KEY,
        nowMs: Long = System.currentTimeMillis(),
    ): Result = withContext(Dispatchers.IO) {
        val pending = runCatching { dao.unsent(MAX_BATCH) }.getOrDefault(emptyList())
        if (pending.isEmpty()) return@withContext Result(0, 0, 0, permanent = false)
        val body = buildBody(pending, profile, BuildConfig.VERSION_NAME)
        val connection = try {
            (URL("$webBaseUrl${MobileProtocol.PATH_HEALTH}").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("X-Api-Key", apiKey)
                setRequestProperty("X-Device-Id", config.deviceId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir la conexión de salud", e)
            return@withContext Result(0, 0, pending.size, permanent = false)
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            // Drena la respuesta (keep-alive) sin exponer cuerpos largos a logs.
            runCatching { connection.inputStream?.use { it.readBytes() } }
                .onFailure { runCatching { connection.errorStream?.use { s -> s.readBytes() } } }
            val permanent = status in 400..499 && status != 401 && status != 408 && status != 429
            if (status in 200..299 || permanent) {
                val ids = pending.map { it.id }
                runCatching { dao.markUploaded(ids, nowMs) }
                    .onFailure { Log.w(TAG, "No se pudo marcar salud subida", it) }
            }
            if (status == 401) {
                Log.w(TAG, "Salud rechazada por credencial (401): se conserva para reintento")
            }
            Result(status, if (status in 200..299 || permanent) pending.size else 0, pending.size, permanent)
        } catch (e: Exception) {
            Log.w(TAG, "Fallo subiendo salud (se reintenta en el próximo ciclo)", e)
            Result(0, 0, pending.size, permanent = false)
        } finally {
            runCatching { connection.disconnect() }
        }
    }
}
