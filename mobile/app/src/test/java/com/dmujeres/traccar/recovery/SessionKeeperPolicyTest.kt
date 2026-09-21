package com.dmujeres.traccar.recovery

import com.dmujeres.traccar.recovery.SessionKeeperPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionKeeperPolicyTest {

    @Test
    fun jornadaActivaSinServicioRevive() {
        assertEquals(Decision.START, SessionKeeperPolicy.decide(true, false, false))
    }

    @Test
    fun sinJornadaNoRevive() {
        assertEquals(Decision.SKIP_NOT_ACTIVE, SessionKeeperPolicy.decide(false, false, false))
        assertEquals(Decision.SKIP_NOT_ACTIVE, SessionKeeperPolicy.decide(false, false, true))
    }

    @Test
    fun servicioVivoNoRevive() {
        assertEquals(Decision.SKIP_RUNNING, SessionKeeperPolicy.decide(true, true, false))
    }

    @Test
    fun stopPendienteLoManejaElCicloDeStop() {
        assertEquals(Decision.SKIP_STOP_PENDING, SessionKeeperPolicy.decide(true, false, true))
    }
}
