package com.usbdiskmanager.viewmodel

import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.usb.api.UsbDeviceRepository
import com.usbdiskmanager.usb.impl.UsbDeviceRepositoryImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
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
    private val usbRepositoryImpl: UsbDeviceRepositoryImpl
) : ViewModel() {

    val connectedDevices: StateFlow<List<DiskDevice>> = usbRepository.connectedDevices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun onUsbDeviceAttached(device: UsbDevice) {
        viewModelScope.launch {
            log("USB device attached: ${device.productName ?: device.deviceName}")
            val hasPermission = usbRepository.hasPermission(device)
            if (!hasPermission) {
                log("Requesting USB permission for ${device.productName}...")
                val granted = usbRepository.requestPermission(device)
                log(if (granted) "Permission granted ✓" else "Permission denied ✗")
                if (granted) usbRepositoryImpl.refreshConnectedDevices()
            } else {
                log("Permission already granted for ${device.productName}")
                usbRepositoryImpl.refreshConnectedDevices()
            }
        }
    }

    fun onSafUriGranted(uri: Uri) {
        _uiState.value = _uiState.value.copy(safUri = uri)
        log("SAF access granted: $uri")
    }

    fun refreshDevices() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            usbRepositoryImpl.refreshConnectedDevices()
            _uiState.value = _uiState.value.copy(isLoading = false)
            log("Device list refreshed")
        }
    }

    fun mountDevice(deviceId: String) {
        viewModelScope.launch {
            log("Mounting device $deviceId...")
            val result = usbRepository.mountDevice(deviceId)
            when (result) {
                is DiskOperationResult.Success -> {
                    log("Mount success: ${result.message}")
                    _uiState.value = _uiState.value.copy(operationMessage = result.message)
                }
                is DiskOperationResult.Error -> {
                    log("Mount error: ${result.message}")
                    _uiState.value = _uiState.value.copy(errorMessage = result.message)
                }
                else -> {}
            }
        }
    }

    fun unmountDevice(deviceId: String) {
        viewModelScope.launch {
            log("Unmounting device $deviceId...")
            val result = usbRepository.unmountDevice(deviceId)
            when (result) {
                is DiskOperationResult.Success -> {
                    log("Unmount success: ${result.message}")
                    _uiState.value = _uiState.value.copy(operationMessage = result.message)
                }
                is DiskOperationResult.Error -> {
                    log("Unmount error: ${result.message}")
                    _uiState.value = _uiState.value.copy(errorMessage = result.message)
                }
                else -> {}
            }
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, operationMessage = null)
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = "[$timestamp] $message"
        Timber.d(entry)
        val current = _logs.value.toMutableList()
        current.add(0, entry) // newest first
        if (current.size > 200) current.removeAt(current.size - 1)
        _logs.value = current
    }
}
