package com.dmujeres.traccar.mqtt

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.dmujeres.traccar.config.AppConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Actualización in-app: consulta la última build publicada en el servidor, descarga el APK
 * y lanza la instalación sobre la versión actual (misma firma, datos conservados).
 */
object UpdateManager {

    data class Latest(val version: String, val url: String, val notes: String?)

    /** Consulta http://<host>:8082/latest.json y devuelve la última versión publicada. */
    suspend fun check(serverUrl: String): Latest? {
        val base = HttpFallbackDispatcher.webBase(serverUrl)
        return try {
            val connection = URL("$base/latest.json").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                ""
            }
            connection.disconnect()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            val version = json.optString("version")
            val url = json.optString("url")
            if (version.isBlank() || url.isBlank()) null else Latest(version, url, json.optString("notes"))
        } catch (e: Exception) {
            null
        }
    }

    /** Compara versiones numéricas (1.0.17 > 1.0.9). */
    fun isNewer(installed: String, latest: String): Boolean {
        fun parts(v: String) = v.split('.').mapNotNull { it.toIntOrNull() }
        val a = parts(installed)
        val b = parts(latest)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a.getOrElse(i) { 0 }
            val bv = b.getOrElse(i) { 0 }
            if (av != bv) return bv > av
        }
        return false
    }

    /** Descarga el APK a la caché de la app. Devuelve el archivo o null si falla. */
    suspend fun download(context: Context, url: String): File? {
        return try {
            val dir = File(context.cacheDir, "updates")
            dir.mkdirs()
            val target = File(dir, "dmujeres-update.apk")
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return null
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            if (target.length() > 0) target else null
        } catch (e: Exception) {
            null
        }
    }

    /** Lanza la instalación del APK descargado (pide permiso de instalación una vez). */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "com.dmujeres.traccar.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

}
