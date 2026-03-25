package com.usbdiskmanager.ps2.data.converter

import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the binary ul.cfg catalog file used by OPL/USBExtreme.
 *
 * Each entry is exactly 64 bytes:
 *   bytes  0-31  : game name (null-padded, 32 chars)
 *   bytes 32-42  : game ID   (null-padded, 11 chars e.g. "SLUS_012.34")
 *   byte   43    : number of parts  (1–128)
 *   byte   44    : media type (0x12 = DVD, 0x14 = CD)
 *   bytes 45-63  : reserved (zeros)
 *
 * OPL reads ul.cfg from the same directory as the UL part files.
 */
@Singleton
class UlCfgManager @Inject constructor() {

    companion object {
        private const val ENTRY_SIZE = 64
        private const val GAME_NAME_LEN = 32
        private const val GAME_ID_LEN = 11
        private const val TYPE_DVD: Byte = 0x12
        private const val TYPE_CD: Byte = 0x14
    }

    /**
     * Add or update an entry in ul.cfg.
     *
     * @param outputDir  directory containing UL part files
     * @param gameId     raw PS2 serial e.g. "SLUS_012.34"
     * @param gameName   display name (truncated to 32 chars)
     * @param numParts   total number of 1 GB parts
     * @param isCD       true if CD disc, false if DVD
     */
    fun addOrUpdateEntry(
        outputDir: String,
        gameId: String,
        gameName: String,
        numParts: Int,
        isCD: Boolean = false
    ) {
        val cfgFile = File(outputDir, "ul.cfg")
        try {
            val entries = readEntries(cfgFile).toMutableList()
            val existingIdx = entries.indexOfFirst { it.gameId == gameId }
            val entry = UlEntry(
                gameName = gameName.take(GAME_NAME_LEN),
                gameId = gameId.take(GAME_ID_LEN).padEnd(GAME_ID_LEN, '\u0000'),
                numParts = numParts.coerceIn(1, 128),
                mediaType = if (isCD) TYPE_CD else TYPE_DVD
            )
            if (existingIdx >= 0) entries[existingIdx] = entry else entries.add(entry)
            writeEntries(cfgFile, entries)
        } catch (e: Exception) {
            Timber.e(e, "Failed to update ul.cfg")
        }
    }

    /** Remove an entry from ul.cfg (called on cancel). */
    fun removeEntry(outputDir: String, gameId: String) {
        val cfgFile = File(outputDir, "ul.cfg")
        if (!cfgFile.exists()) return
        try {
            val entries = readEntries(cfgFile).filter { it.gameId.trim('\u0000') != gameId }
            writeEntries(cfgFile, entries)
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove ul.cfg entry")
        }
    }

    // ────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────

    private data class UlEntry(
        val gameName: String,
        val gameId: String,
        val numParts: Int,
        val mediaType: Byte
    )

    private fun readEntries(file: File): List<UlEntry> {
        if (!file.exists()) return emptyList()
        val size = file.length()
        val count = (size / ENTRY_SIZE).toInt()
        val result = mutableListOf<UlEntry>()
        RandomAccessFile(file, "r").use { raf ->
            for (i in 0 until count) {
                val buf = ByteArray(ENTRY_SIZE)
                raf.readFully(buf)
                val name = String(buf, 0, GAME_NAME_LEN, Charsets.ISO_8859_1).trimNull()
                val id   = String(buf, GAME_NAME_LEN, GAME_ID_LEN, Charsets.ISO_8859_1)
                val parts = buf[43].toInt() and 0xFF
                val type = buf[44]
                result.add(UlEntry(name, id, parts, type))
            }
        }
        return result
    }

    private fun writeEntries(file: File, entries: List<UlEntry>) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0L)
            for (entry in entries) {
                val buf = ByteArray(ENTRY_SIZE) { 0 }
                entry.gameName.toByteArray(Charsets.ISO_8859_1).copyInto(buf, 0, 0, minOf(entry.gameName.length, GAME_NAME_LEN))
                entry.gameId.toByteArray(Charsets.ISO_8859_1).copyInto(buf, GAME_NAME_LEN, 0, minOf(entry.gameId.length, GAME_ID_LEN))
                buf[43] = entry.numParts.toByte()
                buf[44] = entry.mediaType
                raf.write(buf)
            }
        }
    }

    private fun String.trimNull(): String = trimEnd('\u0000')
}
