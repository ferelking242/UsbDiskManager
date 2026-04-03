package com.usbdiskmanager.ps2.ui.transfer

import com.usbdiskmanager.ps2.data.transfer.TransferProgress
import com.usbdiskmanager.ps2.domain.model.UsbGame

enum class TransferMode { USB_TO_INTERNAL, INTERNAL_TO_USB, USB_TO_USB }

data class UsbTransferUiState(
    val isLoading: Boolean = false,
    val usbGames: Map<String, List<UsbGame>> = emptyMap(),
    val internalGames: List<UsbGame> = emptyList(),
    val activeTransfers: Map<String, TransferProgress> = emptyMap(),
    val completedTransfers: Set<String> = emptySet(),
    val error: String? = null
)
