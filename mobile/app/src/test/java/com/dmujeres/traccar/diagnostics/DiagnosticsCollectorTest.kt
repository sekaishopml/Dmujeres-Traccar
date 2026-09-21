package com.dmujeres.traccar.diagnostics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot de diagnóstico sin Android: la forma del whitelist del contrato
 * `POST /api/mobile/v1/diagnostics`, el JSON del body, el detector puro de
 * pasos de reloj y la decisión de throttle del reporter.
 */
class DiagnosticsCollectorTest {

    private fun sample(pending: Int = 7) = DiagnosticsSources(
        versionCode = 75,
        versionName = "1.0.74",
        journeyActive = true,
        journeyElapsedMs = 3_600_000L,
        journeyStartAt = 1_700_000_000_000L,
        pendingCount = pending,
        bufferMax = 5000,
        bufferPolicy = "drop_oldest",
        mqttStatus = "Conectado al servidor",
        lastAckAt = 1_700_000_001_000L,
        lastFixAt = 1_700_000_002_000L,
        reconnects24h = 3,
        netCause = "ok",
        cellular = true,
        airplane = false,
        battery = 85,
        batteryExempt = true,
        idleMs = 15_000L,
        crashes24h = 1,
        anrs24h = 0,
        stuckStops24h = 2,
        clockSteps24h = 4,
        speedStuck24h = 1,
        lastStartError = "sin permiso",
        manufacturer = "test",
        model = "model",
        androidVersion = "14",
        screenOn = true,
        motionState = "STATIONARY",
        oemKey = null,
        oemConfirmed = false,
        readinessVerdict = "",
        continuityState = "",
        continuityCause = "",
        recoveryState = "",
    )

    @Suppress("UNCHECKED_CAST")
    private fun group(report: Map<String, Any?>, name: String): Map<String, Any?> =
        report.getValue(name) as Map<String, Any?>

    @Test
    fun compactTieneExactamenteLosGruposDelContrato() {
        val report = DiagnosticsCollector.compact(sample())
        assertEquals(
            setOf("app", "journey", "buffer", "mqtt", "net", "power", "deviceHealth", "health"),
            report.keys,
        )
        assertEquals(setOf("versionCode", "versionName"), group(report, "app").keys)
        assertEquals(setOf("active", "elapsedMs", "startAt"), group(report, "journey").keys)
        assertEquals(setOf("pending", "max", "policy"), group(report, "buffer").keys)
        assertEquals(
            setOf("status", "lastAckAt", "lastFixAt", "reconnects"),
            group(report, "mqtt").keys,
        )
        assertEquals(setOf("cause", "cellular", "airplane"), group(report, "net").keys)
        assertEquals(setOf("battery", "exempt", "idleMs"), group(report, "power").keys)
        assertEquals(
            setOf("crashes24h", "anrs24h", "stuckStops", "clockSteps24h", "speedStuck24h", "lastStartError"),
            group(report, "health").keys,
        )
    }

    @Test
    fun compactPropagaLosValoresDeLasFuentes() {
        val report = DiagnosticsCollector.compact(sample())
        assertEquals(75, group(report, "app").getValue("versionCode"))
        assertEquals(true, group(report, "journey").getValue("active"))
        assertEquals(3_600_000L, group(report, "journey").getValue("elapsedMs"))
        assertEquals(7, group(report, "buffer").getValue("pending"))
        assertEquals(100000, group(report, "buffer").getValue("max"))
        assertEquals("drop_oldest", group(report, "buffer").getValue("policy"))
        assertEquals(3, group(report, "mqtt").getValue("reconnects"))
        assertEquals(85, group(report, "power").getValue("battery"))
        assertEquals(true, group(report, "net").getValue("cellular"))
        assertEquals(1, group(report, "health").getValue("crashes24h"))
        assertEquals(4, group(report, "health").getValue("clockSteps24h"))
    }

    @Test
    fun compactOmitePendienteNoConsultadoYBlancos() {
        val report = DiagnosticsCollector.compact(
            sample(pending = -1).copy(mqttStatus = "  ", lastStartError = "", versionName = ""),
        )
        assertFalse("pending -1 no debe viajar", group(report, "buffer").containsKey("pending"))
        assertFalse("status en blanco no debe viajar", group(report, "mqtt").containsKey("status"))
        assertFalse("lastStartError en blanco no debe viajar", group(report, "health").containsKey("lastStartError"))
        assertFalse("versionName en blanco no debe viajar", group(report, "app").containsKey("versionName"))
        // El resto del grupo sigue completo.
        assertTrue(group(report, "mqtt").containsKey("lastAckAt"))
    }

    @Test
    fun compactSaneaRangos() {
        val report = DiagnosticsCollector.compact(
            sample().copy(
                pendingCount = 99,
                battery = 180,
                journeyElapsedMs = -5L,
                journeyStartAt = -1L,
                clockSteps24h = -3,
                lastStartError = "x".repeat(200),
            ),
        )
        assertEquals(100, group(report, "power").getValue("battery"))
        assertEquals(0L, group(report, "journey").getValue("elapsedMs"))
        assertEquals(0L, group(report, "journey").getValue("startAt"))
        assertEquals(0, group(report, "health").getValue("clockSteps24h"))
        assertEquals(64, (group(report, "health").getValue("lastStartError") as String).length)
    }

    @Test
    fun toJsonRespetaElEnvelopeDelContrato() {
        val body = DiagnosticsCollector.toJson(DiagnosticsCollector.compact(sample()), ts = 123L)
        val root = JSONObject(body)
        assertEquals(123L, root.getLong("ts"))
        assertFalse("deviceId numérico: se omite (viaja en la cabecera)", root.has("deviceId"))
        val report = root.getJSONObject("report")
        assertEquals(75, report.getJSONObject("app").getInt("versionCode"))
        assertEquals("ok", report.getJSONObject("net").getString("cause"))
        assertEquals(2, report.getJSONObject("health").getInt("stuckStops"))
        assertEquals(15000L, report.getJSONObject("power").getLong("idleMs"))
    }

    @Test
    fun journeyElapsedUsaElRelojHonestoDeLaUi() {
        // Con ancla: persisted + (now - ancla), nunca now - inicio.
        assertEquals(
            4_000L,
            DiagnosticsCollector.journeyElapsedMs(
                persistedElapsedMs = 3_000L,
                persistedWallMs = 10_000L,
                journeyStartAt = 5_000L,
                nowMs = 11_000L,
            ),
        )
        // Sin ancla (jornada sin sincronizar): legacy now - start.
        assertEquals(
            500L,
            DiagnosticsCollector.journeyElapsedMs(0L, 0L, 10_000L, 10_500L),
        )
        // Sin jornada: 0.
        assertEquals(0L, DiagnosticsCollector.journeyElapsedMs(5_000L, 5_000L, 0L, 99_000L))
    }

    @Test
    fun clockStepDeltaDetectaSaltosMasAllaDeSesentaSegundos() {
        // Sin salto (30 s de ventana en ambos relojes).
        assertFalse(DiagnosticsCollector.clockStepDelta(30_000L, 30_000L))
        // Exactamente el umbral NO cuenta (hay que SUPERARLO).
        assertFalse(DiagnosticsCollector.clockStepDelta(90_000L, 30_000L))
        // 60_001 ms de divergencia sí.
        assertTrue(DiagnosticsCollector.clockStepDelta(90_001L, 30_000L))
        // Hacia atrás también (NTP corrige un reloj adelantado).
        assertTrue(DiagnosticsCollector.clockStepDelta(-70_000L, 30_000L))
    }

    @Test
    fun throttleClienteDeNoventaSegundosMenosReasonsForzadas() {
        val t0 = 1_000_000L
        // Forzadas: siempre pasan, aunque el último reporte sea hace 1 ms.
        assertTrue(DiagnosticsReporter.shouldSend("journey_start", t0 - 1, t0))
        assertTrue(DiagnosticsReporter.shouldSend("journey_stop", t0 - 1, t0))
        assertTrue(DiagnosticsReporter.shouldSend("crash_boot", t0 - 1, t0))
        assertTrue(DiagnosticsReporter.shouldSend("manual", t0 - 1, t0))
        // Resto: a los 90 s justos pasa; un ms antes, no.
        assertFalse(DiagnosticsReporter.shouldSend("periodic", t0 - 89_999, t0))
        assertTrue(DiagnosticsReporter.shouldSend("periodic", t0 - 90_000, t0))
        assertTrue(DiagnosticsReporter.shouldSend("ota_update", t0 - 90_000, t0))
        // Nunca hubo reporte: pasa.
        assertTrue(DiagnosticsReporter.shouldSend("periodic", 0L, t0))
    }
}
