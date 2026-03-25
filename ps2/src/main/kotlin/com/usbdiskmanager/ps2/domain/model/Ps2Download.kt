package com.usbdiskmanager.ps2.domain.model

import java.util.UUID

enum class DownloadStatus {
    QUEUED, DOWNLOADING, PAUSED, COMPLETED, ERROR, CANCELLED
}

data class Ps2Download(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val outputPath: String,
    val totalBytes: Long = -1L,
    val downloadedBytes: Long = 0L,
    val speedBps: Double = 0.0,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f

    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED
}
