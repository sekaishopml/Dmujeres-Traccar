package com.dmujeres.traccar.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FASE 2 (prompt maestro): pruebas de CARACTERIZACIÓN del motor de ubicación.
 * Congelan la tabla temporal exacta del watchdog de `TrackingService` antes de
 * extraerla, para que la extracción no cambie comportamiento:
 *
 * - umbral de fix viejo = max(60 s, intervalo × 3000)
 * - sin fix en la corrida: aviso a los 60 s del arranque
 * - re-solicitud cada 2 min (0 = nunca dispara)
 * - recreación del cliente: sin callbacks > 10 min y máx 1/15 min
 * - GNSS forzado/idempotente
 * - alerta sin satélites: jornada > 5 min, GNSS sin datos o <= 1 usado, throttle 10 min
 */
class LocationEnginePolicyTest {

    // ---------- umbrales ----------

    @Test
    fun `umbral stale usa el minimo de 60s con intervalo chico`() {
        assertEquals(60_000L, LocationEnginePolicy.staleThresholdMs(1))
        assertEquals(60_000L, LocationEnginePolicy.staleThresholdMs(10))
        assertEquals(60_000L, LocationEnginePolicy.staleThresholdMs(20))
    }

    @Test
    fun `umbral stale escala con el intervalo x3000`() {
        // 30 s × 3000 = 90_000 > piso → factor
        assertEquals(90_000L, LocationEnginePolicy.staleThresholdMs(30))
        // 60 s × 3000 = 180_000
        assertEquals(180_000L, LocationEnginePolicy.staleThresholdMs(60))
    }

    // ---------- gpsWithoutFix ----------

    @Test
    fun `sin fix aun en la corrida - recien a los 60s del arranque`() {
        val started = 1_000_000L
        assertFalse(
            LocationEnginePolicy.gpsWithoutFix(started + 59_999L, started, 0L, 10L),
        )
        assertTrue(
            LocationEnginePolicy.gpsWithoutFix(started + 60_001L, started, 0L, 10L),
        )
    }

    @Test
    fun `fix de la corrida viejo - umbral dinamico`() {
        val started = 1_000_000L
        val lastFix = started + 100_000L
        // intervalo 10 s → threshold 60 s
        assertFalse(LocationEnginePolicy.gpsWithoutFix(lastFix + 60_000L, started, lastFix, 10L))
        assertTrue(LocationEnginePolicy.gpsWithoutFix(lastFix + 60_001L, started, lastFix, 10L))
    }

    @Test
    fun `fix de corrida anterior no cuenta como fix actual`() {
        val started = 1_000_000L
        val oldFix = started - 5L
        assertTrue(LocationEnginePolicy.gpsWithoutFix(started + 60_001L, started, oldFix, 10L))
        assertFalse(LocationEnginePolicy.gpsWithoutFix(started + 30_000L, started, oldFix, 10L))
    }

    @Test
    fun `intervalo grande escala el umbral`() {
        val started = 1_000_000L
        val lastFix = started + 1L
        // intervalo 60 s → threshold 180 s
        assertFalse(LocationEnginePolicy.gpsWithoutFix(lastFix + 180_000L, started, lastFix, 60L))
        assertTrue(LocationEnginePolicy.gpsWithoutFix(lastFix + 180_001L, started, lastFix, 60L))
    }

    // ---------- re-solicitud ----------

    @Test
    fun `re-solicitud dispara a los 2 min y nunca antes`() {
        assertTrue(LocationEnginePolicy.shouldReregister(1_000_000L, 0L))
        assertFalse(LocationEnginePolicy.shouldReregister(120_000L, 0L))
        assertTrue(LocationEnginePolicy.shouldReregister(120_001L, 0L))
        assertFalse(LocationEnginePolicy.shouldReregister(240_000L, 120_000L))
        assertTrue(LocationEnginePolicy.shouldReregister(240_001L, 120_000L))
    }

    // ---------- recreación del cliente ----------

    @Test
    fun `engine reinit exige callback previo silencio 10 min y 15 min desde el ultimo`() {
        val now = 10_000_000L
        // sin callbacks nunca: no recrea (no hay evidencia de que funcionó)
        assertFalse(LocationEnginePolicy.shouldReinitEngine(now, 0L, 0L))
        // callback hace 9 min: aún no
        assertFalse(LocationEnginePolicy.shouldReinitEngine(now, now - 9 * 60_000L, 0L))
        // callback hace 10 min + 1 ms: sí
        assertTrue(LocationEnginePolicy.shouldReinitEngine(now, now - 10 * 60_000L - 1, 0L))
        // pero si se recreó hace 5 min: no
        assertFalse(
            LocationEnginePolicy.shouldReinitEngine(now, now - 10 * 60_000L - 1, now - 5 * 60_000L),
        )
        // si se recreó hace 15 min + 1 ms: sí
        assertTrue(
            LocationEnginePolicy.shouldReinitEngine(
                now, now - 10 * 60_000L - 1, now - 15 * 60_000L - 1,
            ),
        )
    }

    // ---------- frescura monotónica ----------

    @Test
    fun `isFixRecent respeta el mismo umbral y el centinela 0`() {
        val now = 1_000_000_000_000L
        assertFalse(LocationEnginePolicy.isFixRecent(0L, now, 10L))
        assertTrue(LocationEnginePolicy.isFixRecent(now - 60_000_000_000L, now, 10L))
        assertFalse(LocationEnginePolicy.isFixRecent(now - 60_000_000_001L, now, 10L))
    }

    // ---------- alerta GNSS ----------

    @Test
    fun `alerta GNSS exige jornada madura sin satelites y throttle`() {
        val started = 1_000_000L
        // a los 4 min no
        assertFalse(
            LocationEnginePolicy.noGnssAlertDue(started + 4 * 60_000L, started, 0L, true, 0),
        )
        // a los 6 min sin datos: sí
        assertTrue(
            LocationEnginePolicy.noGnssAlertDue(started + 6 * 60_000L, started, 0L, true, 0),
        )
        // con datos y 5 usados: no
        assertFalse(
            LocationEnginePolicy.noGnssAlertDue(started + 6 * 60_000L, started, 0L, true, 5),
        )
        // con datos y 1 usado: sí
        assertTrue(
            LocationEnginePolicy.noGnssAlertDue(started + 6 * 60_000L, started, 0L, true, 1),
        )
        // throttle: si se avisó hace 5 min, no
        assertFalse(
            LocationEnginePolicy.noGnssAlertDue(
                started + 11 * 60_000L, started, started + 6 * 60_000L, true, 1,
            ),
        )
    }

    // ---------- controlador con estado ----------

    @Test
    fun `controlador - flujo completo gnss forzado idempotente`() {
        val c = LocationTimeoutController()
        assertFalse(c.gnssForced)
        assertTrue(c.enterGnssForce())
        assertTrue(c.gnssForced)
        // segundo intento no vuelve a re-solicitar (el watchdog registra fallback)
        assertFalse(c.enterGnssForce())
        // fix fresco recuperado: sale una vez
        assertTrue(c.exitGnssForce())
        assertFalse(c.exitGnssForce())
        assertFalse(c.gnssForced)
    }

    @Test
    fun `controlador - reregister se resetea al recuperar fix`() {
        val c = LocationTimeoutController()
        val now = 1_000_000L
        assertTrue(c.shouldReregister(now))
        c.noteReregister(now)
        assertFalse(c.shouldReregister(now + 119_999L))
        // fix recuperado: el watchdog actual pone lastGpsReregisterAt=0 → dispara ya
        c.noteFixRecovered()
        assertTrue(c.shouldReregister(now + 119_999L))
    }

    @Test
    fun `controlador - reinit y alerta registran su marca`() {
        val c = LocationTimeoutController()
        val now = 10_000_000L
        assertTrue(c.shouldReinitEngine(now, now - 11 * 60_000L))
        c.noteEngineReinit(now)
        assertFalse(c.shouldReinitEngine(now + 5 * 60_000L, now - 11 * 60_000L))
        assertTrue(c.shouldReinitEngine(now + 16 * 60_000L, now - 11 * 60_000L))

        assertTrue(c.noGnssAlertDue(now, now - 6 * 60_000L, false, null))
        c.noteNoGnssAlert(now)
        assertFalse(c.noGnssAlertDue(now + 5 * 60_000L, now - 6 * 60_000L, false, null))
    }
}
