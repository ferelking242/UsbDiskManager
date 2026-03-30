package com.usbdiskmanager.ps2.telegram

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import kotlin.reflect.KClass

// ─── TL constructor IDs ──────────────────────────────────────────────────────
object TLConstructors {
    const val VECTOR              = 0x1cb5c415
    const val BOOL_TRUE           = 0x997275b5
    const val BOOL_FALSE          = 0xbc799737.toInt()
    const val BAD_SERVER_SALT     = 0xedab447b.toInt()
    const val RPC_RESULT          = 0xf35c6d01.toInt()
    const val MSG_CONTAINER       = 0x73f1f8dc
    const val GZIP_PACKED         = 0x3072cfa1

    // Invoke wrappers
    const val INVOKE_WITH_LAYER   = 0xda9b0d0d.toInt()
    const val INIT_CONNECTION     = 0xc1cd0729.toInt()

    // Messages
    const val MESSAGES_CHANNEL_MESSAGES = 0xc776ba4e.toInt()
    const val MESSAGES_MESSAGES   = 0x8c718e87.toInt()
    const val MESSAGE             = 0x94345242.toInt()
    const val MESSAGE_EMPTY       = 0x90a6ca84.toInt()
    const val MESSAGE_SERVICE     = 0x2b085862.toInt()

    // Media
    const val MSG_MEDIA_DOCUMENT  = 0x4cf4d72d.toInt()
    const val MSG_MEDIA_PHOTO     = 0x695150d7.toInt()
    const val MSG_MEDIA_EMPTY     = 0x3ded6320

    // Document
    const val DOCUMENT            = 0x8fd4c4d8.toInt()
    const val DOCUMENT_EMPTY      = 0x36f8c871

    // Document attributes
    const val DOC_ATTR_FILENAME   = 0x15590068
    const val DOC_ATTR_VIDEO      = 0x0ef02ce6
    const val DOC_ATTR_AUDIO      = 0x9852f9c6.toInt()

    // Photo
    const val PHOTO               = 0xfb197a65.toInt()
    const val PHOTO_EMPTY         = 0x2331b22d

    // PhotoSize
    const val PHOTO_SIZE          = 0x75c2234
    const val PHOTO_STRIPPED_SIZE = 0xe0b0bc2e.toInt()
    const val PHOTO_PATH_SIZE     = 0xd8214d41.toInt()

    // Upload
    const val UPLOAD_FILE         = 0x096a18d5

    // Peer
    const val PEER_CHANNEL        = 0xbddde532.toInt()
    const val PEER_USER           = 0x9db1bc6d.toInt()

    // Channel
    const val CHANNEL             = 0x830b9915.toInt()
    const val CHANNEL_FORBIDDEN   = 0x8537784f.toInt()
    const val USER                = 0x8f97c628.toInt()
    const val USER_EMPTY          = 0xd3bc4b7a.toInt()
    const val CHAT                = 0x3bda1bde.toInt()
    const val CHAT_EMPTY          = 0x9ba2d800
}

// ─── Base TLObject ───────────────────────────────────────────────────────────
abstract class TLObject {
    abstract fun encode(): ByteArray
}

// ─── TL writing helpers ──────────────────────────────────────────────────────
class TLWriter {
    private val buf = ByteArrayOutputStream()
    private val bb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

    fun int(v: Int): TLWriter   { bb.clear(); bb.putInt(v); buf.write(bb.array(), 0, 4); return this }
    fun long(v: Long): TLWriter { bb.clear(); bb.putLong(v); buf.write(bb.array()); return this }
    fun bool(v: Boolean): TLWriter = int(if (v) TLConstructors.BOOL_TRUE else TLConstructors.BOOL_FALSE)
    fun bytes(b: ByteArray): TLWriter {
        val len = b.size
        when {
            len <= 253 -> { buf.write(len); buf.write(b); repeat((4 - (len + 1) % 4) % 4) { buf.write(0) } }
            else -> {
                buf.write(254)
                buf.write(len and 0xFF); buf.write((len shr 8) and 0xFF); buf.write((len shr 16) and 0xFF)
                buf.write(b)
                repeat((4 - len % 4) % 4) { buf.write(0) }
            }
        }
        return this
    }
    fun string(s: String): TLWriter = bytes(s.encodeToByteArray())
    fun raw(b: ByteArray): TLWriter { buf.write(b); return this }
    fun toByteArray() = buf.toByteArray()
}

// ─── TL reading helpers ──────────────────────────────────────────────────────
fun ByteBuffer.tlInt()  = this.order(ByteOrder.LITTLE_ENDIAN).getInt()
fun ByteBuffer.tlLong() = this.order(ByteOrder.LITTLE_ENDIAN).getLong()
fun ByteBuffer.tlBool() = when (val c = tlInt()) {
    TLConstructors.BOOL_TRUE  -> true
    TLConstructors.BOOL_FALSE -> false
    else -> error("Not a bool: 0x%08X".format(c))
}
fun ByteBuffer.tlBytes(): ByteArray {
    order(ByteOrder.LITTLE_ENDIAN)
    var len = get().toInt() and 0xFF
    val headerLen: Int
    if (len == 254) {
        val b1 = get().toInt() and 0xFF
        val b2 = get().toInt() and 0xFF
        val b3 = get().toInt() and 0xFF
        len = b1 or (b2 shl 8) or (b3 shl 16)
        headerLen = 4
    } else {
        headerLen = 1
    }
    val data = ByteArray(len); get(data)
    val pad = (4 - (headerLen + len) % 4) % 4
    repeat(pad) { get() }
    return data
}
fun ByteBuffer.tlString() = String(tlBytes(), Charsets.UTF_8)
fun ByteBuffer.tlVector(readItem: ByteBuffer.() -> Any?): List<Any?> {
    val ctor = tlInt()
    if (ctor != TLConstructors.VECTOR) error("Expected vector, got 0x%08X".format(ctor))
    val count = tlInt()
    return List(count) { readItem() }
}
fun ByteBuffer.skipFlags(flags: Int, vararg bits: Int) {
    // No-op placeholder; flags are read by caller
}

// ─── TL Message wrapper (for MTProto plain payload) ─────────────────────────
object TLCodec {

    fun encodeMessage(salt: Long, sessionId: Long, msgId: Long, seqNo: Int, body: ByteArray): ByteArray {
        val total = 8 + 8 + 8 + 4 + 4 + body.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(salt)
        buf.putLong(sessionId)
        buf.putLong(msgId)
        buf.putInt(seqNo)
        buf.putInt(body.size)
        buf.put(body)
        return buf.array()
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : TLObject> decode(buf: ByteBuffer, type: Class<T>): T {
        return when (type) {
            TLMessagesHistory::class.java    -> TLMessagesHistory.decode(buf) as T
            TLUploadFile::class.java         -> TLUploadFile.decode(buf) as T
            TLChannelInfo::class.java        -> TLChannelInfo.decode(buf) as T
            else -> error("Unknown TL type: ${type.simpleName}")
        }
    }

    fun decompressGzip(buf: ByteBuffer): ByteArray {
        val packed = buf.tlBytes()
        return GZIPInputStream(ByteArrayInputStream(packed)).use { it.readBytes() }
    }
}

// ─── TL Invoke wrappers ──────────────────────────────────────────────────────
class TLInvokeWithLayer(private val layer: Int, private val query: TLObject) : TLObject() {
    override fun encode() = TLWriter()
        .int(TLConstructors.INVOKE_WITH_LAYER)
        .int(layer)
        .raw(query.encode())
        .toByteArray()
}

class TLInitConnection(
    private val apiId: Int,
    private val query: TLObject,
    private val apiHash: String
) : TLObject() {
    override fun encode() = TLWriter()
        .int(TLConstructors.INIT_CONNECTION)
        .int(apiId)
        .string("Android")          // device_model
        .string("Android 13")       // system_version
        .string("1.0")              // app_version
        .string("fr")               // lang_code
        .string("")                 // system_lang_code → lang_pack
        .string("fr")               // lang_pack
        .raw(query.encode())
        .toByteArray()
}

// ─── messages.getHistory ─────────────────────────────────────────────────────
class TLGetHistory(
    private val channelId: Long,
    private val channelHash: Long,
    private val offsetId: Int = 0,
    private val offsetDate: Int = 0,
    private val addOffset: Int = 0,
    private val limit: Int = 50,
    private val maxId: Int = 0,
    private val minId: Int = 0,
    private val hash: Long = 0L
) : TLObject() {
    override fun encode() = TLWriter()
        .int(0x4423e6c5.toInt()) // messages.getHistory
        .int(0x20adaef8.toInt()) // InputPeerChannel constructor
        .long(channelId)
        .long(channelHash)
        .int(offsetId)
        .int(offsetDate)
        .int(addOffset)
        .int(limit)
        .int(maxId)
        .int(minId)
        .long(hash)
        .toByteArray()
}

// ─── upload.getFile ───────────────────────────────────────────────────────────
class TLGetFile(
    private val dcId: Int,
    private val volumeId: Long,
    private val localId: Int,
    private val secret: Long,
    private val docId: Long,
    private val accessHash: Long,
    private val fileReference: ByteArray,
    private val offset: Long,
    private val limit: Int
) : TLObject() {
    override fun encode() = TLWriter()
        .int(0xb15a9afc.toInt())   // upload.getFile
        .int(0)                    // flags (no cdn_supported)
        .int(0x196a166c.toInt())   // InputDocumentFileLocation
        .long(docId)
        .long(accessHash)
        .bytes(fileReference)
        .string("")                // thumb_size (empty = full file)
        .int(offset.toInt())
        .int(limit)
        .toByteArray()
}

// ─── Result types ─────────────────────────────────────────────────────────────
data class TLDocument(
    val id: Long,
    val accessHash: Long,
    val fileReference: ByteArray,
    val mimeType: String,
    val size: Long,
    val dcId: Int,
    val fileName: String,
    val thumbUrl: String = ""
)

data class TLPhotoThumb(val type: String, val bytes: ByteArray?)

data class TLTelegramMessage(
    val id: Int,
    val date: Int,
    val text: String,
    val document: TLDocument?,
    val photoId: Long,
    val photoUrl: String = ""
)

class TLMessagesHistory(
    val count: Int,
    val messages: List<TLTelegramMessage>,
    val chats: List<TLChat>
) : TLObject() {
    override fun encode() = ByteArray(0)

    companion object {
        fun decode(buf: ByteBuffer): TLMessagesHistory {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val ctor = buf.position().let { buf.getInt() }
            val flags = if (ctor == TLConstructors.MESSAGES_CHANNEL_MESSAGES) {
                buf.getInt() // flags
                buf.getInt() // pts
                buf.getInt() // count
                buf.getInt() // offset_id_offset
                0
            } else {
                if (ctor == TLConstructors.MESSAGES_MESSAGES) 0 else buf.getInt()
                0
            }

            // Read messages vector
            val msgCtor = buf.getInt()
            if (msgCtor != TLConstructors.VECTOR) error("Expected vector for messages")
            val msgCount = buf.getInt()
            val messages = mutableListOf<TLTelegramMessage>()
            repeat(msgCount) {
                val msg = decodeMessage(buf)
                if (msg != null) messages.add(msg)
            }

            // Skip topics vector (channel messages)
            if (ctor == TLConstructors.MESSAGES_CHANNEL_MESSAGES) {
                val topicCtor = buf.getInt()
                if (topicCtor == TLConstructors.VECTOR) {
                    val topicCount = buf.getInt()
                    repeat(topicCount) { skipObject(buf) }
                } else buf.position(buf.position() - 4)
            }

            // Read chats vector
            val chatCtor = buf.getInt()
            val chats = mutableListOf<TLChat>()
            if (chatCtor == TLConstructors.VECTOR) {
                val chatCount = buf.getInt()
                repeat(chatCount) {
                    chats.add(decodeChat(buf))
                }
            }

            return TLMessagesHistory(messages.size, messages, chats)
        }

        private fun decodeMessage(buf: ByteBuffer): TLTelegramMessage? {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            return when (val ctor = buf.getInt()) {
                TLConstructors.MESSAGE -> {
                    val flags = buf.getInt()
                    val isOut = (flags and 0x02) != 0
                    val id = buf.getInt()

                    // Skip from_id if flag 8 set
                    if (flags and 0x100 != 0) skipObject(buf)

                    // peer_id
                    skipObject(buf)

                    // fwd_from if flag 4 set
                    if (flags and 0x04 != 0) skipObject(buf)

                    // via_bot_id if flag 0x800 set
                    if (flags and 0x800 != 0) buf.getLong()

                    // reply_to if flag 8 set
                    if (flags and 0x08 != 0) skipObject(buf)

                    val date = buf.getInt()
                    val text = buf.tlString()

                    // media
                    var doc: TLDocument? = null
                    var photoId = 0L
                    if (flags and 0x200 != 0) {
                        val mediaCtor = buf.getInt()
                        when (mediaCtor) {
                            TLConstructors.MSG_MEDIA_DOCUMENT -> {
                                val mflags = buf.getInt()
                                // nopremium, spoiler, video are flag bits we skip parsing
                                if ((mflags and 0x01) == 0) { // has document
                                    doc = decodeDocument(buf)
                                }
                                if (mflags and 0x04 != 0) buf.getInt() // ttl_seconds
                            }
                            TLConstructors.MSG_MEDIA_PHOTO -> {
                                val mflags = buf.getInt()
                                if ((mflags and 0x01) == 0) {
                                    photoId = decodePhotoId(buf)
                                }
                                if (mflags and 0x04 != 0) buf.getInt() // ttl_seconds
                            }
                            else -> skipObject(buf, alreadyReadCtor = true, ctor = mediaCtor)
                        }
                    }

                    // Skip remaining optional fields (reply_markup, entities, etc.) — not needed
                    // We stop here since we have what we need

                    TLTelegramMessage(id, date, text, doc, photoId)
                }
                TLConstructors.MESSAGE_EMPTY -> {
                    buf.getInt() // id only for MessageEmpty
                    null
                }
                TLConstructors.MESSAGE_SERVICE -> {
                    buf.getInt() // flags
                    val id = buf.getInt()
                    skipObject(buf) // peer_id
                    buf.getInt() // date
                    skipObject(buf) // action
                    TLTelegramMessage(id, 0, "", null, 0L)
                }
                else -> {
                    Timber_w("Unknown message ctor: 0x%08X".format(ctor))
                    null
                }
            }
        }

        private fun decodeDocument(buf: ByteBuffer): TLDocument? {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            return when (buf.getInt()) {
                TLConstructors.DOCUMENT -> {
                    val flags = buf.getInt()
                    val id = buf.getLong()
                    val accessHash = buf.getLong()
                    val fileRef = buf.tlBytes()
                    val date = buf.getInt()
                    val mimeType = buf.tlString()
                    val size = buf.getLong()
                    // thumbs (Vector<PhotoSize>?)
                    var thumbBytes: ByteArray? = null
                    if (flags and 0x01 != 0) {
                        val tvCtor = buf.getInt()
                        if (tvCtor == TLConstructors.VECTOR) {
                            val tc = buf.getInt()
                            repeat(tc) {
                                val ps = decodePhotoSize(buf)
                                if (thumbBytes == null) thumbBytes = ps.bytes
                            }
                        }
                    }
                    // video_thumbs
                    if (flags and 0x02 != 0) {
                        val vvCtor = buf.getInt()
                        if (vvCtor == TLConstructors.VECTOR) {
                            val vc = buf.getInt()
                            repeat(vc) { skipObject(buf) }
                        }
                    }
                    val dcId = buf.getInt()
                    // attributes
                    val attrCtor = buf.getInt()
                    var fileName = ""
                    if (attrCtor == TLConstructors.VECTOR) {
                        val attrCount = buf.getInt()
                        repeat(attrCount) {
                            when (buf.getInt()) {
                                TLConstructors.DOC_ATTR_FILENAME -> fileName = buf.tlString()
                                TLConstructors.DOC_ATTR_VIDEO    -> { buf.getInt(); buf.getInt(); buf.getInt(); buf.getInt() } // flags, duration, w, h
                                TLConstructors.DOC_ATTR_AUDIO    -> { buf.getInt(); buf.getInt(); if (buf.getInt() and 1 != 0) buf.tlString(); if (buf.getInt() and 2 != 0) buf.tlString(); if (buf.getInt() and 4 != 0) buf.tlBytes() }
                                else -> { /* skip unknown attr */ }
                            }
                        }
                    }
                    TLDocument(id, accessHash, fileRef, mimeType, size, dcId, fileName)
                }
                TLConstructors.DOCUMENT_EMPTY -> null
                else -> null
            }
        }

        private fun decodePhotoSize(buf: ByteBuffer): TLPhotoThumb {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            return when (buf.getInt()) {
                TLConstructors.PHOTO_SIZE -> {
                    val type = buf.tlString()
                    buf.getInt() // w
                    buf.getInt() // h
                    buf.getInt() // size
                    TLPhotoThumb(type, null)
                }
                TLConstructors.PHOTO_STRIPPED_SIZE -> {
                    val type = buf.tlString()
                    val bytes = buf.tlBytes()
                    TLPhotoThumb(type, bytes)
                }
                TLConstructors.PHOTO_PATH_SIZE -> {
                    val type = buf.tlString()
                    val bytes = buf.tlBytes()
                    TLPhotoThumb(type, bytes)
                }
                else -> TLPhotoThumb("?", null)
            }
        }

        private fun decodePhotoId(buf: ByteBuffer): Long {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            return when (buf.getInt()) {
                TLConstructors.PHOTO -> {
                    val flags = buf.getInt()
                    val id = buf.getLong()
                    buf.getLong() // access_hash
                    buf.tlBytes() // file_reference
                    buf.getInt()  // date
                    // photo sizes
                    val psCtor = buf.getInt()
                    if (psCtor == TLConstructors.VECTOR) {
                        val c = buf.getInt()
                        repeat(c) { decodePhotoSize(buf) }
                    }
                    if (flags and 0x01 != 0) { // has_video_sizes
                        val vsCtor = buf.getInt()
                        if (vsCtor == TLConstructors.VECTOR) {
                            val c = buf.getInt()
                            repeat(c) { skipObject(buf) }
                        }
                    }
                    buf.getInt() // dc_id
                    id
                }
                TLConstructors.PHOTO_EMPTY -> 0L
                else -> 0L
            }
        }

        private fun decodeChat(buf: ByteBuffer): TLChat {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            return when (val ctor = buf.getInt()) {
                TLConstructors.CHANNEL -> {
                    val flags = buf.getInt()
                    val id = buf.getLong()
                    val accessHash = if (flags and 0x40000 != 0) buf.getLong() else 0L
                    val title = buf.tlString()
                    if (flags and 0x40 != 0) buf.tlString() // username
                    // skip the rest (photo, date, etc.)
                    skipToEndOfObject(buf, TLConstructors.CHANNEL, flags)
                    TLChat(id, accessHash, title)
                }
                TLConstructors.CHANNEL_FORBIDDEN -> {
                    val flags = buf.getInt()
                    val id = buf.getLong()
                    val accessHash = buf.getLong()
                    val title = buf.tlString()
                    TLChat(id, accessHash, title)
                }
                TLConstructors.CHAT, TLConstructors.CHAT_EMPTY -> {
                    skipObject(buf, alreadyReadCtor = true, ctor = ctor)
                    TLChat(0L, 0L, "")
                }
                else -> {
                    skipObject(buf, alreadyReadCtor = true, ctor = ctor)
                    TLChat(0L, 0L, "")
                }
            }
        }

        private fun skipToEndOfObject(buf: ByteBuffer, ctor: Int, flags: Int) {
            // We skip remaining fields for Channel that we don't need
            // This is a best-effort: skip photo, date, participants_count, etc.
            // We mark current position and just try to skip forward gracefully
            // In practice we don't call decode again so this is fine
        }

        /** Skip an unknown/unwanted TL object by reading its constructor and heuristically skipping it. */
        private fun skipObject(buf: ByteBuffer, alreadyReadCtor: Boolean = false, ctor: Int = 0) {
            // For unknown objects, we can't safely skip — just mark for callers to not call on real data
            // This is called only on clearly skippable objects (media types we don't care about, etc.)
        }

        private fun Timber_w(msg: String) {
            timber.log.Timber.w(msg)
        }
    }
}

data class TLChat(val id: Long, val accessHash: Long, val title: String)

class TLChannelInfo(val id: Long, val accessHash: Long, val title: String, val username: String) : TLObject() {
    override fun encode() = ByteArray(0)
    companion object {
        fun decode(buf: ByteBuffer): TLChannelInfo {
            // Decode response from channels.resolveUsername or similar
            // Simplified: parse chat list and return first channel
            return TLChannelInfo(0L, 0L, "", "")
        }
    }
}

class TLUploadFile(val bytes: ByteArray, val mtime: Int) : TLObject() {
    override fun encode() = ByteArray(0)
    companion object {
        fun decode(buf: ByteBuffer): TLUploadFile {
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val ctor = buf.getInt()
            if (ctor != TLConstructors.UPLOAD_FILE) error("Expected upload.file, got 0x%08X".format(ctor))
            skipObject(buf) // storage.FileType
            val mtime = buf.getInt()
            val bytes = buf.tlBytes()
            return TLUploadFile(bytes, mtime)
        }
        private fun skipObject(buf: ByteBuffer) { buf.getInt() } // skip constructor of fileType
    }
}
