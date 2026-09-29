package org.traccar.client.sync

/**
 * Decisión PURA de pausa/reanudación de la cola ante 401/403 (JVM, sin Android).
 *
 * Hallazgo de jornada completa: la cola se pausaba (PAUSED) y no se reanudaba
 * nunca —ni tras entrar de nuevo (token nuevo válido), ni tras limpiar la
 * sesión por 401 con token (la app debe seguir con la clave compartida hasta
 * el próximo login). La captura seguía (disco) pero la subida quedaba muerta
 * toda la jornada salvo refresco manual.
 *
 * Reglas (sin tocar protocolo ni cadencias):
 * - 401 CON token = token muerto: se limpia la sesión y se reintenta UNA vez
 *   con la clave compartida (fallback de flota). Si esa también falla, la
 *   siguiente vuelta pausa de verdad. Nunca se borra dato no confirmado.
 * - Cualquier otro PAUSED (401 sin token = clave compartida inválida, 403) =
 *   pausa latcheada hasta que cambie el material de auth o el usuario refresque.
 * - Cambio de material (token o clave distintos a los del momento de la pausa)
 *   = reanudación automática: entrar de nuevo reanuda sin tocar nada más.
 */
object UploadAuthPolicy {

    /**
     * ¿La pausa debe latchearse? false = 401 con token: no latchear, reintentar
     * una vez con la clave compartida tras limpiar la sesión.
     */
    fun shouldLatchPause(httpCode: Int, hadToken: Boolean): Boolean =
        !(hadToken && httpCode == 401)

    /**
     * ¿Reanudar una cola pausada porque el material de auth cambió desde la
     * pausa? Comparación por valor (token y clave vigentes vs. los
     * fotografiados al pausar). Sin cambio no se reanuda (evita el bucle de
     * reintentos contra el mismo 401).
     */
    fun shouldResumeAfterAuthChange(
        pausedToken: String,
        pausedApiKey: String,
        currentToken: String,
        currentApiKey: String,
    ): Boolean = pausedToken != currentToken || pausedApiKey != currentApiKey
}
