package com.dmujeres.traccar.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.dmujeres.traccar.ui.MainActivity
import com.dmujeres.traccar.R
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.tracking.TrackingService
import com.dmujeres.traccar.core.JourneyFormatter

/**
 * Widget de inicio/fin de jornada para la pantalla de inicio.
 *
 * RemoteViews clásico (NO Glance): es la vía de máxima compatibilidad con
 * cualquier launcher/OEM (ZTE/Mifavor, Xiaomi/MIUI, Samsung...). Los widgets
 * de pantalla de bloqueo de terceros están bloqueados por Android moderno;
 * el camino compatible es el widget de inicio.
 *
 * Botones: cada uno lanza TrackingService vía PendingIntent.getForegroundService
 * con ACTION_START / ACTION_STOP (idempotente: el servicio se autoguarda).
 *
 * Refresco (v1):
 *  1) onUpdate → lee estado desde AppConfig y re-renderiza.
 *  2) El servicio empuja JourneyWidget.updateAll(context) cuando refresca su
 *     notificación (arranque, parada y refreshStateAndNotify).
 *  3) updatePeriodMillis del appwidget-provider como red de seguridad.
 * Limitación documentada: entre pulsaciones y push del servicio el contador de
 * minutos puede atrasarse hasta ~1 min; para v1 es aceptable y no se usan
 * AlarmManager/WorkManager desde este paquete (el worker package está vedado).
 */
class JourneyWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
        runCatching {
            val config = AppConfig(context)
            val journeyActive = config.trackingEnabled && config.journeyStartAt > 0L
            val elapsedMs = if (journeyActive) {
                JourneyWidgetState.elapsedDisplayMs(
                    config = config,
                    nowMs = System.currentTimeMillis(),
                )
            } else {
                0L
            }
            val online = config.netCause == "ok"
            val state = JourneyWidgetState.stateFor(
                trackingEnabled = config.trackingEnabled,
                journeyActive = journeyActive,
                elapsedMs = elapsedMs,
                online = online,
            )
            // 2x2: header arriba, estado centrado, botón abajo bien repartidos.
            manager.updateAppWidget(widgetId, buildViews(context, state))
        }.onFailure { Log.w(TAG, "No se pudo renderizar el widget $widgetId", it) }
    }

    private fun buildViews(context: Context, state: WidgetUiState): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_journey)

        // 2x2: header arriba, ESTADO grande centrado ("15 h 42 min"), botón abajo.
        views.setTextViewText(R.id.widget_title, context.getString(R.string.app_name))
        views.setTextViewText(
            R.id.widget_status,
            if (state.active) {
                JourneyWidgetState.formatDurationWords(state.durationMs)
            } else {
                context.getString(R.string.widget_idle)
            },
        )
        views.setTextViewText(
            R.id.widget_subtitle,
            when {
                state.active -> context.getString(R.string.widget_active_prefix)
                state.waitingHint -> context.getString(R.string.widget_waiting)
                else -> context.getString(R.string.widget_idle_hint)
            },
        )
        views.setTextViewText(
            R.id.widget_action,
            context.getString(
                if (state.active) R.string.action_end else R.string.action_start,
            ),
        )
        views.setInt(
            R.id.widget_action,
            "setBackgroundResource",
            buttonBackground(state.color),
        )

        views.setOnClickPendingIntent(
            R.id.widget_action,
            if (state.active) stopPendingIntent(context) else startPendingIntent(context),
        )
        // Tocar header o área de estado hace lo mismo que el botón: un solo
        // gesto útil en launchers que recortan el widget.
        views.setOnClickPendingIntent(
            R.id.widget_header,
            if (state.active) stopPendingIntent(context) else startPendingIntent(context),
        )
        views.setOnClickPendingIntent(
            R.id.widget_content,
            if (state.active) stopPendingIntent(context) else startPendingIntent(context),
        )
        return views
    }

    private fun buttonBackground(color: JourneyColorToken): Int = when (color) {
        JourneyColorToken.Verde -> R.drawable.widget_button_verde
        JourneyColorToken.Rojo -> R.drawable.widget_button_rojo
        JourneyColorToken.Blanco -> R.drawable.widget_button_verde
    }

    companion object {
        private const val TAG = "JourneyWidget"

        /** Códigos distintos para que el launcher no solappe los PendingIntents. */
        private const val REQ_START = 1001
        private const val REQ_STOP = 1002

        private fun startPendingIntent(context: Context): PendingIntent =
            servicePendingIntent(
                context,
                REQ_START,
                Intent(context, TrackingService::class.java).setAction(TrackingService.ACTION_START),
            )

        private fun stopPendingIntent(context: Context): PendingIntent {
            // Parar NUNCA es directo desde el widget (un toque accidental en el
            // bolsillo cerraba la jornada, caso Santiago): abre MainActivity con
            // petición de confirmación y el diálogo habitual decide.
            val intent = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra(MainActivity.EXTRA_CONFIRM_STOP, true)
            }
            return PendingIntent.getActivity(
                context,
                REQ_STOP,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        private fun servicePendingIntent(context: Context, requestCode: Int, intent: Intent): PendingIntent =
            PendingIntent.getForegroundService(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /**
         * Re-renderiza TODOS los widgets instalados. Hook para el orquestador:
         * llamarlo desde TrackingService donde se refresca la notificación.
         */
        fun updateAll(context: Context) {
            runCatching {
                val manager = AppWidgetManager.getInstance(context) ?: return
                val ids = manager.getAppWidgetIds(
                    ComponentName(context, JourneyWidget::class.java),
                )
                if (ids.isEmpty()) return
                ids.forEach { id -> JourneyWidget().render(context, manager, id) }
            }.onFailure { Log.w(TAG, "No se pudo actualizar el widget de jornada", it) }
        }
    }
}

/** Tokens del sistema de 3 colores DMujeres (mismos hex que ui/theme/JourneyColors). */
enum class JourneyColorToken { Verde, Rojo, Blanco }

/** Estado de UI ya resuelto para pintar (sin lógica de Android = testeable). */
data class WidgetUiState(
    val active: Boolean,
    val durationMs: Long,
    val primaryActionStart: Boolean,
    val color: JourneyColorToken,
    val waitingHint: Boolean,
)

/**
 * Lógica pura del widget. [elapsedDisplayMs] es el ÚNICO adaptador a los campos
 * de AppConfig (journeyElapsedMs/journeyElapsedWallMs): si el orquestador los
 * renombra, solo cambia este sitio.
 */
object JourneyWidgetState {

    /** Techo del contador: 24 h (evita relojadas por saltos de reloj anomales). */
    const val MAX_DISPLAY_MS = JourneyFormatter.MAX_SEED_ON_RECOVERY_MS

    /** Duración mostrable robusta a saltos de reloj vía JourneyFormatter. */
    fun elapsedDisplayMs(config: AppConfig, nowMs: Long): Long =
        JourneyFormatter.displayElapsedMs(
            persistedElapsedMs = config.journeyElapsedMs,
            persistedWallMs = config.journeyElapsedWallMs,
            journeyStartWallMs = config.journeyStartAt,
            nowWallMs = nowMs,
        ).coerceIn(0L, MAX_DISPLAY_MS)

    /**
     * "Xh Ym" / "Ym": nunca "0h Ym" por debajo de la hora; 0 ms → "0m".
     * Puro, testeable en JVM.
     */
    fun formatDuration(durationMs: Long): String {
        val (hours, minutes) = JourneyFormatter.durationParts(durationMs.coerceAtMost(MAX_DISPLAY_MS))
        return if (hours <= 0L) "${minutes}m" else "${hours}h ${minutes}m"
    }

    /**
     * Versión legible del título 2x2: "15 h 42 min" / "42 min" (nunca "0 h").
     * Puro, testeable en JVM.
     */
    fun formatDurationWords(durationMs: Long): String {
        val (hours, minutes) = JourneyFormatter.durationParts(durationMs.coerceAtMost(MAX_DISPLAY_MS))
        return if (hours <= 0L) "$minutes min" else "$hours h $minutes min"
    }

    /**
     * Mapeo estado → UI. Sistema de 3 colores: verde = iniciar/activo,
     * rojo = finalizar (el botón activo es rojo porque su acción es finalizar).
     * [online] se conserva en la firma para telemetría futura del widget.
     */
    fun stateFor(
        trackingEnabled: Boolean,
        journeyActive: Boolean,
        elapsedMs: Long,
        online: Boolean,
    ): WidgetUiState = when {
        journeyActive -> WidgetUiState(
            active = true,
            durationMs = elapsedMs.coerceAtLeast(0L),
            primaryActionStart = false,
            color = JourneyColorToken.Rojo,
            waitingHint = false,
        )
        trackingEnabled -> WidgetUiState(
            // tracking encendido pero jornada aún no consolidada: ofrecer inicio
            // con pista de espera, en verde.
            active = false,
            durationMs = 0L,
            primaryActionStart = true,
            color = JourneyColorToken.Verde,
            waitingHint = true,
        )
        else -> WidgetUiState(
            active = false,
            durationMs = 0L,
            primaryActionStart = true,
            color = JourneyColorToken.Verde,
            waitingHint = false,
        )
    }
}
