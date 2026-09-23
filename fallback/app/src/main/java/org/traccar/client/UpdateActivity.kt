package org.traccar.client

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Pantalla de actualización: logo de la marca (misma posición del home), un
 * círculo de carga y el estado real del proceso — "Descargando… N%" y luego
 * "Actualizando…". Antes se tocaba el banner y no se sabía si estaba
 * descargando; ahora el usuario ve exactamente qué está pasando.
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var retry: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_update)
        progress = findViewById(R.id.update_progress)
        status = findViewById(R.id.update_status)
        detail = findViewById(R.id.update_detail)
        retry = findViewById(R.id.update_retry)
        retry.setOnClickListener { download() }
        if (intent.getBooleanExtra(EXTRA_DEMO, false)) {
            // Menú de depuración: recorre el diseño sin descargar nada.
            simulate()
        } else {
            download()
        }
    }

    private fun download() {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val sha256 = intent.getStringExtra(EXTRA_SHA256).orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        retry.visibility = Button.GONE
        progress.visibility = ProgressBar.VISIBLE
        status.text = getString(R.string.update_downloading)
        detail.text = ""
        Thread { runCatching { downloadAndInstall(url, sha256) } }.start()
    }

    /**
     * Recorrido simulado del diseño (menú de depuración): "Descargando… N%"
     * con avance suave, luego "Actualizando…" y el cierre, sin descargar ni
     * instalar nada.
     */
    private fun simulate() {
        retry.visibility = Button.GONE
        progress.visibility = ProgressBar.VISIBLE
        progress.isIndeterminate = true
        status.text = getString(R.string.update_downloading)
        detail.text = ""
        Thread {
            try {
                runOnUiThread {
                    progress.isIndeterminate = false
                    detail.text = "0%"
                }
                for (percent in 0..100 step 4) {
                    Thread.sleep(120)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        detail.text = "$percent%"
                        progress.progress = percent
                    }
                }
                Thread.sleep(400)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    status.text = getString(R.string.update_installing)
                    detail.text = ""
                    progress.isIndeterminate = true
                }
                Thread.sleep(2_200)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    progress.visibility = ProgressBar.GONE
                    status.text = getString(R.string.update_demo_done)
                }
            } catch (e: InterruptedException) {
                // La pantalla se cerró: nada más que hacer.
            }
        }.start()
    }

    private fun downloadAndInstall(url: String, sha256: String) {
        try {
            val target = File(cacheDir, APK_NAME)
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            val total = connection.contentLength.toLong()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var readTotal = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        readTotal += read
                        if (total > 0) {
                            val percent = (readTotal * 100 / total).toInt()
                            runOnUiThread {
                                detail.text = "$percent%"
                                progress.isIndeterminate = false
                                progress.progress = percent
                            }
                        }
                    }
                }
            }
            connection.disconnect()
            if (sha256.isNotBlank() && sha256 != sha256Of(target)) {
                Log.w(TAG, "sha256 no coincide; se descarta la descarga")
                target.delete()
                showError()
                return
            }
            runOnUiThread {
                status.text = getString(R.string.update_installing)
                detail.text = getString(R.string.update_ready)
                progress.isIndeterminate = true
            }
            install(target)
        } catch (e: Exception) {
            Log.w(TAG, "Descarga de actualización falló", e)
            showError()
        }
    }

    private fun install(file: File) {
        val uri = FileProvider.getUriForFile(this, packageName + ".fileprovider", file)
        runOnUiThread {
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }
    }

    private fun showError() {
        runOnUiThread {
            progress.visibility = ProgressBar.GONE
            status.text = getString(R.string.update_download_failed)
            detail.text = ""
            retry.visibility = Button.VISIBLE
        }
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

    companion object {
        private const val TAG = "UpdateActivity"
        private const val APK_NAME = "dmujeres-update.apk"
        const val EXTRA_URL = "update_url"
        const val EXTRA_SHA256 = "update_sha256"
        private const val EXTRA_DEMO = "update_demo"

        /** Abre la pantalla de carga; si falta el permiso, manda a Ajustes. */
        fun start(activity: Activity, url: String, sha256: String) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                !activity.packageManager.canRequestPackageInstalls()
            ) {
                runCatching {
                    activity.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"),
                        ),
                    )
                }
                Toast.makeText(activity, R.string.update_allow_installs, Toast.LENGTH_LONG).show()
                return
            }
            activity.startActivity(
                Intent(activity, UpdateActivity::class.java)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_SHA256, sha256),
            )
        }

        /** Recorrido simulado del diseño, desde el menú de depuración. */
        fun startDemo(activity: Activity) {
            activity.startActivity(
                Intent(activity, UpdateActivity::class.java)
                    .putExtra(EXTRA_DEMO, true),
            )
        }
    }
}
