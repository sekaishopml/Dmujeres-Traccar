package com.dmujeres.traccar.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Bug: duración = nowWall - journeyStartAt colapsaba/inflaba tras correcciones
 * NTP con la app cerrada mucho tiempo. El fix: elapsed monotónico persistido
 * por el servicio + gap wall desde SU último anclaje. Aquí se cubre el núcleo
 * puro (JourneyFormatter.displayElapsedMs / seedElapsedOnRecovery).
 */
class JourneyDisplayElapsedTest {

    private val h = 3_600_000L
    private val min = 60_000L

    // ---- displayElapsedMs ----

    @Test
    fun steadyStateWithoutClockStepMatchesNaive() {
        val start = 1_000_000_000_000L
        val anchor = start + 30 * min      // el servicio persistió a los 30 min
        val persisted = 30 * min           // elapsed real hasta el anclaje
        val now = anchor + 7_000L          // 7 s desde el último anclaje
        val display = JourneyFormatter.displayElapsedMs(persisted, anchor, start, now)
        // Sin salto, wall y monotónico coinciden: == now - start.
        assertEquals(now - start, display)
        assertEquals(persisted + 7_000L, display)
    }

    @Test
    fun ntpBackwardStepAfterManyPersistsDoesNotCollapse() {
        val start = 1_000_000_000_000L
        // Jornada de 10 min acumulada por el servicio, anclada hace 5 s.
        val persisted = 10 * min
        val anchor = start + 2 * h         // da igual: el ancla manda, no el inicio
        // El reloj retrocede 2 h: now queda ANTES del anchor (e incluso del start).
        val now = anchor - 2 * h
        val display = JourneyFormatter.displayElapsedMs(persisted, anchor, start, now)
        // No colapsa a 0 ni negativa: gap acotado a 0, elapsed intacto.
        assertEquals(persisted, display)
        val (hours, minutes) = JourneyFormatter.durationParts(display)
        assertEquals(0L, hours)
        assertEquals(10L, minutes)
    }

    @Test
    fun ntpForwardStepLeavesDurationUnaffected() {
        val start = 1_000_000_000_000L
        // Secuencia real: jornada de 10 min de reloj monotónico; a los 10 min el
        // NTP adelanta el wall 5 h; el servicio persiste DESPUÉS del salto
        // (ancla en el wall nuevo, elapsed sigue siendo 10 min) y la UI lee 2 s
        // después con ese mismo wall.
        val jumped = 5 * h
        val persisted = 10 * min                       // monotónico: el salto no lo toca
        val anchor = start + 10 * min + jumped + 1_000L
        val now = anchor + 2_000L
        val display = JourneyFormatter.displayElapsedMs(persisted, anchor, start, now)
        // = elapsed + gap pequeño; sin las 5 h que sumaría now - start.
        assertEquals(persisted + 2_000L, display)
        assertNotEquals(now - start, display) // el naive sí estuviera inflado
        val (hours, minutes) = JourneyFormatter.durationParts(display)
        assertEquals(0L, hours)
        assertEquals(10L, minutes)
    }

    @Test
    fun neverReturnsNegative() {
        val start = 1_000_000_000_000L
        // now muy por detrás de todo (reloj reseteado a 1970, por ejemplo).
        val display = JourneyFormatter.displayElapsedMs(0L, start + min, start, 0L)
        assertEquals(0L, display)
        val neg = JourneyFormatter.displayElapsedMs(-5L, start, start, start + min)
        assertEquals(min, neg) // elapsed negativo se acota a 0
    }

    @Test
    fun serviceNeverPersistedFallsBackToStartAnchor() {
        val start = 1_000_000_000_000L
        val now = start + 42 * min
        // persisted=0/anchor=0 → comportamiento legacy: gap desde journeyStartAt.
        assertEquals(42 * min, JourneyFormatter.displayElapsedMs(0L, 0L, start, now))
        // Y nunca usa el ancla del servicio como inicio si el ancla no existe.
        assertEquals(0L, JourneyFormatter.displayElapsedMs(0L, 0L, start, start - min))
    }

    @Test
    fun noJourneyReturnsZero() {
        assertEquals(0L, JourneyFormatter.displayElapsedMs(10 * min, 1_234L, 0L, 9_999_999L))
        assertEquals(0L, JourneyFormatter.displayElapsedMs(0L, 0L, -1L, System.currentTimeMillis()))
    }

    // ---- seedElapsedOnRecovery (recuperación del TrackingService tras muerte) ----

    @Test
    fun recoveryRespectsPersistedElapsed() {
        val persisted = 3 * h + 17 * min
        val seeded = JourneyFormatter.seedElapsedOnRecovery(persisted, 1_000L, 1_000L + 10 * h)
        assertEquals(persisted, seeded)
    }

    @Test
    fun recoveryLegacySeedUsesNaiveWallCappedAt24h() {
        val start = 1_000_000_000_000L
        // Legacy (versión sin acumulador): naive = now - start.
        assertEquals(3 * h, JourneyFormatter.seedElapsedOnRecovery(0L, start, start + 3 * h))
        // Salto NTP hacia adelante brutal → techo de 24 h (no 48 h falsas).
        assertEquals(
            JourneyFormatter.MAX_SEED_ON_RECOVERY_MS,
            JourneyFormatter.seedElapsedOnRecovery(0L, start, start + 48 * h),
        )
        // Salto NTP hacia atrás (naive negativa) → suelo en 0, nunca negativo.
        assertEquals(0L, JourneyFormatter.seedElapsedOnRecovery(0L, start, start - 2 * h))
    }

    @Test
    fun recoveryWithoutJourneySeedsZero() {
        assertEquals(0L, JourneyFormatter.seedElapsedOnRecovery(0L, 0L, 1_000L))
        assertEquals(0L, JourneyFormatter.seedElapsedOnRecovery(0L, -5L, 1_000L))
        // persisted<0 basura → tampoco siembra jornada.
        assertEquals(0L, JourneyFormatter.seedElapsedOnRecovery(-1L, 0L, 1_000L))
    }
}
