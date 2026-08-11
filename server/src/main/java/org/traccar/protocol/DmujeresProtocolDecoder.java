/*
 * Copyright 2023 Anton Tananaev (anton@traccar.org)
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
package org.traccar.protocol;

import io.netty.handler.codec.mqtt.MqttPublishMessage;
import jakarta.json.Json;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.traccar.BaseMqttProtocolDecoder;
import org.traccar.Protocol;
import org.traccar.helper.DateUtil;
import org.traccar.helper.UnitsConverter;
import org.traccar.model.Position;
import org.traccar.session.DeviceSession;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Date;

public class DmujeresProtocolDecoder extends BaseMqttProtocolDecoder {

    public DmujeresProtocolDecoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected Object decode(DeviceSession deviceSession, MqttPublishMessage message) throws Exception {

        String content = message.payload().toString(StandardCharsets.UTF_8);
        JsonObject json = Json.createReader(new StringReader(content)).readObject();

        Position position = new Position(getProtocolName());
        position.setDeviceId(deviceSession.getDeviceId());

        if (json.containsKey("ts") || json.containsKey("timestamp")) {
            JsonValue timestampValue = json.containsKey("ts") ? json.get("ts") : json.get("timestamp");
            if (timestampValue.getValueType() == JsonValue.ValueType.NUMBER) {
                long timestamp = ((JsonNumber) timestampValue).longValue();
                if (timestamp < Integer.MAX_VALUE) {
                    timestamp *= 1000;
                }
                position.setTime(new Date(timestamp));
            } else if (timestampValue.getValueType() == JsonValue.ValueType.STRING) {
                String value = ((JsonString) timestampValue).getString();
                if (value.contains("T")) {
                    position.setTime(DateUtil.parseDate(value));
                } else {
                    position.setTime(new Date());
                }
            } else {
                position.setTime(new Date());
            }
        } else {
            position.setTime(new Date());
        }

        Double latitude = null;
        Double longitude = null;

        if (json.containsKey("lat")) {
            latitude = json.getJsonNumber("lat").doubleValue();
        } else if (json.containsKey("latitude")) {
            latitude = json.getJsonNumber("latitude").doubleValue();
        }

        if (json.containsKey("lon")) {
            longitude = json.getJsonNumber("lon").doubleValue();
        } else if (json.containsKey("longitude")) {
            longitude = json.getJsonNumber("longitude").doubleValue();
        }

        if (latitude != null && longitude != null) {
            position.setValid(true);
            position.setLatitude(latitude);
            position.setLongitude(longitude);
        } else {
            getLastLocation(position, position.getDeviceTime());
        }

        if (json.containsKey("speed")) {
            double speed = json.getJsonNumber("speed").doubleValue();
            if (speed >= 0) {
                position.setSpeed(UnitsConverter.knotsFromMps(speed));
            }
        }

        if (json.containsKey("course")) {
            double course = json.getJsonNumber("course").doubleValue();
            if (course >= 0) {
                position.setCourse(course);
            }
        } else if (json.containsKey("bearing")) {
            double bearing = json.getJsonNumber("bearing").doubleValue();
            if (bearing >= 0) {
                position.setCourse(bearing);
            }
        } else if (json.containsKey("heading")) {
            double heading = json.getJsonNumber("heading").doubleValue();
            if (heading >= 0) {
                position.setCourse(heading);
            }
        }

        if (json.containsKey("alt")) {
            position.setAltitude(json.getJsonNumber("alt").doubleValue());
        } else if (json.containsKey("altitude")) {
            position.setAltitude(json.getJsonNumber("altitude").doubleValue());
        }

        if (json.containsKey("acc")) {
            position.setAccuracy(json.getJsonNumber("acc").doubleValue());
        } else if (json.containsKey("accuracy")) {
            position.setAccuracy(json.getJsonNumber("accuracy").doubleValue());
        }

        if (json.containsKey("batt")) {
            position.set(Position.KEY_BATTERY_LEVEL, json.getJsonNumber("batt").doubleValue());
        } else if (json.containsKey("battery")) {
            position.set(Position.KEY_BATTERY_LEVEL, json.getJsonNumber("battery").doubleValue());
        }

        return position;
    }

}
