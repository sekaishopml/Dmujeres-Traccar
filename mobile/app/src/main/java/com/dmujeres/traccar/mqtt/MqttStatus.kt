package com.dmujeres.traccar.mqtt

/** Estado MQTT visible desde el servicio, la UI y la notificación. */
object MqttStatus {
    const val DISCONNECTED = "Sin conexión al servidor"
    const val CONNECTING = "Conectando..."
    const val CONNECTED = "Conectado al servidor"

    @Volatile
    var status: String = DISCONNECTED
        internal set
}
