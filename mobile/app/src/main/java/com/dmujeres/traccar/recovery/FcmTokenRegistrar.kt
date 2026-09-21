package com.dmujeres.traccar.recovery

import android.content.Context
import android.os.Build
import android.util.Log
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.platform.SentryLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import com.dmujeres.traccar.core.MobileProtocol

/**
 * F2: registro/reporte de ACK al backend por el canal HTTP móvil existente
 * (mismo X-Api-Key + X-Device-Id del contrato; NO se inventa identidad).
 * Nunca bloquea el hilo llamador ni lanza: executor daemon mono-hilo.
 */
object FcmAck {

    private const val TAG = "FcmRecovery"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fcm-ack").apply { isDaemon = true }
    }

    /** Reporta una etapa/bloqueo de recovery al server (idempotente por attemptId). */
    fun send(context: Context, config: AppConfig, attemptId: String, stage: String, priority: String, reason: String) {
        if (attemptId.isBlank()) return
        val payload = org.json.JSONObject().apply {
            put("recoveryAttemptId", attemptId)
            put("stage", stage)
            put("priority", priority)
            if (reason.isNotBlank()) put("reason", reason)
        }
        executor.execute {
            runCatching {
                val base = com.dmujeres.traccar.core.MqttServerNormalizer.webBase(
                    config.serverUrl, MobileProtocol.WEB_PORT,
                )
                val connection = (URL("$base${MobileProtocol.PATH_RECOVERY_ACK}").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Api-Key", AppConfig.HTTP_API_KEY)
                    setRequestProperty("X-Device-Id", config.username)
                    doOutput = true
                    outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                if (code !in 200..299) {
                    Log.d(TAG, "ACK $stage rechazado ($code)")
                }
            }.onFailure { error ->
                Log.d(TAG, "ACK $stage falló: ${error.javaClass.simpleName}")
            }
        }
    }
}

/**
 * F2: registro del token FCM en el backend. El token NUNCA se loguea completo
 * (solo prefijo hash de diagnóstico). Sin google-services.json, la app degrada
 * honestamente: fcmConfigured=false, sin crash.
 */
object FcmTokenRegistrar {

    private const val TAG = "FcmToken"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fcm-token").apply { isDaemon = true }
    }

    /** Intenta obtener y registrar el token (llamado al iniciar la app). */
    fun registerIfConfigured(context: Context) {
        context.applicationContext.let { appContext ->
            runCatching {
                val token = com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                token.addOnCompleteListener { task ->
                    if (task.isSuccessful && task.result != null) {
                        register(appContext, task.result)
                    } else {
                        markUnconfigured(appContext, task.exception?.javaClass?.simpleName ?: "no-token")
                    }
                }
            }.onFailure { error ->
                markUnconfigured(appContext, error.javaClass.simpleName)
            }
        }
    }

    /** Registro efectivo (desde registerIfConfigured u onNewToken). */
    fun register(context: Context, explicitToken: String) {
        val config = AppConfig(context)
        val token = explicitToken
        if (token.isBlank()) return
        config.fcmConfigured = true
        config.fcmTokenPrefix = prefixOf(token)
        config.fcmTokenUpdatedAt = System.currentTimeMillis()
        SentryLog.breadcrumb("fcm", "FCM_TOKEN_REGISTERED", "prefix=${config.fcmTokenPrefix}")
        executor.execute {
            runCatching {
                val payload = org.json.JSONObject().apply {
                    put("fcmToken", token)
                    put("appVersion", BuildConfig.VERSION_NAME)
                    put("platform", "android")
                    put("manufacturer", Build.MANUFACTURER.orEmpty())
                    put("model", Build.MODEL.orEmpty())
                    put("androidVersion", Build.VERSION.RELEASE.orEmpty())
                }
                val base = com.dmujeres.traccar.core.MqttServerNormalizer.webBase(
                    config.serverUrl, MobileProtocol.WEB_PORT,
                )
                val connection = (URL("$base${MobileProtocol.PATH_FCM_TOKEN}").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Api-Key", AppConfig.HTTP_API_KEY)
                    setRequestProperty("X-Device-Id", config.username)
                    doOutput = true
                    outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                config.fcmTokenRegistered = code in 200..299
                Log.i(TAG, "Token FCM registrado=${config.fcmTokenRegistered} ($code)")
            }.onFailure { error ->
                config.fcmTokenRegistered = false
                Log.d(TAG, "Registro de token falló: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun markUnconfigured(context: Context, cause: String) {
        val config = AppConfig(context)
        config.fcmConfigured = false
        Log.i(TAG, "FCM no configurado en este build: $cause")
    }

    /** Prefijo hash (sin exponer el token). */
    fun prefixOf(token: String): String = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        digest.take(6).joinToString("") { "%02x".format(it) }
    }.getOrDefault("hash-error")
}
