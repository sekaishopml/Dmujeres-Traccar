package com.dmujeres.traccar.oem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fase 1: política pura de la "Puesta a punto" por dispositivo. */
class SetupChecklistPolicyTest {

    private fun facts(
        sdkInt: Int = 34,
        vendor: String? = "infinix",
        fine: Boolean = true,
        background: Boolean = true,
        notifications: Boolean = true,
        battery: Boolean = true,
        exact: Boolean = true,
        install: Boolean = true,
        freezes: Int = 0,
    ) = SetupChecklistPolicy.Facts(
        fineLocation = fine,
        backgroundLocation = background,
        notifications = notifications,
        batteryExempt = battery,
        exactAlarmsAvailable = exact,
        canInstallUpdates = install,
        freezeSignals = freezes,
        vendor = vendor,
        sdkInt = sdkInt,
    )

    @Test
    fun ordenIncluyeTodosLosPasosAplicables() {
        val steps = SetupChecklistPolicy.stepsFor("infinix", 34)
        assertEquals(
            listOf(
                SetupChecklistPolicy.StepId.LOCATION,
                SetupChecklistPolicy.StepId.BACKGROUND_LOCATION,
                SetupChecklistPolicy.StepId.NOTIFICATIONS,
                SetupChecklistPolicy.StepId.BATTERY_EXEMPTION,
                SetupChecklistPolicy.StepId.EXACT_ALARMS,
                SetupChecklistPolicy.StepId.OEM_SCREENS,
                SetupChecklistPolicy.StepId.INSTALL_UPDATES,
                SetupChecklistPolicy.StepId.FREEZE_WATCH,
            ),
            steps,
        )
    }

    @Test
    fun androidViejoNoPideFondoNiNotificacionesNiFreeze() {
        val steps = SetupChecklistPolicy.stepsFor(null, 28)
        assertFalse(steps.contains(SetupChecklistPolicy.StepId.BACKGROUND_LOCATION))
        assertFalse(steps.contains(SetupChecklistPolicy.StepId.NOTIFICATIONS))
        assertFalse(steps.contains(SetupChecklistPolicy.StepId.FREEZE_WATCH))
        assertFalse(steps.contains(SetupChecklistPolicy.StepId.OEM_SCREENS))
    }

    @Test
    fun sinVendorNoHayPasoOem() {
        val steps = SetupChecklistPolicy.stepsFor("motorola", 34)
        assertFalse(steps.contains(SetupChecklistPolicy.StepId.OEM_SCREENS))
        val stepsXiaomi = SetupChecklistPolicy.stepsFor("xiaomi", 34)
        assertTrue(stepsXiaomi.contains(SetupChecklistPolicy.StepId.OEM_SCREENS))
    }

    @Test
    fun todoCubiertoQuedaVerifiedMenosOem() {
        val f = facts()
        assertTrue(SetupChecklistPolicy.isSettled(f))
        assertEquals(
            SetupChecklistPolicy.StepState.GUIDED,
            SetupChecklistPolicy.stateOf(SetupChecklistPolicy.StepId.OEM_SCREENS, f),
        )
    }

    @Test
    fun permisosFaltantesSeMarcanActionRequired() {
        val f = facts(fine = false, background = false, notifications = false)
        val pending = SetupChecklistPolicy.pendingActions(f)
        assertTrue(pending.contains(SetupChecklistPolicy.StepId.LOCATION))
        assertTrue(pending.contains(SetupChecklistPolicy.StepId.BACKGROUND_LOCATION))
        assertTrue(pending.contains(SetupChecklistPolicy.StepId.NOTIFICATIONS))
        assertFalse(SetupChecklistPolicy.isSettled(f))
    }

    @Test
    fun bateriaNoExentaPideAccionPeroAlarmaExactaQuedaGuiada() {
        val f = facts(battery = false, exact = false)
        assertEquals(
            SetupChecklistPolicy.StepState.ACTION_REQUIRED,
            SetupChecklistPolicy.stateOf(SetupChecklistPolicy.StepId.BATTERY_EXEMPTION, f),
        )
        // Sin exactas sigue habiendo guardián inexacto: no es bloqueo.
        assertEquals(
            SetupChecklistPolicy.StepState.GUIDED,
            SetupChecklistPolicy.stateOf(SetupChecklistPolicy.StepId.EXACT_ALARMS, f),
        )
    }

    @Test
    fun congeladosDetectadosPidenAccionYDiagnostico() {
        val f = facts(freezes = 2)
        assertEquals(
            SetupChecklistPolicy.StepState.ACTION_REQUIRED,
            SetupChecklistPolicy.stateOf(SetupChecklistPolicy.StepId.FREEZE_WATCH, f),
        )
        assertTrue(SetupChecklistPolicy.pendingActions(f).contains(SetupChecklistPolicy.StepId.FREEZE_WATCH))
    }

    @Test
    fun sinInstalarAppsDesconocidasPideAccion() {
        val f = facts(install = false)
        assertEquals(
            SetupChecklistPolicy.StepState.ACTION_REQUIRED,
            SetupChecklistPolicy.stateOf(SetupChecklistPolicy.StepId.INSTALL_UPDATES, f),
        )
    }
}
