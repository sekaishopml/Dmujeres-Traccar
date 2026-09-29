package org.traccar.client

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Canal DMujeres del cliente de respaldo (plan B).
 *
 * El trazado va por el protocolo OsmAnd del cliente oficial (puerto 5055);
 * este objeto cubre lo que el cliente oficial no trae y el servidor sí espera:
 * - registro del token FCM (recuperación por push),
 * - eventos de jornada (inicio/fin) para el reporte del panel,
 * - ack de recuperación,
 * - consulta de actualización OTA.
 *
 * Todo corre en hilos propios: nunca bloquea la UI ni el servicio.
 */
object DmujeresApi {

    private const val TAG = "DmujeresApi"
    const val KEY_JOURNEY_ID = "journeyId"
    const val KEY_PASSWORD = "password"
    const val KEY_JOURNEY_STARTED_AT = "journeyStartedAt"
    const val KEY_JOURNEY_OPEN = "journeyOpen"
    private const val CLIENT = "dmujeres-traccar"
    private const val GITHUB_REPO = "sekaishopml/Dmujeres-Traccar"

    private fun prefs(context: Context) =
        PreferenceManager.getDefaultSharedPreferences(context)

    private fun deviceId(context: Context): String =
        prefs(context).getString(Prefs.DEVICE, "").orEmpty().trim().lowercase()

    /**
     * Llave del canal móvil: la contraseña que CCTV entregó (si el técnico la
     * cambió en modo avanzado) o la de fábrica embebida en el build.
     * Es el FALLBACK cuando no hay sesión guardada (flota instalada).
     */
    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, "").orEmpty()
            .ifBlank { BuildConfig.MOBILE_HTTP_API_KEY }

    /** ¿Hay sesión guardada (token)? Sin sesión se usa la clave compartida. */
    fun hasSession(context: Context): Boolean =
        SessionStore.hasSession(context)

    /** Usuario de la sesión actual (vacío si no hay sesión). */
    fun sessionUser(context: Context): String =
        SessionStore.user(context)

    /**
     * Cabeceras de autenticación del canal móvil: con token se envía
     * `Authorization: Bearer` (sin clave compartida); sin token, la clave
     * actual. Devuelve true si se envió token (para tratar el 401).
     * La identidad (`X-Device-Id`) la pone cada llamada como hasta ahora.
     */
    fun setAuthHeaders(connection: HttpURLConnection, context: Context): Boolean {
        val headers = SessionAuth.authHeaders(SessionStore.token(context), apiKey(context))
        for ((name, value) in headers) {
            connection.setRequestProperty(name, value)
        }
        return SessionStore.hasSession(context)
    }

    /**
     * 401 con token (revocado o usuario deshabilitado): limpia la sesión y deja
     * marcado pedir login de nuevo. No reintenta: quien llamó ya pausó su envío
     * (la cola) o era una sonda de un solo disparo. Sin token no hay sesión que
     * limpiar (el 401 es de la clave compartida).
     */
    fun noteHttpResult(context: Context, code: Int, hadToken: Boolean) {
        if (!SessionAuth.shouldClearSession(code, hadToken)) return
        SessionStore.clearOnUnauthorized(context)
        Log.w(TAG, "sesión terminada por el servidor (401 con token): se pedirá login")
        StatusActivity.addMessage(context.getString(R.string.status_session_expired))
    }

    /** Cierre manual (debug): borra token y datos de sesión. */
    fun logout(context: Context) {
        SessionStore.clear(context)
        Log.i(TAG, "sesión cerrada manualmente")
    }

    /** Base web (999) derivada de la URL OsmAnd configurada (5055). */
    fun webBase(context: Context): String {
        val url = prefs(context).getString(Prefs.URL, "").orEmpty()
            .ifBlank { context.getString(R.string.settings_url_default_value) }
        return url.replace(":5055", ":999").trimEnd('/')
    }

    private fun post(context: Context, path: String, body: JSONObject, onDone: ((Boolean) -> Unit)? = null) {
        val base = webBase(context)
        val device = deviceId(context)
        if (base.isBlank() || device.isBlank()) {
            Log.w(TAG, "Sin URL o id configurado; se omite $path")
            onDone?.invoke(false)
            return
        }
        Thread {
            var ok = false
            try {
                val connection = URL(base + path).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val hadToken = setAuthHeaders(connection, context)
                connection.setRequestProperty("X-Device-Id", device)
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = connection.responseCode
                ok = code in 200..299
                if (!ok) {
                    Log.w(TAG, "$path respondió $code")
                    // 401 con token: limpiar y pedir login, sin reintentar en bucle.
                    noteHttpResult(context, code, hadToken)
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "POST $path falló", e)
            }
            onDone?.invoke(ok)
        }.start()
    }

    /** Registra el token FCM del equipo (endpoint compartido con la app nativa). */
    fun registerFcmToken(context: Context, token: String) {
        if (token.isBlank()) return
        post(
            context,
            "/api/mobile/v1/fcm-token",
            JSONObject().put("fcmToken", token).put("appVersion", BuildConfig.VERSION_NAME),
        )
    }

    /** Inicio de jornada: id = epoch ms, evento mobileJourneyStarted en el panel. */
    fun journeyStarted(context: Context) {
        val journeyId = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_JOURNEY_ID, journeyId)
            .putLong(KEY_JOURNEY_STARTED_AT, System.currentTimeMillis())
            .putBoolean(KEY_JOURNEY_OPEN, true)
            .apply()
        StatusActivity.addMessage(context.getString(R.string.journey_started_toast))
        post(
            context,
            "/api/mobile/v1/journey",
            JSONObject()
                .put("deviceId", deviceId(context))
                .put("action", "start")
                .put("journeyId", journeyId)
                .put("client", CLIENT),
        )
    }

    fun isJourneyOpen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_JOURNEY_OPEN, false)

    /** Hora local del inicio de jornada, para el texto "desde las HH:MM". */
    fun journeyStartedAtLabel(context: Context): String {
        val startedAt = prefs(context).getLong(KEY_JOURNEY_STARTED_AT, 0L)
        val format = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return if (startedAt > 0L) format.format(java.util.Date(startedAt)) else "--:--"
    }

    /** Fin de jornada: cierra la jornada abierta (si la hay). */
    fun journeyEnded(context: Context) {
        val open = prefs(context).getBoolean(KEY_JOURNEY_OPEN, false)
        val journeyId = prefs(context).getLong(KEY_JOURNEY_ID, System.currentTimeMillis())
        prefs(context).edit().putBoolean(KEY_JOURNEY_OPEN, false).apply()
        if (!open) return
        StatusActivity.addMessage(context.getString(R.string.journey_ended_toast))
        post(
            context,
            "/api/mobile/v1/journey",
            JSONObject()
                .put("deviceId", deviceId(context))
                .put("action", "stop")
                .put("journeyId", journeyId)
                .put("client", CLIENT),
        )
    }

    /** Resultado de validar el acceso del colaborador en el servidor. */
    enum class LoginResult { AUTHORIZED, UNKNOWN_USER, BAD_CREDENTIALS, OFFLINE }

    /**
     * Validación completa del login: 200 = autorizado, 404 = usuario
     * desconocido, 401/403 = la clave no es la del canal (contraseña
     * incorrecta) y cualquier otro caso se informa como sin conexión.
     */
    fun checkLogin(context: Context, userId: String, key: String): LoginResult {
        val base = webBase(context)
        if (base.isBlank() || userId.isBlank()) return LoginResult.OFFLINE
        return try {
            val connection = URL("$base/api/mobile/v1/config").openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("X-Api-Key", key)
            connection.setRequestProperty("X-Device-Id", userId)
            val code = connection.responseCode
            connection.disconnect()
            when (code) {
                in 200..299 -> LoginResult.AUTHORIZED
                404 -> LoginResult.UNKNOWN_USER
                401, 403 -> LoginResult.BAD_CREDENTIALS
                else -> LoginResult.OFFLINE
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo validar el acceso", e)
            LoginResult.OFFLINE
        }
    }

    /**
     * Inicio de sesión contra POST /api/mobile/v1/sesion {usuario, clave}.
     * Bloqueante: llamar en hilo propio (como [checkLogin]).
     *
     * - 200: guarda el token en [SessionStore] (reemplaza la sesión anterior),
     *   guarda el usuario y aplica la `configuracion` recibida (solo claves
     *   conocidas) → [LoginResult.AUTHORIZED].
     * - 401: credenciales inválidas → [LoginResult.BAD_CREDENTIALS].
     * - 404: el servidor aún no tiene /sesion → compatibilidad: se valida con
     *   el canal actual ([checkLogin], clave compartida) para no bloquear la
     *   flota durante el despliegue del servidor.
     * - Sin red u otro fallo → [LoginResult.OFFLINE].
     */
    fun login(context: Context, usuario: String, clave: String): LoginResult {
        val user = SessionAuth.normalizeUser(usuario)
        if (user.isBlank() || clave.isBlank()) return LoginResult.OFFLINE
        val base = webBase(context)
        if (base.isBlank()) return LoginResult.OFFLINE
        val code: Int
        val body: String
        try {
            val connection = URL(base + SessionAuth.PATH_SESION).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use {
                it.write(SessionAuth.loginRequestJson(user, clave).toByteArray())
            }
            code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            body = runCatching { stream?.bufferedReader()?.use { it.readText() }.orEmpty() }.getOrDefault("")
            connection.disconnect()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar sesión", e)
            return LoginResult.OFFLINE
        }
        return when (code) {
            in 200..299 -> {
                val token = SessionAuth.extractToken(body)
                if (token.isNullOrBlank()) {
                    Log.w(TAG, "sesion respondió 200 sin token")
                    return LoginResult.OFFLINE
                }
                val expiraEn = SessionAuth.extractExpiresInSeconds(body) ?: 0L
                val nombre = SessionAuth.extractDisplayName(body)
                val expiraEnMs = if (expiraEn > 0) System.currentTimeMillis() + expiraEn * 1_000 else 0L
                SessionStore.save(context, token, user, nombre, expiraEnMs)
                // Equipo de la persona: al entrar se adopta su identificador
                // para que la ruta quede bajo su equipo (aparece en Replay y
                // En vivo). Sin equipo vinculado, el móvil conserva el suyo.
                val equipo = SessionAuth.extractEquipoIdentificador(body)
                if (equipo.isNotBlank()) {
                    prefs(context).edit().putString(Prefs.DEVICE, equipo.lowercase()).apply()
                    Log.i(TAG, "la sesión adoptó el equipo $equipo")
                }
                val config = SessionAuth.extractConfigBlock(body)
                RemoteConfig.applySessionConfig(
                    context,
                    config?.let(SessionAuth::parseConfigBlock),
                )
                Log.i(TAG, "sesión iniciada para $user")
                LoginResult.AUTHORIZED
            }
            401 -> LoginResult.BAD_CREDENTIALS
            404 -> {
                // Servidor anterior a /sesion: no se bloquea la flota, se valida
                // con la clave compartida como hasta ahora.
                Log.i(TAG, "sesion no disponible (404): compatibilidad con clave compartida")
                val legacy = checkLogin(context, user, clave)
                if (legacy == LoginResult.AUTHORIZED) {
                    prefs(context).edit()
                        .putString(Prefs.DEVICE, user)
                        .putString(KEY_PASSWORD, clave)
                        .apply()
                }
                legacy
            }
            else -> LoginResult.OFFLINE
        }
    }

    /**
     * ¿El usuario existe en el servidor? null = no se pudo verificar (sin red).
     * Se usa el endpoint de configuración: 200 = autorizado, 404 = desconocido.
     */
    fun userExists(context: Context, userId: String): Boolean? {
        val base = webBase(context)
        if (base.isBlank() || userId.isBlank()) return null
        return try {
            val connection = URL("$base/api/mobile/v1/config").openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            val hadToken = setAuthHeaders(connection, context)
            connection.setRequestProperty("X-Device-Id", userId)
            val code = connection.responseCode
            connection.disconnect()
            noteHttpResult(context, code, hadToken)
            when (code) {
                in 200..299 -> true
                404 -> false
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo validar el usuario", e)
            null
        }
    }

    /** ¿El servidor responde ahora? (sonda corta al canal de configuración). */
    fun serverReachable(context: Context): Boolean {
        val base = webBase(context)
        val device = deviceId(context)
        if (base.isBlank() || device.isBlank()) return false
        return try {
            val connection = URL("$base/api/mobile/v1/config").openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            val hadToken = setAuthHeaders(connection, context)
            connection.setRequestProperty("X-Device-Id", device)
            val code = connection.responseCode
            connection.disconnect()
            noteHttpResult(context, code, hadToken)
            code in 200..499 // 404 también prueba que el servidor responde
        } catch (e: Exception) {
            false
        }
    }

    /** Reporte al canal de diagnósticos (crashes incluidos; ver panel). */
    fun postDiagnostics(context: Context, body: JSONObject) {
        post(context, "/api/mobile/v1/diagnostics", body)
    }

    /** Acuse del push de recuperación (el servidor audita en tc_recovery_event). */
    fun recoveryAck(context: Context, attemptId: String, stage: String) {
        if (attemptId.isBlank()) return
        post(
            context,
            "/api/mobile/v1/recovery-ack",
            JSONObject()
                .put("recoveryAttemptId", attemptId)
                .put("stage", stage)
                .put("priority", "high")
                .put("reason", "fcm"),
        )
    }

    /**
     * Consulta OTA. [onUpdate] recibe (etiqueta o null, url, sha256): etiqueta
     * null = no hay versión mayor publicada. Orden: canal del servidor (rollout
     * con allowlist) y, si el puerto web no es alcanzable, releases de GitHub
     * (mismo respaldo que la app nativa: en datos móviles el 999 puede estar
     * bloqueado y sin esto el teléfono nunca vería el aviso).
     */
    fun checkOta(context: Context, onUpdate: (String?, String, String) -> Unit) {
        val base = webBase(context)
        val device = deviceId(context)
        Thread {
            if (base.isNotBlank() && device.isNotBlank()) {
                val server = tryServerOta(base, device, context)
                if (server != null) {
                    onUpdate(server.first, server.second, server.third)
                    return@Thread
                }
            }
            val github = tryGithubRelease()
            if (github != null) {
                onUpdate(github.first, github.second, "")
            } else {
                onUpdate(null, "", "")
            }
        }.start()
    }

    /**
     * Canal del servidor (rollout con allowlist). Devuelve el manifiesto solo
     * si lo publicado supera a lo instalado; null = sin actualización visible
     * AHORA (explícito `{update:false}`) o error (401/404/500/sin red).
     *
     * Sin estado "visto": el error cae al respaldo de GitHub en este ciclo y
     * se reintenta contra el servidor en el siguiente (el sello anti-spam lo
     * marca MainActivity al terminar). Un 401 con token limpia la sesión
     * (token muerto, regla de SessionAuth) y el siguiente ciclo ya usa la
     * clave compartida; sin token el 401 es de la clave y no toca la sesión.
     */
    private fun tryServerOta(base: String, device: String, context: Context): Triple<String, String, String>? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL("$base/api/mobile/v1/ota?deviceId=$device&versionCode=${BuildConfig.VERSION_CODE}")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            val hadToken = setAuthHeaders(connection, context)
            val httpCode = runCatching { connection.responseCode }.getOrDefault(-1)
            noteHttpResult(context, httpCode, hadToken)
            // No-2xx (401/404/500…): error, sin control por excepción (antes
            // `inputStream` lanzaba aquí y se caía al catch). Se devuelve null
            // para el respaldo de GitHub de este ciclo; el próximo reintenta.
            if (httpCode !in 200..299) {
                Log.w(TAG, "OTA del servidor respondió $httpCode")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val code = json.optInt("versionCode", 0)
            val apkUrl = json.optString("url")
            val sha = json.optString("sha256")
            if (OtaPolicy.isUpdateAvailable(code, BuildConfig.VERSION_CODE) && apkUrl.isNotBlank()) {
                Triple(json.optString("version", code.toString()), apkUrl, sha)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTA del servidor no disponible", e)
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    /** Releases del repo raíz: tag vX.Y.Z + asset APK. Compara por nombre. */
    private fun tryGithubRelease(): Triple<String, String, String>? {
        return try {
            val connection = URL("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                ""
            }
            connection.disconnect()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            val tag = json.optString("tag_name").removePrefix("v")
            if (!isNewer(tag)) return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val url = assets.getJSONObject(i).optString("browser_download_url")
                if (url.endsWith(".apk")) return Triple(tag, url, "")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "OTA de GitHub no disponible", e)
            null
        }
    }

    /** ¿La versión publicada es mayor que la instalada? (ver OtaPolicy). */
    private fun isNewer(candidate: String): Boolean =
        OtaPolicy.isNewerName(BuildConfig.VERSION_NAME, candidate)
}
