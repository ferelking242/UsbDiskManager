package com.usbdiskmanager.ps2.telegram

import timber.log.Timber
import java.io.DataInputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min
import kotlin.random.Random

// ─── Telegram Data Centers ──────────────────────────────────────────────────

object TelegramDC {
    data class Config(val id: Int, val host: String, val port: Int = 443)

    val DCS = mapOf(
        1 to Config(1, "149.154.175.53"),
        2 to Config(2, "149.154.167.51"),
        3 to Config(3, "149.154.175.100"),
        4 to Config(4, "149.154.167.91"),
        5 to Config(5, "91.108.56.130")
    )

    fun get(id: Int) = DCS[id] ?: DCS[2]!!
}

// ─── MTProto encrypted client ────────────────────────────────────────────────

class MTProtoClient(private val session: TelegramSession) {

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: OutputStream? = null

    private val sessionId = Random.nextLong()
    private val seqNo = AtomicLong(0L)
    private var serverSalt = 0L
    private var connected = false

    // ── Connection ────────────────────────────────────────────────────────

    fun connect() {
        val dc = TelegramDC.get(session.dcId)
        Timber.d("MTProto: connecting to DC${dc.id} @ ${dc.host}:${dc.port}")
        val sock = Socket(dc.host, dc.port)
        sock.soTimeout = 30_000
        socket = sock
        input = DataInputStream(sock.inputStream.buffered())
        output = sock.outputStream

        // Send abridged init byte
        output!!.write(0xEF)
        output!!.flush()
        connected = true
        Timber.d("MTProto: connected (abridged transport)")
    }

    fun disconnect() {
        connected = false
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    // ── High-level send/receive ───────────────────────────────────────────

    @Synchronized
    fun <T : TLObject> send(query: TLObject, responseType: Class<T>): T {
        val msgId = nextMsgId()
        val seqno = nextSeqNo(content = true)
        val plainData = TLCodec.encodeMessage(serverSalt, sessionId, msgId, seqno, query.encode())
        val encrypted = encryptPayload(plainData)
        writeAbridged(encrypted)
        output!!.flush()

        return readResponse(responseType, maxRetries = 3)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : TLObject> readResponse(responseType: Class<T>, maxRetries: Int): T {
        repeat(maxRetries) { attempt ->
            val packet = readAbridged()
            val plain = decryptPayload(packet)
            val buf = ByteBuffer.wrap(plain).order(ByteOrder.LITTLE_ENDIAN)
            buf.position(24) // skip salt(8) + session(8) + msg_id(8)
            val seqno = buf.getInt()
            val msgLen = buf.getInt()
            val bodyBytes = ByteArray(msgLen).also { buf.get(it) }

            val bodyBuf = ByteBuffer.wrap(bodyBytes).order(ByteOrder.LITTLE_ENDIAN)
            val constructor = bodyBuf.getInt()
            Timber.d("MTProto: received constructor=0x%08X attempt=%d".format(constructor, attempt))

            when (constructor) {
                TLConstructors.BAD_SERVER_SALT -> {
                    // 12 bytes: bad_msg_id(8) + error_code(4) + new_salt(8)
                    bodyBuf.position(12)
                    serverSalt = bodyBuf.getLong()
                    Timber.d("MTProto: updated server salt, retrying")
                    val msgId2 = nextMsgId()
                    val seqno2 = nextSeqNo(content = true)
                    // Re-encode last query — caller responsible for re-calling
                    // We'll handle by returning a retryable wrapper via exception
                    throw BadServerSaltException(serverSalt)
                }
                TLConstructors.RPC_RESULT -> {
                    bodyBuf.getLong() // req_msg_id
                    return TLCodec.decode(bodyBuf, responseType)
                }
                TLConstructors.MSG_CONTAINER -> {
                    val count = bodyBuf.getInt()
                    var result: T? = null
                    repeat(count) {
                        bodyBuf.getLong() // msg_id
                        bodyBuf.getInt()  // seqno
                        val bytes = bodyBuf.getInt()
                        val innerCtor = bodyBuf.getInt()
                        if (innerCtor == TLConstructors.RPC_RESULT) {
                            bodyBuf.getLong() // req_msg_id
                            result = TLCodec.decode(bodyBuf, responseType)
                        } else {
                            bodyBuf.position(bodyBuf.position() + bytes - 4)
                        }
                    }
                    return result ?: error("No RPC result in container")
                }
                TLConstructors.GZIP_PACKED -> {
                    val inner = TLCodec.decompressGzip(bodyBuf)
                    val innerBuf = ByteBuffer.wrap(inner).order(ByteOrder.LITTLE_ENDIAN)
                    return TLCodec.decode(innerBuf, responseType)
                }
                else -> throw IllegalStateException("Unexpected constructor: 0x%08X".format(constructor))
            }
        }
        error("Max retries exceeded reading MTProto response")
    }

    // ── Send with salt retry ──────────────────────────────────────────────

    fun <T : TLObject> invoke(query: TLObject, responseType: Class<T>): T {
        repeat(2) {
            try {
                return send(query, responseType)
            } catch (e: BadServerSaltException) {
                Timber.d("MTProto: retrying with new salt ${e.newSalt}")
            }
        }
        return send(query, responseType)
    }

    // ── Abridged transport ────────────────────────────────────────────────

    private fun writeAbridged(data: ByteArray) {
        val out = output!!
        val lenDiv4 = data.size / 4
        if (lenDiv4 <= 0x7E) {
            out.write(lenDiv4)
        } else {
            out.write(0x7F)
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(lenDiv4).array()
            out.write(b, 0, 3)
        }
        out.write(data)
    }

    private fun readAbridged(): ByteArray {
        val inp = input!!
        var lenByte = inp.read()
        val lenDiv4 = if (lenByte < 0x7F) {
            lenByte
        } else {
            val b3 = ByteArray(3)
            inp.readFully(b3)
            ByteBuffer.wrap(byteArrayOf(b3[0], b3[1], b3[2], 0)).order(ByteOrder.LITTLE_ENDIAN).getInt()
        }
        val data = ByteArray(lenDiv4 * 4)
        inp.readFully(data)
        return data
    }

    // ── MTProto Encryption ────────────────────────────────────────────────

    private fun encryptPayload(plainData: ByteArray): ByteArray {
        // Pad to multiple of 16
        val padding = (16 - (plainData.size % 16)) % 16 + (Random.nextInt(15) * 16)
        val padded = plainData + Random.nextBytes(padding)

        // msg_key = middle 16 bytes of SHA256(auth_key[0..36] + padded)
        val sha256Input = session.authKey.slice(0 until 36).toByteArray() + padded
        val sha256 = sha256(sha256Input)
        val msgKey = sha256.slice(8 until 24).toByteArray()

        val (aesKey, aesIv) = deriveAesKeyIv(msgKey, session.authKey, x = 0)
        val encryptedData = aesIgeEncrypt(padded, aesKey, aesIv)

        val result = ByteBuffer.allocate(8 + 16 + encryptedData.size)
        result.order(ByteOrder.LITTLE_ENDIAN).putLong(session.authKeyId)
        result.put(msgKey)
        result.put(encryptedData)
        return result.array()
    }

    private fun decryptPayload(data: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val authKeyId = buf.getLong()
        val msgKey = ByteArray(16); buf.get(msgKey)
        val encData = ByteArray(data.size - 24); buf.get(encData)

        val (aesKey, aesIv) = deriveAesKeyIv(msgKey, session.authKey, x = 8)
        return aesIgeDecrypt(encData, aesKey, aesIv)
    }

    // ── Key derivation ────────────────────────────────────────────────────

    private fun deriveAesKeyIv(msgKey: ByteArray, authKey: ByteArray, x: Int): Pair<ByteArray, ByteArray> {
        val a = sha256(msgKey + authKey.slice(x until x + 36).toByteArray())
        val b = sha256(authKey.slice(x + 40 until x + 76).toByteArray() + msgKey)
        val aesKey = a.slice(0..7).toByteArray() + b.slice(8..23).toByteArray() + a.slice(24..31).toByteArray()
        val aesIv  = b.slice(0..7).toByteArray() + a.slice(8..23).toByteArray() + b.slice(24..31).toByteArray()
        return aesKey to aesIv
    }

    // ── AES-IGE ───────────────────────────────────────────────────────────

    private fun aesIgeEncrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val result = ByteArray(data.size)
        var x = iv.copyOf(16)
        var y = iv.copyOfRange(16, 32)
        for (i in data.indices step 16) {
            val chunk = data.copyOfRange(i, i + 16)
            val enc = cipher.doFinal(xor16(chunk, x))
            val res = xor16(enc, y)
            x = res.copyOf()
            y = chunk.copyOf()
            res.copyInto(result, i)
        }
        return result
    }

    private fun aesIgeDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        val result = ByteArray(data.size)
        var x = iv.copyOf(16)
        var y = iv.copyOfRange(16, 32)
        for (i in data.indices step 16) {
            val chunk = data.copyOfRange(i, i + 16)
            val dec = cipher.doFinal(xor16(chunk, y))
            val res = xor16(dec, x)
            y = res.copyOf()
            x = chunk.copyOf()
            res.copyInto(result, i)
        }
        return result
    }

    private fun xor16(a: ByteArray, b: ByteArray): ByteArray {
        val out = ByteArray(16)
        for (i in 0..15) out[i] = (a[i].toInt() xor b[i].toInt()).toByte()
        return out
    }

    // ── Misc helpers ──────────────────────────────────────────────────────

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private var lastMsgId = 0L
    private fun nextMsgId(): Long {
        val t = System.currentTimeMillis()
        var msgId = t / 1000 * 4_294_967_296L + (t % 1000) * 4_294_967_296L / 1000
        if (msgId <= lastMsgId) msgId = lastMsgId + 4
        lastMsgId = msgId
        return msgId
    }

    private fun nextSeqNo(content: Boolean): Int {
        val n = seqNo.getAndIncrement()
        return if (content) (n * 2 + 1).toInt() else (n * 2).toInt()
    }
}

class BadServerSaltException(val newSalt: Long) : Exception("bad_server_salt, new=$newSalt")
