package com.dmujeres.traccar.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 1: alarma exacta del guardián solo con permiso/capacidad disponible. */
class SessionKeeperAlarmPolicyTest {

    @Test
    fun apiViejaSiempreInexacta() {
        assertFalse(SessionKeeperAlarmPolicy.useExact(30, canScheduleExact = true))
        assertFalse(SessionKeeperAlarmPolicy.useExact(26, canScheduleExact = true))
    }

    @Test
    fun api31SinCapacidadSigueInexacta() {
        assertFalse(SessionKeeperAlarmPolicy.useExact(31, canScheduleExact = false))
        assertFalse(SessionKeeperAlarmPolicy.useExact(34, canScheduleExact = false))
    }

    @Test
    fun api31ConExencionDeBateriaUsaExacta() {
        // Con exención de batería, canScheduleExactAlarms() devuelve true y no
        // hace falta SCHEDULE_EXACT_ALARM (fuente: AlarmManager, fase 1).
        assertTrue(SessionKeeperAlarmPolicy.useExact(31, canScheduleExact = true))
        assertTrue(SessionKeeperAlarmPolicy.useExact(34, canScheduleExact = true))
        assertTrue(SessionKeeperAlarmPolicy.useExact(36, canScheduleExact = true))
    }
}
