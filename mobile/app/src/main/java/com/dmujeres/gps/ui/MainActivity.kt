package com.dmujeres.gps.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val fineGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fineGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = results[Manifest.permission.POST_NOTIFICATIONS] == true
            if (!notifGranted) {
                // El usuario puede denegar; el servicio seguirá intentando mostrar notificaciones.
            }
        }
        requestBatteryOptimizationExemption()
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestBatteryOptimizationExemption()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestInitialPermissions()

        setContent {
            val prefs by viewModel.preferences.collectAsState()
            val status by viewModel.trackingStatus.collectAsState()
            val updateInfo by viewModel.updateInfo.collectAsState()
            val updateAvailable by viewModel.updateAvailable.collectAsState()
            val forceUpdate by viewModel.forceUpdateRequired.collectAsState()
            val isDownloading by viewModel.isDownloading.collectAsState()
            val downloadProgress by viewModel.downloadProgress.collectAsState()
            val statusMessage by viewModel.statusMessage.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(Unit) {
                viewModel.checkForUpdates()
            }

            LaunchedEffect(statusMessage) {
                statusMessage?.let {
                    snackbarHostState.showSnackbar(it)
                    viewModel.clearStatusMessage()
                }
            }

            if (forceUpdate && updateInfo != null) {
                ForceUpdateScreen(
                    versionName = updateInfo!!.versionName,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress,
                    onUpdateClick = { viewModel.downloadAndInstallUpdate() }
                )
            } else {
                MainScreen(
                    prefs = prefs,
                    status = status,
                    updateAvailable = updateAvailable,
                    isDownloading = isDownloading,
                    downloadProgress = downloadProgress,
                    snackbarHostState = snackbarHostState,
                    onUniqueIdChange = viewModel::saveUniqueId,
                    onServerHostChange = viewModel::saveServerHost,
                    onUpdateBaseUrlChange = viewModel::saveUpdateBaseUrl,
                    onTrackingToggle = { enabled ->
                        if (enabled) {
                            requestInitialPermissions()
                        }
                        viewModel.setTrackingEnabled(enabled)
                    },
                    onCheckUpdate = viewModel::checkForUpdates,
                    onDownloadUpdate = viewModel::downloadAndInstallUpdate,
                    onOpenBatterySettings = { openBatteryOptimizationSettings() }
                )
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.preferences.collect { prefs ->
                    if (prefs.trackingEnabled &&
                        prefs.uniqueId.isNotBlank() &&
                        prefs.serverHost.isNotBlank()
                    ) {
                        com.dmujeres.gps.service.GpsTrackingService.start(this@MainActivity)
                    }
                }
            }
        }
    }

    private fun requestInitialPermissions() {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = "package:$packageName".toUri()
                }
                startActivity(intent)
            } catch (_: Exception) {
                openBatteryOptimizationSettings()
            }
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}

@Composable
private fun MainScreen(
    prefs: com.dmujeres.gps.data.AppPreferences,
    status: com.dmujeres.gps.data.TrackingStatus,
    updateAvailable: Boolean,
    isDownloading: Boolean,
    downloadProgress: Int,
    snackbarHostState: SnackbarHostState,
    onUniqueIdChange: (String) -> Unit,
    onServerHostChange: (String) -> Unit,
    onUpdateBaseUrlChange: (String) -> Unit,
    onTrackingToggle: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    var uniqueId by remember(prefs.uniqueId) { mutableStateOf(prefs.uniqueId) }
    var serverHost by remember(prefs.serverHost) { mutableStateOf(prefs.serverHost) }
    var updateBaseUrl by remember(prefs.updateBaseUrl) { mutableStateOf(prefs.updateBaseUrl) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(updateAvailable) {
        if (updateAvailable) showUpdateDialog = true
    }

    if (showUpdateDialog && updateAvailable) {
        AlertDialog(
            onDismissRequest = { showUpdateDialog = false },
            title = { Text("Hay actualización") },
            text = { Text("Hay una nueva versión disponible. ¿Desea actualizar ahora?") },
            confirmButton = {
                TextButton(onClick = {
                    showUpdateDialog = false
                    onDownloadUpdate()
                }) { Text("Actualizar") }
            },
            dismissButton = {
                TextButton(onClick = { showUpdateDialog = false }) { Text("Después") }
            }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "DMujeres GPS",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = uniqueId,
                onValueChange = {
                    uniqueId = it
                    onUniqueIdChange(it)
                },
                label = { Text("ID de equipo") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = serverHost,
                onValueChange = {
                    serverHost = it
                    onServerHostChange(it)
                },
                label = { Text("Servidor (host MQTT)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("ej. gps.ejemplo.com") }
            )

            OutlinedTextField(
                value = updateBaseUrl,
                onValueChange = {
                    updateBaseUrl = it
                    onUpdateBaseUrlChange(it)
                },
                label = { Text("URL base de actualización") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("https://servidor.ejemplo.com") }
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (prefs.trackingEnabled) Color(0xFF1B5E20) else Color(0xFF424242)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (prefs.trackingEnabled) "Rastreo ACTIVO" else "Rastreo INACTIVO",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Activa el envío continuo de ubicación",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = prefs.trackingEnabled,
                        onCheckedChange = onTrackingToggle
                    )
                }
            }

            StatusCard(status = status)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onCheckUpdate,
                    modifier = Modifier.weight(1f),
                    enabled = !isDownloading
                ) {
                    Text("Actualizar")
                }
                Button(
                    onClick = onOpenBatterySettings,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF616161))
                ) {
                    Text("Batería")
                }
            }

            if (isDownloading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.height(24.dp))
                    Text("Descargando actualización… $downloadProgress%")
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: com.dmujeres.gps.data.TrackingStatus) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Estado en vivo", fontWeight = FontWeight.Bold)
            StatusRow(
                label = "Conexión MQTT",
                value = if (status.isConnected) "Conectado" else "Desconectado",
                ok = status.isConnected
            )
            StatusRow(
                label = "Señal",
                value = if (status.hasSignal) "OK" else "Sin señal / reintentando",
                ok = status.hasSignal
            )
            StatusRow(
                label = "Última posición",
                value = status.lastPositionText,
                ok = true
            )
            StatusRow(
                label = "Cola offline",
                value = "${status.queueSize} pendientes",
                ok = status.queueSize == 0
            )
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
            fontWeight = FontWeight.Medium
        )
    }
}
