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
import com.dmujeres.traccar.location.TrackingState
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

        binding.toolbar.setNavigationOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.slide_out_right)
        }

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
        binding.diagRecoverButton.setOnClickListener {
            if (config.trackingEnabled) {
                config.trackingState = TrackingState.SERVICE_RECOVERY.name
                val started = TrackingService.start(this)
                Toast.makeText(
                    this,
                    if (started) R.string.service_recovery_title else R.string.start_error,
                    Toast.LENGTH_LONG,
                ).show()
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val storedState = TrackingState.fromName(config.trackingState)
        val state = if (config.trackingEnabled && !TrackingService.isRunning) {
            TrackingState.SERVICE_RECOVERY
        } else storedState
        binding.diagState.text = state.label +
            if (TrackingService.isRunning) "" else " · " + getString(R.string.diag_service_stopped)

        val gpsMode = Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, 0)
        val lastFix = config.lastFixAt
        val gpsText = when {
            gpsMode == 0 -> getString(R.string.diag_gps_off)
            lastFix > 0 && System.currentTimeMillis() - lastFix < 5 * 60_000 ->
                getString(R.string.diag_gps_ok)
            else -> getString(R.string.diag_gps_waiting)
        }
        binding.diagGps.text = gpsText

        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val online = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        binding.diagNetwork.text = getString(
            if (online) R.string.diag_network_ok else R.string.diag_network_off
        )

        binding.diagServer.text = MqttStatus.status +
            if (MqttStatus.lastError.isNullOrBlank()) "" else "\n${MqttStatus.lastError}"

        lifecycleScope.launch {
            val pending = withContext(Dispatchers.IO) {
                runCatching { (application as DmujeresApp).database.positionDao().count() }.getOrDefault(0)
            }
            val oldest = runCatching {
                (application as DmujeresApp).database.positionDao().oldestEnqueuedAt()
            }.getOrNull()
            binding.diagPending.text = if (pending > 0 && oldest != null && oldest > 0L) {
                getString(R.string.diag_pending_age, pending, agoText(oldest))
            } else {
                getString(R.string.diag_pending, pending)
            }
            val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            binding.diagBattery.text = getString(R.string.diag_battery, battery)
            binding.diagLastFix.text = getString(
                R.string.diag_last_fix,
                config.lastFixAt.takeIf { it > 0L }?.let(::agoText) ?: getString(R.string.diag_never),
            )
            binding.diagLastAck.text = getString(
                R.string.diag_last_ack,
                config.lastAckAt.takeIf { it > 0L }?.let(::agoText) ?: getString(R.string.diag_never),
            )
            val startError = config.lastStartError
            binding.diagDevice.text = Build.MANUFACTURER + " " + Build.MODEL +
                " · Android " + Build.VERSION.RELEASE +
                " · " + getString(R.string.app_version, BuildConfig.VERSION_NAME) +
                if (startError.isBlank()) "" else "\n⚠ " + startError
        }

        if (!config.trackingEnabled) {
            binding.diagServer.text = binding.diagServer.text.toString() +
                " · " + getString(R.string.diag_service_off)
        }
        binding.diagRecoverButton.isEnabled = config.trackingEnabled && !TrackingService.isRunning
    }

    private fun agoText(timestamp: Long): String {
        val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0)) / 60_000
        return when {
            minutes < 1 -> getString(R.string.ago_now)
            minutes < 60 -> getString(R.string.ago_minutes, minutes)
            else -> getString(R.string.ago_hours, minutes / 60)
        }
    }
}
