package org.traccar.client

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Actualización OTA del cliente de respaldo usando el mismo endpoint del
 * servidor que la app nativa (`/api/mobile/v1/ota`, con allowlist de piloto).
 * Descarga el APK, verifica el sha256 y lanza el instalador del sistema.
 */
object OtaUpdater {

    private const val TAG = "OtaUpdater"
    private const val APK_NAME = "dmujeres-update.apk"

    fun checkAndPrompt(activity: Activity) {
        DmujeresApi.checkOta(activity) { versionCode, url, sha256 ->
            activity.runOnUiThread {
                AlertDialog.Builder(activity)
                    .setTitle(R.string.ota_title)
                    .setMessage(activity.getString(R.string.ota_message, versionCode))
                    .setPositiveButton(R.string.ota_install) { _, _ -> downloadAndInstall(activity, url, sha256) }
                    .setNegativeButton(R.string.ota_later, null)
                    .show()
            }
        }
    }

    private fun downloadAndInstall(activity: Activity, url: String, sha256: String) {
        Thread {
            try {
                val target = File(activity.cacheDir, APK_NAME)
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.inputStream.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                connection.disconnect()
                if (sha256.isNotBlank() && sha256 != sha256Of(target)) {
                    Log.w(TAG, "sha256 no coincide; se descarta la descarga")
                    target.delete()
                    return@Thread
                }
                val uri = FileProvider.getUriForFile(
                    activity, activity.packageName + ".fileprovider", target,
                )
                activity.runOnUiThread {
                    val intent = Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Descarga OTA falló", e)
            }
        }.start()
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
