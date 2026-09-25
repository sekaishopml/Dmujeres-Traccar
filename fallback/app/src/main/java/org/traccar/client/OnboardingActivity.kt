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

    /** La transición de permisos se anima una sola vez por visita. */
    private var permissionsTransitionDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        container = findViewById(R.id.step_container)
        primary = findViewById(R.id.btn_primary)
        // La versión se muestra en el pie de las tres pantallas del asistente
        // (bienvenida, login y permisos).
        findViewById<TextView>(R.id.version_label).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME)

        primary.setOnClickListener {
            when (step) {
                STEP_WELCOME -> showLogin()
                STEP_LOGIN -> validateAndContinue()
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

    /**
     * Cierra el teclado al tocar fuera del campo activo (antes quedaba abierto
     * y bloqueaba la pantalla del asistente).
     */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        val focus = currentFocus
        if (focus is EditText) {
            val rect = android.graphics.Rect()
            focus.getGlobalVisibleRect(rect)
            if (!rect.contains(event.x.toInt(), event.y.toInt())) {
                (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(focus.windowToken, 0)
                focus.clearFocus()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    // ── Paso 1: bienvenida ──────────────────────────────────────────────────

    private fun showWelcome() {
        step = STEP_WELCOME
        container.removeAllViews()
        val view = LayoutInflater.from(this).inflate(R.layout.onboarding_step_welcome, container, false)
        container.addView(view)
        primary.text = getString(R.string.onboarding_continue)
        // Entrada escalonada con desenfoque suave: cabecera y los tres pasos.
        listOf(
            R.id.welcome_header to 0L,
            R.id.welcome_step1 to 90L,
            R.id.welcome_step2 to 180L,
            R.id.welcome_step3 to 270L,
            R.id.welcome_footer to 360L,
        ).forEach { (id, delay) ->
            view.findViewById<View>(id)?.let { SoftEntrance.animate(it, delayMs = delay) }
        }
    }

    // ── Paso 2: login (cada quien escribe sus datos) ────────────────────────

    private fun showLogin() {
        step = STEP_LOGIN
        container.removeAllViews()
        container.addView(LayoutInflater.from(this).inflate(R.layout.onboarding_step_login, container, false))
        primary.text = getString(R.string.login_button)
        // Transición suave al cambiar de paso.
        SoftEntrance.transition(container)
    }

    /**
     * Validación completa del login, con el error EN LÍNEA (debajo del título
     * "Inicia sesión", sin pop-up): campos vacíos, usuario no autorizado,
     * contraseña incorrecta o sin conexión. Solo si todo pasa se guardan los
     * datos y se avanza a permisos.
     */
    private fun validateAndContinue() {
        val view = container.getChildAt(0) ?: return
        val userField = view.findViewById<EditText>(R.id.field_user) ?: return
        val passField = view.findViewById<EditText>(R.id.field_pass) ?: return
        val error = view.findViewById<TextView>(R.id.login_error) ?: return

        fun showError(messageRes: Int) {
            error.setText(messageRes)
            error.visibility = View.VISIBLE
        }
        val userId = userField.text.toString().trim().lowercase()
        if (userId.isEmpty()) {
            showError(R.string.login_error_user)
            return
        }
        val password = passField.text.toString().trim()
        if (password.isEmpty()) {
            showError(R.string.login_error_password)
            return
        }
        error.visibility = View.GONE
        primary.isEnabled = false
        primary.text = getString(R.string.login_checking)
        Thread {
            val result = DmujeresApi.checkLogin(this, userId, password)
            runOnUiThread {
                primary.isEnabled = true
                primary.text = getString(R.string.login_button)
                when (result) {
                    DmujeresApi.LoginResult.AUTHORIZED -> {
                        PreferenceManager.getDefaultSharedPreferences(this).edit()
                            .putString(Prefs.DEVICE, userId)
                            .putString(DmujeresApi.KEY_PASSWORD, password)
                            .apply()
                        showPermissions()
                    }
                    DmujeresApi.LoginResult.UNKNOWN_USER -> showError(R.string.login_error_unknown)
                    DmujeresApi.LoginResult.BAD_CREDENTIALS -> showError(R.string.login_error_bad_key)
                    else -> showError(R.string.login_error_offline)
                }
            }
        }.start()
    }

    // ── Paso 3: permisos y batería ──────────────────────────────────────────

    private fun showPermissions() {
        step = STEP_PERMISSIONS
        container.removeAllViews()
        val view = LayoutInflater.from(this).inflate(R.layout.onboarding_step_permissions, container, false)
        container.addView(view)
        val rows = view.findViewById<LinearLayout>(R.id.permissions_container)
        // Transición suave solo la primera vez: al volver de Ajustes no se repite.
        if (!permissionsTransitionDone) {
            permissionsTransitionDone = true
            SoftEntrance.transition(container)
        }

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
        // Reinicio limpio: si el servicio venía corriendo con la configuración
        // vieja (p. ej. arrancó por el sistema antes del login), se reinicia
        // para que tome el usuario y el servidor guardados (con guardas de
        // arranque: ver RemoteConfig.restartService).
        if (TrackingService.isRunning) {
            RemoteConfig.restartService(this)
        } else {
            ContextCompat.startForegroundService(this, Intent(this, TrackingService::class.java))
        }
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
