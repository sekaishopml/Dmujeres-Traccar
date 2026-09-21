package com.dmujeres.traccar.recovery

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.tracking.TrackingService
import com.dmujeres.traccar.platform.Notifications
import com.dmujeres.traccar.recovery.TrackingRecoveryWorker

/**
 * Reinicia el tracking tras el arranque del teléfono o una actualización de la app,
 * solo si el usuario lo había dejado activado.
 *
 * R7 STUCK-STOP: si hay trackingEnabled==true Y journeyStopRequested==true
 * (crash a mitad de stop), se prefiere START (limpiar stopRequested y arrancar)
 * en vez de forzar stop; el stop real pone trackingEnabled=false y sigue
 * funcionando por la rama STOP.
 *
 * ARRANQUE PRIORITARIO: en ramas START se hace reintento en 3 niveles:
 * 1) TrackingService.start (foreground inmediato),
 * 2) WorkManager one-shot expedited inmediato (por si el proceso muere antes
 *    de consolidar el foreground),
 * 3) notificación persistente "jornada retomada" para que el usuario vea que
 *    la jornada sigue (el servicio la refresca al consolidar).
 * Si falta el permiso runtime FOREGROUND_SERVICE_LOCATION (Android 14+) se
 * degrada con aviso (Log + lastStartError + alerta) en vez de crashear.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action
            if (!BootIntents.isBootAction(action)) return
            val config = AppConfig(context)
            when (StuckStopPolicy.decideBoot(
                trackingEnabled = config.trackingEnabled,
                stopRequested = config.journeyStopRequested,
                journeyStartAt = config.journeyStartAt,
            )) {
                StuckStopPolicy.BootAction.START_PREFER_RECOVERY -> {
                    Log.i(TAG, "Boot: trackingEnabled+stopRequested (crash a mitad de stop), prefiero START (limpio stopRequested)")
                    config.journeyStopRequested = false
                    resumeTracking(context, config)
                }
                StuckStopPolicy.BootAction.STOP -> {
                    Log.i(TAG, "Boot: stop real pendiente (trackingEnabled=false), forzando stop")
                    runCatching { TrackingService.stop(context) }
                        .onFailure { Log.w(TAG, "No se pudo forzar stop en boot", it) }
                }
                StuckStopPolicy.BootAction.START -> {
                    Log.i(TAG, "Boot: tracking activo, arrancando servicio ($action)")
                    resumeTracking(context, config)
                }
                StuckStopPolicy.BootAction.NONE -> {
                    Log.i(TAG, "Boot: sin tracking ni stop pendiente, no hago nada")
                }
            }
        } catch (e: Exception) {
            runCatching {
                AppConfig(context).lastStartError = "Error al auto-iniciar: " + (e.message ?: e.javaClass.simpleName)
            }
            Log.e(TAG, "Error al auto-iniciar", e)
        }
    }

    private fun resumeTracking(context: Context, config: AppConfig) {
        // Red de seguridad Doze-proof: con jornada activa, programar el
        // SessionKeeper SIEMPRE, incluso antes de intentar arrancar el FGS.
        // Si el servicio no consolida (instalación/actualización con pantalla
        // apagada, OEM agresivo), la alarma cada 15 min reviva el proceso.
        runCatching { SessionKeeper.schedule(context) }
            .onFailure { Log.w(TAG, "No se pudo programar el SessionKeeper en boot", it) }
        // Android 14+: sin FOREGROUND_SERVICE_LOCATION el startForeground con
        // tipo location lanza SecurityException. Degradar con aviso, no crashear.
        if (isMissingForegroundLocationPermission(context)) {
            val msg = runCatching { context.getString(R.string.boot_fsl_missing) }
                .getOrDefault("Falta el permiso de ubicación en segundo plano")
            Log.w(TAG, "Boot: falta FOREGROUND_SERVICE_LOCATION, degrado con aviso")
            config.lastStartError = msg
            runCatching {
                Notifications.ensureChannel(context)
                Notifications.alert(
                    context,
                    context.getString(R.string.warning_title),
                    msg,
                )
            }.onFailure { Log.w(TAG, "No se pudo avisar FSL faltante", it) }
            // Se encola igual el one-shot: reintentará cuando se conceda el permiso.
            runCatching { TrackingRecoveryWorker.enqueueImmediate(context) }
                .onFailure { Log.w(TAG, "No se pudo encolar recovery inmediato", it) }
            return
        }
        try {
            val started = TrackingService.start(context)
            if (!started) {
                config.lastStartError =
                    "No se pudo arrancar el servicio al encender (revisa permisos)"
                Log.w(TAG, "Boot: TrackingService.start devolvió false")
            } else {
                runCatching { config.lastStartError = "" }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Boot: sin permiso para foreground location", e)
            config.lastStartError = runCatching { context.getString(R.string.boot_fsl_missing) }
                .getOrDefault("Sin permiso de ubicación en segundo plano")
            runCatching {
                Notifications.ensureChannel(context)
                Notifications.alert(
                    context,
                    context.getString(R.string.warning_title),
                    config.lastStartError,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot: no se pudo arrancar el servicio", e)
            config.lastStartError =
                "No se pudo arrancar el servicio al encender (revisa permisos)"
        }
        // Reintento: one-shot expedited inmediato además del periódico existente.
        runCatching { TrackingRecoveryWorker.enqueueImmediate(context) }
            .onFailure { Log.w(TAG, "No se pudo encolar recovery inmediato", it) }
        // Notificación persistente (ongoing) hasta que el servicio consolide.
        runCatching {
            Notifications.ensureChannel(context)
            Notifications.update(
                context,
                context.getString(R.string.journey_resumed_title),
                context.getString(R.string.journey_resumed_body),
            )
        }.onFailure { Log.w(TAG, "No se pudo mostrar aviso de jornada retomada", it) }
    }

    private fun isMissingForegroundLocationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return try {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo comprobar FOREGROUND_SERVICE_LOCATION", e)
            false
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}

/**
 * Acciones de arranque que deben retomar la jornada + chequeo puro del permiso
 * de foreground-location en Android 14. Sin dependencias Android (literales) para
 * poder probarse en JVM con `testDebugUnitTest`.
 */
object BootIntents {
    const val ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED"
    const val ACTION_MY_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"
    const val ACTION_USER_UNLOCKED = "android.intent.action.USER_UNLOCKED"

    /** Broadcast de arranque rápido en HTC/algunas ROMs (no existe en Intent). */
    const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

    val BOOT_ACTIONS: Set<String> = setOf(
        ACTION_BOOT_COMPLETED,
        ACTION_MY_PACKAGE_REPLACED,
        ACTION_USER_UNLOCKED,
        ACTION_QUICKBOOT_POWERON,
    )

    fun isBootAction(action: String?): Boolean = action in BOOT_ACTIONS

    /**
     * ¿Falta el permiso runtime de foreground-location? Solo aplica en API 34+.
     * @param sdkInt Build.VERSION.SDK_INT del dispositivo.
     * @param fslGranted si FOREGROUND_SERVICE_LOCATION ya está concedido.
     */
    fun missingForegroundLocation(sdkInt: Int, fslGranted: Boolean): Boolean =
        sdkInt >= 34 && !fslGranted
}
