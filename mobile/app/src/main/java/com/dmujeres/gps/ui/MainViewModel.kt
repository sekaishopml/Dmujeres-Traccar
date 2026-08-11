package com.dmujeres.gps.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dmujeres.gps.data.AppPreferences
import com.dmujeres.gps.data.PreferencesManager
import com.dmujeres.gps.data.TrackingStatusHolder
import com.dmujeres.gps.service.GpsTrackingService
import com.dmujeres.gps.update.UpdateInfo
import com.dmujeres.gps.update.UpdateManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsManager = PreferencesManager(application)
    private val updateManager = UpdateManager(application)

    val preferences: StateFlow<AppPreferences> = prefsManager.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppPreferences())

    val trackingStatus = TrackingStatusHolder.status

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _updateAvailable = MutableStateFlow(false)
    val updateAvailable: StateFlow<Boolean> = _updateAvailable.asStateFlow()

    private val _forceUpdateRequired = MutableStateFlow(false)
    val forceUpdateRequired: StateFlow<Boolean> = _forceUpdateRequired.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun saveUniqueId(value: String) {
        viewModelScope.launch { prefsManager.setUniqueId(value.trim()) }
    }

    fun saveServerHost(value: String) {
        viewModelScope.launch { prefsManager.setServerHost(value.trim()) }
    }

    fun saveUpdateBaseUrl(value: String) {
        viewModelScope.launch { prefsManager.setUpdateBaseUrl(value.trim()) }
    }

    fun setTrackingEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefsManager.setTrackingEnabled(enabled)
            val prefs = preferences.value
            if (enabled) {
                if (prefs.uniqueId.isNotBlank() && prefs.serverHost.isNotBlank()) {
                    GpsTrackingService.start(getApplication())
                }
            } else {
                GpsTrackingService.stop(getApplication())
            }
        }
    }

    fun checkForUpdates() {
        viewModelScope.launch {
            val baseUrl = preferences.value.updateBaseUrl
            if (baseUrl.isBlank()) {
                _statusMessage.value = "Configure la URL de actualización"
                return@launch
            }
            val info = updateManager.checkForUpdate(baseUrl)
            _updateInfo.value = info
            if (info == null) {
                _statusMessage.value = "No se pudo verificar actualizaciones"
                _updateAvailable.value = false
                _forceUpdateRequired.value = false
                return@launch
            }
            _forceUpdateRequired.value = updateManager.isForceUpdateRequired(info)
            _updateAvailable.value = updateManager.isUpdateAvailable(info)
            if (!_updateAvailable.value && !_forceUpdateRequired.value) {
                _statusMessage.value = "Ya tiene la última versión"
            } else {
                _statusMessage.value = "Actualización disponible: ${info.versionName}"
            }
        }
    }

    fun downloadAndInstallUpdate() {
        val info = _updateInfo.value ?: return
        viewModelScope.launch {
            _isDownloading.value = true
            _downloadProgress.value = 0
            val file = updateManager.downloadApk(info.url) { progress ->
                _downloadProgress.value = progress
            }
            _isDownloading.value = false
            if (file != null) {
                updateManager.installApk(file)
                _statusMessage.value = "Instalando actualización…"
            } else {
                _statusMessage.value = "Error al descargar la actualización"
            }
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
