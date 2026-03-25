package com.usbdiskmanager.ps2.domain.model

/**
 * Represents a PS2 game ISO detected on the device.
 */
data class Ps2Game(
    val id: String,             // Unique ID — absolute path of the ISO
    val title: String,          // Display title (filename without extension)
    val gameId: String,         // PS2 serial e.g. SLUS_012.34 (from SYSTEM.CNF)
    val isoPath: String,        // Absolute path to the .iso file
    val sizeMb: Long,           // File size in bytes
    val region: String,         // Detected region (NTSC-U, NTSC-J, PAL...)
    val coverPath: String?,     // Local path to cover art (null = not downloaded)
    val conversionStatus: ConversionStatus = ConversionStatus.NOT_CONVERTED,
    val discType: DiscType = DiscType.DVD
)

enum class ConversionStatus {
    NOT_CONVERTED,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    ERROR
}

enum class DiscType { CD, DVD }

enum class GameRegion(val prefix: String, val label: String) {
    US("SCUS", "NTSC-U"),
    US2("SLUS", "NTSC-U"),
    EU("SCES", "PAL"),
    EU2("SLES", "PAL"),
    JP("SCPS", "NTSC-J"),
    JP2("SLPS", "NTSC-J"),
    JP3("SCAJ", "NTSC-J"),
    UNKNOWN("", "Unknown");

    companion object {
        fun fromGameId(gameId: String): GameRegion =
            entries.firstOrNull { it.prefix.isNotEmpty() && gameId.startsWith(it.prefix) }
                ?: UNKNOWN
    }
}

/**
 * Snapshot of an ongoing or paused conversion.
 */
data class ConversionProgress(
    val gameId: String,
    val isoPath: String,
    val bytesWritten: Long,
    val totalBytes: Long,
    val currentPart: Int,
    val speedMbps: Double = 0.0,
    val remainingSeconds: Long = 0L
) {
    val percent: Float get() = if (totalBytes > 0) bytesWritten.toFloat() / totalBytes else 0f
    val isComplete: Boolean get() = bytesWritten >= totalBytes
}
