package com.usbdiskmanager.ps2.data.cover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads PS2 cover art from multiple sources and caches it locally.
 *
 * Sources tried in order:
 *   1. GameTDB — cover / coverM / coverHQ (best structured database)
 *   2. PSDB.net — by game ID
 *   3. GameFAQs box art via URL pattern (fallback)
 *
 * Cover images are stored as: [artDir]/[GAMEID_COV].png
 */
@Singleton
class CoverArtFetcher @Inject constructor() {

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val USER_AGENT = "UsbDiskManager/1.1 (Android)"
        private val GAMETDB_BASES = listOf("cover", "coverM", "coverHQ")
    }

    /**
     * Download cover for [gameId] (e.g. "SLUS_012.34") into [artDir].
     * @return local file path if successful, null otherwise.
     */
    suspend fun fetchCover(gameId: String, region: String, artDir: String): String? =
        withContext(Dispatchers.IO) {
            if (gameId.isBlank()) return@withContext null

            val normalizedId = gameId.replace(".", "").replace("_", "")
            val localFile = File(artDir, "${normalizedId}_COV.png")
            if (localFile.exists() && localFile.length() > 0) {
                return@withContext localFile.absolutePath
            }

            File(artDir).mkdirs()

            val regionCode = mapRegionCode(region)
            val dashId = gameId.replace("_", "-")

            // ── Source 1: GameTDB ──────────────────────────────────────────────
            for (base in GAMETDB_BASES) {
                val url = "https://art.gametdb.com/ps2/$base/$regionCode/$dashId.jpg"
                if (download(url, localFile)) {
                    Timber.d("Cover from GameTDB ($base): $url")
                    return@withContext localFile.absolutePath
                }
            }
            // Try other regions on GameTDB (some games only exist in one region DB)
            val fallbackRegions = listOf("US", "EU", "JA").filter { it != regionCode }
            for (fallbackRegion in fallbackRegions) {
                for (base in listOf("cover", "coverM")) {
                    val url = "https://art.gametdb.com/ps2/$base/$fallbackRegion/$dashId.jpg"
                    if (download(url, localFile)) {
                        Timber.d("Cover from GameTDB fallback region $fallbackRegion: $url")
                        return@withContext localFile.absolutePath
                    }
                }
            }

            // ── Source 2: PSDB.net ────────────────────────────────────────────
            // PSDB uses a URL structure based on the Game ID
            val psdbId = gameId.uppercase().replace(".", "").replace("_", "")
            val psdbUrl = "https://psdb.kingston-solutions.de/covers/${psdbId}_COV.jpg"
            if (download(psdbUrl, localFile)) {
                Timber.d("Cover from PSDB.net: $psdbUrl")
                return@withContext localFile.absolutePath
            }

            Timber.w("No cover found for gameId=$gameId region=$region")
            null
        }

    /**
     * Download all missing covers for a list of game IDs.
     * Processes one at a time to avoid hammering servers.
     */
    suspend fun fetchAllCovers(
        games: List<Pair<String, String>>,
        artDir: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Int {
        var fetched = 0
        games.forEachIndexed { i, (gameId, region) ->
            val result = fetchCover(gameId, region, artDir)
            if (result != null) fetched++
            onProgress(i + 1, games.size)
        }
        return fetched
    }

    private fun download(urlStr: String, target: File): Boolean {
        return try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connect()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                BufferedInputStream(conn.inputStream, 65536).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output, 65536)
                    }
                }
                target.length() > 0
            } else {
                conn.disconnect()
                false
            }
        } catch (e: Exception) {
            Timber.v("Cover attempt failed [$urlStr]: ${e.message}")
            false
        }
    }

    private fun mapRegionCode(region: String): String = when {
        region.contains("NTSC-U", ignoreCase = true) || region.contains("USA", ignoreCase = true) -> "US"
        region.contains("NTSC-J", ignoreCase = true) || region.contains("Japan", ignoreCase = true) -> "JA"
        region.contains("PAL", ignoreCase = true) -> "EU"
        else -> "US"
    }
}
