package com.usbdiskmanager.core.model

/**
 * Represents a connected USB disk device with all relevant metadata.
 */
data class DiskDevice(
    val id: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String?,
    val totalSpace: Long,
    val freeSpace: Long,
    val usedSpace: Long,
    val fileSystem: FileSystemType,
    val mountPoint: String?,
    val isMounted: Boolean,
    val isWritable: Boolean,
    val partitions: List<DiskPartition> = emptyList()
) {
    val usedPercent: Float
        get() = if (totalSpace > 0) (usedSpace.toFloat() / totalSpace.toFloat()) else 0f
}

/**
 * Represents a partition on a disk device.
 */
data class DiskPartition(
    val index: Int,
    val label: String,
    val totalSpace: Long,
    val freeSpace: Long,
    val fileSystem: FileSystemType,
    val mountPoint: String?
)

/**
 * Supported file system types.
 */
enum class FileSystemType(val displayName: String) {
    FAT32("FAT32"),
    EXFAT("exFAT"),
    NTFS("NTFS"),
    EXT4("EXT4"),
    EXT3("EXT3"),
    EXT2("EXT2"),
    F2FS("F2FS"),
    UNKNOWN("Unknown");

    companion object {
        fun fromString(value: String?): FileSystemType {
            return when (value?.uppercase()?.trim()) {
                "FAT32", "FAT" -> FAT32
                "EXFAT" -> EXFAT
                "NTFS" -> NTFS
                "EXT4" -> EXT4
                "EXT3" -> EXT3
                "EXT2" -> EXT2
                "F2FS" -> F2FS
                else -> UNKNOWN
            }
        }
    }
}

/**
 * Result of a disk operation (format, benchmark, etc.)
 */
sealed class DiskOperationResult {
    data class Success(val message: String) : DiskOperationResult()
    data class Error(val message: String, val cause: Throwable? = null) : DiskOperationResult()
    data class Progress(val percent: Int, val message: String) : DiskOperationResult()
}

/**
 * Benchmark results for a disk device.
 */
data class BenchmarkResult(
    val deviceId: String,
    val readSpeedMBps: Double,
    val writeSpeedMBps: Double,
    val testFileSizeMB: Int,
    val durationMs: Long
)
