package com.dmujeres.traccar.platform

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.dmujeres.traccar.BuildConfig
import com.dmujeres.traccar.config.AppConfig
import com.dmujeres.traccar.core.MqttServerNormalizer
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.dmujeres.traccar.core.MobileProtocol

/**
 * Actualización in-app: consulta la última build publicada en el servidor, descarga el APK
 * y lanza la instalación sobre la versión actual (misma firma, datos conservados).
 */
object UpdateManager {

    data class Latest(
        val version: String,
        val url: String,
        val notes: String?,
        /** SHA-256 del APK (hex). null/blank = sin verificación (datos viejos). */
        val sha256: String? = null,
        /** P1: versionCode publicado (autoridad de actualización si existe). */
        val versionCode: Int? = null,
        /** P1: versión mínima soportada (opcional). */
        val minVersionCode: Int? = null,
    )

    /** Repo donde se publican los releases con el APK (fallback cuando el :999 no es alcanzable). */
    const val GITHUB_REPO = "sekaishopml/Dmujeres-Traccar"

    /** Motivo del último fallo de check() (null si la última comprobación funcionó). Para Diagnóstico. */
    @Volatile
    var lastError: String? = null
        private set

    private fun userAgent(): String = "DMujeres-Tracking/${BuildConfig.VERSION_NAME} (Android)"

    /**
     * Último uniqueId visto por [check]. Permite que las llamadas sin identidad
     * explícita (p.ej. la UI) sigan consultando el rollout una vez que el
     * checker de plataforma lo aportó. Solo memoria: si no hay identidad se usa
     * el canal latest.json de siempre.
     */
    @Volatile
    private var lastDeviceId: String? = null

    /**
     * Devuelve la última versión publicada.
     * 1) R8: /api/mobile/v1/ota?deviceId=&versionCode= — el servidor decide por
     *    equipo (rollout gradual); si red/HTTP/JSON falla se ignora.
     * 2) http(s)://<host>:999/latest.json (red local/dev).
     * 3) Fallback API de GitHub releases (prod: el 999 no está expuesto a internet).
     */
    suspend fun check(
        serverUrl: String,
        deviceId: String? = null,
        installedCode: Int = BuildConfig.VERSION_CODE,
    ): Latest? {
        if (!deviceId.isNullOrBlank()) lastDeviceId = deviceId.trim()
        val identity = (deviceId ?: lastDeviceId)?.trim().orEmpty()
        val base = MqttServerNormalizer.webBase(serverUrl, MobileProtocol.WEB_PORT)
        if (identity.isNotBlank()) {
            // El serverUrl es MQTT (tcp://) y no dice si la web va por http o
            // https: se prueba http primero y https como fallback (igual que
            // latest.json). Un 404/401 (canal apagado o equipo sin provisionar)
            // también cae al canal clásico.
            for (url in otaCandidates(base, identity, installedCode)) {
                val result = fetchRollout(url, identity, installedCode)
                if (result != null) return result
            }
        }
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

    /** Candidatos http/https del endpoint de rollout con la identidad del equipo. */
    private fun otaCandidates(base: String, deviceId: String, installedCode: Int): List<String> {
        val query = "?deviceId=" + java.net.URLEncoder.encode(deviceId, "UTF-8") +
            "&versionCode=" + installedCode
        return listOf(
            "$base${MobileProtocol.PATH_OTA}$query",
            base.replaceFirst("http://", "https://") + MobileProtocol.PATH_OTA + query,
        )
    }

    /**
     * GET al endpoint de rollout. Devuelve el Latest si el equipo entró (o el
     * centinela "sin update" si el servidor respondió {"update": false}) y null
     * ante red/HTTP/JSON inválido para caer a latest.json como hasta ahora.
     */
    private fun fetchRollout(url: String, deviceId: String, installedCode: Int): Latest? {
        return try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("User-Agent", userAgent())
            connection.setRequestProperty("X-Api-Key", AppConfig.HTTP_API_KEY)
            connection.setRequestProperty("X-Device-Id", deviceId)
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
            val parsed = parseOtaResponse(body, installedCode)
            // Metadatos reales = comprobación sana; {"update": false} no toca
            // el error (decisión normal, no fallo).
            if (parsed != null && parsed.url.isNotBlank()) lastError = null
            parsed
        } catch (e: Exception) {
            lastError = "${hostOf(url)}: ${e.message ?: e.javaClass.simpleName}"
            Log.w("UpdateManager", "GET $url falló: ${e.message}")
            null
        }
    }

    /**
     * Parsea la respuesta del endpoint de rollout (pura, testeable en JVM).
     * - {"update": false} → Latest centinela del instalado: "sin update" y sin
     *   error (el servidor decidió que a este equipo no le toca todavía).
     * - Metadatos válidos → Latest publicado (mismo contrato que latest.json).
     * - JSON inválido / sin version+url / URL rechazada por OtaUrlPolicy → null
     *   para caer a latest.json como hoy.
     */
    fun parseOtaResponse(body: String, installedCode: Int): Latest? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.has("update") && !json.optBoolean("update", true)) {
            return Latest(
                version = BuildConfig.VERSION_NAME,
                url = "",
                notes = null,
                versionCode = installedCode,
            )
        }
        val version = json.optString("version").trim()
        val apkUrl = json.optString("url").trim()
        if (version.isBlank() || apkUrl.isBlank()) return null
        if (!OtaUrlPolicy.isAllowed(apkUrl, release = !BuildConfig.DEBUG)) return null
        return Latest(
            version = version,
            url = apkUrl,
            notes = json.optString("notes").ifBlank { null },
            sha256 = json.optString("sha256").ifBlank { null },
            versionCode = if (json.isNull("versionCode")) null else json.optInt("versionCode", -1).takeIf { it >= 0 },
            minVersionCode = if (json.isNull("minVersionCode")) null else json.optInt("minVersionCode", -1).takeIf { it >= 0 },
        )
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
            } else if (!OtaUrlPolicy.isAllowed(apkUrl, release = !BuildConfig.DEBUG)) {
                lastError = "APK de actualización no permitido (HTTPS requerido): ${hostOf(apkUrl)}"
                Log.w("UpdateManager", "latest.json APK rechazado por política: $apkUrl")
                null
            } else {
                lastError = null
                Latest(
                    version = version,
                    url = apkUrl,
                    notes = json.optString("notes").ifBlank { null },
                    sha256 = json.optString("sha256").ifBlank { null },
                    versionCode = if (json.isNull("versionCode")) null else json.optInt("versionCode", -1).takeIf { it >= 0 },
                    minVersionCode = if (json.isNull("minVersionCode")) null else json.optInt("minVersionCode", -1).takeIf { it >= 0 },
                )
            }
        } catch (e: Exception) {
            lastError = "${hostOf(url)}: ${e.message ?: e.javaClass.simpleName}"
            Log.w("UpdateManager", "GET $url falló: ${e.message}")
            null
        }
    }

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
            val digests = mutableMapOf<String, String>()
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val u = asset.optString("browser_download_url").orEmpty().trim()
                    if (u.isNotBlank()) {
                        urls += u
                        // GitHub expone "digest": "sha256:..." por asset (R3.5-14)
                        asset.optString("digest").orEmpty().trim().removePrefix("sha256:").ifBlank { null }?.let { digests[u] = it }
                    }
                }
            }
            val apkUrl = urls.firstOrNull { it.endsWith(".apk", ignoreCase = true) }
            val parsed = parseGithubRelease(
                tagName = tag,
                assetUrls = urls,
                notes = json.optString("body"),
                sha256 = apkUrl?.let { digests[it] },
            )
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
    fun parseGithubRelease(tagName: String, assetUrls: List<String>, notes: String?, sha256: String? = null): Latest? {
        val version = tagName.trim().removePrefix("v").removePrefix("V")
        val apkUrl = assetUrls.firstOrNull { it.trim().endsWith(".apk", ignoreCase = true) }?.trim()
        if (version.isBlank() || apkUrl.isNullOrBlank()) return null
        return Latest(version, apkUrl, notes?.ifBlank { null }, sha256?.ifBlank { null })
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
     * SHA-256 de un archivo en hex minúsculas (puro JVM, testeable).
     * R3.5-14: verificación de checksum del APK ANTES de instalarlo.
     */
    fun sha256Hex(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Verificación del checksum del APK: true si no se espera ninguno
     * (compatibilidad con latest.json viejo) o si coincide; false si difiere.
     */
    fun apkSha256Matches(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        return sha256Hex(file).equals(expected.trim(), ignoreCase = true)
    }

    /**
     * P1-A3: verificación del paquete descargado ANTES de instalar.
     * Comprueba: paquete correcto, versionCode superior y MISMA firma que la
     * app instalada (no basta un SHA para autenticar un APK).
     * @return null si es instalable; motivo legible si debe rechazarse.
     */
    @Suppress("DEPRECATION")
    fun verifyPackageIntegrity(context: Context, file: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return "APK ilegible"
        if (archive.packageName != context.packageName) {
            return "paquete distinto (${archive.packageName})"
        }
        val archiveCode = if (Build.VERSION.SDK_INT >= 28) {
            archive.longVersionCode
        } else {
            archive.versionCode.toLong()
        }
        val installed = try {
            pm.getPackageInfo(context.packageName, flags)
        } catch (e: Exception) {
            return "no se pudo leer la app instalada"
        }
        val installedCode = if (Build.VERSION.SDK_INT >= 28) {
            installed.longVersionCode
        } else {
            installed.versionCode.toLong()
        }
        if (archiveCode <= installedCode) {
            return "no es una versión superior ($archiveCode <= $installedCode)"
        }
        val archiveSigners = signerDigests(archive, flags)
        val installedSigners = signerDigests(installed, flags)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty()) {
            return "firma no verificable"
        }
        if (archiveSigners != installedSigners) {
            return "firma incompatible con la app instalada"
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: android.content.pm.PackageInfo, flags: Int): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else {
            info.signatures
        }
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return signatures?.map { sig ->
            md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }?.toSet() ?: emptySet()
    }

    /**
     * Descarga el APK a la caché de la app. Reintenta 1 vez ante fallos
     * transitorios (red móvil inestable) y exige tamaño mínimo + SHA-256 si el
     * servidor lo publica (R3.5-14: sin checksum no se instala).
     * Devuelve el archivo o null si falla (motivo en [lastError]).
     */
    suspend fun download(context: Context, url: String, expectedSha256: String? = null): File? {
        val attempt1 = downloadOnce(context, url, expectedSha256)
        if (attempt1 != null) {
            lastError = null
            return attempt1
        }
        val firstError = lastError
        kotlinx.coroutines.delay(3000L)
        val attempt2 = downloadOnce(context, url, expectedSha256)
        if (attempt2 != null) {
            lastError = null
            return attempt2
        }
        if (lastError == null) {
            lastError = firstError
        }
        return null
    }

    private fun downloadOnce(context: Context, url: String, expectedSha256: String? = null): File? {
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
            // R3.5-14: verificación de integridad ANTES de lanzar el instalador.
            if (!apkSha256Matches(target, expectedSha256)) {
                lastError = "checksum SHA-256 no coincide (APK rechazado)"
                Log.w("UpdateManager", "APK con sha256 distinto al publicado: ${target.length()} bytes")
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
        // Fase 1: sin "instalar apps desconocidas" el ACTION_VIEW del APK no
        // abre nada en varias ROMs; se manda directo a la pantalla que lo activa.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.onFailure { Log.w("UpdateManager", "No se pudo abrir install unknown apps", it) }
            return
        }
        val uri = FileProvider.getUriForFile(context, "com.dmujeres.traccar.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

}
