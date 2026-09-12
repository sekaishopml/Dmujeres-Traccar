package com.dmujeres.traccar.util

/**
 * Foto separada del enlace, con 4 dominios que NO son equivalentes:
 *
 * - `transport`: interfaz con ruta (wifi/cellular/other/none). Tener interfaz
 *   NO implica Internet (captive portal, WiFi sin salida).
 * - `validatedInternet`: `NET_CAPABILITY_VALIDATED` del `activeNetwork`.
 *   WiFi sin validar se trata como `none` en todos los contratos.
 * - `mqtt`: estado de SESIÓN (no de socket): solo `READY` (conectado +
 *   suscrito al ACK) autoriza el dispatch MQTT. TCP "conectado" sin subscribe
 *   NO entrega nada.
 * - `serverReachability`: ¿el SERVIDOR confirma? Deriva de `lastAckAt` y de la
 *   edad del pendiente más viejo (los ACK de presence-heartbeat refrescan
 *   `lastAckAt` y ocultarían un backlog de posiciones: por eso existe también
 *   la pata `oldestPendingAt`, mismos umbrales que
 *   `HttpFlushPolicy.STUCK_WITHOUT_ACK_MS/BACKLOG_AGE_MS`).
 *
 * GPS / NETWORK / INTERNET / MQTT / SERVER / PRESENCE / OUTBOX / ROUTE quedan
 * así desacoplados: `INTERNET OFFLINE ≠ GPS OFFLINE`, `MQTT OFFLINE ≠ PHONE
 * OFFLINE`, `GPS sin fix ≠ INTERNET OFFLINE`.
 *
 * Puro (JVM, sin Android): unit-testeable. Lo construye `TrackingService` con
 * `snapshot()` + `MqttManager.ready/connected` + `AppConfig.lastAckAt`.
 */
data class LinkState(
    val transport: Transport,
    val validatedInternet: Boolean,
    val mqtt: MqttLink,
    val serverReachability: ServerReach,
) {
    /** ¿Pedir `MqttManager.connect()`? Solo con Internet validada y sesión no lista. */
    fun shouldAttemptMqtt(): Boolean =
        validatedInternet && mqtt != MqttLink.READY

    /**
     * ¿El enlace está indisponible para entregar? (equivale al antiguo
     * `connectionUnavailable` del watchdog, ahora explícito).
     */
    fun isUnavailable(): Boolean =
        !validatedInternet || mqtt != MqttLink.READY

    companion object {
        /**
         * @param pendingCount filas en `pending_positions` (para no declarar
         * inalcanzable un enlace sano sin nada que enviar).
         */
        fun current(
            transport: Transport,
            validatedInternet: Boolean,
            mqttReady: Boolean,
            mqttConnected: Boolean,
            pendingCount: Int,
            lastAckAt: Long,
            oldestPendingAt: Long?,
            now: Long = System.currentTimeMillis(),
        ): LinkState {
            val mqtt = when {
                mqttReady -> MqttLink.READY
                mqttConnected -> MqttLink.CONNECTING
                else -> MqttLink.DISCONNECTED
            }
            val reachability = when {
                pendingCount <= 0 -> ServerReach.UNKNOWN
                lastAckAt <= 0L || now - lastAckAt > STUCK_WITHOUT_ACK_MS -> ServerReach.UNREACHABLE
                else -> {
                    val oldest = oldestPendingAt?.takeIf { it > 0L }
                    if (oldest != null && now - oldest > BACKLOG_AGE_MS) ServerReach.UNREACHABLE
                    else ServerReach.REACHABLE
                }
            }
            return LinkState(transport, validatedInternet, mqtt, reachability)
        }

        /** Sin ACK de aplicación en este tiempo, el servidor se considera mudo. */
        const val STUCK_WITHOUT_ACK_MS = 2 * 60_000L

        /** Backlog envejecido aunque haya ACKs recientes (presence los oculta). */
        const val BACKLOG_AGE_MS = 10 * 60_000L
    }
}

/** Interfaz con ruta del `activeNetwork` (sin implicar Internet). */
enum class Transport {
    NONE,
    WIFI,
    CELLULAR,
    OTHER,
}

/** Sesión MQTT: solo READY (conectado + suscrito al ACK) entrega. */
enum class MqttLink {
    DISCONNECTED,
    CONNECTING,
    READY,
}

/**
 * ¿Confirma el servidor? UNKNOWN = nada pendiente (no hay señal para juzgar);
 * REACHABLE/UNREACHABLE solo con backlog.
 */
enum class ServerReach {
    UNKNOWN,
    REACHABLE,
    UNREACHABLE,
}
