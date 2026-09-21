package com.dmujeres.traccar.recovery

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/**
 * Evidencia post-hoc de congelado OEM (API 33+): Android registra por qué
 * murió cada instancia del proceso. REASON_FREEZER = la ROM lo congeló
 * (cached-app freezer o gestor propio, p.ej. PowerKit/PowerGenie de Honor).
 *
 * No puede evitar el congelado; sirve para que el asistente de puesta a punto
 * y el diagnóstico digan la verdad ("tu equipo congeló la app N veces").
 */
object FreezeEvidence {

    /** ApplicationExitInfo es API 33+ (TIRAMISU). */
    const val MIN_SDK = 33

    fun freezeCount(context: Context, limit: Int = 10): Int {
        if (Build.VERSION.SDK_INT < MIN_SDK) return 0
        val am = context.getSystemService(ActivityManager::class.java) ?: return 0
        return runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, 0, limit)
                .count { it.reason == ApplicationExitInfo.REASON_FREEZER }
        }.getOrDefault(0)
    }
}
