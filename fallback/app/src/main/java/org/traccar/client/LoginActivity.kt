package org.traccar.client

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.runBlocking

/**
 * Inicio de sesión con usuario y contraseña (POST /api/mobile/v1/sesion).
 *
 * - Usuario + clave + botón entrar + error humano en español (401 o sin red,
 *   sin jerga), en línea debajo del título.
 * - Al entrar se guarda el token y se REEMPLAZA la sesión anterior.
 * - Cambio de cuenta: si hay sesión de OTRO usuario, primero corre el cierre
 *   limpio con el equipo ANTERIOR (flush + fin de jornada) y solo después
 *   hace el login, que adopta el equipo nuevo. Lo capturado con el equipo
 *   viejo queda en la cola con su `device_id` y drena con él. Entrar con el
 *   mismo usuario no repite el cierre.
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
        Responsivo.raiz(this)?.let { Responsivo.centrar(it) }

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
        val needsClose = SessionClosePlan.requiresCleanCloseBeforeLogin(
            SessionStore.hasSession(this),
            SessionStore.user(this),
            user,
        )
        errorView.visibility = View.GONE
        enterButton.isEnabled = false
        enterButton.text = getString(R.string.login_checking)
        Thread {
            // Cambio de cuenta: cierre limpio con el equipo ANTERIOR antes del
            // login (que adopta el nuevo). Si el flush queda a medias por red,
            // se avisa y se continúa: las filas guardan su equipo de captura.
            if (needsClose) {
                val report = runBlocking { SessionCloser.cerrarSesionLimpia(this@LoginActivity) }
                if (report.remaining > 0) {
                    runOnUiThread {
                        val aviso = getString(R.string.login_switch_pending_fmt, report.remaining)
                        Toast.makeText(this, aviso, Toast.LENGTH_LONG).show()
                        StatusActivity.addMessage(aviso)
                    }
                }
            }
            val result = DmujeresApi.login(this, user, password)
            runOnUiThread {
                enterButton.isEnabled = true
                enterButton.text = getString(R.string.login_enter)
                when (result) {
                    DmujeresApi.LoginResult.AUTHORIZED -> {
                        // Inicio limpio en otra sesión: la sesión se reemplaza
                        // (SessionStore.save) y además no queda captura
                        // congelada ni nombre/usuario residual (si venía de
                        // otro usuario, el cierre limpio ya retiró el
                        // anterior). El servicio se
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
