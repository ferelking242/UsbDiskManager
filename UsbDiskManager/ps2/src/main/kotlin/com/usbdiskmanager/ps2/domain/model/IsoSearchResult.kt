package com.usbdiskmanager.ps2.domain.model

data class IsoSearchResult(
    val identifier: String,
    val title: String,
    val description: String = "",
    val region: String = "",
    val fileSize: Long = 0L,
    val fileName: String = "",
    val downloadUrl: String = "",
    val coverUrl: String = ""
)

data class UsbGame(
    val gameName: String,
    val gameId: String,
    val numParts: Int,
    val isCd: Boolean,
    val mountPoint: String,
    val partFiles: List<String> = emptyList(),
    val sizeBytes: Long = 0L
)
