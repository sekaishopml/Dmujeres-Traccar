package com.dmujeres.gps.service

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class MqttManager(
    private val host: String,
    private val port: Int,
    private val clientId: String
) {
    private var client: Mqtt3AsyncClient? = null
    @Volatile
    var isConnected: Boolean = false
        private set

    private val statusTopic = "dmujeres/$clientId/status"
    private val positionTopic = "dmujeres/position"

    suspend fun connect(): Boolean {
        disconnect()
        return try {
            val mqttClient = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(host)
                .serverPort(port)
                .automaticReconnect()
                .initialDelay(1, TimeUnit.SECONDS)
                .maxDelay(30, TimeUnit.SECONDS)
                .applyAutomaticReconnect()
                .addConnectedListener { isConnected = true }
                .addDisconnectedListener { isConnected = false }
                .buildAsync()

            val connected = suspendCancellableCoroutine { cont ->
                mqttClient.connectWith()
                    .willPublish()
                    .topic(statusTopic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload("""{"status":"offline"}""".toByteArray())
                    .applyWillPublish()
                    .send()
                    .whenComplete { _, error ->
                        if (error == null) {
                            mqttClient.publishWith()
                                .topic(statusTopic)
                                .qos(MqttQos.AT_LEAST_ONCE)
                                .payload("""{"status":"online"}""".toByteArray())
                                .send()
                            cont.resume(true)
                        } else {
                            Log.e(TAG, "MQTT connect failed", error)
                            cont.resume(false)
                        }
                    }
            }

            if (connected) {
                client = mqttClient
                isConnected = true
            }
            connected
        } catch (e: Exception) {
            Log.e(TAG, "MQTT connect error", e)
            false
        }
    }

    suspend fun publishPosition(payload: String): Boolean {
        val mqttClient = client ?: return false
        if (!isConnected) return false

        return suspendCancellableCoroutine { cont ->
            mqttClient.publishWith()
                .topic(positionTopic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload(payload.toByteArray())
                .send()
                .whenComplete { _, error ->
                    cont.resume(error == null)
                }
        }
    }

    fun disconnect() {
        try {
            client?.let { mqttClient ->
                if (isConnected) {
                    mqttClient.publishWith()
                        .topic(statusTopic)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .payload("""{"status":"offline"}""".toByteArray())
                        .send()
                }
                mqttClient.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "MQTT disconnect error", e)
        } finally {
            client = null
            isConnected = false
        }
    }

    companion object {
        private const val TAG = "MqttManager"
    }
}
