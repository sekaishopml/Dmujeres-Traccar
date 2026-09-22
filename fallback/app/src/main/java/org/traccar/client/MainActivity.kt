/*
 * Copyright 2017 - 2021 Anton Tananaev (anton@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Button
import android.widget.ImageView
import android.view.View
import android.os.SystemClock
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {

    private var tapCount = 0
    private var tapFirstAt = 0L
    private var tapLastAt = 0L

    /** Último número de posiciones en el búfer, leído por el refresco de la tarjeta. */
    @Volatile
    private var cachedPending = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(MainFragment.KEY_ONBOARDED, false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        // Plan B: el servicio queda siempre encendido (sin interruptor visible).
        prefs.edit().putBoolean(MainFragment.KEY_STATUS, true).apply()
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        if (prefs.getBoolean(MainFragment.KEY_DEBUG, false)) {
            // Modo avanzado: lista de ajustes (MainFragment, solo para soporte).
            setContentView(R.layout.main)
        } else {
            // Modo normal: pantalla propia, sin ninguna configuración visible.
            setContentView(R.layout.activity_locked_home)
            wireLockedHome()
        }
        showUpdateDialogIfAvailable()
    }

    /**
     * Banner superior de actualización (como la app nativa): al abrir, si hay
     * versión nueva baja deslizándose y empuja todo el home (incluida la
     * hamburguesa); al tocarlo descarga e instala. Sin versión nueva no
     * aparece nada.
     */
    private fun showUpdateDialogIfAvailable() {
        val banner = findViewById<LinearLayout>(R.id.update_banner) ?: return
        DmujeresApi.checkOta(this) { label, url, sha256 ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (label == null) return@runOnUiThread
                if (banner.visibility == View.VISIBLE) return@runOnUiThread
                banner.visibility = View.VISIBLE
                val height = (46 * resources.displayMetrics.density).toInt()
                val slide = android.view.animation.TranslateAnimation(0f, 0f, -height.toFloat(), 0f)
                slide.duration = 350
                banner.startAnimation(slide)
                banner.setOnClickListener {
                    OtaUpdater.downloadAndInstall(this, url, sha256)
                }
            }
        }
    }

    private var durationTicker: Runnable? = null

    /** Pantalla principal: logo, banner de estado, 3 cuadros y botón de jornada.
     *  Mismo orden y colores del panel: banner rojo (sin jornada) / verde
     *  (en jornada), cuadros de Pendientes, Batería y Duración, y skeleton
     *  pulsante mientras cargan los datos. Sin scroll. */
    private fun wireLockedHome() {
        val button = findViewById<Button>(R.id.journey_button)
        val version = findViewById<TextView>(R.id.version_label)
        val updateButton = findViewById<Button>(R.id.update_button)
        // Consola de estado (1.1.x): arriba a la derecha.
        findViewById<android.widget.ImageButton>(R.id.console_button).setOnClickListener {
            startActivity(Intent(this, StatusActivity::class.java))
        }

        button.setOnClickListener {
            if (DmujeresApi.isJourneyOpen(this)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.journey_confirm_title)
                    .setMessage(R.string.journey_confirm_body)
                    .setPositiveButton(R.string.journey_confirm_ok) { _, _ ->
                        DmujeresApi.journeyEnded(this)
                        refreshLockedHome()
                    }
                    .setNegativeButton(R.string.journey_confirm_cancel, null)
                    .show()
            } else {
                ContextCompat.startForegroundService(
                    this, Intent(this, TrackingService::class.java),
                )
                DmujeresApi.journeyStarted(this)
                refreshLockedHome()
            }
        }
        // ACTUALIZAR: refresco manual de posición y servicio con el servidor
        // (reenvía pendientes y pide un fix inmediato). La actualización de la
        // APP es el banner superior.
        updateButton.setOnClickListener {
            val active = TrackingService.refreshNow()
            Toast.makeText(
                this,
                if (active) R.string.refresh_now_ok else R.string.refresh_now_off,
                Toast.LENGTH_SHORT,
            ).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { runCatching { refreshLockedHome() } },
                2_500L,
            )
        }
        version.text = getString(
            R.string.version_footer_fmt,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        version.setOnClickListener { onVersionTap() }
        version.setOnLongClickListener {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(MainFragment.KEY_DEBUG, false).apply()
            Toast.makeText(this, R.string.debug_disabled, Toast.LENGTH_SHORT).show()
            true
        }
        // Skeleton del dashboard (como en la app nativa): cubos que pulsan hasta
        // que los datos están listos (batería inmediata, buffer en segundo hilo).
        val skeleton = findViewById<View>(R.id.skeleton_group)
        val content = findViewById<View>(R.id.home_content)
        val anim = android.animation.ValueAnimator.ofFloat(1.0f, 0.4f, 1.0f).apply {
            duration = 900
            repeatCount = android.animation.ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        anim.addUpdateListener { skeleton.alpha = it.animatedValue as Float }
        anim.start()
        Thread {
            val pendingCount = try {
                DatabaseHelper(this).countPositions()
            } catch (e: Exception) {
                0
            }
            runOnUiThread {
                anim.cancel()
                skeleton.visibility = View.GONE
                content.visibility = View.VISIBLE
                refreshLockedHome()
            }
        }.start()

        // El tiempo de jornada avanza cada 30 s mientras la pantalla esté abierta.
        durationTicker = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                // Reprogramar SIEMPRE primero y refrescar a prueba de fallos: si
                // algo lanza, la cadena de refresco no se puede quedar muerta
                // (pasó: los cuadros quedaron congelados).
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 15_000L)
                runCatching { refreshLockedHome() }
            }
        }
        refreshLockedHome()
    }


    /** Banner de estado (3 colores), cuadros y botón con el estado real. */
    override fun onResume() {
        super.onResume()
        // Al volver a la pantalla principal, refresco inmediato.
        runCatching { refreshLockedHome() }
    }

    private fun refreshLockedHome() {
        if (isFinishing || isDestroyed) return
        val open = DmujeresApi.isJourneyOpen(this)
        val running = TrackingService.isRunning
        // La causa real de "no hay ruta": ubicación del sistema apagada.
        // Se avisa con la pill y una fila que abre los ajustes.
        val locationOn = runCatching {
            (getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager)
                .isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        }.getOrDefault(true)
        findViewById<LinearLayout>(R.id.location_warning)?.visibility =
            if (locationOn) View.GONE else View.VISIBLE
        findViewById<LinearLayout>(R.id.location_warning)?.setOnClickListener {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        }
        val pill = findViewById<LinearLayout>(R.id.status_pill)
        val pillText = findViewById<TextView>(R.id.pill_text)
        val button = findViewById<Button>(R.id.journey_button)

        // Estado visible (los mismos 4 del panel): deshabilitado (jornada
        // apagada), sin conexión (no puede enviar), detenido (quieto) o en línea
        // (en movimiento). Ubicación apagada tiene prioridad: impide trazar.
        val motionState = MotionMonitor.isMoving()
        when {
            !locationOn -> {
                pill.setBackgroundResource(R.drawable.bg_pill_red)
                pillText.text = getString(R.string.pill_location_off)
                pillText.setTextColor(getColor(R.color.white))
                button.setBackgroundResource(
                    if (open) R.drawable.bg_button_primary else R.drawable.bg_button_green,
                )
                button.text = getString(
                    if (open) R.string.journey_stop_upper else R.string.journey_start_upper,
                )
            }
            !open -> {
                pill.setBackgroundResource(R.drawable.bg_pill_gray)
                pillText.text = getString(R.string.pill_disabled)
                pillText.setTextColor(getColor(R.color.white))
                button.setBackgroundResource(R.drawable.bg_button_green)
                button.text = getString(R.string.journey_start_upper)
            }
            !canSend() -> {
                pill.setBackgroundResource(R.drawable.bg_pill_orange)
                pillText.text = getString(R.string.pill_no_connection)
                pillText.setTextColor(getColor(R.color.white))
                button.setBackgroundResource(R.drawable.bg_button_primary)
                button.text = getString(R.string.journey_stop_upper)
            }
            motionState == true -> {
                pill.setBackgroundResource(R.drawable.bg_pill_green)
                pillText.text = getString(R.string.pill_online)
                pillText.setTextColor(getColor(R.color.white))
                button.setBackgroundResource(R.drawable.bg_button_primary)
                button.text = getString(R.string.journey_stop_upper)
            }
            else -> {
                pill.setBackgroundResource(R.drawable.bg_pill_blue)
                pillText.text = getString(R.string.pill_stopped)
                pillText.setTextColor(getColor(R.color.white))
                button.setBackgroundResource(R.drawable.bg_button_primary)
                button.text = getString(R.string.journey_stop_upper)
            }
        }

        val battery = readBattery()
        findViewById<TextView>(R.id.battery_value)?.text = getString(R.string.battery_value_fmt, battery.first)
        findViewById<TextView>(R.id.battery_value)?.setTextColor(
            getColor(
                when {
                    battery.first <= 15 -> R.color.primary
                    battery.first <= 35 -> android.R.color.holo_orange_dark
                    else -> R.color.status_ok
                },
            ),
        )
        // El búfer se relee en cada refresco (30 s): antes solo se leía al
        // arrancar y la tarjeta quedaba congelada en 0.
        Thread {
            val pending = runCatching { DatabaseHelper(this).countPositions() }.getOrDefault(0)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Guardamos el valor para que canSend() sepa si queda algo por enviar.
                cachedPending = pending
                findViewById<TextView>(R.id.pending_value)?.text = pending.toString()
            }
        }.start()
        refreshDuration()
    }

    private fun refreshDuration() {
        val text = findViewById<TextView>(R.id.duration_value) ?: return
        if (!DmujeresApi.isJourneyOpen(this)) {
            text.text = getString(R.string.journey_none_banner)
            return
        }
        val startedAt = PreferenceManager.getDefaultSharedPreferences(this)
            .getLong(DmujeresApi.KEY_JOURNEY_STARTED_AT, 0L)
        if (startedAt <= 0L) {
            text.text = getString(R.string.journey_none_banner)
            return
        }
        val minutes = ((System.currentTimeMillis() - startedAt) / 60_000L).toInt()
        text.text = getString(R.string.journey_duration_fmt, minutes / 60, minutes % 60)
    }

    private fun refreshLockedHomeDuration() = refreshDuration()

    /**
     * ¿La app puede enviar ahora? Usamos señales reales de la app (resultado de
     * los envíos + búfer pendiente) y no las APIs del sistema (NetworkManager /
     * ConnectivityManager), porque en algunas ROMs y con VPN activa mienten.
     */
    private fun canSend(): Boolean = !ConnectionState.isFailing() && cachedPending == 0

    private fun readBattery(): Pair<Int, Boolean> {
        val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) return -1 to false
        val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 1)
        val status = intent.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        return pct to (status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
            status == android.os.BatteryManager.BATTERY_STATUS_FULL)
    }

    private fun onVersionTap() {
        val now = SystemClock.elapsedRealtime()
        val valid = tapCount > 0 && now - tapLastAt in 1..1_200L && now - tapFirstAt <= 4_000L
        tapCount = if (valid) tapCount + 1 else 1
        if (!valid) tapFirstAt = now
        tapLastAt = now
        if (tapCount >= 5) {
            tapCount = 0
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(MainFragment.KEY_DEBUG, true).apply()
            Toast.makeText(this, R.string.debug_enabled, Toast.LENGTH_LONG).show()
            recreate()
        }
    }

}
