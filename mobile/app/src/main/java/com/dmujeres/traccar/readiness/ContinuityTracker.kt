package com.dmujeres.traccar.readiness

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.tracking.TrackingService
import com.dmujeres.traccar.core.TrackingState

/**
 * Máquina de la ventana de CONTINUIDAD del DeviceReadinessGate.
 *
 * La prueba mide si el tracking CONTINÚA mientras la pantalla está apagada
 * (criterio principal; la recuperación es respaldo y nunca la sustituye).
 * Flujo: [startTest] activa tracking sin jornada; el receiver de pantalla del
 * servicio avisa [onScreenOff]/[onScreenOn]; cada fix aceptado suma
 * [onFixAccepted]. Un ticker de 1 s mide el congelamiento del proceso: si
 * elapsedRealtime salta más de FREEZE_GAP_MS entre ticks, el proceso dejó de
 * correr (freezer OEM) y la evidencia se acumula honestamente en
 * frozenSeconds. La decisión es de ContinuityTestPolicy (pura, testeable).
 */
object ContinuityTracker {

    private const val TAG = "ContinuityTracker"

    /** Salto de monotónico que delata proceso congelado entre ticks de 1 s. */
    private const val FREEZE_GAP_MS = 5_000L

    private var tickerHandler: Handler? = null
    private var lastTickElapsed: Long = 0L
    private var appContext: Context? = null

    /** Arranca la prueba: activa tracking sin jornada y espera SCREEN_OFF. */
    fun startTest(context: Context) {
        val config = AppConfig(context.applicationContext)
        config.continuityPrevTracking = config.trackingEnabled
        config.continuityRunning = true
        config.continuityScreenOffAt = 0L
        config.continuityFixesDuringOff = 0
        config.continuityFrozenSeconds = 0
        appContext = context.applicationContext
        if (!config.trackingEnabled) {
            config.trackingEnabled = true
            config.trackingState = TrackingState.SERVICE_RECOVERY.name
            TrackingService.start(context)
        }
        Log.i(TAG, "CONTINUITY_TEST_START prevTracking=${config.continuityPrevTracking}")
    }

    /** SCREEN_OFF: abre la ventana de observación. */
    fun onScreenOff(context: Context) {
        val config = AppConfig(context.applicationContext)
        if (!config.continuityRunning || config.continuityScreenOffAt != 0L) return
        config.continuityScreenOffAt = System.currentTimeMillis()
        appContext = context.applicationContext
        startFreezeWatcher()
        Log.i(TAG, "CONTINUITY_WINDOW_OPEN")
    }

    /** Fix aceptado por el pipeline durante la ventana. */
    fun onFixAccepted(context: Context) {
        val config = AppConfig(context.applicationContext)
        if (!config.continuityRunning || config.continuityScreenOffAt == 0L) return
        val fixes = config.continuityFixesDuringOff + 1
        config.continuityFixesDuringOff = fixes
        Log.d(TAG, "CONTINUITY_FIX_OFF fixes=$fixes")
    }

    /** Evidencia de congelamiento medida por el ticker de 1 s. */
    fun addFrozenSeconds(seconds: Int) {
        val context = appContext ?: return
        val config = AppConfig(context)
        if (!config.continuityRunning) return
        val total = config.continuityFrozenSeconds + seconds
        config.continuityFrozenSeconds = total
        Log.w(TAG, "CONTINUITY_FREEZE_EVIDENCE +${seconds}s total=${total}s")
    }

    /** SCREEN_ON o cierre manual: evalúa y restaura el tracking previo. */
    fun onScreenOn(context: Context) {
        val config = AppConfig(context.applicationContext)
        if (!config.continuityRunning || config.continuityScreenOffAt == 0L) return
        val outcome = evaluate(context)
        Log.i(TAG, "CONTINUITY_RESULT ${outcome.state} cause=${outcome.cause} " +
            "fixes=${outcome.fixesDuringOff} frozen=${outcome.frozenSeconds}")
    }

    /** Evalúa con la evidencia persistida; la política pura decide. */
    fun evaluate(context: Context): ContinuityTestPolicy.Outcome {
        val config = AppConfig(context.applicationContext)
        stopFreezeWatcher()
        val screenOffAt = config.continuityScreenOffAt
        val windowMs = if (screenOffAt > 0) {
            (System.currentTimeMillis() - screenOffAt).coerceIn(0L, ContinuityTestPolicy.WINDOW_MS)
        } else 0L
        val outcome = ContinuityTestPolicy.evaluate(
            fixesDuringOff = config.continuityFixesDuringOff,
            processAlive = true, // corre dentro del proceso: vivo por definición
            fgsAlive = TrackingService.isRunning,
            networkAvailable = isNetworkAvailable(context),
            frozenSeconds = config.continuityFrozenSeconds,
            oemGuidePresent = com.dmujeres.traccar.oem.VendorSettings.currentVendor() != null,
            screenOffMs = screenOffAt,
            windowMs = windowMs,
        )
        persistOutcome(config, outcome)
        // La prueba no deja tracking encendido si el usuario no lo tenía.
        if (!config.continuityPrevTracking && config.trackingEnabled) {
            config.trackingEnabled = false
            config.trackingState = TrackingState.TRACKING_DISABLED_BY_USER.name
            TrackingService.stop(context)
        }
        config.continuityRunning = false
        config.continuityScreenOffAt = 0L
        return outcome
    }

    private fun persistOutcome(config: AppConfig, outcome: ContinuityTestPolicy.Outcome) {
        config.continuityState = outcome.state
        config.continuityCause = outcome.cause.name
        config.continuityAt = System.currentTimeMillis()
    }

    private fun startFreezeWatcher() {
        if (tickerHandler != null) return
        lastTickElapsed = SystemClock.elapsedRealtime()
        val handler = Handler(Looper.getMainLooper())
        tickerHandler = handler
        val tick = object : Runnable {
            override fun run() {
                val context = appContext
                if (context == null) {
                    stopFreezeWatcher()
                    return
                }
                val now = SystemClock.elapsedRealtime()
                val delta = now - lastTickElapsed
                lastTickElapsed = now
                if (delta > FREEZE_GAP_MS + 1_000L) {
                    // El proceso estuvo congelado: evidencia honesta del freezer.
                    addFrozenSeconds(((delta - 1_000L) / 1_000L).toInt().coerceAtLeast(1))
                }
                val config = AppConfig(context)
                if (!config.continuityRunning) {
                    stopFreezeWatcher()
                    return
                }
                handler.postDelayed(this, 1_000L)
            }
        }
        handler.postDelayed(tick, 1_000L)
    }

    private fun stopFreezeWatcher() {
        tickerHandler?.removeCallbacksAndMessages(null)
        tickerHandler = null
    }

    private fun isNetworkAvailable(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? android.net.ConnectivityManager ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)
}
