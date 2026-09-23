package org.traccar.client

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.judemanutd.autostarter.AutoStartPermissionHelper

/**
 * Primer arranque DMujeres: bienvenida + login (pre-rellenado) + permisos.
 *
 * Reglas de diseño del plan B:
 * - La configuración viene de fábrica: el usuario NO ve URL ni precisión.
 * - El acceso a depuración es el menú que se abre con 5 toques en la versión
 *   del home (aquí ya no hay pie de versión).
 * - El servicio queda SIEMPRE encendido: al terminar el asistente arranca la
 *   captura y no hay interruptor visible para apagarla.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var container: FrameLayout
    private lateinit var primary: Button
    private var step = STEP_WELCOME

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        container = findViewById(R.id.step_container)
        primary = findViewById(R.id.btn_primary)

        primary.setOnClickListener {
            when (step) {
                STEP_WELCOME -> showLogin()
                STEP_LOGIN -> {
                    persistLoginFields()
                    validateAndContinue()
                }
                else -> if (requiredGranted()) finishOnboarding() else requestMissing()
            }
        }
        // El menú de depuración puede abrir directamente un paso del asistente
        // para revisar el diseño sin recorrerlo entero.
        when (intent.getStringExtra(EXTRA_STEP)) {
            STEP_LOGIN -> showLogin()
            STEP_PERMISSIONS -> showPermissions()
            else -> showWelcome()
        }
    }

    override fun onResume() {
        super.onResume()
        if (step == STEP_PERMISSIONS) showPermissions()
    }

    /** La cabecera (logo, nombre y lema) solo se muestra donde pertenece. */
    private fun setHeaderVisible(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.header_title).visibility = visibility
        findViewById<TextView>(R.id.header_subtitle).visibility = visibility
    }

    // ── Paso 1: bienvenida ──────────────────────────────────────────────────

    private fun showWelcome() {
        step = STEP_WELCOME
        setHeaderVisible(true)
        container.removeAllViews()
        container.addView(LayoutInflater.from(this).inflate(R.layout.onboarding_step_welcome, container, false))
        primary.text = getString(R.string.onboarding_continue)
    }

    // ── Paso 2: login (solo los datos que entregó CCTV) ─────────────────────

    private fun showLogin() {
        step = STEP_LOGIN
        setHeaderVisible(false)
        container.removeAllViews()
        val view = LayoutInflater.from(this).inflate(R.layout.onboarding_step_login, container, false)
        container.addView(view)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val user = view.findViewById<EditText>(R.id.field_user)
        val pass = view.findViewById<EditText>(R.id.field_pass)
        user.setText(prefs.getString(Prefs.DEVICE, ""))
        // La contraseña la escribe el colaborador (la que le entregó CCTV); el
        // canal móvil se autentica aparte con la llave de la empresa.
        pass.setText(prefs.getString(DmujeresApi.KEY_PASSWORD, ""))
        // Campos editables para cada quien (usuario y clave). El servidor es
        // configuración interna: no se muestra aquí (ver menú de depuración).
        user.isEnabled = true
        pass.isEnabled = true
        primary.text = getString(R.string.login_button)
    }

    // ── Paso 3: permisos y batería ──────────────────────────────────────────

    /** Guarda lo editado (id y contraseña). */
    private fun persistLoginFields() {
        val view = container.getChildAt(0) ?: return
        val user = view.findViewById<EditText>(R.id.field_user) ?: return
        val pass = view.findViewById<EditText>(R.id.field_pass) ?: return
        val url = view.findViewById<EditText>(R.id.field_url)
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            // El usuario se guarda en minúsculas: el servidor busca por
            // identificador exacto y "Jeremy" no es lo mismo que "jeremy".
            .putString(Prefs.DEVICE, user.text.toString().trim().lowercase())
            .putString(DmujeresApi.KEY_PASSWORD, pass.text.toString().trim())
            .apply()
        // El campo de servidor ya no existe en el login (configuración interna);
        // si el paso lo trae (flujo viejo en caché), lo conservamos sin editar.
        if (url != null) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(Prefs.URL, url.text.toString().trim())
                .apply()
        }
    }

    /**
     * Antes de pasar a permisos: el usuario es obligatorio y, si hay red, se
     * verifica contra el servidor (debe existir). Así nadie entra con una
     * credencial que no está autorizada.
     */
    private fun validateAndContinue() {
        val user = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(Prefs.DEVICE, "").orEmpty().trim()
        if (user.isEmpty()) {
            Toast.makeText(this, R.string.login_user_required, Toast.LENGTH_LONG).show()
            return
        }
        val password = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(DmujeresApi.KEY_PASSWORD, "").orEmpty().trim()
        if (password.isEmpty()) {
            Toast.makeText(this, R.string.login_password_required, Toast.LENGTH_LONG).show()
            return
        }
        primary.isEnabled = false
        primary.text = getString(R.string.login_checking)
        Thread {
            val exists = DmujeresApi.userExists(this, user)
            runOnUiThread {
                primary.isEnabled = true
                primary.text = getString(R.string.login_button)
                when (exists) {
                    false -> Toast.makeText(this, R.string.login_user_unknown, Toast.LENGTH_LONG).show()
                    else -> showPermissions()
                }
            }
        }.start()
    }

    private fun showPermissions() {
        step = STEP_PERMISSIONS
        setHeaderVisible(true)
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
            .putBoolean(Prefs.ONBOARDED, true)
            .putBoolean(Prefs.STATUS, true)
            .apply()
        ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun ignoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = getSystemService(PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }

    companion object {
        private const val EXTRA_STEP = "step"
        private const val REQUEST_LOCATION = 100
        private const val REQUEST_BACKGROUND = 101
        private const val REQUEST_NOTIFICATIONS = 102

        const val STEP_WELCOME = "welcome"
        const val STEP_LOGIN = "login"
        const val STEP_PERMISSIONS = "permissions"

        /** Abre el asistente, opcionalmente en un paso concreto (pruebas). */
        fun start(context: Context, step: String? = null) {
            context.startActivity(
                Intent(context, OnboardingActivity::class.java)
                    .putExtra(EXTRA_STEP, step ?: STEP_WELCOME),
            )
        }
    }
}
