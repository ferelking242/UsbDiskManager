package com.usbdiskmanager.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.core.model.BenchmarkResult
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.log.LogManager
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
    val selectedFormatType: String = "FAT32",
    val formatLog: List<String> = emptyList()
)

@HiltViewModel
class DiskDetailViewModel @Inject constructor(
    private val usbRepository: UsbDeviceRepository,
    private val benchmarkManager: UsbBenchmarkManager,
    private val logManager: LogManager,
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
            if (device != null) {
                logManager.log("[USB] Périphérique chargé: ${device.name} — ${device.mountPoint ?: "non monté"} — ${device.fileSystem.displayName}")
            }
        }

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
                errorMessage = "Périphérique non monté. Montez-le d'abord."
            )
            return
        }

        logManager.log("[BENCH] Démarrage benchmark: ${device.name} @ $mountPoint")
        _uiState.value = _uiState.value.copy(isBenchmarking = true, benchmarkResult = null)

        benchmarkManager.runBenchmark(deviceId, mountPoint)
            .onEach { (message, result) ->
                _uiState.value = _uiState.value.copy(
                    operationMessage = message,
                    benchmarkResult = result ?: _uiState.value.benchmarkResult,
                    isBenchmarking = result == null
                )
                if (result != null) {
                    logManager.log("[BENCH] ✓ Lecture: %.1f MB/s | Écriture: %.1f MB/s".format(
                        result.readSpeedMBps, result.writeSpeedMBps))
                }
            }
            .catch { e ->
                Timber.e(e, "Benchmark error")
                logManager.log("[BENCH] ERREUR: ${e.message}")
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

        logManager.log("[FORMAT] Démarrage formatage: ${device.name} → $fsType${if (label.isNotBlank()) " label=$label" else ""}")

        _uiState.value = _uiState.value.copy(
            isFormatting = true,
            showFormatConfirmation = false,
            operationProgress = 0,
            formatLog = emptyList()
        )

        usbRepository.formatDevice(deviceId, fsType, label)
            .onEach { result ->
                when (result) {
                    is DiskOperationResult.Progress -> {
                        val msg = "[FORMAT] ${result.percent}% — ${result.message}"
                        logManager.log(msg)
                        _uiState.value = _uiState.value.copy(
                            operationProgress = result.percent,
                            operationMessage = result.message,
                            formatLog = _uiState.value.formatLog + result.message
                        )
                    }
                    is DiskOperationResult.Success -> {
                        logManager.log("[FORMAT] ✓ Succès: ${result.message}")
                        _uiState.value = _uiState.value.copy(
                            isFormatting = false,
                            operationMessage = result.message,
                            operationProgress = 100,
                            formatLog = _uiState.value.formatLog + "✓ ${result.message}"
                        )
                        loadDevice()
                    }
                    is DiskOperationResult.Error -> {
                        logManager.log("[FORMAT] ERREUR: ${result.message}")
                        _uiState.value = _uiState.value.copy(
                            isFormatting = false,
                            errorMessage = result.message,
                            formatLog = _uiState.value.formatLog + "✗ ${result.message}"
                        )
                    }
                }
            }
            .catch { e ->
                Timber.e(e, "Format error")
                logManager.log("[FORMAT] EXCEPTION: ${e.message}")
                _uiState.value = _uiState.value.copy(
                    isFormatting = false,
                    errorMessage = "Format error: ${e.message}",
                    formatLog = _uiState.value.formatLog + "✗ Exception: ${e.message}"
                )
            }
            .launchIn(viewModelScope)
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, operationMessage = null)
    }

    fun clearFormatLog() {
        _uiState.value = _uiState.value.copy(formatLog = emptyList())
    }
}
