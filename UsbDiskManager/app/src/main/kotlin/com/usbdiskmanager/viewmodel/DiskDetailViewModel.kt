package com.usbdiskmanager.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.core.model.BenchmarkResult
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.usb.api.UsbDeviceRepository
import com.usbdiskmanager.usb.impl.UsbBenchmarkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class DiskDetailUiState(
    val device: DiskDevice? = null,
    val isLoading: Boolean = false,
    val operationProgress: Int = 0,
    val operationMessage: String? = null,
    val errorMessage: String? = null,
    val benchmarkResult: BenchmarkResult? = null,
    val isBenchmarking: Boolean = false,
    val isFormatting: Boolean = false,
    val showFormatConfirmation: Boolean = false,
    val selectedFormatType: String = "FAT32"
)

@HiltViewModel
class DiskDetailViewModel @Inject constructor(
    private val usbRepository: UsbDeviceRepository,
    private val benchmarkManager: UsbBenchmarkManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val deviceId: String = savedStateHandle.get<String>("deviceId") ?: ""

    private val _uiState = MutableStateFlow(DiskDetailUiState())
    val uiState: StateFlow<DiskDetailUiState> = _uiState.asStateFlow()

    init {
        loadDevice()
    }

    private fun loadDevice() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val device = usbRepository.refreshDevice(deviceId)
            _uiState.value = _uiState.value.copy(device = device, isLoading = false)
        }

        // Also observe live updates
        usbRepository.connectedDevices.onEach { devices ->
            val device = devices.find { it.id == deviceId }
            if (device != null) {
                _uiState.value = _uiState.value.copy(device = device)
            }
        }.launchIn(viewModelScope)
    }

    fun startBenchmark() {
        val device = _uiState.value.device ?: return
        val mountPoint = device.mountPoint ?: run {
            _uiState.value = _uiState.value.copy(
                errorMessage = "Device is not mounted. Please mount it first."
            )
            return
        }

        _uiState.value = _uiState.value.copy(isBenchmarking = true, benchmarkResult = null)

        benchmarkManager.runBenchmark(deviceId, mountPoint)
            .onEach { (message, result) ->
                _uiState.value = _uiState.value.copy(
                    operationMessage = message,
                    benchmarkResult = result ?: _uiState.value.benchmarkResult,
                    isBenchmarking = result == null
                )
            }
            .catch { e ->
                Timber.e(e, "Benchmark error")
                _uiState.value = _uiState.value.copy(
                    isBenchmarking = false,
                    errorMessage = "Benchmark failed: ${e.message}"
                )
            }
            .launchIn(viewModelScope)
    }

    fun showFormatConfirmation(show: Boolean) {
        _uiState.value = _uiState.value.copy(showFormatConfirmation = show)
    }

    fun setFormatType(type: String) {
        _uiState.value = _uiState.value.copy(selectedFormatType = type)
    }

    fun formatDevice(label: String = "") {
        val device = _uiState.value.device ?: return
        val fsType = _uiState.value.selectedFormatType

        _uiState.value = _uiState.value.copy(
            isFormatting = true,
            showFormatConfirmation = false,
            operationProgress = 0
        )

        usbRepository.formatDevice(deviceId, fsType, label)
            .onEach { result ->
                when (result) {
                    is DiskOperationResult.Progress -> {
                        _uiState.value = _uiState.value.copy(
                            operationProgress = result.percent,
                            operationMessage = result.message
                        )
                    }
                    is DiskOperationResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isFormatting = false,
                            operationMessage = result.message,
                            operationProgress = 100
                        )
                        loadDevice()
                    }
                    is DiskOperationResult.Error -> {
                        _uiState.value = _uiState.value.copy(
                            isFormatting = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
            .catch { e ->
                Timber.e(e, "Format error")
                _uiState.value = _uiState.value.copy(
                    isFormatting = false,
                    errorMessage = "Format error: ${e.message}"
                )
            }
            .launchIn(viewModelScope)
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, operationMessage = null)
    }
}
