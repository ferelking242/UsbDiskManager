package com.usbdiskmanager.storage.impl

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.usbdiskmanager.core.model.ClipboardOperation
import com.usbdiskmanager.core.model.ClipboardState
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.core.model.FileItem
import com.usbdiskmanager.core.model.FileSortOrder
import com.usbdiskmanager.core.util.copyWithProgress
import com.usbdiskmanager.core.util.listFileItems
import com.usbdiskmanager.core.util.toFileItem
import com.usbdiskmanager.storage.api.FileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : FileRepository {

    private var clipboard: ClipboardState? = null

    override suspend fun listFiles(
        path: String,
        showHidden: Boolean,
        sortOrder: FileSortOrder
    ): List<FileItem> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            Timber.w("Directory not found: $path")
            return@withContext emptyList()
        }
        val files = dir.listFileItems(showHidden)
        files.sortedWith(sortOrder.comparator())
    }

    override suspend fun listFilesFromUri(
        treeUri: Uri,
        relativePath: String
    ): List<FileItem> = withContext(Dispatchers.IO) {
        try {
            val rootDoc = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext emptyList()

            val targetDoc = if (relativePath.isEmpty()) rootDoc
            else {
                var current = rootDoc
                for (segment in relativePath.split("/").filter { it.isNotEmpty() }) {
                    current = current.findFile(segment) ?: return@withContext emptyList()
                }
                current
            }

            targetDoc.listFiles().map { doc ->
                FileItem(
                    name = doc.name ?: "unknown",
                    path = "${treeUri}/${relativePath}/${doc.name}",
                    size = if (doc.isFile) doc.length() else 0L,
                    lastModified = doc.lastModified(),
                    isDirectory = doc.isDirectory,
                    mimeType = doc.type
                )
            }.sortedWith(
                compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name }
            )
        } catch (e: Exception) {
            Timber.e(e, "Error listing files from URI: $treeUri")
            emptyList()
        }
    }

    override fun copyFiles(
        sources: List<FileItem>,
        destinationPath: String
    ): Flow<DiskOperationResult> = flow {
        val destDir = File(destinationPath)
        if (!destDir.exists()) destDir.mkdirs()

        var copied = 0
        val total = sources.size

        for (source in sources) {
            emit(DiskOperationResult.Progress(
                (copied * 100) / total,
                "Copying ${source.name}..."
            ))
            try {
                val result = withContext(Dispatchers.IO) {
                    copyFileOrDirectory(File(source.path), destDir)
                }
                if (!result) {
                    emit(DiskOperationResult.Error("Failed to copy ${source.name}"))
                    return@flow
                }
            } catch (e: Exception) {
                Timber.e(e, "Copy failed for ${source.path}")
                emit(DiskOperationResult.Error("Copy error: ${e.message}"))
                return@flow
            }
            copied++
        }
        emit(DiskOperationResult.Progress(100, "Copy complete"))
        emit(DiskOperationResult.Success("Copied $total item(s) to $destinationPath"))
    }

    override fun moveFiles(
        sources: List<FileItem>,
        destinationPath: String
    ): Flow<DiskOperationResult> = flow {
        val destDir = File(destinationPath)
        if (!destDir.exists()) destDir.mkdirs()

        var moved = 0
        val total = sources.size

        for (source in sources) {
            emit(DiskOperationResult.Progress(
                (moved * 100) / total,
                "Moving ${source.name}..."
            ))
            try {
                withContext(Dispatchers.IO) {
                    val src = File(source.path)
                    val dest = File(destDir, src.name)
                    // Try atomic rename first (same filesystem)
                    if (!src.renameTo(dest)) {
                        // Fallback: copy then delete
                        copyFileOrDirectory(src, destDir)
                        src.deleteRecursively()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Move failed for ${source.path}")
                emit(DiskOperationResult.Error("Move error: ${e.message}"))
                return@flow
            }
            moved++
        }
        emit(DiskOperationResult.Progress(100, "Move complete"))
        emit(DiskOperationResult.Success("Moved $total item(s) to $destinationPath"))
    }

    override suspend fun deleteFiles(items: List<FileItem>): DiskOperationResult =
        withContext(Dispatchers.IO) {
            var deletedCount = 0
            for (item in items) {
                try {
                    val file = File(item.path)
                    if (file.deleteRecursively()) {
                        deletedCount++
                    } else {
                        return@withContext DiskOperationResult.Error(
                            "Failed to delete ${item.name}"
                        )
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Delete failed for ${item.path}")
                    return@withContext DiskOperationResult.Error(
                        "Delete error: ${e.message}"
                    )
                }
            }
            DiskOperationResult.Success("Deleted $deletedCount item(s)")
        }

    override suspend fun createDirectory(parentPath: String, name: String): DiskOperationResult =
        withContext(Dispatchers.IO) {
            val dir = File(parentPath, name)
            if (dir.exists()) {
                DiskOperationResult.Error("Directory '$name' already exists")
            } else if (dir.mkdirs()) {
                DiskOperationResult.Success("Directory '$name' created")
            } else {
                DiskOperationResult.Error("Failed to create directory '$name'")
            }
        }

    override suspend fun rename(item: FileItem, newName: String): DiskOperationResult =
        withContext(Dispatchers.IO) {
            val file = File(item.path)
            val newFile = File(file.parent, newName)
            if (newFile.exists()) {
                DiskOperationResult.Error("'$newName' already exists")
            } else if (file.renameTo(newFile)) {
                DiskOperationResult.Success("Renamed to '$newName'")
            } else {
                DiskOperationResult.Error("Failed to rename")
            }
        }

    override fun getClipboard(): ClipboardState? = clipboard

    override fun setClipboard(
        items: List<FileItem>,
        operation: ClipboardOperation,
        sourcePath: String
    ) {
        clipboard = ClipboardState(items, operation, sourcePath)
    }

    override fun clearClipboard() { clipboard = null }

    override fun pasteClipboard(destinationPath: String): Flow<DiskOperationResult> = flow {
        val cb = clipboard ?: run {
            emit(DiskOperationResult.Error("Clipboard is empty"))
            return@flow
        }

        when (cb.operation) {
            ClipboardOperation.COPY -> {
                copyFiles(cb.items, destinationPath).collect { emit(it) }
            }
            ClipboardOperation.CUT -> {
                moveFiles(cb.items, destinationPath).collect { emit(it) }
                clipboard = null // Clear after cut-paste
            }
        }
    }

    override suspend fun searchFiles(rootPath: String, query: String): List<FileItem> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<FileItem>()
            File(rootPath).walkTopDown()
                .filter { it.name.contains(query, ignoreCase = true) }
                .take(500) // Limit results to avoid memory issues
                .forEach { results.add(it.toFileItem()) }
            results
        }

    override suspend fun getFileDetails(item: FileItem): FileItem =
        withContext(Dispatchers.IO) {
            val file = File(item.path)
            val totalSize = if (file.isDirectory) {
                file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            } else {
                file.length()
            }
            item.copy(size = totalSize)
        }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun copyFileOrDirectory(source: File, destDir: File): Boolean {
        return if (source.isDirectory) {
            val newDir = File(destDir, source.name)
            newDir.mkdirs()
            source.listFiles()?.all { copyFileOrDirectory(it, newDir) } ?: true
        } else {
            val dest = File(destDir, source.name)
            source.copyTo(dest, overwrite = true)
            true
        }
    }

    private fun FileSortOrder.comparator(): Comparator<FileItem> = when (this) {
        FileSortOrder.NAME_ASC -> compareByDescending<FileItem> { it.isDirectory }
            .thenBy { it.name.lowercase() }
        FileSortOrder.NAME_DESC -> compareByDescending<FileItem> { it.isDirectory }
            .thenByDescending { it.name.lowercase() }
        FileSortOrder.SIZE_ASC -> compareByDescending<FileItem> { it.isDirectory }
            .thenBy { it.size }
        FileSortOrder.SIZE_DESC -> compareByDescending<FileItem> { it.isDirectory }
            .thenByDescending { it.size }
        FileSortOrder.DATE_ASC -> compareByDescending<FileItem> { it.isDirectory }
            .thenBy { it.lastModified }
        FileSortOrder.DATE_DESC -> compareByDescending<FileItem> { it.isDirectory }
            .thenByDescending { it.lastModified }
    }
}
