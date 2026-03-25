package com.usbdiskmanager.ps2.data.scanner

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.usbdiskmanager.ps2.domain.model.ConversionStatus
import com.usbdiskmanager.ps2.domain.model.Ps2Game
import com.usbdiskmanager.ps2.engine.IsoEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IsoScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: IsoEngine
) {

    companion object {
        val DEFAULT_ISO_DIR: String
            get() = "${Environment.getExternalStorageDirectory()}/PS2Manager/ISO"

        val DEFAULT_UL_DIR: String
            get() = "${Environment.getExternalStorageDirectory()}/PS2Manager/UL"

        val DEFAULT_ART_DIR: String
            get() = "${Environment.getExternalStorageDirectory()}/PS2Manager/ART"
    }

    /**
     * Scan a list of directory paths for .iso files.
     * Game metadata is extracted via [IsoEngine] — no direct parser coupling.
     */
    suspend fun scanDirectories(paths: List<String>): List<Ps2Game> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<Ps2Game>()
            val seen = mutableSetOf<String>()

            for (path in paths) {
                val dir = File(path)
                if (!dir.exists() || !dir.isDirectory) continue
                dir.walkTopDown()
                    .maxDepth(4)
                    .filter { it.isFile && it.extension.lowercase() == "iso" }
                    .forEach { isoFile ->
                        if (seen.add(isoFile.absolutePath)) {
                            parseIso(isoFile)?.let { results.add(it) }
                        }
                    }
            }
            results.sortedBy { it.title }
        }

    /**
     * Scan a SAF-provided URI by resolving it to a real path.
     */
    suspend fun scanUri(uri: Uri): List<Ps2Game> =
        withContext(Dispatchers.IO) {
            val path = resolveUriToPath(uri) ?: return@withContext emptyList()
            scanDirectories(listOf(path))
        }

    /**
     * Create the default PS2Manager folder structure if it doesn't exist.
     */
    suspend fun ensureStructure(base: String) = withContext(Dispatchers.IO) {
        listOf("$base/ISO", "$base/UL", "$base/ART").forEach { path ->
            val dir = File(path)
            if (!dir.exists()) {
                Timber.d("Created dir $path: ${dir.mkdirs()}")
            }
        }
    }

    // ────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────

    private suspend fun parseIso(file: File): Ps2Game? = try {
        val info = engine.getIsoInfo(file.absolutePath)
        val coverPath = coverArtPath(info.gameId, DEFAULT_ART_DIR)
        Ps2Game(
            id = file.absolutePath,
            title = file.nameWithoutExtension,
            gameId = info.gameId,
            isoPath = file.absolutePath,
            sizeMb = file.length(),
            region = info.region,
            coverPath = coverPath,
            discType = info.discType,
            conversionStatus = ConversionStatus.NOT_CONVERTED
        )
    } catch (e: Exception) {
        Timber.w(e, "Could not parse ISO: ${file.name}")
        null
    }

    private fun coverArtPath(gameId: String, artDir: String): String? {
        if (gameId.isEmpty()) return null
        val normalized = gameId.replace(".", "").replace("_", "")
        val file = File("$artDir/${normalized}_COV.png")
        return if (file.exists()) file.absolutePath else null
    }

    private fun resolveUriToPath(uri: Uri): String? {
        val path = uri.path ?: return null
        val primary = path.substringAfter("primary:", "")
        if (primary.isNotEmpty()) {
            return "${Environment.getExternalStorageDirectory()}/$primary"
        }
        return path
    }
}
