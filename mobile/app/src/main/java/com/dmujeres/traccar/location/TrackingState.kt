package com.dmujeres.traccar.location

/**
 * Estados diferenciados del watchdog (requisito del proyecto). El usuario puede desactivar
 * el tracking voluntariamente; TRACKING_DISABLED_BY_USER es un estado persistente.
 */
enum class TrackingState(val label: String) {
    TRACKING_ACTIVE("Jornada activa y enviando ubicación"),
    GPS_DISABLED("Sin señal de GPS"),
    NETWORK_OFFLINE("Sin conexión a Internet"),
    MQTT_DISCONNECTED("Servidor no disponible"),
    SERVER_UNAVAILABLE("Servidor no disponible"),
    PENDING_ACK_TIMEOUT("Pendientes sin confirmación"),
    BATTERY_LOW("Batería baja"),
    BUFFER_FULL("Buffer de ubicación lleno"),
    PERMISSION_MISSING("Faltan permisos de ubicación"),
    SERVICE_RECOVERY("Recuperando el servicio"),
    TRACKING_DISABLED_BY_USER("Jornada finalizada");

    companion object {
        fun fromName(value: String): TrackingState =
            entries.firstOrNull { it.name == value } ?: TRACKING_DISABLED_BY_USER
    }
}
