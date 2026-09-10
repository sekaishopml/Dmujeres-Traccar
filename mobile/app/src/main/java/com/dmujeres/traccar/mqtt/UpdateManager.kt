package com.dmujeres.traccar.mqtt

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.config.AppConfig
import kotlinx.coroutines.delay
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

    /** Repo donde se publican los releases con el APK (fallback cuando el :999 no es alcanzable). */
    const val GITHUB_REPO = "sekaishopml/Dmujeres-Traccar"

    /** Motivo del último fallo de check() (null si la última comprobación funcionó). Para Diagnóstico. */
    @Volatile
    var lastError: String? = null
        private set

    private fun userAgent(): String = "DMujeres-Tracking/${BuildConfig.VERSION_NAME} (Android)"

    /**
     * Devuelve la última versión publicada.
     * 1) http(s)://<host>:999/latest.json (red local/dev).
     * 2) Fallback API de GitHub releases (prod: el 999 no está expuesto a internet).
     */
    suspend fun check(serverUrl: String): Latest? {
        val base = MqttServerNormalizer.webBase(serverUrl, AppConfig.WEB_PORT)
        // El serverUrl es MQTT (tcp://) y no dice si la web va por http o https:
        // se prueba http primero y https como fallback.
        val candidates = listOf("$base/latest.json", base.replaceFirst("http://", "https://") + "/latest.json")
        for (url in candidates) {
            val result = fetchLatest(url)
            if (result != null) return result
        }
        val github = fetchGithubLatest()
        if (github != null) return github
        Log.w("UpdateManager", "sin actualización alcanzable (web $base + GitHub $GITHUB_REPO)")
        return null
    }

    private fun fetchLatest(url: String): Latest? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", userAgent())
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                lastError = "HTTP $code en ${hostOf(url)}"
                Log.w("UpdateManager", "GET $url -> HTTP $code")
                ""
            }
            connection.disconnect()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            val version = json.optString("version").trim()
            val apkUrl = json.optString("url").trim()
            if (version.isBlank() || apkUrl.isBlank()) {
                lastError = "latest.json sin version/url en ${hostOf(url)}"
                Log.w("UpdateManager", "latest.json sin version/url en $url: $body")
                null
            } else {
                lastError = null
                Latest(version, apkUrl, json.optString("notes"))
            }
        } catch (e: Exception) {
            lastError = "${hostOf(url)}: ${e.message ?: e.javaClass.simpleName}"
            Log.w("UpdateManager", "GET $url falló: ${e.message}")
            null
        }
    }

    /** Fallback: último release de GitHub (tag vX.Y.Z + asset .apk). No necesita el :999. */
    private fun fetchGithubLatest(): Latest? {
        val url = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", userAgent())
            val code = connection.responseCode
            val body = if (code in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                lastError = "GitHub HTTP $code" + if (code == 403 || code == 429) " (límite de consultas, reintenta más tarde)" else ""
                Log.w("UpdateManager", "GET $url -> HTTP $code")
                ""
            }
            connection.disconnect()
            if (body.isBlank()) return null
            val json = JSONObject(body)
            // tag "v1.0.46" -> version "1.0.46"
            val tag = json.optString("tag_name")
            val assets = json.optJSONArray("assets")
            val urls = mutableListOf<String>()
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val u = assets.optJSONObject(i)?.optString("browser_download_url").orEmpty().trim()
                    if (u.isNotBlank()) urls += u
                }
            }
            val parsed = parseGithubRelease(tag, urls, json.optString("body"))
            if (parsed == null) {
                lastError = "release GitHub sin APK usable"
                Log.w("UpdateManager", "release GitHub sin tag .apk usable: $body".take(300))
            } else {
                lastError = null
            }
            parsed
        } catch (e: Exception) {
            lastError = "GitHub: ${e.message ?: e.javaClass.simpleName}"
            Log.w("UpdateManager", "GET $url falló: ${e.message}")
            null
        }
    }

    private fun hostOf(url: String): String =
        runCatching { URL(url).host }.getOrNull() ?: url.take(40)

    /**
     * Parsea tag + assets ya extraídos del JSON (función pura, testeable en JVM).
     * tag "v1.0.46" -> version "1.0.46"; se elige la primera URL terminada en .apk.
     */
    fun parseGithubRelease(tagName: String, assetUrls: List<String>, notes: String?): Latest? {
        val version = tagName.trim().removePrefix("v").removePrefix("V")
        val apkUrl = assetUrls.firstOrNull { it.trim().endsWith(".apk", ignoreCase = true) }?.trim()
        if (version.isBlank() || apkUrl.isNullOrBlank()) return null
        return Latest(version, apkUrl, notes?.ifBlank { null })
    }

    /** Compara versiones numéricas (1.0.17 > 1.0.9; tolera sufijos como -beta y espacios). */
    fun isNewer(installed: String, latest: String): Boolean {
        fun parts(v: String) = v.trim().split('.').map {
            // "46-beta" -> 46 ; "x" -> se ignora
            it.trim().takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0
        }
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

    /** Tamaño mínimo aceptable del APK (evita instalar páginas de error truncadas). */
    const val MIN_APK_BYTES = 5_000_000L

    /**
     * Descarga el APK a la caché de la app. Reintenta 1 vez ante fallos
     * transitorios (red móvil inestable) y exige tamaño mínimo.
     * Devuelve el archivo o null si falla (motivo en [lastError]).
     */
    suspend fun download(context: Context, url: String): File? {
        val attempt1 = downloadOnce(context, url)
        if (attempt1 != null) {
            lastError = null
            return attempt1
        }
        val firstError = lastError
        kotlinx.coroutines.delay(3000L)
        val attempt2 = downloadOnce(context, url)
        if (attempt2 != null) {
            lastError = null
            return attempt2
        }
        if (lastError == null) {
            lastError = firstError
        }
        return null
    }

    private fun downloadOnce(context: Context, url: String): File? {
        return try {
            val dir = File(context.cacheDir, "updates")
            if (!dir.exists() && !dir.mkdirs()) {
                lastError = "sin espacio en el dispositivo"
                Log.w("UpdateManager", "no se pudo crear ${dir.absolutePath}")
                return null
            }
            val target = File(dir, "dmujeres-update.apk")
            if (target.exists()) {
                target.delete()
            }
            val connection = URL(url.trim()).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent())
            connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
            if (connection.responseCode !in 200..299) {
                lastError = "${hostOf(url)} respondió HTTP ${connection.responseCode}"
                Log.w("UpdateManager", "GET $url -> HTTP ${connection.responseCode}")
                connection.disconnect()
                return null
            }
            connection.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE * 4) }
            }
            connection.disconnect()
            if (target.length() < MIN_APK_BYTES) {
                lastError = "descarga incompleta (${target.length() / 1024} KB)"
                Log.w("UpdateManager", "APK demasiado pequeño: ${target.length()} bytes")
                runCatching { target.delete() }
                return null
            }
            target
        } catch (e: Exception) {
            lastError = hostOf(url) + ": " + (e.message ?: e.javaClass.simpleName)
            Log.w("UpdateManager", "descarga falló: ${e.message}")
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
