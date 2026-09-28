package org.traccar.client

/** Estado del canal de avisos (FCM) visto por el refresco manual. */
enum class FirebaseState { OK, FAIL, NA }

/** Estado de la consulta de configuración remota. */
enum class ConfigState { OK, UPDATED, NA }

/**
 * Resumen honesto del refresco manual. El texto vive en strings.xml y se
 * muestra en el botón ACTUALIZAR (una línea, con ellipsize).
 */
enum class RefreshSummary(val textRes: Int, val allGood: Boolean) {
    JOURNEY_CLOSED(R.string.refresh_summary_journey_closed, false),
    NO_NETWORK(R.string.refresh_summary_no_net, false),
    SERVER_DOWN(R.string.refresh_summary_server_off, false),
    GPS_OFF(R.string.refresh_summary_gps_off, false),
    PENDING_UNSENT(R.string.refresh_summary_pending, false),
    ALL_GOOD_WARNINGS(R.string.refresh_summary_all_good_warnings, true),
    ALL_GOOD_CONFIG(R.string.refresh_summary_all_good_config, true),
    ALL_GOOD(R.string.refresh_step_done, true),
}

/**
 * Resultado real de cada paso del refresco (lógica pura, testeable en JVM).
 *
 * Prioridad del resumen: jornada cerrada > sin red > servidor > GPS >
 * pendientes > avisos/config. Los avisos (FCM sin token) y la config sin
 * consultar no son fallos: si todo lo demás está bien, el resumen sigue
 * siendo bueno con la coletilla del aviso.
 */
data class RefreshOutcome(
    val journeyOpen: Boolean = true,
    val pendingBefore: Int = 0,
    val pendingAfter: Int = 0,
    val gpsOn: Boolean = true,
    val online: Boolean = true,
    val serverOk: Boolean = true,
    val firebase: FirebaseState = FirebaseState.OK,
    val config: ConfigState = ConfigState.OK,
) {

    /** Enviados en este refresco (nunca negativo). */
    val sent: Int get() = (pendingBefore - pendingAfter).coerceAtLeast(0)

    fun summary(): RefreshSummary = when {
        !journeyOpen -> RefreshSummary.JOURNEY_CLOSED
        !online -> RefreshSummary.NO_NETWORK
        !serverOk -> RefreshSummary.SERVER_DOWN
        !gpsOn -> RefreshSummary.GPS_OFF
        pendingAfter > 0 -> RefreshSummary.PENDING_UNSENT
        firebase == FirebaseState.FAIL -> RefreshSummary.ALL_GOOD_WARNINGS
        config == ConfigState.NA -> RefreshSummary.ALL_GOOD_CONFIG
        else -> RefreshSummary.ALL_GOOD
    }
}
