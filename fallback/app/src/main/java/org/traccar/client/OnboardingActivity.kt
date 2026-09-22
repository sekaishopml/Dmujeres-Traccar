package org.traccar.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.judemanutd.autostarter.AutoStartPermissionHelper

/**
 * Primer arranque DMujeres: login (pre-rellenado) + permisos + batería.
 *
 * Reglas de diseño del plan B:
 * - La configuración viene de fábrica: el usuario NO ve URL ni precisión.
 * - El acceso avanzado (config real) se habilita con 5 toques en la versión.
 * - El servicio queda SIEMPRE encendido: al terminar el asistente arranca la
 *   captura y no hay interruptor visible para apagarla.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var primary: Button
    private var step = 0

    private var tapCount = 0
    private var tapFirstAt = 0L
    private var tapLastAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        container = findViewById(R.id.step_container)
        primary = findViewById(R.id.btn_primary)
        val version = findViewById<TextView>(R.id.version_label)
        version.text = getString(R.string.version_format, BuildConfig.VERSION_NAME)
        version.setOnClickListener { onVersionTap() }
        version.setOnLongClickListener {
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(MainFragment.KEY_DEBUG, false).apply()
            Toast.makeText(this, R.string.debug_disabled, Toast.LENGTH_SHORT).show()
            true
        }

        primary.setOnClickListener {
            when (step) {
                0 -> {
                    persistLoginFields()
                    showPermissions()
                }
                else -> if (requiredGranted()) finishOnboarding() else requestMissing()
            }
        }
        showLogin()
    }

    override fun onResume() {
        super.onResume()
        if (step == 1) showPermissions()
    }

    // ── Paso 1: login pre-rellenado ─────────────────────────────────────────

    private fun showLogin() {
        step = 0
        container.removeAllViews()
        val view = LayoutInflater.from(this).inflate(R.layout.onboarding_step_login, container, false)
        container.addView(view)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val debug = prefs.getBoolean(MainFragment.KEY_DEBUG, false)
        val user = view.findViewById<EditText>(R.id.field_user)
        val pass = view.findViewById<EditText>(R.id.field_pass)
        val url = view.findViewById<EditText>(R.id.field_url)
        val urlGroup = view.findViewById<LinearLayout>(R.id.url_group)
        user.setText(prefs.getString(MainFragment.KEY_DEVICE, ""))
        // La contraseña es la llave del canal móvil: se muestra enmascarada y
        // solo es editable en modo avanzado (5 toques en la versión).
        pass.setText(DmujeresApi.apiKey(this))
        url.setText(prefs.getString(MainFragment.KEY_URL, ""))
        user.isEnabled = debug
        pass.isEnabled = debug
        urlGroup.visibility = if (debug) LinearLayout.VISIBLE else LinearLayout.GONE
        primary.text = getString(R.string.onboarding_continue)
    }

    // ── Paso 2: permisos y batería ──────────────────────────────────────────

    /** Guarda lo editado en modo avanzado (id, contraseña, servidor). */
    private fun persistLoginFields() {
        val view = container.getChildAt(0) ?: return
        val user = view.findViewById<EditText>(R.id.field_user) ?: return
        val pass = view.findViewById<EditText>(R.id.field_pass) ?: return
        val url = view.findViewById<EditText>(R.id.field_url) ?: return
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(MainFragment.KEY_DEVICE, user.text.toString().trim())
            .putString(DmujeresApi.KEY_PASSWORD, pass.text.toString().trim())
            .putString(MainFragment.KEY_URL, url.text.toString().trim())
            .apply()
    }

    private fun showPermissions() {
        step = 1
        container.removeAllViews()
        val view = LayoutInflater.from(this).inflate(R.layout.onboarding_step_permissions, container, false)
        container.addView(view)
        val rows = view.findViewById<LinearLayout>(R.id.permissions_container)

        addRow(rows, R.string.perm_location, isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            addRow(rows, R.string.perm_background, isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                requestBackground()
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addRow(rows, R.string.perm_notifications, isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            }
        }
        addRow(rows, R.string.perm_battery, ignoringBatteryOptimizations()) { requestBattery() }
        addRow(rows, R.string.perm_autostart, false) { openAutostart() }

        primary.text = if (requiredGranted()) {
            getString(R.string.onboarding_finish)
        } else {
            getString(R.string.onboarding_grant_missing)
        }
    }

    private fun addRow(rows: LinearLayout, titleRes: Int, granted: Boolean, action: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.onboarding_permission_row, rows, false)
        row.findViewById<TextView>(R.id.row_title).setText(titleRes)
        row.findViewById<TextView>(R.id.row_status).setText(
            if (granted) R.string.onboarding_perms_ok else R.string.onboarding_perms_pending,
        )
        row.findViewById<ImageView>(R.id.row_dot)
            .setImageResource(if (granted) R.drawable.ic_dot_ok else R.drawable.ic_dot_pending)
        row.findViewById<Button>(R.id.row_action).setOnClickListener { action() }
        rows.addView(row)
    }

    private fun requiredGranted(): Boolean {
        if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            return false
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            isGranted(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestMissing() {
        when {
            !isGranted(Manifest.permission.ACCESS_FINE_LOCATION) -> requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQUEST_LOCATION,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> requestBackground()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !isGranted(Manifest.permission.POST_NOTIFICATIONS) -> requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATIONS,
            )
        }
    }

    private fun requestBackground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ ya no muestra diálogo: se abre el detalle de la app.
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQUEST_BACKGROUND)
        }
    }

    private fun requestBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }

    private fun openAutostart() {
        runCatching {
            if (!AutoStartPermissionHelper.getInstance().getAutoStartPermission(this)) {
                Toast.makeText(this, R.string.perm_autostart_manual, Toast.LENGTH_LONG).show()
            }
        }.onFailure {
            Toast.makeText(this, R.string.perm_autostart_manual, Toast.LENGTH_LONG).show()
        }
    }

    // ── Cierre: servicio siempre encendido ──────────────────────────────────

    private fun finishOnboarding() {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean(MainFragment.KEY_ONBOARDED, true)
            .putBoolean(MainFragment.KEY_STATUS, true)
            .apply()
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ── Acceso oculto: 5 toques en la versión ───────────────────────────────

    private fun onVersionTap() {
        val now = SystemClock.elapsedRealtime()
        val valid = tapCount > 0 && now - tapLastAt in 1..MAX_TAP_GAP_MS &&
            now - tapFirstAt <= TAP_WINDOW_MS
        tapCount = if (valid) tapCount + 1 else 1
        if (!valid) tapFirstAt = now
        tapLastAt = now
        if (tapCount >= REQUIRED_TAPS) {
            tapCount = 0
            PreferenceManager.getDefaultSharedPreferences(this)
                .edit().putBoolean(MainFragment.KEY_DEBUG, true).apply()
            Toast.makeText(this, R.string.debug_enabled, Toast.LENGTH_LONG).show()
            if (step == 0) showLogin()
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    private companion object {
        const val REQUEST_LOCATION = 100
        const val REQUEST_BACKGROUND = 101
        const val REQUEST_NOTIFICATIONS = 102
        const val REQUIRED_TAPS = 5
        const val MAX_TAP_GAP_MS = 1_200L
        const val TAP_WINDOW_MS = 4_000L
    }
}
