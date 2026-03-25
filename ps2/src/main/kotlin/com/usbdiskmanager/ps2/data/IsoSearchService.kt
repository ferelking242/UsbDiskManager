package com.usbdiskmanager.ps2.data

import com.usbdiskmanager.ps2.domain.model.IsoSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Searches Internet Archive (archive.org) for PS2 ISO files.
 *
 * Archive.org is a legitimate digital preservation library with a public API.
 * Collections used:
 *   - softwarelibrary_ps2  (official preservation collection)
 *   - Additionally full-text search with mediatype:software
 *
 * Search  → https://archive.org/advancedsearch.php
 * Files   → https://archive.org/metadata/{identifier}
 * Download → https://archive.org/download/{identifier}/{filename}
 */
@Singleton
class IsoSearchService @Inject constructor() {

    companion object {
        private const val TIMEOUT_MS = 20_000
        private const val USER_AGENT = "UsbDiskManager/1.1 (Android; archive.org client)"
        private const val SEARCH_URL = "https://archive.org/advancedsearch.php"
        private const val METADATA_URL = "https://archive.org/metadata"
        private const val DOWNLOAD_BASE = "https://archive.org/download"
        private const val MAX_RESULTS = 40
    }

    /**
     * Search archive.org for PS2 ISO files matching [query].
     * Returns a list of results sorted by relevance/downloads.
     */
    suspend fun search(query: String): List<IsoSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        try {
            val encodedQuery = URLEncoder.encode(
                "($query) AND (collection:softwarelibrary_ps2 OR collection:No-Intro) AND mediatype:software",
                "UTF-8"
            )
            val url = buildString {
                append(SEARCH_URL)
                append("?q=$encodedQuery")
                append("&fl[]=identifier,title,description,subject,downloads")
                append("&rows=$MAX_RESULTS")
                append("&output=json")
                append("&sort[]=downloads+desc")
            }

            val json = fetchJson(url) ?: return@withContext emptyList()
            val docs = json.getJSONObject("response").getJSONArray("docs")
            val results = mutableListOf<IsoSearchResult>()

            for (i in 0 until docs.length()) {
                val doc = docs.getJSONObject(i)
                val identifier = doc.optString("identifier", "")
                val title = doc.optString("title", identifier)
                val description = doc.optString("description", "")
                val subject = doc.optString("subject", "")
                val region = parseRegionFromSubject(subject)

                if (identifier.isNotBlank()) {
                    results.add(
                        IsoSearchResult(
                            identifier = identifier,
                            title = title.trimTitle(),
                            description = description.take(200),
                            region = region
                        )
                    )
                }
            }
            results
        } catch (e: Exception) {
            Timber.e(e, "ISO search failed for query: $query")
            emptyList()
        }
    }

    /**
     * Fetch detailed metadata for an identifier to find the downloadable ISO file.
     * Tries to find .iso first, then .7z/.zip if not found.
     */
    suspend fun resolveDownloadUrl(identifier: String): IsoSearchResult? = withContext(Dispatchers.IO) {
        try {
            val metaJson = fetchJson("$METADATA_URL/$identifier") ?: return@withContext null
            val files = metaJson.getJSONArray("files")
            val title = metaJson.optJSONObject("metadata")?.optString("title", identifier) ?: identifier

            // Priority: .iso > .7z > .zip (avoid .txt, .cue, .png etc.)
            var bestFile: JSONObject? = null
            var bestPriority = Int.MAX_VALUE

            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val name = file.optString("name", "")
                val priority = when {
                    name.endsWith(".iso", ignoreCase = true) -> 0
                    name.endsWith(".7z", ignoreCase = true)  -> 1
                    name.endsWith(".zip", ignoreCase = true) -> 2
                    else -> Int.MAX_VALUE
                }
                if (priority < bestPriority) {
                    bestPriority = priority
                    bestFile = file
                }
            }

            if (bestFile == null) return@withContext null

            val fileName = bestFile.optString("name", "")
            val sizeBytes = bestFile.optString("size", "0").toLongOrNull() ?: 0L
            val downloadUrl = "$DOWNLOAD_BASE/$identifier/$fileName"
            val region = guessRegionFromTitle(title)

            IsoSearchResult(
                identifier = identifier,
                title = title.trimTitle(),
                region = region,
                fileSize = sizeBytes,
                fileName = fileName,
                downloadUrl = downloadUrl,
                coverUrl = buildCoverUrl(identifier, title)
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve download URL for $identifier")
            null
        }
    }

    private fun fetchJson(urlStr: String): JSONObject? {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        return try {
            conn.connect()
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } else null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRegionFromSubject(subject: String): String = when {
        subject.contains("NTSC-U", ignoreCase = true) || subject.contains("USA", ignoreCase = true) -> "NTSC-U"
        subject.contains("NTSC-J", ignoreCase = true) || subject.contains("Japan", ignoreCase = true) -> "NTSC-J"
        subject.contains("PAL", ignoreCase = true)    -> "PAL"
        else -> ""
    }

    private fun guessRegionFromTitle(title: String): String = when {
        title.contains("(USA)", ignoreCase = true)   -> "NTSC-U"
        title.contains("(Japan)", ignoreCase = true) -> "NTSC-J"
        title.contains("(Europe)", ignoreCase = true)-> "PAL"
        title.contains("(Fr)", ignoreCase = true)    -> "PAL"
        else -> ""
    }

    private fun buildCoverUrl(identifier: String, title: String): String {
        return "https://archive.org/services/img/$identifier"
    }

    private fun String.trimTitle(): String =
        this.replace(Regex("\\s*\\([^)]+\\)\\s*"), " ").trim()
            .replace(Regex("\\s{2,}"), " ")
}
