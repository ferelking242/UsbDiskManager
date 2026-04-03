package com.usbdiskmanager.viewmodel

import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.log.LogManager
import com.usbdiskmanager.shizuku.ShizukuManager
import com.usbdiskmanager.shizuku.ShizukuState
import com.usbdiskmanager.usb.api.UsbDeviceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val safUri: Uri? = null,
    val operationMessage: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val usbRepository: UsbDeviceRepository,
    private val logManager: LogManager,
    private val shizukuManager: ShizukuManager
) : ViewModel() {

    val connectedDevices = usbRepository.connectedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShizukuState.NotRunning)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        logManager.log("App démarrée — scan USB en cours…")
        shizukuManager.initialize()
        observeShizukuState()
    }

    private fun observeShizukuState() {
        viewModelScope.launch {
            shizukuManager.state.collect { state ->
                val msg = when (state) {
                    is ShizukuState.Ready ->
                        "Shizuku actif — accès privilégié : NTFS/EXT4, format, blkid disponibles"
                    is ShizukuState.NotRunning ->
                        "Shizuku non démarré — mode standard (FAT32/exFAT seulement)"
                    is ShizukuState.NotInstalled ->
                        "Shizuku non installé — mode standard"
                    is ShizukuState.PermissionDenied ->
                        "Shizuku: permission refusée"
                    is ShizukuState.PermissionNotRequested ->
                        "Shizuku actif — en attente d'autorisation"
                }
                logManager.log(msg)
            }
        }
    }

    fun requestShizukuPermission() {
        logManager.log("Demande de permission Shizuku…")
        shizukuManager.requestPermission()
    }

    fun onUsbDeviceAttached(device: UsbDevice) {
        viewModelScope.launch {
            logManager.log("USB connecté: ${device.productName ?: device.deviceName}")
            val hasPermission = usbRepository.hasPermission(device)
            if (!hasPermission) {
                logManager.log("Demande de permission USB…")
                val granted = usbRepository.requestPermission(device)
                logManager.log(if (granted) "Permission USB accordée ✓" else "Permission USB refusée ✗")
                if (granted) usbRepository.refreshConnectedDevices()
            } else {
                logManager.log("Permission USB déjà accordée")
                usbRepository.refreshConnectedDevices()
            }
        }
    }

    fun onSafUriGranted(uri: Uri) {
        _uiState.value = _uiState.value.copy(safUri = uri)
        logManager.log("Accès SAF accordé: $uri")
    }

    fun refreshDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            logManager.log("Actualisation…")
            usbRepository.refreshConnectedDevices()
            val count = connectedDevices.value.size
            val shizuku = if (shizukuManager.isReady) " [Shizuku actif]" else ""
            logManager.log("Scan terminé — $count périphérique(s)$shizuku")
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun mountDevice(deviceId: String) {
        viewModelScope.launch {
            val mode = if (shizukuManager.isReady) " via Shizuku" else ""
            logManager.log("Montage$mode…")
            val result = usbRepository.mountDevice(deviceId)
            when (result) {
                is DiskOperationResult.Success -> {
                    logManager.log("Montage: ${result.message}")
                    _uiState.value = _uiState.value.copy(operationMessage = result.message)
                }
                is DiskOperationResult.Error -> {
                    logManager.log("Erreur montage: ${result.message}")
                    _uiState.value = _uiState.value.copy(errorMessage = result.message)
                }
                else -> {}
            }
        }
    }

    fun unmountDevice(deviceId: String) {
        viewModelScope.launch {
            logManager.log("Éjection du périphérique $deviceId…")
            val result = usbRepository.unmountDevice(deviceId)
            when (result) {
                is DiskOperationResult.Success -> {
                    logManager.log("Éjection: ${result.message}")
                    _uiState.value = _uiState.value.copy(operationMessage = result.message)
                }
                is DiskOperationResult.Error -> {
                    logManager.log("Erreur éjection: ${result.message}")
                    _uiState.value = _uiState.value.copy(errorMessage = result.message)
                }
                else -> {}
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, operationMessage = null)
    }
}
