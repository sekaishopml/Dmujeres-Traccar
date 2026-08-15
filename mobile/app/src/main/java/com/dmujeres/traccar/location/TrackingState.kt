package com.dmujeres.traccar.location

/**
 * Estados diferenciados del watchdog (requisito del proyecto). El usuario puede desactivar
 * el tracking voluntariamente; TRACKING_DISABLED_BY_USER es un estado persistente.
 */
enum class TrackingState(val label: String) {
    TRACKING_ACTIVE("Tracking activo"),
    GPS_DISABLED("GPS apagado o sin señal"),
    NETWORK_OFFLINE("Sin conexión a Internet"),
    MQTT_DISCONNECTED("Servidor de mensajes no disponible"),
    SERVER_UNAVAILABLE("Servidor no disponible"),
    PENDING_ACK_TIMEOUT("Pendientes sin confirmación"),
    BATTERY_LOW("Batería baja"),
    PERMISSION_MISSING("Permisos de ubicación requeridos"),
    SERVICE_RECOVERY("Recuperando servicio"),
    TRACKING_DISABLED_BY_USER("Tracking desactivado por el usuario"),
}
