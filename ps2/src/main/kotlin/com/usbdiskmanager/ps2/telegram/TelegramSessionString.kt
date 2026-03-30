package com.usbdiskmanager.ps2.telegram

import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Parses Pyrogram v1/v2 and Telethon session strings.
 *
 * Pyrogram v2 layout (base64url, 275 bytes):
 *   dc_id     : 1 byte
 *   api_id    : 4 bytes (big-endian int)
 *   test_mode : 1 byte (bool)
 *   auth_key  : 256 bytes
 *   user_id   : 8 bytes (int64)
 *   is_bot    : 1 byte (bool)
 *
 * Pyrogram v1 layout (base64url, 265 bytes):
 *   dc_id    : 1 byte
 *   auth_key : 256 bytes
 *   user_id  : 4 bytes (int32)
 *   is_bot   : 1 byte (bool)
 *
 * Telethon layout (base64url, 263 bytes):
 *   dc_id          : 1 byte
 *   server_address : 4 bytes (IPv4) or 16 bytes (IPv6)
 *   port           : 2 bytes (big-endian short)
 *   auth_key       : 256 bytes
 */
data class TelegramSession(
    val dcId: Int,
    val apiId: Int,          // 0 if unknown (Telethon / Pyrogram v1)
    val authKey: ByteArray,  // 256 bytes
    val userId: Long,        // 0 if unknown (Telethon)
    val isBot: Boolean,
    val format: String       // "pyrogram_v2" | "pyrogram_v1" | "telethon"
) {
    /** Last 8 bytes of SHA-1(authKey) — used as auth_key_id in MTProto. */
    val authKeyId: Long by lazy {
        val sha1 = MessageDigest.getInstance("SHA-1").digest(authKey)
        ByteBuffer.wrap(sha1, sha1.size - 8, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
    }

    override fun equals(other: Any?) = other is TelegramSession &&
        dcId == other.dcId && authKey.contentEquals(other.authKey)
    override fun hashCode() = dcId * 31 + authKey.contentHashCode()
}

object TelegramSessionString {

    fun parse(raw: String): TelegramSession {
        val cleaned = raw.trim()
        val bytes = try {
            Base64.decode(
                cleaned.replace('-', '+').replace('_', '/'),
                Base64.NO_WRAP
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("Session string invalide: ${e.message}")
        }

        return when (bytes.size) {
            275 -> parsePyrogramV2(bytes)
            265 -> parsePyrogramV1(bytes)
            263 -> parseTelethon(bytes, ipv6 = false)
            275 + 12 -> parseTelethon(bytes, ipv6 = true)
            else -> throw IllegalArgumentException(
                "Format inconnu (${bytes.size} octets). Pyrogram v2=275, v1=265, Telethon=263"
            )
        }
    }

    private fun parsePyrogramV2(b: ByteArray): TelegramSession {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val dcId  = buf.get().toInt() and 0xFF
        val apiId = buf.getInt()
        val testMode = buf.get().toInt() != 0
        val authKey = ByteArray(256); buf.get(authKey)
        val userId = buf.getLong()
        val isBot = buf.get().toInt() != 0
        return TelegramSession(dcId, apiId, authKey, userId, isBot, "pyrogram_v2")
    }

    private fun parsePyrogramV1(b: ByteArray): TelegramSession {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val dcId = buf.get().toInt() and 0xFF
        val authKey = ByteArray(256); buf.get(authKey)
        val userId = buf.getInt().toLong()
        val isBot = buf.get().toInt() != 0
        return TelegramSession(dcId, 0, authKey, userId, isBot, "pyrogram_v1")
    }

    private fun parseTelethon(b: ByteArray, ipv6: Boolean): TelegramSession {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN)
        val dcId = buf.get().toInt() and 0xFF
        val addrLen = if (ipv6) 16 else 4
        val addr = ByteArray(addrLen); buf.get(addr)
        buf.getShort() // port
        val authKey = ByteArray(256); buf.get(authKey)
        return TelegramSession(dcId, 0, authKey, 0L, false, "telethon")
    }

    /** Serialize back to a base64url Pyrogram v2 string. */
    fun serialize(session: TelegramSession): String {
        val buf = ByteBuffer.allocate(275).order(ByteOrder.BIG_ENDIAN)
        buf.put(session.dcId.toByte())
        buf.putInt(session.apiId)
        buf.put(0.toByte()) // test_mode = false
        buf.put(session.authKey)
        buf.putLong(session.userId)
        buf.put(if (session.isBot) 1.toByte() else 0.toByte())
        return Base64.encodeToString(buf.array(), Base64.URL_SAFE or Base64.NO_WRAP)
    }
}
