package com.dmujeres.traccar.util

/**
 * Wrapper mínimo de breadcrumbs de Sentry (el SDK se inicializa a mano en
 * DmujeresApp.initCrashReporting; sin DSN o sin init, addBreadcrumb es no-op).
 *
 * Todo va en runCatching: una breadcrumb NUNCA puede tumbar el journey, el
 * watchdog ni un receiver, y así el resto del código (y los tests JVM) no
 * necesita a Sentry en el classpath en tiempo de ejecución.
 */
object SentryLog {

    /** type: "journey" | "diag" | ... | category: subsystem | message: texto corto. */
    fun breadcrumb(type: String, category: String, message: String) {
        runCatching {
            val crumb = io.sentry.Breadcrumb().apply {
                this.type = type
                this.category = category
                this.message = message
            }
            io.sentry.Sentry.addBreadcrumb(crumb)
        }
    }
}
