package org.traccar.client

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Inicio de sesión con usuario y contraseña (POST /api/mobile/v1/sesion).
 *
 * - Usuario + clave + botón entrar + error humano en español (401 o sin red,
 *   sin jerga), en línea debajo del título.
 * - Al entrar se guarda el token y se REEMPLAZA la sesión anterior.
 * - No es una puerta obligatoria: si no hay sesión guardada, la app sigue
 *   funcionando con la clave compartida actual (compatibilidad con la flota
 *   instalada). A esta pantalla se llega desde el asistente, desde el aviso de
 *   sesión vencida o tras cerrar sesión en depuración.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var userField: EditText
    private lateinit var passField: EditText
    private lateinit var errorView: TextView
    private lateinit var enterButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        findViewById<TextView>(R.id.version_label).text =
            getString(R.string.version_format, BuildConfig.VERSION_NAME)
        userField = findViewById(R.id.field_user)
        passField = findViewById(R.id.field_pass)
        errorView = findViewById(R.id.login_error)
        enterButton = findViewById(R.id.btn_enter)

        // Entrar con otro usuario reemplaza la sesión anterior: se avisa.
        val current = SessionStore.user(this)
        if (current.isNotBlank()) {
            findViewById<TextView>(R.id.login_current_session).apply {
                text = getString(R.string.login_current_session_fmt, current)
                visibility = View.VISIBLE
            }
        }

        enterButton.setOnClickListener { doLogin() }
    }

    /**
     * Cierra el teclado al tocar fuera del campo activo (igual que el asistente).
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

    private fun doLogin() {
        val user = userField.text.toString().trim().lowercase()
        if (user.isEmpty()) {
            showError(R.string.login_error_user)
            return
        }
        val password = passField.text.toString().trim()
        if (password.isEmpty()) {
            showError(R.string.login_error_password)
            return
        }
        errorView.visibility = View.GONE
        enterButton.isEnabled = false
        enterButton.text = getString(R.string.login_checking)
        Thread {
            val result = DmujeresApi.login(this, user, password)
            runOnUiThread {
                enterButton.isEnabled = true
                enterButton.text = getString(R.string.login_enter)
                when (result) {
                    DmujeresApi.LoginResult.AUTHORIZED -> {
                        // Inicio limpio en otra sesión: la sesión se reemplaza
                        // (SessionStore.save) y además no queda captura
                        // congelada ni nombre/usuario residual (el cierre
                        // limpio ya retiró el anterior). El servicio se
                        // reinicia para tomar la sesión nueva: al arrancar
                        // reconcilia la jornada con GET /journey (continúa la
                        // abierta del equipo o espera una nueva, nunca dos
                        // abiertas locales) y la cola drena primero lo
                        // heredado (lo más viejo primero).
                        TrackingController.captureFrozen = false
                        if (TrackingService.isRunning) {
                            RemoteConfig.restartService(this)
                        } else {
                            androidx.core.content.ContextCompat.startForegroundService(
                                this,
                                Intent(this, TrackingService::class.java),
                            )
                        }
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finish()
                    }
                    DmujeresApi.LoginResult.UNKNOWN_USER -> showError(R.string.login_error_unknown)
                    DmujeresApi.LoginResult.BAD_CREDENTIALS -> showError(R.string.login_error_credentials)
                    else -> showError(R.string.login_error_offline)
                }
            }
        }.start()
    }

    private fun showError(messageRes: Int) {
        errorView.setText(messageRes)
        errorView.visibility = View.VISIBLE
    }

    companion object {
        /** Abre la pantalla de login (cierre de sesión, sesión vencida). */
        fun start(context: Context) {
            context.startActivity(Intent(context, LoginActivity::class.java))
        }
    }
}
