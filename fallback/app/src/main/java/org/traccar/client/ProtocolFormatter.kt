/*
 * Copyright 2012 - 2021 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client

import android.net.Uri

object ProtocolFormatter {

    /** m/s → nudos (unidad del protocolo OsmAnd). */
    private const val KNOTS_PER_MPS = 1.9438444924406


    fun formatRequest(url: String, position: Position, alarm: String? = null): String {
        val serverUrl = Uri.parse(url)
        val builder = serverUrl.buildUpon()
            .appendQueryParameter("id", position.deviceId)
            .appendQueryParameter("timestamp", (position.time.time / 1000).toString())
            .appendQueryParameter("lat", position.latitude.toString())
            .appendQueryParameter("lon", position.longitude.toString())
            // El protocolo OsmAnd/Traccar interpreta `speed` en NUDOS y el fix
            // de Android viene en m/s (el proveedor ya ajusta a 0 cuando el
            // equipo está realmente quieto).
            .appendQueryParameter("speed", (position.speed * KNOTS_PER_MPS).toString())
            .appendQueryParameter("bearing", position.course.toString())
            .appendQueryParameter("altitude", position.altitude.toString())
            .appendQueryParameter("accuracy", position.accuracy.toString())
            .appendQueryParameter("batt", position.battery.toString())
        if (position.charging) {
            builder.appendQueryParameter("charge", position.charging.toString())
        }
        if (position.mock) {
            builder.appendQueryParameter("mock", position.mock.toString())
        }
        if (alarm != null) {
            builder.appendQueryParameter("alarm", alarm)
        }
        return builder.build().toString()
    }
}
