package com.usbdiskmanager.storage.api

import android.net.Uri
import com.usbdiskmanager.core.model.ClipboardOperation
import com.usbdiskmanager.core.model.ClipboardState
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.core.model.FileItem
import com.usbdiskmanager.core.model.FileSortOrder
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for file system operations on USB disks.
 */
interface FileRepository {

    /**
     * List files in a directory.
     */
    suspend fun listFiles(
        path: String,
        showHidden: Boolean = false,
        sortOrder: FileSortOrder = FileSortOrder.NAME_ASC
    ): List<FileItem>

    /**
     * List files using SAF (Storage Access Framework) URI.
     */
    suspend fun listFilesFromUri(
        treeUri: Uri,
        relativePath: String = ""
    ): List<FileItem>

    /**
     * Copy files to a destination path.
     * Emits progress updates.
     */
    fun copyFiles(
        sources: List<FileItem>,
        destinationPath: String
    ): Flow<DiskOperationResult>

    /**
     * Move files to a destination path.
     * Emits progress updates.
     */
    fun moveFiles(
        sources: List<FileItem>,
        destinationPath: String
    ): Flow<DiskOperationResult>

    /**
     * Delete files and/or directories.
     */
    suspend fun deleteFiles(items: List<FileItem>): DiskOperationResult

    /**
     * Create a new directory.
     */
    suspend fun createDirectory(parentPath: String, name: String): DiskOperationResult

    /**
     * Rename a file or directory.
     */
    suspend fun rename(item: FileItem, newName: String): DiskOperationResult

    /**
     * Get the clipboard state.
     */
    fun getClipboard(): ClipboardState?

    /**
     * Set clipboard with given items.
     */
    fun setClipboard(items: List<FileItem>, operation: ClipboardOperation, sourcePath: String)

    /**
     * Clear the clipboard.
     */
    fun clearClipboard()

    /**
     * Paste clipboard contents to destination.
     */
    fun pasteClipboard(destinationPath: String): Flow<DiskOperationResult>

    /**
     * Search files by name in the given path.
     */
    suspend fun searchFiles(rootPath: String, query: String): List<FileItem>

    /**
     * Get file details (size including subdirectories).
     */
    suspend fun getFileDetails(item: FileItem): FileItem
}
