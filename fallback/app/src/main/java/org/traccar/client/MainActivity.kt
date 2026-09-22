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

    /** Aviso emergente de actualización al abrir la app (además del banner). */
    private fun showUpdateDialogIfAvailable() {
        DmujeresApi.checkOta(this) { label, url, sha256 ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.update_dialog_title))
                    .setMessage(getString(R.string.update_row_text, label))
                    .setPositiveButton(R.string.update_action) { _, _ ->
                        OtaUpdater.downloadAndInstall(this, url, sha256)
                    }
                    .setNegativeButton(R.string.update_later, null)
                    .show()
            }
        }
    }

    /** Pantalla principal: servicio activo, botón de jornada y versión (5 toques). */
    private fun wireLockedHome() {
        val button = findViewById<Button>(R.id.journey_button)
        val status = findViewById<TextView>(R.id.journey_status)
        val version = findViewById<TextView>(R.id.version_label)
        val updateRow = findViewById<LinearLayout>(R.id.update_row)
        val updateText = findViewById<TextView>(R.id.update_text)

        button.setOnClickListener {
            if (DmujeresApi.isJourneyOpen(this)) {
                AlertDialog.Builder(this)
                    .setTitle(R.string.journey_confirm_title)
                    .setMessage(R.string.journey_confirm_body)
                    .setPositiveButton(R.string.journey_confirm_ok) { _, _ ->
                        DmujeresApi.journeyEnded(this)
                        refreshJourneyUi(button, status)
                    }
                    .setNegativeButton(R.string.journey_confirm_cancel, null)
                    .show()
            } else {
                ContextCompat.startForegroundService(
                    this, Intent(this, TrackingService::class.java),
                )
                DmujeresApi.journeyStarted(this)
                refreshJourneyUi(button, status)
            }
        }
        version.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        version.setOnClickListener { onVersionTap() }
        version.setOnLongClickListener {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(MainFragment.KEY_DEBUG, false).apply()
            Toast.makeText(this, R.string.debug_disabled, Toast.LENGTH_SHORT).show()
            true
        }
        DmujeresApi.checkOta(this) { label, url, sha256 ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateText.text = getString(R.string.update_row_text, label)
                updateRow.visibility = View.VISIBLE
                updateRow.setOnClickListener {
                    OtaUpdater.downloadAndInstall(this, url, sha256)
                }
            }
        }
        refreshJourneyUi(button, status)
    }

    private fun refreshJourneyUi(button: Button, status: TextView) {
        if (DmujeresApi.isJourneyOpen(this)) {
            button.text = getString(R.string.journey_stop_upper)
            status.text = getString(
                R.string.journey_active_since,
                DmujeresApi.journeyStartedAtLabel(this),
            )
        } else {
            button.text = getString(R.string.journey_start_upper)
            status.text = getString(R.string.journey_none)
        }
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
