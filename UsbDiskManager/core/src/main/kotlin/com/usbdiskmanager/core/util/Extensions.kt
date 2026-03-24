package com.usbdiskmanager.core.util

import com.usbdiskmanager.core.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Execute a shell command and return stdout.
 * Used for low-level disk operations (format, mount, etc.)
 */
suspend fun executeShellCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
    try {
        Timber.d("Shell: $command")
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command))
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        ShellResult(exitCode, stdout.trim(), stderr.trim())
    } catch (e: Exception) {
        Timber.e(e, "Shell command failed: $command")
        ShellResult(-1, "", e.message ?: "Unknown error")
    }
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0
    val output: String get() = if (stdout.isNotEmpty()) stdout else stderr
}

/**
 * Copy bytes from InputStream to OutputStream with progress callbacks.
 * Handles large files (>50 GB) correctly using Long arithmetic.
 */
suspend fun copyWithProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long,
    bufferSize: Int = 4 * 1024 * 1024, // 4MB buffer for performance
    onProgress: suspend (bytesWritten: Long, percent: Int) -> Unit
) = withContext(Dispatchers.IO) {
    val buffer = ByteArray(bufferSize)
    var written = 0L
    var read: Int
    while (input.read(buffer).also { read = it } != -1) {
        output.write(buffer, 0, read)
        written += read
        val percent = if (totalBytes > 0) ((written * 100) / totalBytes).toInt() else 0
        onProgress(written, percent)
    }
    output.flush()
}

/**
 * Recursively delete a directory and all its contents.
 */
suspend fun File.deleteRecursivelyAsync(): Boolean = withContext(Dispatchers.IO) {
    deleteRecursively()
}

/**
 * Convert a File to a FileItem model.
 */
fun File.toFileItem(): FileItem = FileItem(
    name = name,
    path = absolutePath,
    size = if (isFile) length() else 0L,
    lastModified = lastModified(),
    isDirectory = isDirectory,
    isHidden = isHidden
)

/**
 * List files in a directory as FileItem list.
 */
suspend fun File.listFileItems(showHidden: Boolean = false): List<FileItem> =
    withContext(Dispatchers.IO) {
        listFiles()
            ?.filter { showHidden || !it.isHidden }
            ?.map { it.toFileItem() }
            ?.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name })
            ?: emptyList()
    }
