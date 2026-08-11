package com.dmujeres.gps.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.dmujeres.gps.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class UpdateManager(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Consulta GET {updateBaseUrl}/update/latest.json */
    suspend fun checkForUpdate(updateBaseUrl: String): UpdateInfo? = withContext(Dispatchers.IO) {
        if (updateBaseUrl.isBlank()) return@withContext null
        val base = updateBaseUrl.trimEnd('/')
        val url = "$base/update/latest.json"
        try {
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            UpdateInfo.fromJson(body)
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: $url", e)
            null
        }
    }

    fun isUpdateAvailable(info: UpdateInfo): Boolean =
        info.versionCode > BuildConfig.VERSION_CODE

    fun isForceUpdateRequired(info: UpdateInfo): Boolean =
        BuildConfig.VERSION_CODE < info.minVersionCode

    suspend fun downloadApk(url: String, onProgress: (Int) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).get().build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val body = response.body ?: return@withContext null
                val total = body.contentLength()
                val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
                val file = File(dir, "update-${System.currentTimeMillis()}.apk")

                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress(((downloaded * 100) / total).toInt())
                            }
                        }
                    }
                }
                file
            } catch (e: Exception) {
                Log.e(TAG, "APK download failed", e)
                null
            }
        }

    fun installApk(apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }

    companion object {
        private const val TAG = "UpdateManager"
    }
}
