package com.dmujeres.traccar.recovery

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.data.StopDrainPolicy
import com.dmujeres.traccar.DmujeresApp
import com.dmujeres.traccar.R
import com.dmujeres.traccar.tracking.TrackingService
import com.dmujeres.traccar.core.MqttServerNormalizer
import com.dmujeres.traccar.outbox.PositionOutboxDispatcher
import com.dmujeres.traccar.recovery.StuckStopPolicy
import com.dmujeres.traccar.platform.Notifications
import com.dmujeres.traccar.platform.UpdateChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import com.dmujeres.traccar.core.MobileProtocol

/**
 * Red de seguridad: recupera el servicio activo y drena posiciones pendientes después de cerrar
 * una jornada, sin perder el outbox si la app ya no está abierta.
 *
 * R7 STUCK-STOP: si hay trackingEnabled==true Y journeyStopRequested==true
 * (crash a mitad de stop), se prefiere START (limpiar stopRequested y arrancar)
 * en vez de forzar stop; el stop real pone trackingEnabled=false y sigue
 * funcionando por la rama STOP/DRAIN.
 *
 * SEGUNDO PLANO BLINDADO: el periódico NO exige REQUIRED_NETWORK (mataría el
 * drenaje offline: el outbox debe drenar por HTTP/MQTT cuando vuelva red, no
 * solo cuando WorkManager crea que hay red). En reconexión se encola un
 * one-shot expedited inmediato ([enqueueReconnect]) con backoff exponencial.
 */
class TrackingRecoveryWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = AppConfig(applicationContext)
        when (StuckStopPolicy.decideRecovery(
            trackingEnabled = config.trackingEnabled,
            stopRequested = config.journeyStopRequested,
            journeyStartAt = config.journeyStartAt,
            isRunning = TrackingService.isRunning,
        )) {
            StuckStopPolicy.RecoveryAction.START_PREFER_RECOVERY -> {
                Log.i(TAG, "Recovery: trackingEnabled+stopRequested (crash a mitad de stop), prefiero START (limpio stopRequested)")
                config.journeyStopRequested = false
                if (!TrackingService.isRunning) {
                    val started = runCatching { TrackingService.start(applicationContext) }.getOrDefault(false)
                    if (!started) {
                        Log.e(TAG, "No se pudo reactivar el servicio")
                        config.lastStartError = "La red de seguridad no pudo reactivar el servicio"
                        runCatching { Notifications.resumeRequired(applicationContext) }
                    }
                }
            }
            StuckStopPolicy.RecoveryAction.START -> {
                Log.i(TAG, "Recovery: tracking activo sin servicio, reactivando")
                val started = runCatching { TrackingService.start(applicationContext) }.getOrDefault(false)
                if (!started) {
                    Log.e(TAG, "No se pudo reactivar el servicio")
                    config.lastStartError = "La red de seguridad no pudo reactivar el servicio"
                    runCatching { Notifications.resumeRequired(applicationContext) }
                }
            }
            StuckStopPolicy.RecoveryAction.STOP -> {
                Log.i(TAG, "Recovery: stop real pendiente (trackingEnabled=false), forzando stop")
                runCatching { TrackingService.stop(applicationContext) }
            }
            StuckStopPolicy.RecoveryAction.NONE -> {
                Log.i(TAG, "Recovery: sin acción (tracking=${config.trackingEnabled} running=${TrackingService.isRunning})")
            }
        }
        if (!config.trackingEnabled && !TrackingService.isRunning) {
            val dao = (applicationContext as DmujeresApp).database.positionDao()
            drainPending(dao, config)
            val remaining = withContext(Dispatchers.IO) { dao.count() }
            if (remaining == 0) {
                config.journeyStopRequested = false
            }
        }
        UpdateChecker.checkAndRefreshBadge(applicationContext)
        return Result.success()
    }

    /**
     * Necesario para el one-shot expedited ([immediateRequest]): en API 31+ el
     * sistema exige ForegroundInfo o el expedited falla. Usa la notificación
     * persistente de jornada retomada.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        Notifications.ensureChannel(applicationContext)
        val notification = Notifications.foregroundNotification(
            applicationContext,
            applicationContext.getString(R.string.journey_resumed_title),
            applicationContext.getString(R.string.journey_resumed_body),
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Notifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            ForegroundInfo(Notifications.NOTIFICATION_ID, notification)
        }
    }

    private suspend fun drainPending(dao: com.dmujeres.traccar.data.PositionDao, config: AppConfig) {
        // Igual que el flush de cierre: drenar por HTTP (propietario único de
        // posiciones) hasta vaciar o hasta el deadline, sin abortar al primer
        // intento fallido (la red puede volver a mitad). Lo no drenado sigue en
        // Room para el próximo ciclo; nunca se descarta (lo terminal va a
        // cuarentena, no a borrado).
        val ctx = PositionOutboxDispatcher.DispatchContext(
            webBaseUrl = MqttServerNormalizer.webBase(config.serverUrl, MobileProtocol.WEB_PORT),
            apiKey = AppConfig.HTTP_API_KEY,
            journeyStartAt = config.journeyStartAt,
            onConfirmedPosition = { item ->
                if (PositionOutboxDispatcher.isCurrentJourneyPosition(config.journeyStartAt, item)) {
                    runCatching { config.recordJourneyConfirmed(item.journeyId) }
                }
            },
            onQuarantined = { runCatching { config.incQuarantinedTotal() } },
        )
        val deadline = System.currentTimeMillis() + StopDrainPolicy.TIMEOUT_MS
        while (!StopDrainPolicy.timedOut(System.currentTimeMillis(), deadline)) {
            val remaining = withContext(Dispatchers.IO) { dao.count() }
            if (remaining == 0) return
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    PositionOutboxDispatcher.flushOnce(
                        dao, PositionOutboxDispatcher.HttpTransport, ctx, includePresence = true,
                    )
                }
            }.getOrElse { error ->
                Log.w("TrackingRecoveryWorker", "No se pudo drenar el outbox", error)
                PositionOutboxDispatcher.FlushOutcome(0, 0, 0, transportOk = false)
            }
            if (!outcome.transportOk && outcome.confirmed == 0 && outcome.quarantined == 0) {
                Log.d("TrackingRecoveryWorker", "drain detenido sin transporte; Room intacto")
            }
            delay(StopDrainPolicy.retryDelayAfter(outcome.confirmed + outcome.quarantined))
        }
    }

    companion object {
        const val UNIQUE_NAME = "tracking-recovery"
        const val STARTUP_NAME = "tracking-recovery-startup"

        /** One-shot inmediato tras BOOT (arranque prioritario). */
        const val BOOT_NAME = "tracking-recovery-boot"

        /** One-shot inmediato al volver la red (reconexión). */
        const val RECONNECT_NAME = "tracking-recovery-reconnect"
        private const val TAG = "TrackingRecoveryWorker"

        /**
         * Periódico de seguridad cada 15 min SIN RequiredNetwork (el drenaje
         * offline no debe esperar a que WorkManager vea red) + backoff
         * exponencial de 10 min ante reintentos.
         */
        fun periodicRequest(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<TrackingRecoveryWorker>(
                RecoverySchedule.PERIODIC_MINUTES,
                TimeUnit.MINUTES,
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                RecoverySchedule.BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            ).build()

        /**
         * One-shot expedited inmediato (boot + reconexión). Con
         * RUN_AS_NON_EXPEDITED si no hay cuota: igual corre, sin crashear.
         */
        fun immediateRequest(): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<TrackingRecoveryWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    RecoverySchedule.BACKOFF_MINUTES,
                    TimeUnit.MINUTES,
                ).build()

        /** Encola el one-shot de arranque (BootReceiver). No lanza: devuelve null si falla. */
        fun enqueueImmediate(context: Context): UUID? = runCatching {
            val request = immediateRequest()
            WorkManager.getInstance(context).enqueueUniqueWork(
                BOOT_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            request.id
        }.getOrNull()

        /** Encola el one-shot de reconexión (DmujeresApp al volver red). No lanza. */
        fun enqueueReconnect(context: Context): UUID? = runCatching {
            val request = immediateRequest()
            WorkManager.getInstance(context).enqueueUniqueWork(
                RECONNECT_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            request.id
        }.getOrNull()
    }
}

/**
 * Constantes y decisiones puras de la red de seguridad (JVM, sin Android) para
 * `testDebugUnitTest`. Documenta por qué NO se usa REQUIRED_NETWORK.
 */
object RecoverySchedule {
    /** Periódico cada 15 min (mínimo de WorkManager). */
    const val PERIODIC_MINUTES = 15L

    /** Backoff exponencial entre reintentos del worker. */
    const val BACKOFF_MINUTES = 10L

    /**
     * El periódico NO debe exigir red: con jornada activa y sin cobertura el
     * outbox (Room) debe seguir drenando por HTTP/MQTT en cuanto vuelva la red,
     * y el worker debe poder reactivar el servicio también sin red (GPS sigue
     * capturando offline). Exigir REQUIRED_NETWORK mataría ese drenaje.
     */
    fun requiresNetwork(): Boolean = false

    /** Solo tiene sentido el one-shot inmediato si la jornada sigue activa. */
    fun shouldEnqueueImmediate(trackingEnabled: Boolean): Boolean = trackingEnabled

    /** Al volver la red se reintenta drenaje + re-registro GPS aunque haya red. */
    fun shouldEnqueueOnReconnect(trackingEnabled: Boolean): Boolean = trackingEnabled
}
