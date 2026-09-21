package com.dmujeres.traccar.platform

/**
 * P1 (R3.5-A): políticas PURAS de actualización OTA. El versionCode es la
 * autoridad; las URLs de release deben ser HTTPS (excepción legado explícita
 * mientras no haya TLS en producción); la instalación tiene puerta de
 * seguridad para no reiniciar la app en momentos peligrosos.
 *
 * Sin efectos colaterales: todo testeable en JVM.
 */
object OtaVersionPolicy {

    enum class Decision { UP_TO_DATE, UPDATE_AVAILABLE, INVALID_METADATA }

    /**
     * @param installedCode versionCode instalado (BuildConfig.VERSION_CODE)
     * @param latestCode versionCode publicado (null si el canal no lo trae)
     * @param latestName versionName publicado (fallback de comparación)
     * @param minVersionCode si el publicado no supera este mínimo y el
     *        instalado es menor, la actualización se considera requerida
     *        (sigue siendo UPDATE_AVAILABLE; la UI puede marcarla obligatoria).
     */
    fun decide(
        installedCode: Int,
        latestCode: Int?,
        latestName: String?,
        installedName: String?,
        minVersionCode: Int? = null,
    ): Decision {
        if (latestCode != null && latestCode >= 0) {
            return when {
                latestCode > installedCode -> Decision.UPDATE_AVAILABLE
                latestCode < installedCode -> Decision.UP_TO_DATE // nunca downgrade
                else -> {
                    // Mismo código: solo aceptar si el nombre es estrictamente mayor
                    // (empates de nombre con mismo código = sin update).
                    val name = latestName.orEmpty()
                    val installed = installedName.orEmpty()
                    if (name.isNotBlank() && installed.isNotBlank() &&
                        UpdateManager.isNewer(installed, name)
                    ) {
                        Decision.UPDATE_AVAILABLE
                    } else {
                        Decision.UP_TO_DATE
                    }
                }
            }
        }
        // Canal sin versionCode (legado): comparación por nombre, sin downgrade.
        val name = latestName.orEmpty()
        if (name.isBlank()) return Decision.INVALID_METADATA
        if (minVersionCode != null && installedCode < minVersionCode) return Decision.UPDATE_AVAILABLE
        return if (UpdateManager.isNewer(installedName.orEmpty(), name)) {
            Decision.UPDATE_AVAILABLE
        } else {
            Decision.UP_TO_DATE
        }
    }

    /** ¿La actualización es obligatoria por minVersionCode? */
    fun isMandatory(installedCode: Int, minVersionCode: Int?): Boolean =
        minVersionCode != null && installedCode < minVersionCode
}

object OtaUrlPolicy {

    /**
     * Host de producción legado servido por HTTP mientras no exista TLS.
     * SEGURIDAD: eliminar esta excepción cuando `OTA_PUBLIC_BASE_URL` sea
     * HTTPS (INFRA_PENDING documentado en FINAL_CLOSURE_AUDIT).
     */
    val LEGACY_HTTP_HOSTS = setOf("68.168.20.219")

    /**
     * @param release true en builds de release (BuildConfig.DEBUG == false)
     * @return true si la URL es aceptable para consultar/descargar OTA.
     */
    fun isAllowed(url: String?, release: Boolean): Boolean {
        val u = url?.trim().orEmpty()
        if (u.isEmpty()) return false
        val parsed = runCatching { java.net.URI(u) }.getOrNull() ?: return false
        val scheme = parsed.scheme?.lowercase() ?: return false
        val host = parsed.host?.lowercase() ?: return false
        if (scheme == "https") return true
        if (scheme != "http") return false
        if (!release) return true // debug/dev: LAN y localhost permitidos
        // Release + HTTP: SOLO la excepción legado documentada.
        return host in LEGACY_HTTP_HOSTS
    }
}

object OtaInstallPolicy {

    enum class Decision { INSTALL_NOW, POSTPONE }

    /**
     * Puerta de instalación segura: NO reiniciar la app si hay recovery activo
     * o una jornada recién iniciada (riesgo de perder el arranque de captura).
     * El APK verificado se conserva y el estado queda UPDATE_READY.
     */
    fun canInstallNow(recoveryActive: Boolean, journeyJustStarted: Boolean): Decision =
        if (recoveryActive || journeyJustStarted) Decision.POSTPONE else Decision.INSTALL_NOW

    /** Una jornada se considera "recién iniciada" dentro de esta ventana. */
    const val JOURNEY_JUST_STARTED_MS = 2 * 60 * 1000L

    fun journeyJustStarted(journeyStartAtMs: Long, nowMs: Long): Boolean =
        journeyStartAtMs > 0L && nowMs - journeyStartAtMs < JOURNEY_JUST_STARTED_MS
}
