package com.dmujeres.traccar.tracking

/**
 * Política pura (JVM) del guard de foreground anti-OEM: con pantalla apagada,
 * los fabricantes (ZTE CpuFreezer, HiOS/Infinix, MIUI) des-promueven el FGS y
 * congelan el proceso sin re-promoverlo jamás. Esta tabla decide, con las
 * señales legibles del sistema, tres cosas:
 * - Qué restricciones BLOQUEAN el inicio de la jornada (arrancar es morir en
 *   segundos: background-restricted por appops o bucket de reposo RESTRICTED).
 * - Qué señales solo advierten (bucket RARE: arrancar igual, avisar una vez).
 * - El periodo del guardián de sesión: acelerado en marcha (el OEM puede matar
 *   el proceso en carretera y perder ruta real) y base en quietud.
 * Sin Android: las lecturas (try/catch) las hace el llamador.
 */
object ForegroundGuardPolicy {

    // ActivityManager.isBackgroundRestricted() existe desde API 28 (appops).
    const val SDK_BG_RESTRICTED = 28
    // El bucket RESTRICTED (45) solo se reporta con fiabilidad desde API 30.
    const val SDK_STANDBY_RESTRICTED = 30

    // Buckets de UsageStatsManager.getAppStandbyBucket() usados en la decisión.
    const val STANDBY_EXEMPTED = 5
    const val STANDBY_ACTIVE = 10
    const val STANDBY_RARE = 40
    const val STANDBY_RESTRICTED = 45

    /** true = no iniciar la jornada: el sistema tiene la app en restricción dura. */
    fun shouldBlockStart(isBackgroundRestricted: Boolean, standbyBucket: Int, sdkInt: Int): Boolean {
        if (sdkInt >= SDK_BG_RESTRICTED && isBackgroundRestricted) return true
        if (sdkInt >= SDK_STANDBY_RESTRICTED && standbyBucket == STANDBY_RESTRICTED) return true
        return false
    }

    /** true = iniciar igual, pero avisar al usuario una vez por sesión. */
    fun shouldWarnStart(isBackgroundRestricted: Boolean, standbyBucket: Int, sdkInt: Int): Boolean =
        !shouldBlockStart(isBackgroundRestricted, standbyBucket, sdkInt) &&
            sdkInt >= SDK_BG_RESTRICTED &&
            (isBackgroundRestricted || standbyBucket == STANDBY_RARE)

    /**
     * Periodo del guardián de sesión (1.1.8): con JORNADA ACTIVA la cadencia
     * es de 2 min SIEMPRE (no solo en marcha). Evidencia real: en equipos con
     * proceso congelado (ZTE/HONOR/Infinix) el modo adaptativo nunca cambia a
     * MOVING, así que la cadena quedaba en 15 min y la ruta salía con ~4
     * puntos/hora; los despertares SÍ funcionan (cadencias exactas medidas),
     * por lo que 2 min multiplica los puntos sin depender del OEM.
     * Sin jornada: 15 min (SessionKeeper.PERIOD_MS) — no hay nada que proteger.
     */
    fun keeperPeriodMs(moving: Boolean, journeyActive: Boolean = true): Long = when {
        !journeyActive -> 900_000L
        else -> 120_000L
    }

    /** Umbrales de la evidencia de viaje con fixes ralos (1.1.8). */
    const val KEEPER_FAST_MIN_DT_MS = 90_000L
    const val KEEPER_FAST_MIN_MOVE_M = 100.0

    /**
     * ¿El fix recién aceptado evidencia un viaje con fixes ralos? (proceso
     * congelado que despierta cada N min: el fix trae >= 100 m de desplazamiento
     * real desde el anterior). Con true la cadena anti-OEM va a 2 min.
     */
    fun keeperFastByDisplacement(dtMs: Long, movedM: Double): Boolean =
        dtMs >= KEEPER_FAST_MIN_DT_MS && movedM >= KEEPER_FAST_MIN_MOVE_M
}
