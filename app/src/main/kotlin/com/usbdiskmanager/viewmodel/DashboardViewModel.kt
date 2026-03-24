package com.usbdiskmanager.viewmodel

import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.log.LogManager
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
    private val logManager: LogManager
) : ViewModel() {

    val connectedDevices = usbRepository.connectedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        logManager.log("App started — scanning USB devices")
    }

    fun onUsbDeviceAttached(device: UsbDevice) {
        viewModelScope.launch {
            logManager.log("USB device attached: ${device.productName ?: device.deviceName}")
            val hasPermission = usbRepository.hasPermission(device)
            if (!hasPermission) {
                logManager.log("Requesting USB permission for ${device.productName ?: device.deviceName}…")
                val granted = usbRepository.requestPermission(device)
                logManager.log(if (granted) "Permission granted ✓" else "Permission denied ✗")
                if (granted) usbRepository.refreshConnectedDevices()
            } else {
                logManager.log("Permission already granted for ${device.productName ?: device.deviceName}")
                usbRepository.refreshConnectedDevices()
            }
        }
    }

    fun onSafUriGranted(uri: Uri) {
        _uiState.value = _uiState.value.copy(safUri = uri)
        logManager.log("SAF access granted: $uri")
    }

    fun refreshDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            logManager.log("Refreshing device list…")
            usbRepository.refreshConnectedDevices()
            val count = connectedDevices.value.size
            logManager.log("Scan complete — $count device(s) found")
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun mountDevice(deviceId: String) {
        viewModelScope.launch {
            logManager.log("Mounting device $deviceId…")
            val result = usbRepository.mountDevice(deviceId)
            when (result) {
                is DiskOperationResult.Success -> {
                    logManager.log("Mount: ${result.message}")
                    _uiState.value = _uiState.value.copy(operationMessage = result.message)
                }
                is DiskOperationResult.Error -> {
                    logManager.log("Mount error: ${result.message}")
                    _uiState.value = _uiState.value.copy(errorMessage = result.message)
                }
                else -> {}
            }
        }
    }

    fun unmountDevice(deviceId: String) {
        viewModelScope.launch {
            logManager.log("Unmounting device $deviceId…")
            val result = usbRepository.unmountDevice(deviceId)
            when (result) {
                is DiskOperationResult.Success -> {
                    logManager.log("Unmount: ${result.message}")
                    _uiState.value = _uiState.value.copy(operationMessage = result.message)
                }
                is DiskOperationResult.Error -> {
                    logManager.log("Unmount error: ${result.message}")
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
