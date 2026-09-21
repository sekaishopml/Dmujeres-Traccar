package com.dmujeres.traccar.tracking

import com.dmujeres.traccar.core.TrackingState

/**
 * Política pura (JVM) de alertas por transición de estado: caracteriza la
 * decisión exacta que hoy vive en `TrackingService.setState` (FASE 2:
 * congelarla antes de extraer el controlador de notificaciones).
 *
 * Reglas:
 * - Nunca alertar por TRACKING_ACTIVE, TRACKING_DISABLED_BY_USER ni SERVICE_RECOVERY.
 * - NETWORK_OFFLINE/MQTT_DISCONNECTED/SERVER_UNAVAILABLE/BATTERY_LOW tienen su
 *   propia alerta operativa con retardo (maybeNotifyOperationalAlerts).
 * - GPS_DISABLED y PENDING_ACK_TIMEOUT son "silenciosos": la notificación
 *   persistente ya los muestra y sonar cada cambio era spam.
 * - Dedupe: la misma alerta no se repite si es el mismo estado alertado.
 */
object NotificationStatePolicy {

    fun isDelayedAlert(state: TrackingState): Boolean = when (state) {
        TrackingState.NETWORK_OFFLINE,
        TrackingState.MQTT_DISCONNECTED,
        TrackingState.SERVER_UNAVAILABLE,
        TrackingState.BATTERY_LOW,
        -> true
        else -> false
    }

    fun isSilent(state: TrackingState): Boolean = when (state) {
        TrackingState.NO_FRESH_FIX,
        TrackingState.GPS_FALLBACK,
        TrackingState.PENDING_ACK_TIMEOUT,
        -> true
        else -> false
    }

    fun shouldAlertOnTransition(state: TrackingState, lastAlertedState: TrackingState?): Boolean {
        if (state == TrackingState.TRACKING_ACTIVE ||
            state == TrackingState.TRACKING_DISABLED_BY_USER ||
            state == TrackingState.SERVICE_RECOVERY
        ) {
            return false
        }
        if (isDelayedAlert(state) || isSilent(state)) return false
        return state != lastAlertedState
    }
}
