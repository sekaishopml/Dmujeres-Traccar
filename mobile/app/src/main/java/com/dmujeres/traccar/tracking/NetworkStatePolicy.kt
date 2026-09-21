package com.dmujeres.traccar.tracking

/**
 * FASE R3: decisiones puras de etiquetado de red/telemetría, extraídas de
 * `TrackingService` para poder congelarlas en JVM. Los VALORES son contrato con
 * el servidor (dashboard/presence) y no cambian: "wifi" | "mobile" | "none",
 * "gps" | "network" | "fused" | "unknown".
 */
object NetworkStatePolicy {

    const val LABEL_WIFI = "wifi"
    const val LABEL_MOBILE = "mobile"
    const val LABEL_NONE = "none"

    const val PROVIDER_GPS = "gps"
    const val PROVIDER_NETWORK = "network"
    const val PROVIDER_FUSED = "fused"
    const val PROVIDER_UNKNOWN = "unknown"

    /**
     * Etiqueta común de red (wifi|mobile|none) para presence y position.
     * Requiere NET_CAPABILITY_VALIDATED: un socket sin validar se reporta como
     * "none" (sin Internet real).
     */
    fun label(validated: Boolean, hasWifiTransport: Boolean): String = when {
        !validated -> LABEL_NONE
        hasWifiTransport -> LABEL_WIFI
        else -> LABEL_MOBILE
    }

    /**
     * Etiqueta que persistía `persistNetState` al validarse la red: wifi/mobile
     * según transporte; sin transporte conocido se conserva la anterior.
     */
    fun validatedLabel(previous: String, hasWifiTransport: Boolean, hasCellTransport: Boolean): String =
        when {
            hasWifiTransport -> LABEL_WIFI
            hasCellTransport -> LABEL_MOBILE
            else -> previous
        }

    /** Etiqueta del contrato (server/panel) para el proveedor del fix. */
    fun providerLabel(provider: String?): String = when (provider?.lowercase()) {
        PROVIDER_GPS -> PROVIDER_GPS
        PROVIDER_NETWORK -> PROVIDER_NETWORK
        null, "" -> PROVIDER_UNKNOWN
        else -> PROVIDER_FUSED
    }

    /**
     * Umbral de fix rancio para el heartbeat de presencia: el mayor entre 60 s
     * y 3 intervalos efectivos de captura.
     */
    fun fixStaleAfterMs(effectiveIntervalSeconds: Long): Long =
        maxOf(60_000L, effectiveIntervalSeconds * 3_000L)
}
