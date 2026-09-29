package org.traccar.client

/**
 * Decisión PURA del aviso de actualización (JVM, sin Android ni red).
 *
 * Regla del dueño: el banner sale SIEMPRE que haya una versión mayor publicada
 * y la instalada sea menor, en cada apertura y cada minuto, sin ningún estado
 * que lo suprima salvo actualizar. No existe "ya visto": ante un error de red
 * no se marca nada como visto, se reintenta en el siguiente ciclo.
 *
 * El freno de [CHECK_GAP_MS] solo limita la RED (anti-spam: como máximo una
 * consulta por minuto con la app abierta); nunca suprime el aviso. La apertura
 * en frío ([coldStart]) consulta siempre, así que reabrir rápido no puede dejar
 * a ciegas. [MAX_DEFER_AFTER_OPEN_MS] es el tope absoluto: aunque el freno
 * creciera en el futuro, tras abrir nunca se bloquea más de ~2 min.
 */
object OtaPolicy {

    /** Freno anti-spam de RED entre chequeos con la app abierta (1 min). */
    const val CHECK_GAP_MS = 60_000L

    /** Tope absoluto de bloqueo tras abrir (~2 min). */
    const val MAX_DEFER_AFTER_OPEN_MS = 120_000L

    /**
     * ¿Hay versión mayor publicada? Comparación por versionCode (canal del
     * servidor). Igual o menor = no hay aviso (sin downgrade).
     */
    fun isUpdateAvailable(publishedVersionCode: Int, installedVersionCode: Int): Boolean =
        publishedVersionCode > installedVersionCode

    /**
     * ¿La versión publicada (nombre "X.Y.Z", canal GitHub) es mayor que la
     * instalada? Numérica por segmento, sin downgrade: un segmento no numérico
     * vale 0 y nunca gana (p. ej. un sufijo "-beta" no supera a la release).
     */
    fun isNewerName(installed: String, candidate: String): Boolean {
        val installedParts = installed.split(".")
        val publishedParts = candidate.split(".")
        for (i in 0 until maxOf(installedParts.size, publishedParts.size)) {
            val a = installedParts.getOrNull(i)?.toIntOrNull() ?: 0
            val b = publishedParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (a != b) return b > a
        }
        return false
    }

    /**
     * ¿Toca consultar ahora? La apertura en frío siempre consulta (el banner se
     * deriva de lo publicado en cada apertura). Con la app abierta se respeta
     * el freno de 1 min, con tope absoluto de ~2 min. Un error no escribe
     * "visto": solo avanza el sello de tiempo y el siguiente ciclo reintenta.
     */
    fun shouldCheck(nowMs: Long, lastCheckMs: Long, coldStart: Boolean): Boolean {
        if (coldStart) return true
        if (lastCheckMs <= 0L) return true
        if (lastCheckMs > nowMs) return true // reloj movido hacia atrás: no quedar a ciegas
        val elapsed = nowMs - lastCheckMs
        if (elapsed >= MAX_DEFER_AFTER_OPEN_MS) return true
        return elapsed >= CHECK_GAP_MS
    }
}
