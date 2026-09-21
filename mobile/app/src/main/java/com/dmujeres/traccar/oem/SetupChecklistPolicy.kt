package com.dmujeres.traccar.oem

/**
 * Fase 1 "Puesta a punto": checklist por dispositivo y versión de Android.
 * Política PURA (JVM): no toca Android; recibe hechos ya recolectados.
 *
 * Regla de honestidad: solo los pasos con API se marcan VERIFIED; las
 * pantallas OEM y las alarmas exactas derivadas nunca se declaran aplicadas
 * (GUIDED) porque Android no permite leerlas.
 */
object SetupChecklistPolicy {

    enum class StepId {
        LOCATION,
        BACKGROUND_LOCATION,
        NOTIFICATIONS,
        BATTERY_EXEMPTION,
        EXACT_ALARMS,
        OEM_SCREENS,
        INSTALL_UPDATES,
        FREEZE_WATCH,
    }

    enum class StepState {
        /** Comprobado por API. */
        VERIFIED,

        /** Hay que pedirlo (permiso o diálogo del sistema). */
        ACTION_REQUIRED,

        /** Solo se puede abrir la pantalla; el ajuste lo confirma la persona. */
        GUIDED,

        /** No aplica en esta versión de Android. */
        NOT_APPLICABLE,
    }

    data class Facts(
        val fineLocation: Boolean,
        val backgroundLocation: Boolean,
        val notifications: Boolean,
        val batteryExempt: Boolean,
        val exactAlarmsAvailable: Boolean,
        val canInstallUpdates: Boolean,
        val freezeSignals: Int,
        val vendor: String?,
        val sdkInt: Int,
    )

    /** Pasos aplicables en orden, por API y fabricante. */
    fun stepsFor(vendor: String?, sdkInt: Int): List<StepId> = buildList {
        add(StepId.LOCATION)
        if (sdkInt >= 29) add(StepId.BACKGROUND_LOCATION)
        if (sdkInt >= 33) add(StepId.NOTIFICATIONS)
        add(StepId.BATTERY_EXEMPTION)
        if (sdkInt >= 31) add(StepId.EXACT_ALARMS)
        if (VendorSettings.intentChainFor(vendor).isNotEmpty()) add(StepId.OEM_SCREENS)
        add(StepId.INSTALL_UPDATES)
        if (sdkInt >= 33) add(StepId.FREEZE_WATCH)
    }

    fun stateOf(step: StepId, facts: Facts): StepState = when (step) {
        StepId.LOCATION ->
            if (facts.fineLocation) StepState.VERIFIED else StepState.ACTION_REQUIRED
        StepId.BACKGROUND_LOCATION ->
            if (facts.sdkInt < 29 || facts.backgroundLocation) StepState.VERIFIED else StepState.ACTION_REQUIRED
        StepId.NOTIFICATIONS ->
            if (facts.sdkInt < 33 || facts.notifications) StepState.VERIFIED else StepState.ACTION_REQUIRED
        StepId.BATTERY_EXEMPTION ->
            if (facts.batteryExempt) StepState.VERIFIED else StepState.ACTION_REQUIRED
        // Si la exención de batería ya dio alarmas exactas: verificado; si no,
        // el guardián sigue en inexacta (válido) y se informa como guiado.
        StepId.EXACT_ALARMS ->
            if (facts.exactAlarmsAvailable) StepState.VERIFIED else StepState.GUIDED
        // Las pantallas OEM nunca son verificables por API.
        StepId.OEM_SCREENS -> StepState.GUIDED
        StepId.INSTALL_UPDATES ->
            if (facts.canInstallUpdates) StepState.VERIFIED else StepState.ACTION_REQUIRED
        // Señales de congelado (ApplicationExitInfo): 0 = sin evidencia.
        StepId.FREEZE_WATCH ->
            if (facts.freezeSignals <= 0) StepState.VERIFIED else StepState.ACTION_REQUIRED
    }

    /** Pasos que requieren acción de la persona, en orden. */
    fun pendingActions(facts: Facts): List<StepId> =
        stepsFor(facts.vendor, facts.sdkInt).filter { stateOf(it, facts) == StepState.ACTION_REQUIRED }

    /** ¿Todo lo verificable está resuelto? */
    fun isSettled(facts: Facts): Boolean = pendingActions(facts).isEmpty()
}
