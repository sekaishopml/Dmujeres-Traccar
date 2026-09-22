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
import androidx.appcompat.app.AlertDialog
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(MainFragment.KEY_ONBOARDED, false)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        // Plan B: el servicio queda siempre encendido (sin interruptor visible).
        prefs.edit().putBoolean(MainFragment.KEY_STATUS, true).apply()
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        // Aviso emergente de actualización (además del banner de la pantalla
        // principal): cubre el caso en que el usuario no ve la fila del banner.
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

}
