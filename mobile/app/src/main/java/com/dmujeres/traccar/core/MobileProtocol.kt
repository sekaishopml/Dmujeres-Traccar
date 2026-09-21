package com.dmujeres.traccar.core

/**
 * FASE R6: contrato de protocolo móvil centralizado (topics MQTT, endpoints
 * HTTP y estados de jornada). Antes vivía disperso entre `AppConfig`
 * (formato de topics), cada uploader (paths literales) y las cadenas
 * "started"/"ended" del pipeline.
 *
 * PROHIBIDO cambiar los VALORES sin migración coordinada server + dashboard:
 * son la interfaz con `org.traccar.mobile` (Mobile*Resource) y el consumer
 * MQTT. Este objeto es puro (sin Android) y tiene test de contrato.
 */
object MobileProtocol {

    /** Puerto del plano web/API del servidor (Traccar + endpoints móviles). */
    const val WEB_PORT = 999

    /** Estados de jornada en la presencia (headers `journeyStarted`/`journeyEnded`). */
    const val JOURNEY_STATUS_STARTED = "started"
    const val JOURNEY_STATUS_ENDED = "ended"

    private const val TOPIC_ROOT = "dmj/v1/devices"

    /** Topic de subida: dmj/v1/devices/{deviceId}/telemetry */
    fun telemetryTopic(deviceId: String): String = "$TOPIC_ROOT/$deviceId/telemetry"

    /** Topic de ACK: dmj/v1/devices/{deviceId}/ack */
    fun ackTopic(deviceId: String): String = "$TOPIC_ROOT/$deviceId/ack"

    // Endpoints HTTP móviles (contrato con Mobile*Resource).
    const val PATH_POSITIONS = "/api/mobile/v1/positions"
    const val PATH_HEALTH = "/api/mobile/v1/health"
    const val PATH_DIAGNOSTICS = "/api/mobile/v1/diagnostics"
    const val PATH_FCM_TOKEN = "/api/mobile/v1/fcm-token"
    const val PATH_RECOVERY_ACK = "/api/mobile/v1/recovery-ack"
    const val PATH_CONFIG = "/api/mobile/v1/config"

    /** R8: rollout OTA gradual ({@code ?deviceId=&versionCode=}). */
    const val PATH_OTA = "/api/mobile/v1/ota"
}
