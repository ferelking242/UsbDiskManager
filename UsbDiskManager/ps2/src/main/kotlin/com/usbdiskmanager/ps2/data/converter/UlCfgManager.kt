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

    data class UlEntry(
        val gameName: String,
        val gameId: String,
        val numParts: Int,
        val mediaType: Byte
    ) {
        val isCd: Boolean get() = mediaType == TYPE_CD
        val gameIdClean: String get() = gameId.trimEnd('\u0000')
    }

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
            val existingIdx = entries.indexOfFirst { it.gameIdClean == gameId }
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

    fun removeEntry(outputDir: String, gameId: String) {
        val cfgFile = File(outputDir, "ul.cfg")
        if (!cfgFile.exists()) return
        try {
            val entries = readEntries(cfgFile).filter { it.gameIdClean != gameId }
            writeEntries(cfgFile, entries)
        } catch (e: Exception) {
            Timber.e(e, "Failed to remove ul.cfg entry")
        }
    }

    /**
     * Read all entries from a ul.cfg file.
     * Returns empty list if file doesn't exist or is unreadable.
     */
    fun readAllEntries(cfgFile: File): List<UlEntry> = readEntries(cfgFile)

    /**
     * Merge two ul.cfg files into a destination.
     * Entries from [sourceCfg] are merged into [destCfg].
     * If a gameId already exists in dest, the dest version wins (no overwrite).
     * Returns the number of new entries added.
     */
    fun mergeInto(sourceCfg: File, destCfg: File): Int {
        return try {
            val destEntries = readEntries(destCfg).toMutableList()
            val sourceEntries = readEntries(sourceCfg)
            val existingIds = destEntries.map { it.gameIdClean }.toSet()
            var added = 0
            for (entry in sourceEntries) {
                if (entry.gameIdClean !in existingIds) {
                    destEntries.add(entry)
                    added++
                }
            }
            writeEntries(destCfg, destEntries)
            added
        } catch (e: Exception) {
            Timber.e(e, "Failed to merge ul.cfg")
            0
        }
    }

    /**
     * Merge two ul.cfg files producing a new unified one at [outputCfg].
     * Source A takes priority when there's a conflict (same gameId).
     * Returns total entry count in the merged result.
     */
    fun mergeFiles(fileA: File, fileB: File, outputCfg: File): Int {
        return try {
            val entriesA = readEntries(fileA)
            val entriesB = readEntries(fileB)
            val idsFromA = entriesA.map { it.gameIdClean }.toSet()
            val merged = entriesA.toMutableList()
            for (entry in entriesB) {
                if (entry.gameIdClean !in idsFromA) {
                    merged.add(entry)
                }
            }
            writeEntries(outputCfg, merged)
            merged.size
        } catch (e: Exception) {
            Timber.e(e, "Failed to merge ul.cfg files")
            0
        }
    }

    /**
     * Public wrapper around writeEntries — used by UlCfgMergerScreen to write
     * a temporary file before merging, without exposing the internal implementation.
     */
    fun writeEntriesPublic(file: File, entries: List<UlEntry>) = writeEntries(file, entries)

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    private fun readEntries(file: File): List<UlEntry> {
        if (!file.exists() || file.length() == 0L) return emptyList()
        val count = (file.length() / ENTRY_SIZE).toInt()
        val result = mutableListOf<UlEntry>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                for (i in 0 until count) {
                    val buf = ByteArray(ENTRY_SIZE)
                    raf.readFully(buf)
                    val name = String(buf, 0, GAME_NAME_LEN, Charsets.ISO_8859_1).trimEnd('\u0000')
                    val id = String(buf, GAME_NAME_LEN, GAME_ID_LEN, Charsets.ISO_8859_1)
                    val parts = buf[43].toInt() and 0xFF
                    val type = buf[44]
                    result.add(UlEntry(name, id, parts, type))
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read ul.cfg: ${file.absolutePath}")
        }
        return result
    }

    private fun writeEntries(file: File, entries: List<UlEntry>) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0L)
            for (entry in entries) {
                val buf = ByteArray(ENTRY_SIZE) { 0 }
                val nameBytes = entry.gameName.toByteArray(Charsets.ISO_8859_1)
                nameBytes.copyInto(buf, 0, 0, minOf(nameBytes.size, GAME_NAME_LEN))
                val idBytes = entry.gameId.toByteArray(Charsets.ISO_8859_1)
                idBytes.copyInto(buf, GAME_NAME_LEN, 0, minOf(idBytes.size, GAME_ID_LEN))
                buf[43] = entry.numParts.toByte()
                buf[44] = entry.mediaType
                raf.write(buf)
            }
        }
    }
}
