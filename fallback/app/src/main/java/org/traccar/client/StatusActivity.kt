/*
 * Copyright 2012 - 2021 Anton Tananaev (anton@traccar.org)
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
import android.widget.ArrayAdapter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ListView
import java.text.DateFormat
import java.util.*

class StatusActivity : AppCompatActivity() {

    private var adapter: ArrayAdapter<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nuevo diseño: logo arriba, estado actual y los últimos movimientos
        // desde el tope (antes era una lista pelada y el contenido se veía abajo).
        setContentView(R.layout.activity_status)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, android.R.id.text1, messages)
        val listView = findViewById<ListView>(android.R.id.list)
        listView.adapter = adapter
        adapter?.let { adapters.add(it) }
    }

    override fun onResume() {
        super.onResume()
        updateState()
    }

    /** Estado actual del equipo, con el mismo criterio del home. */
    private fun updateState() {
        val state = findViewById<android.widget.TextView>(R.id.status_state) ?: return
        val open = DmujeresApi.isJourneyOpen(this)
        val running = TrackingService.isRunning
        val locationOn = runCatching {
            (getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager)
                .isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        }.getOrDefault(true)
        val (bg, label) = when {
            !locationOn -> R.drawable.bg_pill_red to getString(R.string.pill_location_off)
            !open -> R.drawable.bg_pill_gray to getString(R.string.pill_disabled)
            !running || ConnectionState.isFailing() -> R.drawable.bg_pill_orange to getString(R.string.pill_no_connection)
            else -> R.drawable.bg_pill_green to getString(R.string.pill_online)
        }
        state.setBackgroundResource(bg)
        state.text = label
    }

    override fun onDestroy() {
        adapters.remove(adapter)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val inflater = menuInflater
        inflater.inflate(R.menu.status, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.clear) {
            clearMessages()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    companion object {
        private const val LIMIT = 20
        private val messages = LinkedList<String>()
        private val adapters: MutableSet<ArrayAdapter<String>> = HashSet()

        private fun notifyAdapters() {
            for (adapter in adapters) {
                adapter.notifyDataSetChanged()
            }
        }

        fun addMessage(originalMessage: String) {
            var message = originalMessage
            val format = DateFormat.getTimeInstance(DateFormat.MEDIUM)
            message = format.format(Date()) + " - " + message
            messages.add(message)
            while (messages.size > LIMIT) {
                messages.removeFirst()
            }
            notifyAdapters()
        }

        fun clearMessages() {
            messages.clear()
            notifyAdapters()
        }
    }
}
