package com.usbdiskmanager.core.model

/**
 * Represents a file or directory in the USB disk file system.
 */
data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val isHidden: Boolean = false,
    val mimeType: String? = null,
    val isSelected: Boolean = false
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "")

    val displaySize: String
        get() = formatSize(size)

    companion object {
        fun formatSize(bytes: Long): String {
            return when {
                bytes < 1024L -> "$bytes B"
                bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
                bytes < 1024L * 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
                else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }
}

/**
 * Sorting options for file listing.
 */
enum class FileSortOrder {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC
}

/**
 * Clipboard operation type.
 */
enum class ClipboardOperation {
    COPY, CUT
}

/**
 * Clipboard state for copy/paste/move operations.
 */
data class ClipboardState(
    val items: List<FileItem>,
    val operation: ClipboardOperation,
    val sourcePath: String
)
