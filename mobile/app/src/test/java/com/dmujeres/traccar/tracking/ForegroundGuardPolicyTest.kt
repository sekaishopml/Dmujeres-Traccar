package com.dmujeres.traccar.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tabla pura del guard de foreground: qué restricciones del sistema (appops de
 * segundo plano / bucket de reposo) bloquean el arranque de la jornada, cuáles
 * solo advierten y el periodo del guardián de sesión según el movimiento.
 */
class ForegroundGuardPolicyTest {

    @Test
    fun bloqueaConBackgroundRestrictedDesde28() {
        // Appops "background restricted" (API 28+): arrancar es morir enseguida.
        assertTrue(ForegroundGuardPolicy.shouldBlockStart(true, ForegroundGuardPolicy.STANDBY_ACTIVE, 28))
        assertTrue(ForegroundGuardPolicy.shouldBlockStart(true, ForegroundGuardPolicy.STANDBY_RESTRICTED, 30))
        // Antes de API 28 la señal no existe (no se puede leer).
        assertFalse(ForegroundGuardPolicy.shouldBlockStart(true, ForegroundGuardPolicy.STANDBY_ACTIVE, 27))
    }

    @Test
    fun bloqueaConBucketRestrictedDesde30() {
        assertTrue(ForegroundGuardPolicy.shouldBlockStart(false, ForegroundGuardPolicy.STANDBY_RESTRICTED, 30))
        assertTrue(ForegroundGuardPolicy.shouldBlockStart(false, ForegroundGuardPolicy.STANDBY_RESTRICTED, 34))
        // En API < 30 el bucket 45 no es una señal fiable.
        assertFalse(ForegroundGuardPolicy.shouldBlockStart(false, ForegroundGuardPolicy.STANDBY_RESTRICTED, 29))
    }

    @Test
    fun noBloqueaBucketsSanos() {
        assertFalse(ForegroundGuardPolicy.shouldBlockStart(false, ForegroundGuardPolicy.STANDBY_EXEMPTED, 30))
        assertFalse(ForegroundGuardPolicy.shouldBlockStart(false, ForegroundGuardPolicy.STANDBY_ACTIVE, 30))
        assertFalse(ForegroundGuardPolicy.shouldBlockStart(false, ForegroundGuardPolicy.STANDBY_ACTIVE, 26))
    }

    @Test
    fun rareSoloAdvierteNuncaBloquea() {
        // RARE (40) sin restricción de appops: advertir, no bloquear.
        assertFalse(ForegroundGuardPolicy.shouldBlockStart(false, ForegroundGuardPolicy.STANDBY_RARE, 30))
        assertTrue(ForegroundGuardPolicy.shouldWarnStart(false, ForegroundGuardPolicy.STANDBY_RARE, 30))
        assertTrue(ForegroundGuardPolicy.shouldWarnStart(false, ForegroundGuardPolicy.STANDBY_RARE, 28))
        // Antes de API 28 no hay señales legibles: ni advertencia.
        assertFalse(ForegroundGuardPolicy.shouldWarnStart(false, ForegroundGuardPolicy.STANDBY_RARE, 27))
    }

    @Test
    fun warningNuncaConBloqueoActivo() {
        // isBackgroundRestricted desde API 28 bloquea: nunca coexiste warning.
        assertTrue(ForegroundGuardPolicy.shouldBlockStart(true, ForegroundGuardPolicy.STANDBY_ACTIVE, 28))
        assertFalse(ForegroundGuardPolicy.shouldWarnStart(true, ForegroundGuardPolicy.STANDBY_ACTIVE, 28))
        // Bucket RESTRICTED en API 30+ bloquea antes de que exista warning.
        assertFalse(ForegroundGuardPolicy.shouldWarnStart(false, ForegroundGuardPolicy.STANDBY_RESTRICTED, 30))
    }


    @Test
    fun keeperRapidoPorDesplazamientoReal() {
        // Fix ralo con desplazamiento real (proceso congelado despertando):
        // joseph 11.5 km con fixes cada 15 min -> cada fix mueve >100 m.
        assertTrue(ForegroundGuardPolicy.keeperFastByDisplacement(900_000L, 300.0))
        assertTrue(ForegroundGuardPolicy.keeperFastByDisplacement(90_000L, 100.0))
        // Fix frecuente (dt corto) NO acelera: ya hay captura densa.
        assertFalse(ForegroundGuardPolicy.keeperFastByDisplacement(10_000L, 500.0))
        // Quieto con dt largo NO acelera (no es viaje).
        assertFalse(ForegroundGuardPolicy.keeperFastByDisplacement(900_000L, 20.0))
    }

    @Test
    fun periodoKeeperConJornadaSiempre2Min() {
        // 1.1.8: con jornada activa la cadena anti-OEM va a 2 min aunque el
        // modo adaptativo esté en quietud (proceso congelado no detecta MOVING).
        assertEquals(120_000L, ForegroundGuardPolicy.keeperPeriodMs(true, journeyActive = true))
        assertEquals(120_000L, ForegroundGuardPolicy.keeperPeriodMs(false, journeyActive = true))
        // Sin jornada: base de 15 min (nada que proteger).
        assertEquals(900_000L, ForegroundGuardPolicy.keeperPeriodMs(false, journeyActive = false))
        assertEquals(900_000L, ForegroundGuardPolicy.keeperPeriodMs(true, journeyActive = false))
    }
}
