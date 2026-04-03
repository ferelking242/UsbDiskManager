package com.usbdiskmanager.ps2.telegram

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.drinkless.tdlib.TdApi
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ─── File part (one physical file of a possibly multi-part game) ──────────────

data class TelegramFilePart(
    val partNumber: Int,           // 1-based (1 for single-file games)
    val tdlibMessageId: Long,      // Actual TDLib message ID of this document message
    val fileName: String,          // Full file name: "GameName.part1.rar"
    val fileExtension: String,     // Lowercase: ".rar", ".7z", ".iso", ".zip", ".bin", ".chd"
    val fileSizeBytes: Long,
    val mimeType: String,
    val tdlibFileId: Int = 0       // TDLib internal file ID (0 when web-scraped)
)

// ─── Game post (assembled from a cluster of consecutive channel messages) ──────

data class TelegramGamePost(
    // ── Channel identity ──────────────────────────────────────────────────────
    val channelUsername: String,
    val tdlibChatId: Long = 0L,
    val anchorMessageId: Long = 0L,  // TDLib msg ID of the metadata anchor message
    val date: Int = 0,

    // ── Parsed metadata ───────────────────────────────────────────────────────
    val title: String,
    val console: String = "PS2",
    val genre: String = "",
    val series: String = "",
    val region: String = "",
    val publisher: String = "",
    val releaseDate: String = "",
    val modes: String = "",
    val description: String = "",
    val gameId: String = "",         // PS2 serial e.g. "SLES-12345"

    // ── Media ─────────────────────────────────────────────────────────────────
    val coverPhotoFileId: Int = 0,      // TDLib file ID for the cover photo
    val coverPhotoUrl: String? = null,  // Local path after download
    val screenshotFileIds: List<Int> = emptyList(),

    // ── Files ─────────────────────────────────────────────────────────────────
    val fileParts: List<TelegramFilePart> = emptyList()
) {
    // ── Backward-compat helpers for existing UI & DownloadManager ─────────────

    /** Telegram URL post number visible in t.me/channel/NNN. */
    val messageId: Int
        get() = if (anchorMessageId > 0L) (anchorMessageId ushr 20).toInt().coerceAtLeast(1)
                else 1

    /** TDLib message ID of the first file part — used to call getMessage() correctly. */
    val tdlibFirstFileMessageId: Long
        get() = fileParts.firstOrNull()?.tdlibMessageId ?: anchorMessageId

    /** Alias for anchorMessageId — used by the ViewModel for pagination. */
    val tdlibMessageId: Long
        get() = anchorMessageId

    val fileName: String       get() = fileParts.firstOrNull()?.fileName ?: ""
    val fileSizeBytes: Long    get() = fileParts.sumOf { it.fileSizeBytes }
    val mimeType: String       get() = fileParts.firstOrNull()?.mimeType ?: "application/octet-stream"
    val documentId: Long       get() = fileParts.firstOrNull()?.tdlibFileId?.toLong() ?: 0L
    val thumbnailFileId: Int   get() = coverPhotoFileId
    val thumbnailUrl: String?  get() = coverPhotoUrl
    val isMultiPart: Boolean   get() = fileParts.size > 1
    val isIso: Boolean         get() = fileParts.any { it.fileExtension in GAME_EXTENSIONS }

    val sizeFormatted: String
        get() = fileSizeBytes.let { b ->
            when {
                b > 1_000_000_000L -> "%.1f GB".format(b / 1e9)
                b > 1_000_000L     -> "%.0f MB".format(b / 1e6)
                b > 0L             -> "%.0f KB".format(b / 1e3)
                else               -> "?"
            }
        }

    override fun equals(other: Any?) =
        other is TelegramGamePost &&
            anchorMessageId == other.anchorMessageId &&
            channelUsername == other.channelUsername

    override fun hashCode() = 31 * anchorMessageId.hashCode() + channelUsername.hashCode()

    companion object {
        val GAME_EXTENSIONS = setOf(".iso", ".bin", ".7z", ".zip", ".rar", ".chd")
    }
}

// ─── Auth states ──────────────────────────────────────────────────────────────

sealed class TelegramSetupState {
    object NotConfigured : TelegramSetupState()
    object WaitingPhoneNumber : TelegramSetupState()
    data class WaitingCode(val phoneNumber: String) : TelegramSetupState()
    object WaitingPassword : TelegramSetupState()
    object Ready : TelegramSetupState()
    data class Error(val message: String) : TelegramSetupState()
}

// ─── Channel config ───────────────────────────────────────────────────────────

data class TelegramChannelConfig(
    val username: String,
    val displayName: String,
    val id: Long = 0L,
    val accessHash: Long = 0L
)

// ─── Internal prefs keys ──────────────────────────────────────────────────────

private const val PREFS_NAME   = "telegram_prefs"
private const val KEY_API_ID   = "api_id"
private const val KEY_API_HASH = "api_hash"
private const val KEY_CHANNELS = "channels"
private const val KEY_PHONE    = "phone"

private val DEFAULT_CHANNELS = listOf(
    TelegramChannelConfig("opens_ps2", "Opens PS2")
)

// ─── Service ──────────────────────────────────────────────────────────────────

@Singleton
class TelegramChannelService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tdlib: TDLibClient
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val tdlibAuthState: StateFlow<TdApi.AuthorizationState?> get() = tdlib.authState

    // ── Credentials ───────────────────────────────────────────────────────────

    fun getSavedApiId(): Int = prefs.getInt(KEY_API_ID, 0)
    fun getSavedApiHash(): String = prefs.getString(KEY_API_HASH, "") ?: ""
    fun getSavedPhone(): String = prefs.getString(KEY_PHONE, "") ?: ""

    fun saveCredentials(apiId: Int, apiHash: String) {
        prefs.edit {
            putInt(KEY_API_ID, apiId)
            putString(KEY_API_HASH, apiHash.trim())
        }
        tdlib.initialize(apiId, apiHash.trim())
    }

    fun initializeTDLib() {
        val apiId   = getSavedApiId()
        val apiHash = getSavedApiHash()
        if (apiId > 0 && apiHash.isNotBlank()) tdlib.initialize(apiId, apiHash)
    }

    fun getSetupState(): TelegramSetupState {
        if (getSavedApiId() <= 0 || getSavedApiHash().isBlank())
            return TelegramSetupState.NotConfigured
        return when (tdlib.authState.value) {
            is TdApi.AuthorizationStateReady                -> TelegramSetupState.Ready
            is TdApi.AuthorizationStateWaitPhoneNumber,
            is TdApi.AuthorizationStateWaitTdlibParameters -> TelegramSetupState.WaitingPhoneNumber
            is TdApi.AuthorizationStateWaitCode            -> TelegramSetupState.WaitingCode(getSavedPhone())
            is TdApi.AuthorizationStateWaitPassword        -> TelegramSetupState.WaitingPassword
            null -> if (getSavedApiId() > 0) TelegramSetupState.WaitingPhoneNumber
                    else TelegramSetupState.NotConfigured
            else -> TelegramSetupState.WaitingPhoneNumber
        }
    }

    suspend fun sendPhone(phone: String): Result<Unit> = runCatching {
        prefs.edit { putString(KEY_PHONE, phone.trim()) }
        tdlib.setPhoneNumber(phone.trim())
    }

    suspend fun sendCode(code: String): Result<Unit> = runCatching {
        tdlib.checkCode(code.trim())
    }

    suspend fun sendPassword(password: String): Result<Unit> = runCatching {
        tdlib.checkPassword(password)
    }

    suspend fun logOut() {
        runCatching { tdlib.logOut() }
        prefs.edit { remove(KEY_PHONE) }
    }

    fun clearSetup() {
        prefs.edit {
            remove(KEY_API_ID)
            remove(KEY_API_HASH)
            remove(KEY_PHONE)
        }
        tdlib.close()
    }

    // ── Channel management ────────────────────────────────────────────────────

    fun getSavedChannels(): List<TelegramChannelConfig> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return DEFAULT_CHANNELS
        return raw.split("|").mapNotNull { entry ->
            val p = entry.split(",")
            if (p.size >= 2) TelegramChannelConfig(
                p[0], p[1],
                p.getOrNull(2)?.toLongOrNull() ?: 0L,
                p.getOrNull(3)?.toLongOrNull() ?: 0L
            ) else null
        }.ifEmpty { DEFAULT_CHANNELS }
    }

    fun saveChannels(channels: List<TelegramChannelConfig>) {
        prefs.edit {
            putString(KEY_CHANNELS, channels.joinToString("|") {
                "${it.username},${it.displayName},${it.id},${it.accessHash}"
            })
        }
    }

    fun addChannel(username: String, name: String) {
        val current = getSavedChannels().toMutableList()
        val clean   = username.removePrefix("@").removePrefix("https://t.me/").trim()
        if (current.none { it.username == clean }) {
            current.add(TelegramChannelConfig(clean, name.ifBlank { "@$clean" }))
            saveChannels(current)
        }
    }

    fun removeChannel(username: String) {
        saveChannels(getSavedChannels().filter { it.username != username })
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TDLib NATIVE INDEXING
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Fetches a batch of messages and returns only complete game posts.
     * Each post has all metadata, cover photo, description, and file parts assembled.
     *
     * [fromTdlibId] = 0  → start from the very latest message.
     */
    suspend fun fetchChannelPostsTDLib(
        channelUsername: String,
        fromTdlibId: Long = 0L,
        limit: Int = 100
    ): List<TelegramGamePost> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val chat     = tdlib.searchPublicChat(channelUsername)
        val batch    = tdlib.getChatHistory(chat.id, fromTdlibId, limit)
        val messages = batch.messages?.toList() ?: return@withContext emptyList()
        // getChatHistory returns newest-first → sort ascending for forward-scan
        buildGameGroups(messages.sortedBy { it.id }, channelUsername, chat.id)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CORE ALGORITHM
    // ══════════════════════════════════════════════════════════════════════════
    //
    // The channel posts a PS2 game as a cluster of consecutive messages:
    //
    //   [Photo]               ← game cover  (optional, before or as anchor)
    //   [Photo/Text]          ← ANCHOR: contains "Console : PS2" + Genre/Region/…
    //   [Text]                ← game description  (optional)
    //   [Photo] …             ← gameplay screenshots  (optional)
    //   [System requirements] ← noise → skipped
    //   [Document] …          ← one or more ISO / archive file parts
    //   ── next game starts ──
    //
    // The algorithm scans messages in ascending order (oldest first), identifies
    // anchors, then collects all subsequent messages until the next anchor.
    // ══════════════════════════════════════════════════════════════════════════

    private fun buildGameGroups(
        messages: List<TdApi.Message>,   // sorted ascending by TDLib message ID
        channelUsername: String,
        chatId: Long
    ): List<TelegramGamePost> {

        val groups = mutableListOf<TelegramGamePost>()
        val n = messages.size
        var i = 0

        while (i < n) {
            val msg     = messages[i]
            val msgText = extractMessageText(msg)

            // Skip Telegram service/system notifications
            if (isServiceMessage(msg))                         { i++; continue }
            // Skip noise messages
            if (msgText.isNotBlank() && isNoise(msgText))      { i++; continue }
            // Not a game anchor → keep scanning
            if (!hasGameMetadata(msgText))                     { i++; continue }

            // ═══════════════════════ ANCHOR FOUND ════════════════════════════

            // Cover photo logic:
            //   Case A: the anchor IS a photo (caption = metadata) → cover = this photo
            //   Case B: anchor is a text message → look backward up to 5 msgs for a photo
            var coverFileId    = 0
            var coverCaption   = ""   // title from cover photo caption (fallback)
            if (msg.content is TdApi.MessagePhoto) {
                coverFileId = extractPhotoThumbnailId(msg)
            } else {
                for (j in (i - 1) downTo maxOf(0, i - 5)) {
                    val prev     = messages[j]
                    val prevText = extractMessageText(prev)
                    if (prev.content is TdApi.MessagePhoto && !hasGameMetadata(prevText)) {
                        coverFileId  = extractPhotoThumbnailId(prev)
                        // The photo caption is often the game title (e.g. opens_ps2 channel)
                        coverCaption = prevText.trim()
                        break
                    }
                }
            }

            // Parse all metadata fields from the anchor text
            // If the anchor text has no title line (starts directly with "Console :"),
            // fall back to the cover photo's caption (used in channels like opens_ps2).
            val rawTitle = parseTitle(msgText)
            val title    = rawTitle.ifBlank { coverCaption.take(100) }
            val genre       = parseField(msgText, "Genre")
            val series      = parseField(msgText, "Series")
            val region      = parseRegion(msgText)
            val publisher   = parseField(msgText, "Publisher")
            val releaseDate = parseField(msgText, listOf("Released", "Release Date"))
            val modes       = parseField(msgText, listOf("Mode(s)", "Modes", "Mode"))
            val gameId      = parseGameSerial(msgText)

            // Scan FORWARD: collect description, screenshots, and file parts
            var description = ""
            val screenshots = mutableListOf<Int>()
            val fileParts   = mutableListOf<TelegramFilePart>()
            var j           = i + 1

            while (j < n) {
                val next     = messages[j]
                val nextText = extractMessageText(next)

                // Hit the next game anchor → this group is done
                if (!isServiceMessage(next) && hasGameMetadata(nextText)) break

                if (isServiceMessage(next))                              { j++; continue }
                if (nextText.isNotBlank() && isNoise(nextText))          { j++; continue }

                when (val content = next.content) {

                    is TdApi.MessageText -> {
                        val text = content.text?.text ?: ""
                        if (description.isBlank() && isDescriptionText(text)) {
                            description = text
                                .replaceFirst(
                                    Regex("^Description\\s*:?\\s*", RegexOption.IGNORE_CASE), ""
                                ).trim()
                        }
                    }

                    is TdApi.MessagePhoto -> {
                        val id = extractPhotoThumbnailId(next)
                        if (id > 0) {
                            // First photo in forward scan before any docs = cover (opens_ps2 style)
                            if (coverFileId == 0 && fileParts.isEmpty()) {
                                coverFileId = id
                            } else {
                                screenshots.add(id)
                            }
                        }
                    }

                    is TdApi.MessageDocument -> {
                        if (content.document == null) { j++; continue }
                        val doc = content.document!!
                        val fileNameRaw = doc.fileName?.takeIf { it.isNotBlank() }
                        if (fileNameRaw == null) { j++; continue }
                        val fileName = fileNameRaw
                        val ext = fileExtension(fileName)
                        // Must be a game format
                        if (ext !in TelegramGamePost.GAME_EXTENSIONS)    { j++; continue }
                        // Must be big enough to be a real game (not a tiny attachment)
                        if (doc.document.size < MIN_GAME_FILE_BYTES)     { j++; continue }

                        fileParts.add(
                            TelegramFilePart(
                                partNumber     = detectPartNumber(fileName, fileParts.size + 1),
                                tdlibMessageId = next.id,
                                fileName       = fileName,
                                fileExtension  = ext,
                                fileSizeBytes  = doc.document.size,
                                mimeType       = mimeForExtension(ext),
                                tdlibFileId    = doc.document.id
                            )
                        )
                    }

                    else -> { /* polls, videos, audio, stickers → ignored */ }
                }
                j++
            }

            // Only emit a game post if at least one downloadable file was found
            if (fileParts.isNotEmpty()) {
                groups.add(
                    TelegramGamePost(
                        channelUsername   = channelUsername,
                        tdlibChatId       = chatId,
                        anchorMessageId   = msg.id,
                        date              = msg.date,
                        title             = title,
                        genre             = genre,
                        series            = series,
                        region            = region,
                        publisher         = publisher,
                        releaseDate       = releaseDate,
                        modes             = modes,
                        description       = description,
                        gameId            = gameId,
                        coverPhotoFileId  = coverFileId,
                        screenshotFileIds = screenshots.toList(),
                        fileParts         = fileParts.toList()
                    )
                )
            }

            // Advance past all messages consumed by this group
            i = j
        }

        return groups
    }

    // ── Thumbnail download via TDLib ───────────────────────────────────────────

    suspend fun downloadThumbnail(fileId: Int): String? = runCatching {
        val file = tdlib.startDownload(fileId, priority = 1)
        if (file.local.isDownloadingCompleted && file.local.path.isNotBlank())
            return file.local.path
        val update = tdlib.fileUpdates
            .first { it.file.id == fileId && it.file.local.isDownloadingCompleted }
        update.file.local.path.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ══════════════════════════════════════════════════════════════════════════
    // WEB SCRAPING FALLBACK (no auth required)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Fetches and parses the public t.me/s/ preview page.
     * Limited: only the last ~20 messages are visible and metadata is often absent.
     * Consecutive document messages with the same base name are grouped as multi-part.
     */
    suspend fun fetchChannelPostsWeb(
        channelUsername: String,
        beforeId: Int = 0
    ): List<TelegramGamePost> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            val url = "https://t.me/s/$channelUsername".let {
                if (beforeId > 0) "$it?before=$beforeId" else it
            }
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; UsbDiskManager/1.0)")
            conn.connectTimeout = 15_000
            conn.readTimeout    = 20_000
            val html = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            parseWebHtml(html, channelUsername)
        } catch (e: Exception) {
            Timber.e(e, "fetchChannelPostsWeb failed for $channelUsername")
            emptyList()
        }
    }

    private data class WebBubble(
        val msgId: Int,
        val text: String,
        val docName: String,
        val docSize: String,
        val thumbUrl: String?
    )

    private fun parseWebHtml(html: String, channelUsername: String): List<TelegramGamePost> {
        val bubbles = extractWebBubbles(html, channelUsername)
        val posts   = mutableListOf<TelegramGamePost>()
        val pending = mutableListOf<WebBubble>()

        fun flush() {
            if (pending.isEmpty()) return
            val files = pending.filter { it.docName.isNotBlank() }
            if (files.isEmpty()) { pending.clear(); return }

            val metaText = pending.firstOrNull { hasGameMetadata(it.text) }?.text ?: ""
            val descText = pending.firstOrNull {
                isDescriptionText(it.text) && !hasGameMetadata(it.text)
            }?.text ?: ""

            val parts = files.mapIndexed { idx, b ->
                val ext = fileExtension(b.docName)
                TelegramFilePart(
                    partNumber     = detectPartNumber(b.docName, idx + 1),
                    tdlibMessageId = files.first().msgId.toLong() shl 20,
                    fileName       = b.docName,
                    fileExtension  = ext,
                    fileSizeBytes  = parseSizeText(b.docSize),
                    mimeType       = mimeForExtension(ext)
                )
            }

            val combined = "$metaText ${files.first().docName}"
            posts.add(
                TelegramGamePost(
                    channelUsername = channelUsername,
                    anchorMessageId = files.first().msgId.toLong() shl 20,
                    title           = if (metaText.isNotBlank()) parseTitle(metaText)
                                      else fileNameToTitle(files.first().docName),
                    genre           = parseField(metaText, "Genre"),
                    series          = parseField(metaText, "Series"),
                    region          = parseRegion(combined),
                    publisher       = parseField(metaText, "Publisher"),
                    releaseDate     = parseField(metaText, listOf("Released", "Release Date")),
                    modes           = parseField(metaText, listOf("Mode(s)", "Modes")),
                    description     = descText
                        .replaceFirst(Regex("^Description\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
                        .trim(),
                    gameId          = parseGameSerial(combined),
                    coverPhotoUrl   = files.first().thumbUrl,
                    fileParts       = parts
                )
            )
            pending.clear()
        }

        for (bubble in bubbles) {
            val firstFile = pending.firstOrNull { it.docName.isNotBlank() }

            val continues = when {
                bubble.docName.isBlank() && firstFile == null             -> true
                bubble.docName.isBlank() && !hasGameMetadata(bubble.text) -> true
                bubble.docName.isNotBlank() && firstFile != null          ->
                    shareBaseName(bubble.docName, firstFile.docName)
                else -> true
            }

            if (!continues) flush()
            // New game anchor while files are already pending → flush first
            if (bubble.docName.isBlank() && hasGameMetadata(bubble.text) &&
                pending.any { it.docName.isNotBlank() }) flush()

            pending.add(bubble)
        }
        flush()

        return posts
    }

    private fun extractWebBubbles(html: String, channelUsername: String): List<WebBubble> {
        val result = mutableListOf<WebBubble>()
        val msgPat  = Regex(
            """data-post="$channelUsername/(\d+)"[\s\S]*?class="tgme_widget_message_bubble"([\s\S]*?)(?=data-post=|${'$'})"""
        )
        val textPat  = Regex("""class="tgme_widget_message_text[^"]*"[^>]*>([\s\S]*?)</div>""")
        val docName  = Regex("""tgme_widget_message_document_title[^>]*>([^<]+)<""")
        val docSize  = Regex("""tgme_widget_message_document_extra[^>]*>([^<]+)<""")
        val thumbPat = Regex("""tgme_widget_message_photo_wrap[^"]*"[^>]*style="background-image:url\('([^']+)'\)""")

        for (m in msgPat.findAll(html)) {
            val id   = m.groupValues[1].toIntOrNull() ?: continue
            val body = m.groupValues[2]

            val rawText = textPat.find(body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), " ")
                ?.replace("&amp;", "&")?.replace("&lt;", "<")
                ?.replace("&gt;", ">")?.replace("&nbsp;", " ")
                ?.trim() ?: ""

            if (rawText.isNotBlank() && isNoise(rawText)) continue

            val name  = docName.find(body)?.groupValues?.get(1)?.trim() ?: ""
            val size  = docSize.find(body)?.groupValues?.get(1)?.trim() ?: ""
            val thumb = thumbPat.find(body)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

            if (name.isNotBlank() && fileExtension(name) !in TelegramGamePost.GAME_EXTENSIONS) continue

            result.add(WebBubble(id, rawText, name, size, thumb))
        }
        return result
    }

    // ── Deep link ─────────────────────────────────────────────────────────────

    fun getTelegramDeepLink(channelUsername: String, messageId: Int) =
        "https://t.me/$channelUsername/$messageId"

    // ══════════════════════════════════════════════════════════════════════════
    // DETECTION & PARSING HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * True if text has structured PS2 game metadata.
     * Requires "Console" + "PS2" AND at least one of Genre/Region/Publisher/Released.
     */
    private fun hasGameMetadata(text: String): Boolean {
        if (text.isBlank()) return false
        return text.contains("Console", ignoreCase = true) &&
               text.contains("PS2",     ignoreCase = true) &&
               (text.contains("Genre",     ignoreCase = true) ||
                text.contains("Region",    ignoreCase = true) ||
                text.contains("Publisher", ignoreCase = true) ||
                text.contains("Released",  ignoreCase = true))
    }

    /**
     * True if text is a long-form game description block.
     */
    private fun isDescriptionText(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.startsWith("Description", ignoreCase = true)) return true
        return text.length > 80 && !hasGameMetadata(text) && !isNoise(text)
    }

    private fun isServiceMessage(msg: TdApi.Message): Boolean =
        when (msg.content) {
            is TdApi.MessagePinMessage,
            is TdApi.MessageChatJoinByLink,
            is TdApi.MessageChatAddMembers,
            is TdApi.MessageChatDeleteMember -> true
            else -> false
        }

    private fun isNoise(text: String): Boolean {
        if (text.isBlank()) return false
        val t = text.lowercase()
        if (NOISE_PREFIXES.any { t.startsWith(it) }) return true
        if (SPAM_KEYWORDS.any { t.contains(it) })    return true
        if (EXTERNAL_DOMAINS.any { t.contains(it) }) return true
        return false
    }

    // ── Metadata field parsers ────────────────────────────────────────────────

    /** First non-blank non-metadata line is the game title. */
    private fun parseTitle(text: String): String {
        val metaRe = Regex(
            "^\\s*(Console|Genre|Region|Publisher|Released|Release Date|Mode|Series)\\s*:",
            RegexOption.IGNORE_CASE
        )
        for (line in text.lines()) {
            val clean = line.replace(Regex("<[^>]+>"), "").trim()
            if (clean.isBlank()) continue
            if (metaRe.containsMatchIn(clean)) break
            return clean.take(100)
        }
        return ""
    }

    private fun parseField(text: String, key: String) = parseField(text, listOf(key))

    private fun parseField(text: String, keys: List<String>): String {
        for (line in text.lines()) {
            for (key in keys) {
                val m = Regex(
                    "^\\s*${Regex.escape(key)}\\s*:\\s*(.+)$",
                    RegexOption.IGNORE_CASE
                ).find(line)
                if (m != null) return m.groupValues[1].trim().take(200)
            }
        }
        return ""
    }

    private fun parseRegion(text: String): String {
        val field = parseField(text, "Region")
        if (field.isNotBlank()) return when {
            field.contains("USA",          ignoreCase = true) ||
            field.contains("NTSC-U",       ignoreCase = true) ||
            field.contains("North America", ignoreCase = true) -> "NTSC-U"
            field.contains("Europe", ignoreCase = true) ||
            field.contains("PAL",    ignoreCase = true)        -> "PAL"
            field.contains("Japan",  ignoreCase = true) ||
            field.contains("NTSC-J", ignoreCase = true) ||
            field.contains("Asia",   ignoreCase = true)        -> "NTSC-J"
            else -> field.take(20)
        }
        val t = text.lowercase()
        return when {
            "(usa)"    in t || "ntsc-u" in t || "slus" in t || "scus" in t -> "NTSC-U"
            "(europe)" in t || "(pal)"  in t || "sles" in t || "sces" in t -> "PAL"
            "(japan)"  in t || "ntsc-j" in t || "slps" in t || "slpm" in t -> "NTSC-J"
            else -> ""
        }
    }

    private fun parseGameSerial(text: String): String =
        PS2_SERIAL_RE.find(text)?.value?.replace("_", "-")?.uppercase() ?: ""

    // ── TDLib message helpers ─────────────────────────────────────────────────

    private fun extractMessageText(msg: TdApi.Message): String =
        when (val c = msg.content) {
            is TdApi.MessageText     -> c.text?.text ?: ""
            is TdApi.MessagePhoto    -> c.caption?.text ?: ""
            is TdApi.MessageDocument -> c.caption?.text ?: ""
            is TdApi.MessageVideo    -> c.caption?.text ?: ""
            else -> ""
        }

    /** Returns the TDLib file ID of the photo sized closest to 320px wide. */
    private fun extractPhotoThumbnailId(msg: TdApi.Message): Int {
        val photo = when (val c = msg.content) {
            is TdApi.MessagePhoto    -> c.photo
            is TdApi.MessageDocument -> return c.document?.thumbnail?.file?.id ?: 0
            else -> return 0
        } ?: return 0
        return photo.sizes
            .filter { it.width > 0 }
            .minByOrNull { kotlin.math.abs(it.width - 320) }
            ?.photo?.id ?: 0
    }

    // ── File helpers ──────────────────────────────────────────────────────────

    private fun fileExtension(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".rar") -> ".rar"
            lower.endsWith(".7z")  -> ".7z"
            lower.endsWith(".zip") -> ".zip"
            lower.endsWith(".iso") -> ".iso"
            lower.endsWith(".bin") -> ".bin"
            lower.endsWith(".chd") -> ".chd"
            else -> lower.substringAfterLast('.', "").let { if (it.isNotBlank()) ".$it" else "" }
        }
    }

    private fun mimeForExtension(ext: String): String = when (ext) {
        ".iso" -> "application/x-iso9660-image"
        ".7z"  -> "application/x-7z-compressed"
        ".zip" -> "application/zip"
        ".rar" -> "application/x-rar-compressed"
        else   -> "application/octet-stream"
    }

    /**
     * Detects sequential part number from file name.
     * "Game.part2.rar" → 2,  "Game (3).zip" → 3,  "Game.rar" → fallback.
     */
    private fun detectPartNumber(fileName: String, fallback: Int): Int {
        PART_NUMBER_RES.forEach { re ->
            val v = re.find(fileName)?.groupValues?.get(1)?.toIntOrNull()
            if (v != null) return v
        }
        return fallback
    }

    /** True if two file names belong to the same multi-part archive. */
    private fun shareBaseName(a: String, b: String): Boolean {
        fun base(s: String) = s.lowercase()
            .replace(Regex("\\.part\\d+\\.\\w+$"), "")
            .replace(Regex("\\s*\\(\\d+\\)\\s*\\.\\w+$"), "")
            .replace(Regex("\\.\\w+$"), "")
            .trim()
        val ba = base(a); val bb = base(b)
        return ba.isNotBlank() && ba == bb
    }

    private fun fileNameToTitle(fn: String) = fn
        .replace(Regex("\\.part\\d+\\.\\w+$"), "")
        .replace(Regex("\\.\\w+$"), "")
        .replace("_", " ").replace("-", " ")
        .trim().take(100)

    private fun parseSizeText(s: String): Long {
        val n = Regex("([\\d.]+)").find(s.trim())?.groupValues?.get(1)?.toDoubleOrNull()
            ?: return 0L
        return when {
            "GB" in s.uppercase() -> (n * 1_000_000_000).toLong()
            "MB" in s.uppercase() -> (n * 1_000_000).toLong()
            "KB" in s.uppercase() -> (n * 1_000).toLong()
            else -> 0L
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CONSTANTS
    // ══════════════════════════════════════════════════════════════════════════

    companion object {
        /** Files smaller than 50 MB are not game files. */
        private const val MIN_GAME_FILE_BYTES = 50L * 1024L * 1024L

        private val PART_NUMBER_RES = listOf(
            Regex("""\bpart\s*(\d+)\b""",  RegexOption.IGNORE_CASE),
            Regex("""\((\d+)\)\s*\.\w+$"""),
            Regex("""[-_\s](\d+)\s*\.\w+$""")
        )

        private val PS2_SERIAL_RE = Regex(
            "(SLES|SCES|SLUS|SCUS|SLED|SCED|SLPM|SLPS|SCPS|SCAJ)[_-]?\\d{3}\\.\\d{2}",
            RegexOption.IGNORE_CASE
        )

        /** Text starting with these (lowercased) is unconditional noise. */
        private val NOISE_PREFIXES = listOf(
            "system requirements to run the game",
            "you can request your ps2 games",
            "ps2 game request",
            "ps2 games list has been updated",
            "which game", "which games",
            "join my channel", "join from the below link",
            "happy gaming", "merry christmas",
            "how to install", "from tomorrow",
            "hi guys", "hi folks",
            "it been a while",
            "proof", "note 1.",
            "music is going to start", "music started"
        )

        private val SPAM_KEYWORDS = listOf(
            "promo code", "gift card", "dm me", "₹", "rs ", "discount",
            "selling the promo", "only one member", "interested people dm",
            "serum", "toner", "shipping charge",
            "whatsapp.com", "wa.me",
            "earn money", "cryptocurrency", "bitcoin", "honeygain",
            "jupiterpe", "minepi.com", "loan", "fincorp", "banksathi",
            "promiby", "texovera", "pi is a new digital currency"
        )

        private val EXTERNAL_DOMAINS = listOf(
            "store.epicgames.com", "store.steampowered.com",
            "comments.bot/thread", "t.me/ps2gameslist",
            "t.me/gamemodspsworld", "cfwaifu.com"
        )
    }
}
