package org.traccar.client

import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * Menú de depuración (acceso oculto: 5 toques en la versión).
 *
 * Reemplaza al panel de ajustes de Traccar, que ya no existe: la configuración
 * es interna y aquí solo se revisan las pantallas del diseño (login, permisos,
 * bienvenida, consola, actualización…) y el estado del backend.
 */
class DebugActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        findViewById<TextView>(R.id.version_label).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME)

        val rows = findViewById<LinearLayout>(R.id.debug_rows)

        addSection(rows, R.string.debug_section_screens)
        addRow(rows, R.string.debug_home_title, R.string.debug_home_summary) {
            startActivity(android.content.Intent(this, MainActivity::class.java))
        }
        addRow(rows, R.string.debug_welcome_title, R.string.debug_welcome_summary) {
            OnboardingActivity.start(this, OnboardingActivity.STEP_WELCOME)
        }
        addRow(rows, R.string.debug_login_title, R.string.debug_login_summary) {
            OnboardingActivity.start(this, OnboardingActivity.STEP_LOGIN)
        }
        addRow(rows, R.string.debug_perms_title, R.string.debug_perms_summary) {
            OnboardingActivity.start(this, OnboardingActivity.STEP_PERMISSIONS)
        }
        addRow(rows, R.string.debug_wizard_title, R.string.debug_wizard_summary) {
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putBoolean(Prefs.ONBOARDED, false).apply()
            OnboardingActivity.start(this, OnboardingActivity.STEP_WELCOME)
        }
        addRow(rows, R.string.debug_console_title, R.string.debug_console_summary) {
            startActivity(android.content.Intent(this, StatusActivity::class.java))
        }
        addRow(rows, R.string.debug_update_title, R.string.debug_update_summary) {
            UpdateActivity.startDemo(this)
        }
        addRow(rows, R.string.debug_finish_title, R.string.debug_finish_summary) {
            showFinishJourneyDialog()
        }

        addSection(rows, R.string.debug_section_backend)
        addRow(rows, R.string.debug_server_title, R.string.debug_server_summary) { editServer() }
        addRow(rows, R.string.debug_user_title, R.string.debug_user_summary) { testUser() }
        addRow(rows, R.string.debug_diagnostics_title, R.string.debug_diagnostics_summary) {
            showDiagnostics()
        }
        addRow(rows, R.string.debug_buffer_title, R.string.debug_buffer_summary) { showBuffer() }
        addRow(rows, R.string.debug_clear_console_title, R.string.debug_clear_console_summary) {
            StatusActivity.clearMessages()
            toast(getString(R.string.debug_clear_console_done))
        }
    }

    // ── Diseño de filas ─────────────────────────────────────────────────────

    private fun addSection(rows: LinearLayout, titleRes: Int) {
        val density = resources.displayMetrics.density
        val title = TextView(this).apply {
            setText(titleRes)
            setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.muted))
            textSize = 12f
            letterSpacing = 0.08f
            setPadding(
                (4 * density).toInt(),
                ((if (rows.childCount == 0) 8 else 28) * density).toInt(),
                (4 * density).toInt(),
                (10 * density).toInt(),
            )
        }
        rows.addView(title)
    }

    private fun addRow(rows: LinearLayout, titleRes: Int, summaryRes: Int, action: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.debug_row, rows, false)
        row.findViewById<TextView>(R.id.row_title).setText(titleRes)
        row.findViewById<TextView>(R.id.row_summary).setText(summaryRes)
        row.setOnClickListener { action() }
        rows.addView(row)
    }

    // ── Pantallas ───────────────────────────────────────────────────────────

    /** Mismo diálogo del home, con la duración real de la jornada. */
    private fun showFinishJourneyDialog() {
        val startedAt = PreferenceManager.getDefaultSharedPreferences(this)
            .getLong(DmujeresApi.KEY_JOURNEY_STARTED_AT, 0L)
        val minutes = if (startedAt > 0L) {
            ((System.currentTimeMillis() - startedAt) / 60_000L).toInt()
        } else {
            0
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.journey_confirm_title)
            .setMessage(getString(R.string.journey_confirm_body, minutes / 60, minutes % 60))
            .setPositiveButton(R.string.journey_confirm_ok) { _, _ ->
                DmujeresApi.journeyEnded(this)
            }
            .setNegativeButton(R.string.journey_confirm_cancel, null)
            .show()
    }

    // ── Backend ─────────────────────────────────────────────────────────────

    /** Muestra el usuario y permite cambiar la URL interna del servidor. */
    private fun editServer() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val field = EditText(this).apply {
            setText(prefs.getString(Prefs.URL, ""))
            setHint(R.string.debug_server_hint)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(TextView(this@DebugActivity).apply {
                text = getString(
                    R.string.debug_server_user_fmt,
                    prefs.getString(Prefs.DEVICE, ""),
                )
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.muted))
                textSize = 13f
            })
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.debug_server_title)
            .setView(container)
            .setPositiveButton(R.string.debug_save) { _, _ ->
                prefs.edit().putString(Prefs.URL, field.text.toString().trim()).apply()
                toast(getString(R.string.debug_server_saved))
            }
            .setNegativeButton(R.string.debug_close, null)
            .show()
    }

    /** Verifica el usuario configurado contra el servidor (como el login). */
    private fun testUser() {
        val user = PreferenceManager.getDefaultSharedPreferences(this)
            .getString(Prefs.DEVICE, "").orEmpty()
        toast(getString(R.string.debug_testing_user))
        Thread {
            val exists = DmujeresApi.userExists(this, user)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(
                    getString(
                        when (exists) {
                            true -> R.string.debug_user_ok
                            false -> R.string.debug_user_unknown
                            null -> R.string.debug_user_offline
                        },
                    ),
                )
            }
        }.start()
    }

    /** Resumen local del estado del servicio (sin adb ni panel). */
    private fun showDiagnostics() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val locationOn = runCatching {
            (getSystemService(Context.LOCATION_SERVICE) as LocationManager)
                .isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(false)
        val pending = runCatching { DatabaseHelper(this).countPositions() }.getOrDefault(-1)
        val journeyOpen = DmujeresApi.isJourneyOpen(this)
        val lines = listOf(
            getString(
                R.string.debug_diag_service_fmt,
                getString(if (TrackingService.isRunning) R.string.debug_active else R.string.debug_stopped),
            ),
            getString(
                R.string.debug_diag_gps_fmt,
                getString(if (locationOn) R.string.debug_on else R.string.debug_off),
            ),
            getString(R.string.debug_diag_pending_fmt, pending),
            getString(
                R.string.debug_diag_journey_fmt,
                if (journeyOpen) {
                    getString(R.string.debug_open_since, DmujeresApi.journeyStartedAtLabel(this))
                } else {
                    getString(R.string.debug_closed)
                },
            ),
            getString(
                R.string.debug_diag_connection_fmt,
                getString(if (ConnectionState.isFailing()) R.string.debug_failing else R.string.debug_ok),
            ),
            getString(R.string.debug_diag_user_fmt, prefs.getString(Prefs.DEVICE, "")),
            getString(R.string.debug_diag_server_fmt, prefs.getString(Prefs.URL, "")),
            getString(
                R.string.debug_diag_version_fmt,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
            ),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.debug_diagnostics_title)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(R.string.debug_close, null)
            .show()
    }

    private fun showBuffer() {
        Thread {
            val pending = runCatching { DatabaseHelper(this).countPositions() }.getOrDefault(-1)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                toast(getString(R.string.debug_buffer_toast, pending))
            }
        }.start()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
