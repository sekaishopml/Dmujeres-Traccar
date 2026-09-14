package com.dmujeres.traccar.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SilenceDiagnosisTest {

    private val NOW = 1_000_000_000L

    private fun layers(
        callbackAgeMs: Long = 30_000L,
        acceptedAgeMs: Long = 30_000L,
        ackAgeMs: Long = 30_000L,
        httpAgeMs: Long = 30_000L,
        mqttAgeMs: Long = 30_000L,
        fixReceived: Long = 100L,
        fixRejected: Long = 0L,
        journeyActive: Boolean = true,
    ): SilenceDiagnosis.SilenceLayers = SilenceDiagnosis.SilenceLayers(
        callbackAt = NOW - callbackAgeMs,
        acceptedAt = NOW - acceptedAgeMs,
        storedAt = NOW - acceptedAgeMs,
        ackAt = NOW - ackAgeMs,
        httpAt = NOW - httpAgeMs,
        mqttAt = NOW - mqttAgeMs,
        fixReceived = fixReceived,
        fixRejected = fixRejected,
        journeyActive = journeyActive,
        nowMs = NOW,
    )

    @Test
    fun ruleA_notActiveIsDetenido() {
        assertEquals(
            "DETENIDO",
            SilenceDiagnosis.diagnose(layers(journeyActive = false, callbackAgeMs = 60 * 60_000L)),
        )
    }

    @Test
    fun ruleB_staleCallbackIsGpsSinCallbacks() {
        assertEquals(
            "GPS sin callbacks",
            SilenceDiagnosis.diagnose(layers(callbackAgeMs = 11 * 60_000L)),
        )
    }

    @Test
    fun ruleC_rejectsWithFreshCallbackIsGpsConRechazos() {
        assertEquals(
            "GPS con rechazos",
            SilenceDiagnosis.diagnose(
                layers(
                    callbackAgeMs = 30_000L,
                    acceptedAgeMs = 15 * 60_000L,
                    fixRejected = 7L,
                ),
            ),
        )
    }

    @Test
    fun ruleD_freshAcceptedWithoutAckOrHttpIsSinConfirmacion() {
        assertEquals(
            "Sin confirmación (red/servidor)",
            SilenceDiagnosis.diagnose(
                layers(
                    acceptedAgeMs = 30_000L,
                    ackAgeMs = 4 * 60_000L,
                    httpAgeMs = 3 * 60_000L,
                ),
            ),
        )
    }

    @Test
    fun ruleE_freshAckIsOk() {
        assertEquals(
            "OK (captura y envío)",
            SilenceDiagnosis.diagnose(
                layers(
                    ackAgeMs = 30_000L,
                    httpAgeMs = 30_000L,
                    mqttAgeMs = 30_000L,
                ),
            ),
        )
    }

    @Test
    fun ruleF_staleMqttWithFreshHttpIsMqttCaido() {
        assertEquals(
            "MQTT caído (posiciones OK)",
            SilenceDiagnosis.diagnose(
                layers(
                    ackAgeMs = 10 * 60_000L,
                    httpAgeMs = 30_000L,
                    mqttAgeMs = 20 * 60_000L,
                ),
            ),
        )
    }

    @Test
    fun ruleG_freshCallbackStaleAckIsOfflineCapturando() {
        // Todo viejo salvo el callback crudo: capturando sin red.
        assertEquals(
            "Sin red: capturando offline",
            SilenceDiagnosis.diagnose(
                layers(
                    callbackAgeMs = 60_000L,
                    acceptedAgeMs = 20 * 60_000L,
                    ackAgeMs = 20 * 60_000L,
                    httpAgeMs = 20 * 60_000L,
                    mqttAgeMs = 20 * 60_000L,
                ),
            ),
        )
    }

    @Test
    fun rulePriority_orderMatters() {
        // DETENIDO gana aunque todo lo demás esté viejo (regla A primero).
        assertEquals(
            "DETENIDO",
            SilenceDiagnosis.diagnose(
                layers(journeyActive = false, callbackAgeMs = 60 * 60_000L, ackAgeMs = 60 * 60_000L),
            ),
        )
        // GPS sin callbacks gana a rechazos (regla B antes que C): callback
        // viejo + rechazos → sin callbacks.
        assertEquals(
            "GPS sin callbacks",
            SilenceDiagnosis.diagnose(
                layers(callbackAgeMs = 11 * 60_000L, fixRejected = 5L, acceptedAgeMs = 15 * 60_000L),
            ),
        )
    }
}
