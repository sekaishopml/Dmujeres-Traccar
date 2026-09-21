package com.dmujeres.traccar.tracking

import com.dmujeres.traccar.core.TrackingState

import android.app.Service
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.util.Log
import androidx.core.app.ServiceCompat
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.core.MqttStatus
import com.dmujeres.traccar.diagnostics.DiagnosticsReporter
import com.dmujeres.traccar.core.JourneyFormatter
import com.dmujeres.traccar.platform.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FASE 3 (§3): controlador de notificaciones/foreground extraído de
 * `TrackingService` SIN cambio de comportamiento:
 * - reclamo/re-afirmación del foreground (anti ForegroundServiceDidNotStartInTime
 *   y anti-demote OEM),
 * - publicación de estado y alertas por transición ([NotificationStatePolicy]),
 * - refresco de la notificación persistente y del widget.
 *
 * No decide tracking: es el plano de presentación/estado del plano de health.
 */
class TrackingNotificationController(
    private val service: Service,
    private val config: AppConfig,
    private val scopeProvider: () -> CoroutineScope,
    private val pendingCount: suspend () -> Int,
    private val journeyElapsedMs: () -> Long,
    private val batteryLevel: () -> Int,
    private val isStarted: () -> Boolean,
    private val isStopping: () -> Boolean,
    /** Transición de estado publicada (observabilidad de salud, nunca tracking). */
    private val onStateTransition: (TrackingState) -> Unit = {},
    /** Refresco del widget (presentación inyectada por el borde; evita tracking→ui). */
    private val onRefreshWidget: () -> Unit = {},
) {

    /** El foreground ya fue reclamado (startForeground OK) en esta corrida. */
    @Volatile var foregroundClaimed = false
        private set

    @Volatile var currentState = TrackingState.TRACKING_DISABLED_BY_USER
        private set

    /**
     * Último estado POR EL QUE SE ALERTÓ (distinto de currentState): una alerta
     * solo sale si el estado alertable CAMBIÓ respecto a la última alerta, no
     * en cada ciclo de refresh ni en flaps A→recovery→A.
     */
    @Volatile private var lastAlertedState: TrackingState? = null

    /** Primera re-afirmación por corrida se loguea en I; las demás en DEBUG. */
    @Volatile private var foregroundReassertLogged = false

    private val strings: Resources get() = service.resources

    /**
     * Reclama el foreground con la notificación mínima ("Iniciando
     * seguimiento…") idempotente. Devuelve null OK / el Throwable del fallo
     * (SecurityException en Android 14 sin FOREGROUND_SERVICE_LOCATION, u otro).
     */
    fun claimForeground(): Throwable? {
        if (foregroundClaimed) return null
        return try {
            ServiceCompat.startForeground(
                service,
                Notifications.NOTIFICATION_ID,
                Notifications.foregroundNotification(
                    service,
                    strings.getString(R.string.app_name),
                    strings.getString(R.string.tracking_starting),
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            foregroundClaimed = true
            null
        } catch (e: Exception) {
            e
        }
    }

    /** Libera el reclamo (quita la notificación ongoing del id 1, también la de boot). */
    fun releaseForeground() {
        foregroundClaimed = false
        runCatching { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
    }

    /**
     * Re-afirma el estado foreground (mismo id/canal/tipo): los OEM pueden
     * demotear el FGS a types=0 con pantalla apagada y no hay getter público
     * para detectarlo, así que se re-promueve de forma barata e idempotente en
     * cada señal (screen_on, fix aceptado). El fallo no es fatal.
     */
    fun reassertForeground(reason: String, logAlways: Boolean = false) {
        if (!config.trackingEnabled || !isStarted() || isStopping()) return
        val tag = TAG
        runCatching {
            ServiceCompat.startForeground(
                service,
                Notifications.NOTIFICATION_ID,
                Notifications.foregroundNotification(
                    service,
                    strings.getString(R.string.app_name),
                    TrackingState.fromName(config.trackingState).label,
                ),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
            foregroundClaimed = true
        }.onSuccess {
            if (logAlways || !foregroundReassertLogged) {
                foregroundReassertLogged = true
                Log.i(tag, "Foreground re-afirmado ($reason)")
            } else {
                Log.d(tag, "Foreground re-afirmado ($reason)")
            }
        }.onFailure {
            if (logAlways || !foregroundReassertLogged) {
                foregroundReassertLogged = true
                Log.w(tag, "No se pudo re-afirmar el foreground ($reason)", it)
            } else {
                Log.d(tag, "Re-afirmación de foreground falló ($reason): ${it.message}")
            }
        }
    }

    fun publishState(state: TrackingState) {
        currentState = state
        config.trackingState = state.name
    }

    fun setState(state: TrackingState) {
        if (currentState == state) {
            config.trackingState = state.name
            return
        }
        val previous = currentState
        publishState(state)
        if (state != previous) {
            runCatching { onStateTransition(state) }
        }
        if (NotificationStatePolicy.shouldAlertOnTransition(state, lastAlertedState)) {
            lastAlertedState = state
            Notifications.alert(service, strings.getString(R.string.warning_title), state.label)
        }
        // "Todo en orden" sin notificación: el estado se ve en la persistente y
        // en el banner; sonar cada regreso a activo era spam silencioso-útil.
        if (state == TrackingState.TRACKING_ACTIVE && previous != TrackingState.TRACKING_ACTIVE) {
            lastAlertedState = TrackingState.TRACKING_ACTIVE
        }
        refreshStateAndNotify()
    }

    fun refreshStateAndNotify() {
        scopeProvider().launch {
            val pending = withContext(Dispatchers.IO) {
                runCatching { pendingCount() }.getOrDefault(0)
            }
            // Piggyback diagnóstico: con jornada activa, reporte periódico cada
            // 60 min (se dispara desde la ruta por-fix y desde los cambios de
            // estado, que también llaman aquí). El throttle real lo aplica el
            // reporter; aquí solo el umbral horario.
            runCatching {
                if (isStarted() && !isStopping() &&
                    System.currentTimeMillis() - config.diagnosticsLastReportAt > DiagnosticsReporter.PERIODIC_MS
                ) {
                    DiagnosticsReporter.report(service, "periodic", pending)
                }
            }
            val battery = batteryLevel()
            val state = TrackingState.fromName(config.trackingState)

            val journeyStart = config.journeyStartAt
            val journeyLine = if (config.trackingEnabled && journeyStart > 0) {
                // Reloj del servicio (monotónico), no wall bruto: un paso NTP no
                // colapsa ni infla la duración de la notificación.
                val (hours, minutes) = JourneyFormatter.durationParts(journeyElapsedMs())
                strings.getString(R.string.notif_journey_duration, hours, minutes)
            } else {
                strings.getString(R.string.notif_journey_finished)
            }

            val details = mutableListOf<String>()
            details += strings.getString(R.string.notif_state, state.label)
            details += MqttStatus.status
            if (battery in 0..100) {
                details += strings.getString(R.string.notif_battery, battery)
            }

            val lines = mutableListOf<String>(journeyLine)
            lines += details.joinToString(" · ")
            if (battery in 1..20) {
                lines += strings.getString(R.string.notif_warn_battery)
            }
            val lastFix = config.lastFixAt
            if (lastFix > 0 && System.currentTimeMillis() - lastFix > 5 * 60_000) {
                lines += strings.getString(R.string.notif_warn_gps)
            }
            Notifications.update(service, strings.getString(R.string.app_name), lines.joinToString("\n"))
            // Push del widget de inicio/fin de jornada: mismo ciclo que la notificación.
            runCatching { onRefreshWidget() }
        }
    }

    /** Refresca el widget de jornada fuera del ciclo de notificación (p. ej. al apagar). */
    fun refreshWidget() {
        runCatching { onRefreshWidget() }
    }

    /** Providers del ciclo de vida inyectados por el servicio (evita acoplamiento). */

    private companion object {
        const val TAG = "TrackingNotifications"
    }
}
