package com.dmujeres.traccar.capture

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.DmujeresApp
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.data.PendingPosition
import com.dmujeres.traccar.transport.Envelope
import com.google.android.gms.location.LocationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * L1: receptor de los fixes que el FLP entrega por PendingIntent (request con
 * batching registrado en [LocationEngine]). Es el camino de captura que NO
 * necesita el proceso ni un callback vivo: el broadcast despierta la app y
 * aquí se decide qué hacer con el lote.
 *
 * Contrato:
 * - Servicio vivo ([L1FixBridge.listener] seteado): el fix entra al MISMO
 *   pipeline que el callback del FLP (onNewLocation) y aquí NO se toca Room.
 * - Servicio muerto (listener null): el fix se persiste DIRECTO en la cola
 *   Room SIN arrancar TrackingService; al recuperarse la jornada el outbox lo
 *   drena como cualquier pendiente.
 * - Nunca lanza: todo va en runCatching + Log.w. Un crash en el receptor
 *   mataría el proceso y perdería el lote completo.
 */
class L1LocationReceiver : BroadcastReceiver() {

    /** Dedup del lote: el FLP puede re-entregar el mismo fix en batches solapados. */
    private val dedup = FixDedupPolicy()

    override fun onReceive(context: Context, intent: Intent) {
        val config = runCatching { AppConfig(context) }.getOrNull() ?: return
        // Guardas L1: captura habilitada y permiso fino real. Sin ellas, el
        // broadcast (posiblemente residual) no debe tocar nada.
        if (!config.l1PendingIntentEnabled) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val result = runCatching { LocationResult.extractResult(intent) }.getOrNull() ?: return
        // goAsync: el insert en Room es I/O y no puede correr en el thread del
        // broadcast; finish() en finally libera el wakelock del receptor.
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                result.locations.forEach { location ->
                    runCatching { handle(location, config, appContext) }
                        .onFailure { Log.w(TAG, "No se pudo procesar el fix L1", it) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Recepción L1 falló", e)
            } finally {
                runCatching { pendingResult.finish() }
            }
        }
    }

    /** Dedup + ruteo: servicio vivo entrega al pipeline; muerto, persiste aquí. */
    private suspend fun handle(location: Location, config: AppConfig, appContext: Context) {
        val elapsedNanos = runCatching { location.elapsedRealtimeNanos }.getOrDefault(0L)
        if (!dedup.accept(elapsedNanos, location.latitude, location.longitude)) {
            Log.d(TAG, "Fix L1 duplicado (elapsed=$elapsedNanos); se ignora")
            return
        }
        if (L1FixBridge.listener != null) {
            L1FixBridge.deliver(location)
            return
        }
        persistWithoutService(location, config, appContext)
    }

    /**
     * Servicio muerto: inserta el fix en Room sin arrancar TrackingService.
     * Filtros de calidad/antigüedad (storeAll apagado = estricto) y secuencia
     * durable reservada en la propia transacción de Room.
     */
    private suspend fun persistWithoutService(
        location: Location,
        config: AppConfig,
        appContext: Context,
    ) {
        val accuracy = location.accuracy.toDouble()
        // Techo común a ambos modos: un fix peor que 500 m no aporta geometría.
        if (!accuracy.isFinite() || accuracy > MAX_ACCURACY_M) {
            Log.d(TAG, "Fix L1 descartado por accuracy=$accuracy")
            return
        }
        val fixElapsedNanos = runCatching { location.elapsedRealtimeNanos }.getOrDefault(0L)
        // Modo estricto (storeAll apagado): además del techo, se descartan
        // fixes de más de 2 min. Reloj monotónico (inmune a saltos NTP);
        // elapsed 0 = desconocido y no se castiga.
        if (!config.storeAllEnabled && fixElapsedNanos > 0L &&
            SystemClock.elapsedRealtimeNanos() - fixElapsedNanos > MAX_FIX_AGE_NANOS
        ) {
            Log.d(TAG, "Fix L1 descartado por antigüedad")
            return
        }
        val dao = (appContext as DmujeresApp).database.positionDao()
        val sequence = dao.nextSequence(0L)
        val messageId = Envelope.newMessageId(config.username, sequence)
        val observedAt = Envelope.nowIso()
        // Mismo contrato que el servicio: la velocidad viaja en km/h.
        val speedKmh = if (location.hasSpeed()) {
            (location.speed.coerceAtLeast(0f) * 3.6).toDouble()
        } else {
            0.0
        }
        val payload = Envelope.buildPosition(
            messageId = messageId,
            deviceId = config.username,
            sequence = sequence,
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = accuracy,
            speed = speedKmh,
            bearing = if (location.hasBearing()) location.bearing.toDouble() else 0.0,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            observedAt = observedAt,
            pending = 0,
            battery = batteryLevel(appContext),
            network = "unknown",
            journeyId = config.journeyStartAt.takeIf { it > 0L },
        )
        dao.insert(
            PendingPosition(
                messageId = messageId,
                deviceId = config.username,
                sequence = sequence,
                payload = payload,
                observedAt = observedAt,
                journeyId = config.journeyStartAt,
            ),
        )
        Log.i(TAG, "Fix L1 persistido sin servicio (seq=$sequence acc=$accuracy)")
    }

    /** Batería best-effort con BatteryManager: -1 si no se puede leer. */
    private fun batteryLevel(context: Context): Int = runCatching {
        (context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .takeIf { it in 0..100 } ?: -1
    }.getOrDefault(-1)

    private companion object {
        const val TAG = "L1LocationReceiver"

        /** Techo de accuracy (m) para persistir sin servicio (ambos modos). */
        const val MAX_ACCURACY_M = 500.0

        /** Antigüedad máxima de un fix en modo estricto: 2 min. */
        const val MAX_FIX_AGE_NANOS = 2L * 60L * 1_000_000_000L
    }
}
