package com.dmujeres.traccar.core

/**
 * Normalización única de la dirección del servidor.
 *
 * Antes la lógica estaba duplicada en [com.dmujeres.traccar.config.AppConfig]
 * (setter privado) y en [MqttManager] (companion). También centraliza la
 * derivación de la base web (mismo host, puerto web) que usan el dispatcher
 * HTTP y el reporte de diagnóstico.
 *
 * No depende de [com.dmujeres.traccar.config.AppConfig] a propósito para evitar
 * un ciclo config <-> mqtt: el puerto web se pasa como parámetro.
 */
object MqttServerNormalizer {

    /**
     * Convierte la dirección a un formato que Paho entiende (tcp:// o ssl://).
     * Paho NO acepta mqtt://. Añade puerto por defecto si falta.
     */
    fun normalizeServer(server: String): String {
        var value = server.trim().trimEnd('/')
        var secure = false
        if (value.startsWith("mqtts://")) { value = value.removePrefix("mqtts://"); secure = true }
        if (value.startsWith("ssl://")) { value = value.removePrefix("ssl://"); secure = true }
        if (value.startsWith("mqtt://")) value = value.removePrefix("mqtt://")
        if (value.startsWith("http://")) value = value.removePrefix("http://")
        if (value.startsWith("https://")) { value = value.removePrefix("https://"); secure = true }
        if (value.contains("://")) return value
        val hostPort = value.substringBefore('/')
        val hasPort = hostPort.contains(':')
        val defaultPort = if (secure) ":8883" else ":1883"
        return (if (secure) "ssl://" else "tcp://") + hostPort + if (hasPort) "" else defaultPort
    }

    /** Deriva la base web del servidor MQTT (mismo host, puerto web). */
    fun webBase(serverUrl: String, webPort: Int = 999): String {
        val server = serverUrl.removePrefix("tcp://").removePrefix("mqtt://")
            .removePrefix("ssl://").removePrefix("mqtts://").substringBefore('/')
        val host = server.substringBefore(':')
        val uriHost = runCatching { java.net.URI(serverUrl).host }.getOrNull()
        val finalHost = if (!uriHost.isNullOrBlank()) uriHost else host
        return "http://$finalHost:$webPort"
    }
}
