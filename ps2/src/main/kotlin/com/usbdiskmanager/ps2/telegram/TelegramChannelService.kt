package com.usbdiskmanager.ps2.telegram

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

// ─── Domain model ─────────────────────────────────────────────────────────────

data class TelegramChannelConfig(
    val username: String,
    val displayName: String,
    val id: Long = 0L,
    val accessHash: Long = 0L
)

data class TelegramGamePost(
    val messageId: Int,
    val channelUsername: String,
    val title: String,
    val description: String,
    val region: String,
    val gameId: String,
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String,
    val documentId: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val dcId: Int,
    val coverPhotoId: Long,
    val date: Int
) {
    val isIso get() = mimeType.contains("iso", ignoreCase = true) ||
        fileName.endsWith(".iso", ignoreCase = true) ||
        fileName.endsWith(".bin", ignoreCase = true) ||
        fileName.endsWith(".7z", ignoreCase = true) ||
        fileName.endsWith(".zip", ignoreCase = true)
    val sizeFormatted get() = when {
        fileSizeBytes > 1_000_000_000L -> "%.1f GB".format(fileSizeBytes / 1e9)
        fileSizeBytes > 1_000_000L     -> "%.0f MB".format(fileSizeBytes / 1e6)
        fileSizeBytes > 0L             -> "%.0f KB".format(fileSizeBytes / 1e3)
        else                           -> "?"
    }
    override fun equals(other: Any?) = other is TelegramGamePost && messageId == other.messageId && channelUsername == other.channelUsername
    override fun hashCode() = 31 * messageId + channelUsername.hashCode()
}

data class TelegramDownloadProgress(
    val bytesWritten: Long,
    val totalBytes: Long,
    val isDone: Boolean = false,
    val error: String? = null
) {
    val fraction get() = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

sealed class TelegramSetupState {
    object NotConfigured : TelegramSetupState()
    data class Ready(val session: TelegramSession, val apiHash: String) : TelegramSetupState()
    data class Error(val message: String) : TelegramSetupState()
}

// ─── Prefs keys ───────────────────────────────────────────────────────────────
private const val PREFS_NAME   = "telegram_prefs"
private const val KEY_SESSION  = "session_string"
private const val KEY_API_HASH = "api_hash"
private const val KEY_CHANNELS = "channels"

private val DEFAULT_CHANNELS = listOf(
    TelegramChannelConfig("pcsx2iso",    "Playstation 2 Roms"),
    TelegramChannelConfig("ps2isodl",    "PS2 ISO Downloads"),
    TelegramChannelConfig("PSXGames",    "PSX / PS2 Games")
)

// ─── Service ──────────────────────────────────────────────────────────────────

@Singleton
class TelegramChannelService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ── Setup / persistence ───────────────────────────────────────────────

    fun getSetupState(): TelegramSetupState {
        val raw = prefs.getString(KEY_SESSION, null) ?: return TelegramSetupState.NotConfigured
        val apiHash = prefs.getString(KEY_API_HASH, null) ?: return TelegramSetupState.NotConfigured
        return try {
            val session = TelegramSessionString.parse(raw)
            TelegramSetupState.Ready(session, apiHash)
        } catch (e: Exception) {
            TelegramSetupState.Error(e.message ?: "Session invalide")
        }
    }

    fun saveSetup(sessionString: String, apiHash: String): Result<TelegramSession> {
        return try {
            val session = TelegramSessionString.parse(sessionString)
            prefs.edit {
                putString(KEY_SESSION, sessionString.trim())
                putString(KEY_API_HASH, apiHash.trim())
            }
            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clearSetup() {
        prefs.edit { remove(KEY_SESSION); remove(KEY_API_HASH) }
    }

    // ── Channel management ────────────────────────────────────────────────

    fun getSavedChannels(): List<TelegramChannelConfig> {
        val raw = prefs.getString(KEY_CHANNELS, null)
        if (raw == null) return DEFAULT_CHANNELS
        return raw.split("|").mapNotNull { entry ->
            val parts = entry.split(",")
            if (parts.size >= 2) TelegramChannelConfig(parts[0], parts[1],
                parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                parts.getOrNull(3)?.toLongOrNull() ?: 0L)
            else null
        }.ifEmpty { DEFAULT_CHANNELS }
    }

    fun saveChannels(channels: List<TelegramChannelConfig>) {
        val raw = channels.joinToString("|") { "${it.username},${it.displayName},${it.id},${it.accessHash}" }
        prefs.edit { putString(KEY_CHANNELS, raw) }
    }

    fun addChannel(username: String, name: String) {
        val current = getSavedChannels().toMutableList()
        val clean = username.removePrefix("@").removePrefix("https://t.me/").trim()
        if (current.none { it.username == clean }) {
            current.add(TelegramChannelConfig(clean, name.ifBlank { "@$clean" }))
            saveChannels(current)
        }
    }

    fun removeChannel(username: String) {
        val updated = getSavedChannels().filter { it.username != username }
        saveChannels(updated)
    }

    // ── Channel browsing (web scraping — no auth needed for public channels) ─

    suspend fun fetchChannelPostsWeb(
        channelUsername: String,
        beforeId: Int = 0
    ): List<TelegramGamePost> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val url = buildString {
                append("https://t.me/s/$channelUsername")
                if (beforeId > 0) append("?before=$beforeId")
            }
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; UsbDiskManager)")
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseWebChannelHtml(html, channelUsername)
        } catch (e: Exception) {
            Timber.e(e, "fetchChannelPostsWeb failed for $channelUsername")
            emptyList()
        }
    }

    private fun parseWebChannelHtml(html: String, channelUsername: String): List<TelegramGamePost> {
        val posts = mutableListOf<TelegramGamePost>()

        // Match message blocks
        val msgPattern = Regex(
            """data-post="$channelUsername/(\d+)"[\s\S]*?class="tgme_widget_message_bubble"([\s\S]*?)(?=data-post=|$)"""
        )
        // Caption/text
        val textPattern = Regex("""class="tgme_widget_message_text[^"]*"[^>]*>([\s\S]*?)</div>""")
        // Photo thumbnail
        val photoPattern = Regex("""tgme_widget_message_photo_wrap[^"]*"[^>]*style="background-image:url\('([^']+)'\)""")
        // File info (document)
        val docNamePattern = Regex("""tgme_widget_message_document_title[^>]*>([^<]+)<""")
        val docSizePattern = Regex("""tgme_widget_message_document_extra[^>]*>([^<]+)<""")

        for (match in msgPattern.findAll(html)) {
            val msgId = match.groupValues[1].toIntOrNull() ?: continue
            val body  = match.groupValues[2]

            val rawText = textPattern.find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), " ")
                ?.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                ?.replace("&nbsp;", " ")?.trim() ?: ""
            val photoUrl = photoPattern.find(body)?.groupValues?.get(1) ?: ""
            val docName  = docNamePattern.find(body)?.groupValues?.get(1)?.trim() ?: ""
            val docSize  = docSizePattern.find(body)?.groupValues?.get(1)?.trim() ?: ""

            if (docName.isBlank() && photoUrl.isBlank() && rawText.isBlank()) continue

            // Detect if this looks like a PS2 game post
            val isGameContent = isPs2Content(rawText, docName)
            if (!isGameContent && docName.isBlank()) continue

            val title = extractTitle(rawText, docName)
            val region = extractRegion(rawText + " " + docName)
            val gameId = extractGameId(rawText)
            val sizeBytes = parseSizeText(docSize)
            val mime = when {
                docName.endsWith(".iso", true) -> "application/x-iso9660-image"
                docName.endsWith(".bin", true) -> "application/octet-stream"
                docName.endsWith(".7z", true)  -> "application/x-7z-compressed"
                docName.endsWith(".zip", true) -> "application/zip"
                else                           -> "application/octet-stream"
            }

            posts.add(
                TelegramGamePost(
                    messageId      = msgId,
                    channelUsername= channelUsername,
                    title          = title,
                    description    = rawText.take(300),
                    region         = region,
                    gameId         = gameId,
                    fileName       = docName.ifBlank { "message_$msgId.iso" },
                    fileSizeBytes  = sizeBytes,
                    mimeType       = mime,
                    documentId     = 0L, // filled via MTProto if needed
                    accessHash     = 0L,
                    fileReference  = ByteArray(0),
                    dcId           = 0,
                    coverPhotoId   = 0L,
                    date           = 0
                ).apply {
                    // Inject photoUrl into a transient field via companion
                }
            )
        }
        return posts
    }

    // ── MTProto: fetch messages with full doc metadata ─────────────────────

    suspend fun fetchChannelPostsMTProto(
        channelUsername: String,
        channelId: Long,
        channelHash: Long,
        offsetId: Int = 0,
        limit: Int = 50
    ): Result<List<TelegramGamePost>> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val state = getSetupState()
        if (state !is TelegramSetupState.Ready) {
            return@withContext Result.failure(IllegalStateException("Session Telegram non configurée"))
        }
        val client = MTProtoClient(state.session)
        try {
            client.connect()
            val query = TLInvokeWithLayer(
                layer = 185,
                query = TLInitConnection(
                    apiId = state.session.apiId.takeIf { it > 0 } ?: 2040,
                    query = TLGetHistory(channelId, channelHash, offsetId, 0, 0, limit),
                    apiHash = state.apiHash
                )
            )
            val history = client.invoke(query, TLMessagesHistory::class.java)
            val posts = history.messages.mapNotNull { msg ->
                val doc = msg.document ?: return@mapNotNull null
                if (!isGameDocument(doc)) return@mapNotNull null
                TelegramGamePost(
                    messageId       = msg.id,
                    channelUsername = channelUsername,
                    title           = extractTitle(msg.text, doc.fileName),
                    description     = msg.text.take(300),
                    region          = extractRegion(msg.text + " " + doc.fileName),
                    gameId          = extractGameId(msg.text),
                    fileName        = doc.fileName,
                    fileSizeBytes   = doc.size,
                    mimeType        = doc.mimeType,
                    documentId      = doc.id,
                    accessHash      = doc.accessHash,
                    fileReference   = doc.fileReference,
                    dcId            = doc.dcId,
                    coverPhotoId    = msg.photoId,
                    date            = msg.date
                )
            }
            Result.success(posts)
        } catch (e: Exception) {
            Timber.e(e, "fetchChannelPostsMTProto failed")
            Result.failure(e)
        } finally {
            client.disconnect()
        }
    }

    // ── MTProto: download a file ──────────────────────────────────────────

    fun downloadDocument(
        post: TelegramGamePost,
        outputDir: File,
        chunkSize: Int = 512 * 1024 // 512 KB per request
    ): Flow<TelegramDownloadProgress> = flow {
        val state = getSetupState()
        if (state !is TelegramSetupState.Ready) {
            emit(TelegramDownloadProgress(0L, 0L, error = "Session non configurée")); return@flow
        }
        val outFile = File(outputDir, post.fileName)
        var offset = 0L
        val total = post.fileSizeBytes
        var client: MTProtoClient? = null

        try {
            client = MTProtoClient(state.session)
            client.connect()
            FileOutputStream(outFile).use { fos ->
                while (true) {
                    val request = TLInvokeWithLayer(
                        layer = 185,
                        TLInitConnection(
                            state.session.apiId.takeIf { it > 0 } ?: 2040,
                            TLGetFile(
                                dcId = post.dcId,
                                volumeId = 0L, localId = 0, secret = 0L,
                                docId = post.documentId,
                                accessHash = post.accessHash,
                                fileReference = post.fileReference,
                                offset = offset,
                                limit = chunkSize
                            ),
                            state.apiHash
                        )
                    )
                    val chunk = client.invoke(request, TLUploadFile::class.java)
                    if (chunk.bytes.isEmpty()) break
                    fos.write(chunk.bytes)
                    offset += chunk.bytes.size
                    emit(TelegramDownloadProgress(offset, total))
                    if (chunk.bytes.size < chunkSize) break
                }
            }
            emit(TelegramDownloadProgress(offset, total, isDone = true))
        } catch (e: Exception) {
            Timber.e(e, "Download failed for ${post.fileName}")
            emit(TelegramDownloadProgress(offset, total, error = e.message ?: "Erreur inconnue"))
        } finally {
            client?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    // ── Deep-link helper ──────────────────────────────────────────────────

    fun getTelegramDeepLink(channelUsername: String, messageId: Int) =
        "https://t.me/$channelUsername/$messageId"

    // ── Content detection helpers ─────────────────────────────────────────

    private fun isPs2Content(text: String, fileName: String): Boolean {
        val combined = (text + " " + fileName).lowercase()
        return PS2_KEYWORDS.any { combined.contains(it) }
    }

    private fun isGameDocument(doc: TLDocument): Boolean {
        val combined = (doc.fileName + " " + doc.mimeType).lowercase()
        return doc.size > 1_000_000L && (
            combined.contains(".iso") || combined.contains(".bin") ||
            combined.contains(".7z") || combined.contains(".zip") ||
            combined.contains("iso9660") || combined.contains("octet-stream")
        )
    }

    private fun extractTitle(caption: String, fileName: String): String {
        val fromCaption = caption
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.replace(Regex("<[^>]+>"), "")
            ?.take(80)
            ?.trim()
        if (!fromCaption.isNullOrBlank()) return fromCaption

        return fileName
            .removeSuffix(".iso").removeSuffix(".bin").removeSuffix(".7z").removeSuffix(".zip")
            .replace("_", " ").replace("-", " ").trim()
            .take(80)
    }

    private fun extractRegion(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("(usa)") || t.contains("ntsc-u") || t.contains("slus") -> "NTSC-U"
            t.contains("(europe)") || t.contains("pal") || t.contains("sles") -> "PAL"
            t.contains("(japan)") || t.contains("ntsc-j") || t.contains("slps") || t.contains("slpm") -> "NTSC-J"
            else -> ""
        }
    }

    private fun extractGameId(text: String): String {
        return PS2_ID_REGEX.find(text)?.value?.replace("_", "-") ?: ""
    }

    private fun parseSizeText(sizeStr: String): Long {
        val cleaned = sizeStr.trim()
        val num = Regex("([\\d.]+)").find(cleaned)?.groupValues?.get(1)?.toDoubleOrNull() ?: return 0L
        return when {
            cleaned.contains("GB", ignoreCase = true) -> (num * 1_000_000_000).toLong()
            cleaned.contains("MB", ignoreCase = true) -> (num * 1_000_000).toLong()
            cleaned.contains("KB", ignoreCase = true) -> (num * 1_000).toLong()
            else -> 0L
        }
    }

    companion object {
        private val PS2_KEYWORDS = listOf("ps2", "playstation 2", "iso", "slus", "sles", "slpm", "slps", "pcsx2", ".iso", "rom")
        private val PS2_ID_REGEX = Regex("(SLES|SCES|SLUS|SCUS|SLED|SCED|SLPM|SLPS|SCPS|SCAJ)[_-]?\\d{3}\\.\\d{2}", RegexOption.IGNORE_CASE)
    }
}
