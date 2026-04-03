package com.usbdiskmanager.ps2.data.transfer

import com.usbdiskmanager.ps2.data.converter.UlCfgManager
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import com.usbdiskmanager.ps2.domain.model.UsbGame
import com.usbdiskmanager.ps2.util.FilesystemChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class TransferProgress(
    val gameName: String,
    val currentPart: Int,
    val totalParts: Int,
    val bytesTotal: Long,
    val bytesTransferred: Long,
    val isDone: Boolean = false,
    val error: String? = null
) {
    val fraction: Float get() = if (bytesTotal == 0L) 0f else (bytesTransferred.toFloat() / bytesTotal.toFloat()).coerceIn(0f, 1f)
}

@Singleton
class UsbGameTransferManager @Inject constructor(
    private val cfgManager: UlCfgManager,
    private val fsChecker: FilesystemChecker
) {

    /**
     * List all PS2 UL games found at [mountPoint].
     * Reads ul.cfg from the root and cross-references the actual UL part files.
     */
    suspend fun listGamesOnMount(mountPoint: String): List<UsbGame> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val cfgFile = File(mountPoint, "ul.cfg")
            val entries = if (cfgFile.exists()) {
                try { cfgManager.readAllEntries(cfgFile) } catch (e: Exception) {
                    Timber.w(e, "Could not read ul.cfg at $mountPoint"); emptyList()
                }
            } else emptyList()

            val rootDir = File(mountPoint)
            entries.mapNotNull { entry ->
                val parts = rootDir.listFiles { f ->
                    f.name.startsWith("ul.${entry.gameIdClean}.")
                } ?: emptyArray()
                if (parts.isEmpty() && entry.numParts > 0) {
                    Timber.w("No UL parts found for ${entry.gameIdClean} at $mountPoint")
                    return@mapNotNull null
                }
                UsbGame(
                    gameName = entry.gameName.ifBlank { entry.gameIdClean },
                    gameId = entry.gameIdClean,
                    numParts = entry.numParts,
                    isCd = entry.isCd,
                    mountPoint = mountPoint,
                    partFiles = parts.map { it.absolutePath }.sorted(),
                    sizeBytes = parts.sumOf { it.length() }
                )
            }
        }

    /**
     * Copy a game FROM [fromMount] TO the internal UL directory.
     * Emits progress updates as parts are copied.
     */
    fun copyToInternal(game: UsbGame): Flow<TransferProgress> = transferGame(
        game = game,
        fromMount = game.mountPoint,
        toDir = File(IsoScanner.DEFAULT_UL_DIR),
        toCfgFile = File(IsoScanner.DEFAULT_UL_DIR, "ul.cfg")
    )

    /**
     * Copy a game FROM internal UL directory TO [toMount].
     */
    fun copyFromInternalToUsb(game: UsbGame, toMount: String): Flow<TransferProgress> {
        val internalDir = File(IsoScanner.DEFAULT_UL_DIR)
        val fullParts = internalDir.listFiles { f ->
            f.name.startsWith("ul.${game.gameId}.")
        }?.map { it.absolutePath }?.sorted() ?: emptyList()

        val resolvedGame = game.copy(mountPoint = internalDir.absolutePath, partFiles = fullParts)
        return transferGame(
            game = resolvedGame,
            fromMount = internalDir.absolutePath,
            toDir = File(toMount),
            toCfgFile = File(toMount, "ul.cfg")
        )
    }

    /**
     * Direct USB→USB transfer (from one mount to another).
     */
    fun directUsbToUsb(game: UsbGame, toMount: String): Flow<TransferProgress> = transferGame(
        game = game,
        fromMount = game.mountPoint,
        toDir = File(toMount),
        toCfgFile = File(toMount, "ul.cfg")
    )

    private fun transferGame(
        game: UsbGame,
        fromMount: String,
        toDir: File,
        toCfgFile: File
    ): Flow<TransferProgress> = flow {
        toDir.mkdirs()

        val parts = game.partFiles.ifEmpty {
            File(fromMount).listFiles { f ->
                f.name.startsWith("ul.${game.gameId}.")
            }?.map { it.absolutePath }?.sorted() ?: emptyList()
        }

        if (parts.isEmpty()) {
            emit(TransferProgress(game.gameName, 0, 0, 0, 0, error = "Aucun fichier UL trouvé pour ${game.gameId}"))
            return@flow
        }

        val totalBytes = parts.sumOf { File(it).length() }
        var transferred = 0L

        parts.forEachIndexed { index, srcPath ->
            val srcFile = File(srcPath)
            val destFile = File(toDir, srcFile.name)

            if (destFile.exists() && destFile.length() == srcFile.length()) {
                transferred += srcFile.length()
                emit(TransferProgress(game.gameName, index + 1, parts.size, totalBytes, transferred))
                return@forEachIndexed
            }

            srcFile.inputStream().buffered(1024 * 256).use { input ->
                destFile.outputStream().buffered(1024 * 256).use { output ->
                    val buf = ByteArray(1024 * 256)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        transferred += n
                        emit(TransferProgress(game.gameName, index + 1, parts.size, totalBytes, transferred))
                    }
                }
            }
        }

        // Merge into destination ul.cfg (does not overwrite existing entries)
        try {
            cfgManager.addOrUpdateEntry(
                outputDir = toDir.absolutePath,
                gameId = game.gameId,
                gameName = game.gameName,
                numParts = parts.size,
                isCD = game.isCd
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to update ul.cfg at ${toDir.absolutePath}")
        }

        emit(TransferProgress(game.gameName, parts.size, parts.size, totalBytes, totalBytes, isDone = true))
    }.flowOn(Dispatchers.IO)
}
