package com.dmujeres.traccar.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests del RecoveryJournal ampliado (eventos finos + motivos). */
class RecoveryJournalExtendedTest {

    @Test
    fun formatRecoveryEventEsAuditables() {
        val line = RecoveryJournal.formatRecoveryEvent(
            RecoveryJournal.RecoveryEventType.RECOVERY_ATTEMPT,
            RecoveryJournal.RecoveryReason.OEM_FREEZE,
            sessionId = "s1",
            attemptId = "a1",
            atMs = 1234L,
        )
        assertTrue(line.contains("eventType=RECOVERY_ATTEMPT"))
        assertTrue(line.contains("reason=OEM_FREEZE"))
        assertTrue(line.contains("sessionId=s1"))
        assertTrue(line.contains("attemptId=a1"))
        assertTrue(line.contains("payloadVersion=1"))
    }

    @Test
    fun successMapeaAOk() {
        assertEquals(
            RecoveryJournal.RESULT_OK,
            RecoveryJournal.resultFor(RecoveryJournal.RecoveryEventType.RECOVERY_SUCCESS),
        )
    }

    @Test
    fun failedYBlockedMapeanABlocked() {
        assertEquals(
            RecoveryJournal.RESULT_BLOCKED,
            RecoveryJournal.resultFor(RecoveryJournal.RecoveryEventType.RECOVERY_FAILED),
        )
        assertEquals(
            RecoveryJournal.RESULT_BLOCKED,
            RecoveryJournal.resultFor(RecoveryJournal.RecoveryEventType.RECOVERY_BLOCKED),
        )
    }

    @Test
    fun eventosIntermediosQuedanPendientes() {
        assertEquals(
            RecoveryJournal.RESULT_PENDING,
            RecoveryJournal.resultFor(RecoveryJournal.RecoveryEventType.RECOVERY_ATTEMPT),
        )
        assertEquals(
            RecoveryJournal.RESULT_PENDING,
            RecoveryJournal.resultFor(RecoveryJournal.RecoveryEventType.RECOVERY_RECEIVED),
        )
    }

    @Test
    fun motivosIncluyenLosDelMasterPrompt() {
        val reasons = RecoveryJournal.RecoveryReason.values().map { it.name }
        listOf(
            "PROCESS_DEAD", "FGS_DEAD", "NO_CALLBACK", "NETWORK_DOWN", "SERVER_SILENT",
            "OEM_FREEZE", "FCM_DEPRIORITIZED", "PERMISSION", "SECURITY_EXCEPTION", "UNKNOWN",
        ).forEach { assertTrue(reasons.contains(it)) }
    }

    @Test
    fun apiExistenteIntacta() {
        // Los callers existentes siguen teniendo las constantes/funciones.
        assertEquals("ok", RecoveryJournal.RESULT_OK)
        assertEquals("blocked", RecoveryJournal.RESULT_BLOCKED)
        assertEquals("ok-pending", RecoveryJournal.RESULT_PENDING)
    }
}
