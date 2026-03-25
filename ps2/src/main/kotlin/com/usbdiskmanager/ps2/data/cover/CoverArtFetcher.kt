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
 * Downloads PS2 cover art from GameTDB and caches it locally.
 *
 * Cover images are stored as: [artDir]/[GAMEID_COV].png
 * where GAMEID is normalized (no underscores/dots).
 *
 * Sources tried in order:
 *   1. art.gametdb.com/ps2/cover/[region]/[raw_id].jpg
 *   2. art.gametdb.com/ps2/coverM/[region]/[raw_id].jpg  (medium)
 *   3. art.gametdb.com/ps2/coverHQ/[region]/[raw_id].jpg (HQ)
 */
@Singleton
class CoverArtFetcher @Inject constructor() {

    companion object {
        private const val TIMEOUT_MS = 15_000
        private const val USER_AGENT = "UsbDiskMaestro/1.0 (Android)"
        private val COVER_BASES = listOf("cover", "coverM", "coverHQ")
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

            val regionCode = mapRegionCode(region)
            val artDir2 = File(artDir).also { it.mkdirs() }

            for (base in COVER_BASES) {
                val urlStr = "https://art.gametdb.com/ps2/$base/$regionCode/${gameId.replace("_", "-")}.jpg"
                try {
                    val downloaded = download(urlStr, localFile)
                    if (downloaded) {
                        Timber.d("Cover downloaded: $urlStr → ${localFile.absolutePath}")
                        return@withContext localFile.absolutePath
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed cover attempt: $urlStr")
                }
            }
            null
        }

    private fun download(urlStr: String, target: File): Boolean {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connect()

        return if (conn.responseCode == HttpURLConnection.HTTP_OK) {
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
    }

    private fun mapRegionCode(region: String): String = when {
        region.contains("NTSC-U", ignoreCase = true) -> "US"
        region.contains("NTSC-J", ignoreCase = true) -> "JA"
        region.contains("PAL", ignoreCase = true)    -> "EU"
        else -> "US"
    }
}
