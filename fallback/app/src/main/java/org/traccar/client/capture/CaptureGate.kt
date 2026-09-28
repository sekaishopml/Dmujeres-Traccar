package org.traccar.client.capture

/**
 * Orquestador con estado de los filtros de captura (JVM, sin Android).
 *
 * Orden por fix (lo usa `TrackingController`):
 * 1. [isTeleport]: rechazo de error ANTES de que el salto contamine la
 *    máquina, el último fix o el vigilante. Si es teleport se descarta y no se
 *    toca nada más.
 * 2. [evaluate]: el fix ya pasó el teleport; se registra como último aceptado
 *    (referencia fresca para el próximo, se almacene o no: un punto real pero
 *    redundante sigue siendo una referencia válida) y se decide si se
 *    almacena: giro (obliga) > movimiento (guarda) > colapso en parado
 *    (tira el ruido) > latido (un punto cada 5 min).
 *
 * Tiempos: las ventanas de datos (teleport, quietud sostenida, latido) usan el
 * tiempo de captura del GPS; el enfriamiento de giro (3 s) usa el reloj de
 * pared, porque limita ráfagas de entrega, no de captura.
 *
 * No toca cadencias, protocolo, jornada ni recuperación: solo dice guardar o
 * tirar cada fix. El antiduplicado de entrega ([DuplicateFixGuard] en
 * `write()`) sigue intacto como última red.
 */
class CaptureGate {

    /** Último fix ALMACENADO (referencia de distancia, latido y rumbo). */
    data class Stored(
        val capturedAtMs: Long,
        val lat: Double,
        val lon: Double,
        val courseDeg: Double,
    )

    /** Por qué se guardó o tiró el fix (diagnóstico, sin datos fabricados). */
    enum class Reason {
        STORE_FIRST,
        STORE_MOVE,
        STORE_TURN,
        STORE_HEARTBEAT,
        DROP_COLLAPSE,
        DROP_TELEPORT,
    }

    data class Outcome(val store: Boolean, val reason: Reason)

    private var lastStored: Stored? = null
    private var lastAccepted: TeleportGuard.Reference? = null
    private var lastTurnExtraMs: Long = Long.MIN_VALUE
    private var stillSinceMs: Long? = null

    /** Siembra con el último almacenado del historial (cero queries en régimen). */
    fun seed(capturedAtMs: Long, lat: Double, lon: Double, courseDeg: Double, accuracyM: Double) {
        lastStored = Stored(capturedAtMs, lat, lon, courseDeg)
        lastAccepted = TeleportGuard.Reference(capturedAtMs, lat, lon, accuracyM)
        stillSinceMs = null
        lastTurnExtraMs = Long.MIN_VALUE
    }

    /** ¿El fix es un salto imposible? No muta nada: solo juzga. */
    fun isTeleport(
        lat: Double,
        lon: Double,
        capturedAtMs: Long,
        accuracyM: Double,
        speedKn: Double,
    ): Boolean {
        val verdict = TeleportGuard.evaluate(
            TeleportGuard.Candidate(capturedAtMs, lat, lon, accuracyM, speedKn),
            lastAccepted,
        )
        return verdict == TeleportGuard.Verdict.REJECT
    }

    /**
     * Decide si el fix (ya no teleport) se almacena.
     *
     * @param stationary la máquina declaró STATIONARY para este fix.
     * @param nowMs reloj de pared (enfriamiento de giro).
     */
    fun evaluate(
        lat: Double,
        lon: Double,
        courseDeg: Double,
        accuracyM: Double,
        speedKn: Double,
        capturedAtMs: Long,
        stationary: Boolean,
        nowMs: Long,
    ): Outcome {
        // Referencia fresca para el próximo teleport, se almacene o no.
        lastAccepted = TeleportGuard.Reference(capturedAtMs, lat, lon, accuracyM)
        // Quietud sostenida para el colapso en ACTIVE (un pico aislado de 0
        // no colapsa: tiene que durar 60 s seguidos).
        val still = speedKn < StationaryCollapse.STILL_SPEED_KN
        val since = stillSinceMs
        if (still) {
            if (since == null) stillSinceMs = capturedAtMs
        } else {
            stillSinceMs = null
        }
        val sustained = still && since != null &&
            capturedAtMs - since >= StationaryCollapse.STILL_SUSTAINED_MS
        val stored = lastStored
        if (stored == null) {
            lastStored = Stored(capturedAtMs, lat, lon, courseDeg)
            return Outcome(true, Reason.STORE_FIRST)
        }
        val distM = Geo.distanceM(stored.lat, stored.lon, lat, lon)
        val elapsedMs = capturedAtMs - stored.capturedAtMs
        // El giro obliga aunque el colapso lo tiraría (la esquina no se corta).
        val sinceTurn = if (lastTurnExtraMs == Long.MIN_VALUE) Long.MAX_VALUE else nowMs - lastTurnExtraMs
        if (TurnCapture.isTurn(stored.courseDeg, courseDeg, speedKn, sinceTurn, true)) {
            lastStored = Stored(capturedAtMs, lat, lon, courseDeg)
            lastTurnExtraMs = nowMs
            return Outcome(true, Reason.STORE_TURN)
        }
        val keep = StationaryCollapse.shouldStore(
            stationary = stationary,
            stillSustained = sustained,
            speedKn = speedKn,
            distFromStoredM = distM,
            msSinceStored = elapsedMs,
            hasReference = true,
        )
        if (!keep) return Outcome(false, Reason.DROP_COLLAPSE)
        lastStored = Stored(capturedAtMs, lat, lon, courseDeg)
        val reason = if ((stationary || sustained) && elapsedMs >= StationaryCollapse.HEARTBEAT_MS) {
            Reason.STORE_HEARTBEAT
        } else {
            Reason.STORE_MOVE
        }
        return Outcome(true, reason)
    }
}
