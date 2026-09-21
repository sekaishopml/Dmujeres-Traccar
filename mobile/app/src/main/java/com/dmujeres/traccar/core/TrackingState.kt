package com.dmujeres.traccar.core

/**
 * Estados del servicio de tracking. El usuario puede desactivarlo voluntariamente;
 * TRACKING_DISABLED_BY_USER es un estado persistente.
 */
enum class TrackingState(val label: String) {
    TRACKING_ACTIVE("Jornada activa y enviando ubicación"),
    /**
     * E7: "sin GPS" NO significa GPS apagado: es "sin fix fresco" (puede ser
     * proveedor fallando, proceso despertado tarde, quietud, o cielo tapado).
     * El alias legacy "GPS_DISABLED" se acepta al leer estados persistidos.
     */
    NO_FRESH_FIX("Sin fix fresco de GPS"),
    GPS_FALLBACK("Usando GPS del sistema"),
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
        /** Alias legacy persistido antes de E7 (compatibilidad de lectura). */
        private const val LEGACY_GPS_DISABLED = "GPS_DISABLED"

        fun fromName(value: String): TrackingState {
            val normalized = if (value == LEGACY_GPS_DISABLED) NO_FRESH_FIX.name else value
            return entries.firstOrNull { it.name == normalized } ?: TRACKING_DISABLED_BY_USER
        }
    }
}
