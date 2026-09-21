package com.dmujeres.traccar.readiness

/**
 * Política pura (JVM) de la prueba de continuidad: el criterio PRINCIPAL del
 * DeviceReadinessGate. La prueba confirma que "el tracking continúa mientras
 * la pantalla está apagada": el servicio Android (otro dueño) alimenta los
 * fixes aceptados durante la ventana con pantalla apagada, la vivencia del
 * proceso y del FGS, la red disponible y la evidencia in-app de congelamiento
 * (un ticker de 1 s cuyos saltos de elapsedRealtime delatan freeze del
 * proceso, frozenSeconds > 0).
 * Sin Android: las lecturas y el cronometraje los hace el llamador.
 */
object ContinuityTestPolicy {

    /** Ventana de observación con pantalla apagada. */
    const val WINDOW_MS = 90_000L

    /** Causa del fallo (o NONE). Nunca fabricar una causa sin evidencia. */
    enum class Cause { NONE, OEM_FREEZE, NO_CALLBACK, PROCESS_DEAD, FGS_DEAD }

    data class Outcome(
        val state: String,        // PASS | FAILED | INCONCLUSIVE
        val cause: Cause,
        val fixesDuringOff: Int,
        val screenOffMs: Long,    // epoch del SCREEN_OFF (0 = sin evento)
        val windowMs: Long,       // ventana real observada (<= WINDOW_MS)
        val frozenSeconds: Int,   // 0 = sin congelamiento observado
        val note: String,
    )

    /**
     * Evalúa la prueba de continuidad.
     * @param fixesDuringOff fixes aceptados con pantalla apagada (>=0)
     * @param processAlive el proceso sigue vivo al evaluar
     * @param fgsAlive el FGS sigue en foreground al evaluar
     * @param networkAvailable red disponible durante la ventana (para descartar INCONCLUSIVE)
     * @param frozenSeconds evidencia de congelamiento del proceso (0 = ninguna)
     * @param oemGuidePresent el OEM tiene guía (para rotular OEM_FREEZE como causa probable)
     * @param screenOffMs epoch del SCREEN_OFF (0 = no se registró)
     * @param windowMs ventana real transcurrida
     */
    fun evaluate(
        fixesDuringOff: Int,
        processAlive: Boolean,
        fgsAlive: Boolean,
        networkAvailable: Boolean,
        frozenSeconds: Int,
        oemGuidePresent: Boolean,
        screenOffMs: Long,
        windowMs: Long,
    ): Outcome {
        // Sin ventana real no hubo observación: INCONCLUSIVE, nunca PASS ni FAIL.
        if (windowMs <= 0L) {
            return Outcome(
                state = "INCONCLUSIVE",
                cause = Cause.NONE,
                fixesDuringOff = fixesDuringOff,
                screenOffMs = screenOffMs,
                windowMs = windowMs,
                frozenSeconds = frozenSeconds,
                note = "sin ventana observada",
            )
        }

        // El objetivo es que la captura CONTINÚE durante TODA la ventana: si el
        // propio proceso midió un congelamiento, hubo fixes tempranos pero la
        // captura se detuvo (patrón cfreezer) → FAIL, no PASS. Regla
        // aprobada: "cfreezer + 0 captura (tras el freeze) = FAIL", y los
        // fixes previos al freeze no salvan la continuidad interrumpida.
        if (frozenSeconds > 0) {
            val cause = if (oemGuidePresent) Cause.OEM_FREEZE else Cause.NO_CALLBACK
            val note = if (oemGuidePresent) {
                "cfreezer-style freeze: proceso congelado $frozenSeconds s; " +
                    "captura interrumpida con pantalla apagada ($fixesDuringOff fixes antes del freeze)"
            } else {
                "proceso congelado $frozenSeconds s sin guía OEM"
            }
            return Outcome(
                state = "FAILED",
                cause = cause,
                fixesDuringOff = fixesDuringOff,
                screenOffMs = screenOffMs,
                windowMs = windowMs,
                frozenSeconds = frozenSeconds,
                note = note,
            )
        }

        // PASS: la captura continuó con pantalla apagada (sin congelamiento
        // medido). No exige red: el estado PASS no depende del ACK de red.
        if (fixesDuringOff >= 1 && processAlive && fgsAlive) {
            return Outcome(
                state = "PASS",
                cause = Cause.NONE,
                fixesDuringOff = fixesDuringOff,
                screenOffMs = screenOffMs,
                windowMs = windowMs,
                frozenSeconds = frozenSeconds,
                note = "captura continuó con pantalla apagada",
            )
        }

        // Sin fixes y sin red no se puede confirmar ni desmentir: repetir.
        // Nunca es PASS.
        if (fixesDuringOff == 0 && !networkAvailable) {
            return Outcome(
                state = "INCONCLUSIVE",
                cause = Cause.NONE,
                fixesDuringOff = fixesDuringOff,
                screenOffMs = screenOffMs,
                windowMs = windowMs,
                frozenSeconds = frozenSeconds,
                note = "sin red durante la ventana; repetir",
            )
        }

        // Fallo con una sola causa por evidencia, en orden de especificidad.
        val cause: Cause
        val note: String
        when {
            !processAlive -> {
                // Gana PROCESS_DEAD aunque hubiera fixes: si el proceso murió
                // después de capturar, el PASS ya no es válido.
                cause = Cause.PROCESS_DEAD
                note = "el proceso murió durante la ventana"
            }
            !fgsAlive -> {
                cause = Cause.FGS_DEAD
                note = "el FGS dejó de estar en primer plano"
            }
            else -> {
                cause = Cause.NO_CALLBACK
                note = "sin fixes con pantalla apagada y sin congelamiento medido"
            }
        }
        return Outcome(
            state = "FAILED",
            cause = cause,
            fixesDuringOff = fixesDuringOff,
            screenOffMs = screenOffMs,
            windowMs = windowMs,
            frozenSeconds = frozenSeconds,
            note = note,
        )
    }
}
