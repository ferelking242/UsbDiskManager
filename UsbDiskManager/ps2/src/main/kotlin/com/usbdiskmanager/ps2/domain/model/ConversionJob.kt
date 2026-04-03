package com.usbdiskmanager.ps2.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity — persists conversion state so interrupted jobs can be resumed.
 *
 * A job survives app crashes, reboots, and explicit pauses.
 * On next launch the converter reads bytesWritten and resumes from that offset.
 */
@Entity(tableName = "conversion_jobs")
data class ConversionJob(
    @PrimaryKey val isoPath: String,
    val gameId: String,
    val gameTitle: String,
    val outputDir: String,
    val totalBytes: Long,
    val bytesWritten: Long = 0L,
    val currentPart: Int = 0,
    val status: String = "PENDING",   // PENDING | RUNNING | PAUSED | DONE | ERROR
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isPending: Boolean get() = status == "PENDING"
    val isRunning: Boolean get() = status == "RUNNING"
    val isPaused: Boolean get() = status == "PAUSED"
    val isDone: Boolean get() = status == "DONE"
    val isError: Boolean get() = status == "ERROR"
    val isResumable: Boolean get() = isPaused || isError || isRunning
    val progressPercent: Float
        get() = if (totalBytes > 0) bytesWritten.toFloat() / totalBytes else 0f
}
