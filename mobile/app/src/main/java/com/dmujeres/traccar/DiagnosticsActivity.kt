package com.dmujeres.traccar

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.databinding.ActivityDiagnosticsBinding
import com.dmujeres.traccar.location.TrackingService
import com.dmujeres.traccar.mqtt.MqttManager
import com.dmujeres.traccar.mqtt.MqttStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla de diagnóstico: estado visible y en grande para el colaborador.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private lateinit var config: AppConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = AppConfig(this)

        binding.diagTestButton.setOnClickListener {
            MqttManager.testConnection(
                config.serverUrl, config.username, config.password
            ) { success, message ->
                runOnUiThread {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    refresh()
                }
            }
        }
        binding.diagPermissionsButton.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val gpsMode = Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0)
        val lastFix = config.lastFixAt
        val gpsText = when {
            gpsMode == 0 -> getString(R.string.diag_gps_off)
            lastFix > 0 && System.currentTimeMillis() - lastFix < 5 * 60_000 ->
                getString(R.string.diag_gps_ok)
            else -> getString(R.string.diag_gps_waiting)
        }
        binding.diagGps.text = "📍 " + gpsText

        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        binding.diagNetwork.text = "🌐 " + getString(
            if (online) R.string.diag_network_ok else R.string.diag_network_off
        )

        binding.diagServer.text = "🖥 " + MqttStatus.status +
            if (config.trackingEnabled) "" else " · " + getString(R.string.diag_service_off)

        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                runCatching { (application as DmujeresApp).database.positionDao().count() }.getOrDefault(0)
            }
            binding.diagPending.text = "⏳ " + getString(R.string.diag_pending, pending)
            val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            binding.diagBattery.text = "🔋 " + getString(R.string.diag_battery, battery)
            val startError = config.lastStartError
            binding.diagDevice.text = Build.MANUFACTURER + " " + Build.MODEL +
                " · Android " + Build.VERSION.RELEASE +
                " · " + getString(R.string.app_version, BuildConfig.VERSION_NAME) +
                if (startError.isBlank()) "" else "\n⚠ " + startError
        }

        binding.diagServer.text = binding.diagServer.text.toString() +
            if (TrackingService.isRunning) "" else " · " + getString(R.string.diag_service_stopped)
    }
}
