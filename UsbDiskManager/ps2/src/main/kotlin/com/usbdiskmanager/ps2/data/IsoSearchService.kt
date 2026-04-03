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

@Singleton
class IsoSearchService @Inject constructor() {

    companion object {
        private const val TIMEOUT_MS = 25_000
        private const val USER_AGENT = "Mozilla/5.0 (Android; UsbDiskManager/1.1)"
        private const val SEARCH_URL = "https://archive.org/advancedsearch.php"
        private const val METADATA_URL = "https://archive.org/metadata"
        private const val DOWNLOAD_BASE = "https://archive.org/download"
        private const val MAX_RESULTS = 40
    }

    suspend fun search(query: String): List<IsoSearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // Try multiple strategies in order
        val results = searchStrategy1(query)
        if (results.isNotEmpty()) return@withContext results

        val results2 = searchStrategy2(query)
        if (results2.isNotEmpty()) return@withContext results2

        return@withContext searchStrategy3(query)
    }

    // Strategy 1: PS2 software library collection
    private fun searchStrategy1(query: String): List<IsoSearchResult> {
        return try {
            val q = URLEncoder.encode(
                "($query) AND mediatype:software AND (subject:ps2 OR subject:playstation2 OR collection:softwarelibrary_ps2)",
                "UTF-8"
            )
            val url = "$SEARCH_URL?q=$q" +
                "&fl[]=identifier&fl[]=title&fl[]=description&fl[]=subject&fl[]=downloads" +
                "&rows=$MAX_RESULTS&output=json&sort[]=downloads+desc"
            parseSearchResponse(fetchJson(url))
        } catch (e: Exception) {
            Timber.w(e, "Search strategy 1 failed for: $query")
            emptyList()
        }
    }

    // Strategy 2: Broader software search with PS2 in title
    private fun searchStrategy2(query: String): List<IsoSearchResult> {
        return try {
            val q = URLEncoder.encode(
                "($query) AND mediatype:software AND (title:ps2 OR description:playstation2 OR subject:iso)",
                "UTF-8"
            )
            val url = "$SEARCH_URL?q=$q" +
                "&fl[]=identifier&fl[]=title&fl[]=description&fl[]=subject&fl[]=downloads" +
                "&rows=$MAX_RESULTS&output=json&sort[]=downloads+desc"
            parseSearchResponse(fetchJson(url))
        } catch (e: Exception) {
            Timber.w(e, "Search strategy 2 failed for: $query")
            emptyList()
        }
    }

    // Strategy 3: Fallback - just search by title in all software
    private fun searchStrategy3(query: String): List<IsoSearchResult> {
        return try {
            val q = URLEncoder.encode(
                "title:($query) AND mediatype:software",
                "UTF-8"
            )
            val url = "$SEARCH_URL?q=$q" +
                "&fl[]=identifier&fl[]=title&fl[]=description&fl[]=subject&fl[]=downloads" +
                "&rows=$MAX_RESULTS&output=json&sort[]=downloads+desc"
            parseSearchResponse(fetchJson(url))
        } catch (e: Exception) {
            Timber.w(e, "Search strategy 3 failed for: $query")
            emptyList()
        }
    }

    private fun parseSearchResponse(json: JSONObject?): List<IsoSearchResult> {
        if (json == null) return emptyList()
        val docs = json.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        val results = mutableListOf<IsoSearchResult>()

        for (i in 0 until docs.length()) {
            val doc = docs.getJSONObject(i)
            val identifier = doc.optString("identifier", "")
            if (identifier.isBlank()) continue
            val title = doc.optString("title", identifier)
            val description = doc.optString("description", "")
            val subject = doc.optString("subject", "")
            val region = parseRegionFromSubject(subject + " " + title)

            results.add(
                IsoSearchResult(
                    identifier = identifier,
                    title = title.trimTitle(),
                    description = description.take(200),
                    region = region,
                    coverUrl = "https://archive.org/services/img/$identifier"
                )
            )
        }
        return results
    }

    suspend fun resolveDownloadUrl(identifier: String): IsoSearchResult? = withContext(Dispatchers.IO) {
        try {
            val metaJson = fetchJson("$METADATA_URL/$identifier") ?: return@withContext null
            val files = metaJson.optJSONArray("files") ?: return@withContext null
            val title = metaJson.optJSONObject("metadata")?.optString("title", identifier) ?: identifier

            var bestFile: JSONObject? = null
            var bestPriority = Int.MAX_VALUE

            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val name = file.optString("name", "")
                val priority = when {
                    name.endsWith(".iso", ignoreCase = true) -> 0
                    name.endsWith(".7z", ignoreCase = true) -> 1
                    name.endsWith(".zip", ignoreCase = true) -> 2
                    name.endsWith(".bin", ignoreCase = true) -> 3
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
            val downloadUrl = "$DOWNLOAD_BASE/$identifier/${URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")}"
            val region = guessRegionFromTitle(title)

            IsoSearchResult(
                identifier = identifier,
                title = title.trimTitle(),
                region = region,
                fileSize = sizeBytes,
                fileName = fileName,
                downloadUrl = downloadUrl,
                coverUrl = "https://archive.org/services/img/$identifier"
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to resolve download URL for $identifier")
            null
        }
    }

    private fun fetchJson(urlStr: String): JSONObject? {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.instanceFollowRedirects = true
            try {
                conn.connect()
                val code = conn.responseCode
                if (code in 200..299) {
                    JSONObject(conn.inputStream.bufferedReader().readText())
                } else {
                    Timber.w("HTTP $code from $urlStr")
                    null
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Timber.e(e, "fetchJson failed: $urlStr")
            null
        }
    }

    private fun parseRegionFromSubject(text: String): String = when {
        text.contains("NTSC-U", ignoreCase = true) || text.contains("USA", ignoreCase = true) -> "NTSC-U"
        text.contains("NTSC-J", ignoreCase = true) || text.contains("Japan", ignoreCase = true) -> "NTSC-J"
        text.contains("PAL", ignoreCase = true) || text.contains("Europe", ignoreCase = true) -> "PAL"
        else -> ""
    }

    private fun guessRegionFromTitle(title: String): String = when {
        title.contains("(USA)", ignoreCase = true) -> "NTSC-U"
        title.contains("(US)", ignoreCase = true) -> "NTSC-U"
        title.contains("(Japan)", ignoreCase = true) -> "NTSC-J"
        title.contains("(Europe)", ignoreCase = true) -> "PAL"
        title.contains("(Fr)", ignoreCase = true) -> "PAL"
        title.contains("(De)", ignoreCase = true) -> "PAL"
        else -> ""
    }

    private fun String.trimTitle(): String =
        this.replace(Regex("\\s{2,}"), " ").trim()
}
