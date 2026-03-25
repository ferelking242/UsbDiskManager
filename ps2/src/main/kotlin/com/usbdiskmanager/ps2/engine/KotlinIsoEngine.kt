package com.usbdiskmanager.ps2.engine

import com.usbdiskmanager.ps2.domain.model.ConversionProgress
import com.usbdiskmanager.ps2.domain.model.DiscType
import com.usbdiskmanager.ps2.domain.model.GameRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Robust pure-Kotlin [IsoEngine] implementation.
 *
 * Game ID detection uses three cascading strategies so real-world PS2 ISOs
 * are handled even when the ISO 9660 structure is non-standard or broken.
 *
 * Strategy A — ISO 9660 filesystem traversal (2048 and 2352 sector variants)
 * Strategy B — Streaming raw scan for the SYSTEM.CNF file content
 * Strategy C — Streaming raw scan for a bare BOOT2 / BOOT pattern
 *
 * All strategies stream the file in chunks: never loads the full ISO into RAM.
 * Scanning stops immediately when the ID is found.
 *
 * The UL conversion logic and the architecture contract (IsoEngine) are
 * completely decoupled from the parsing strategies — swapping to
 * [NativeIsoEngine] remains a single-line Hilt binding change.
 */
@Singleton
class KotlinIsoEngine @Inject constructor() : IsoEngine {

    // ─────────────────────────────────────────────────────────────────────────
    // IsoEngine — public API
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun extractGameId(path: String): String =
        withContext(Dispatchers.IO) { resolveGameId(File(path)) ?: "" }

    override suspend fun getIsoInfo(path: String): IsoInfo =
        withContext(Dispatchers.IO) {
            val file = File(path)
            val gameId = resolveGameId(file)
                ?: deriveIdFromFilename(file.nameWithoutExtension)
            val region = GameRegion.fromGameId(gameId)
            IsoInfo(
                gameId = gameId,
                title = file.nameWithoutExtension,
                discType = if (file.length() < CD_MAX_BYTES) DiscType.CD else DiscType.DVD,
                sizeBytes = file.length(),
                region = region.label
            )
        }

    override fun convertToUl(
        input: String,
        output: String,
        resumeOffset: Long
    ): Flow<ConversionProgress> = flow {
        val isoFile = File(input)
        if (!isoFile.exists()) { Timber.e("ISO not found: $input"); return@flow }

        val info = getIsoInfo(input)
        val gameId = info.gameId.ifEmpty { isoFile.nameWithoutExtension }
        val totalBytes = isoFile.length()
        val outputDir = File(output).also { it.mkdirs() }
        val normalizedId = normalizeGameId(gameId)
        val buf = ByteArray(BUFFER_SIZE)
        var bytesWritten = resumeOffset
        var currentPart = (resumeOffset / PART_SIZE).toInt()
        var positionInPart = resumeOffset % PART_SIZE
        var lastEmitMs = System.currentTimeMillis()
        var lastBytes = resumeOffset

        Timber.d("KotlinIsoEngine: convert $gameId total=$totalBytes resume=$resumeOffset")

        FileInputStream(isoFile).use { fis ->
            BufferedInputStream(fis, BUFFER_SIZE).use { bis ->
                if (resumeOffset > 0) {
                    var skipped = 0L
                    while (skipped < resumeOffset) {
                        val n = bis.skip(resumeOffset - skipped)
                        if (n <= 0) break
                        skipped += n
                    }
                }

                var partFile = openPartFile(outputDir, normalizedId, currentPart, positionInPart)

                while (true) {
                    if (!currentCoroutineContext().isActive) {
                        partFile.close()
                        Timber.d("KotlinIsoEngine: cancelled at $bytesWritten")
                        break
                    }
                    val toRead = minOf(
                        buf.size.toLong(),
                        totalBytes - bytesWritten,
                        PART_SIZE - positionInPart
                    ).toInt()
                    if (toRead <= 0) { partFile.close(); break }

                    val read = bis.read(buf, 0, toRead)
                    if (read <= 0) { partFile.close(); break }

                    partFile.write(buf, 0, read)
                    bytesWritten += read
                    positionInPart += read

                    if (positionInPart >= PART_SIZE) {
                        partFile.close()
                        currentPart++
                        positionInPart = 0L
                        if (bytesWritten < totalBytes) {
                            partFile = openPartFile(outputDir, normalizedId, currentPart, 0L)
                        }
                    }

                    val now = System.currentTimeMillis()
                    if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                        val elapsed = (now - lastEmitMs).coerceAtLeast(1L)
                        val delta = bytesWritten - lastBytes
                        val speedMbps = (delta.toDouble() / 1_048_576.0) / (elapsed / 1000.0)
                        val remaining = if (speedMbps > 0.0)
                            ((totalBytes - bytesWritten) / 1_048_576.0 / speedMbps).toLong()
                        else Long.MAX_VALUE
                        emit(ConversionProgress(gameId, input, bytesWritten, totalBytes, currentPart, speedMbps, remaining))
                        lastEmitMs = now
                        lastBytes = bytesWritten
                    }
                }

                if (bytesWritten == totalBytes) {
                    emit(ConversionProgress(gameId, input, bytesWritten, totalBytes, currentPart, 0.0, 0L))
                    Timber.d("KotlinIsoEngine: done $gameId")
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun calculateResumeOffset(outputDir: String, gameId: String): Long {
        val normalizedId = normalizeGameId(gameId)
        return File(outputDir).listFiles { f -> f.name.startsWith("ul.$normalizedId.") }
            ?.sortedBy { it.name }
            ?.sumOf { it.length() }
            ?: 0L
    }

    override fun deletePartFiles(outputDir: String, gameId: String) {
        val normalized = normalizeGameId(gameId)
        File(outputDir).listFiles { f -> f.name.startsWith("ul.$normalized.") }
            ?.forEach { it.delete() }
        Timber.d("Deleted UL parts for $gameId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Game ID resolution — three cascading strategies
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Try all strategies in order and return the first successful result.
     * Returns null only when all three strategies fail.
     */
    private fun resolveGameId(file: File): String? {
        if (!file.exists() || !file.isFile || file.length() < MIN_ISO_SIZE) return null

        val si = detectSectorLayout(file)

        // Strategy A — proper ISO 9660 directory traversal
        runCatching { strategyA_filesystem(file, si) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { Timber.d("[A] $it — ${file.name}") }
            ?.let { return it }

        // Strategy B — raw scan for the SYSTEM.CNF *content* (BOOT2 line)
        runCatching { strategyB_scanSystemCnf(file) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { Timber.d("[B] $it — ${file.name}") }
            ?.let { return it }

        // Strategy C — direct BOOT2 / BOOT pattern scan anywhere in the binary
        runCatching { strategyC_scanBoot2(file) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.also { Timber.d("[C] $it — ${file.name}") }
            ?.let { return it }

        Timber.w("All strategies failed for ${file.name}")
        return null
    }

    // ── Strategy A: ISO 9660 filesystem ──────────────────────────────────────

    private fun strategyA_filesystem(file: File, si: SectorLayout): String? =
        RandomAccessFile(file, "r").use { raf ->
            val root = readRootDirRecord(raf, si) ?: return null
            val cnf = findFileInDir(raf, si, root.lba, root.dataLen, "SYSTEM.CNF")
                ?: findFileInDir(raf, si, root.lba, root.dataLen, "SYSTEM.INI")
                ?: return null
            val text = readSectors(raf, si, cnf.lba, cnf.dataLen.coerceAtMost(4096L))
            BOOT_LINE_REGEX.find(text)?.groupValues?.getOrNull(1)?.uppercase()
        }

    private fun readRootDirRecord(raf: RandomAccessFile, si: SectorLayout): DirRecord? {
        // Try primary sector, then fall back to supplementary descriptors (sectors 17-31)
        for (pvdSector in 16L..31L) {
            runCatching {
                val pvd = readSectors(raf, si, pvdSector, 2048L)
                if (pvd.length >= 7 && pvd[0].code == 0x01 && pvd.substring(1, 6) == "CD001") {
                    val bytes = pvd.toByteArray(Charsets.ISO_8859_1)
                    return parseDirRecord(bytes, 156)
                }
                // Type 0xFF = volume descriptor set terminator
                if (pvd.isNotEmpty() && pvd[0].code == 0xFF) return null
            }
        }
        return null
    }

    private fun findFileInDir(
        raf: RandomAccessFile,
        si: SectorLayout,
        dirLba: Long,
        dirLen: Long,
        target: String
    ): DirRecord? {
        val buf = readSectors(raf, si, dirLba, dirLen.coerceAtMost(MAX_DIR_READ))
            .toByteArray(Charsets.ISO_8859_1)
        var pos = 0
        var safetyCounter = 0
        while (pos < buf.size && safetyCounter++ < MAX_DIR_ENTRIES) {
            val recLen = buf.getOrNull(pos)?.toInt()?.and(0xFF) ?: break
            if (recLen == 0) {
                // Padding to next sector boundary
                val nextSector = ((pos / SECTOR_2048) + 1) * SECTOR_2048
                if (nextSector >= buf.size) break
                pos = nextSector.toInt()
                continue
            }
            if (pos + recLen > buf.size) break
            val rec = parseDirRecord(buf, pos)
            if (rec == null) { pos += recLen; continue }
            if (!rec.isDir && rec.name.equals(target, ignoreCase = true)) return rec
            pos += recLen
        }
        return null
    }

    private fun parseDirRecord(data: ByteArray, offset: Int): DirRecord? {
        val recLen = data.getOrNull(offset)?.toInt()?.and(0xFF) ?: return null
        if (recLen < 33 || offset + recLen > data.size) return null
        val lba = le32(data, offset + 2)
        val dataLen = le32(data, offset + 10)
        val flags = data.getOrNull(offset + 25)?.toInt()?.and(0xFF) ?: return null
        val idLen = data.getOrNull(offset + 32)?.toInt()?.and(0xFF) ?: return null
        if (idLen == 0 || offset + 33 + idLen > data.size) return null
        val name = String(data, offset + 33, idLen, Charsets.ISO_8859_1)
            .trimEnd('\u0000', '\u0001')
            .substringBefore(';')
            .trim()
        return DirRecord(lba, dataLen, (flags and 0x02) != 0, name)
    }

    /** Read [byteCount] bytes starting at logical block [lba], respecting sector layout. */
    private fun readSectors(raf: RandomAccessFile, si: SectorLayout, lba: Long, byteCount: Long): String {
        val toRead = byteCount.coerceAtMost(MAX_READ_BYTES).toInt()
        val buf = ByteArray(toRead)
        val fileOffset = lba * si.sectorSize + si.dataOffset
        if (fileOffset + toRead > raf.length()) return ""
        raf.seek(fileOffset)
        raf.read(buf)
        return String(buf, Charsets.ISO_8859_1)
    }

    // ── Strategy B: raw scan for SYSTEM.CNF content ──────────────────────────

    /**
     * Scan raw bytes for the text pattern "SYSTEM.CNF" and then look for a
     * BOOT2 line in the surrounding 2 KB window.
     * Works on ISOs where the filesystem is broken but the CNF text is intact.
     */
    private fun strategyB_scanSystemCnf(file: File): String? {
        val target = "SYSTEM.CNF".toByteArray(Charsets.ISO_8859_1)
        return streamingSearch(file, RAW_SCAN_LIMIT_BYTES) { window ->
            var idx = window.indexOf(target)
            while (idx >= 0) {
                // Read up to 2 KB forward from the hit position
                val end = minOf(idx + 2048, window.size)
                val context = String(window, idx, end - idx, Charsets.ISO_8859_1)
                val id = BOOT_LINE_REGEX.find(context)?.groupValues?.getOrNull(1)
                if (!id.isNullOrBlank()) return@streamingSearch id.uppercase()
                idx = window.indexOf(target, idx + 1)
            }
            null
        }
    }

    // ── Strategy C: direct BOOT2 / BOOT pattern anywhere in the binary ───────

    /**
     * Scan raw bytes directly for the BOOT2/BOOT assignment pattern.
     * Most reliable fallback — works even when the SYSTEM.CNF file name
     * isn't present but the content was written somewhere in the image.
     */
    private fun strategyC_scanBoot2(file: File): String? =
        streamingSearch(file, RAW_SCAN_LIMIT_BYTES) { window ->
            val text = String(window, Charsets.ISO_8859_1)
            BOOT_LINE_REGEX.find(text)?.groupValues?.getOrNull(1)?.uppercase()
        }

    // ── Streaming search helper ───────────────────────────────────────────────

    /**
     * Stream [file] in [BUFFER_SIZE] chunks up to [maxBytes], calling [finder]
     * on each overlapping window (chunk + [OVERLAP_SIZE] bytes from previous chunk).
     * Returns immediately when [finder] returns a non-null result.
     */
    private fun streamingSearch(
        file: File,
        maxBytes: Long,
        finder: (ByteArray) -> String?
    ): String? {
        val chunk = ByteArray(BUFFER_SIZE)
        var overlap = ByteArray(0)
        var totalRead = 0L

        FileInputStream(file).use { fis ->
            while (totalRead < maxBytes) {
                val toRead = minOf(BUFFER_SIZE.toLong(), maxBytes - totalRead).toInt()
                val read = fis.read(chunk, 0, toRead)
                if (read <= 0) break

                // Combine tail of previous chunk with current chunk to catch boundary patterns
                val window = overlap + chunk.copyOf(read)
                finder(window)?.let { return it }

                // Keep last OVERLAP_SIZE bytes as overlap for next iteration
                overlap = window.copyOfRange(maxOf(0, window.size - OVERLAP_SIZE), window.size)
                totalRead += read
            }
        }
        return null
    }

    // ── Sector layout detection ───────────────────────────────────────────────

    /**
     * Detect whether the file uses standard 2048-byte sectors or raw
     * 2352-byte sectors (BIN/raw CD sector format).
     * Falls back to 2048 on any error.
     */
    private fun detectSectorLayout(file: File): SectorLayout {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (file.length() < 12) return@use SectorLayout(SECTOR_2048, 0L)
                val sync = ByteArray(12)
                raf.seek(0)
                raf.read(sync)
                val isCdSync = sync[0] == 0x00.toByte() &&
                    (1..10).all { sync[it] == 0xFF.toByte() } &&
                    sync[11] == 0x00.toByte()
                if (isCdSync) {
                    raf.seek(15) // Mode byte at offset 15 in the sector header
                    val mode = raf.read()
                    // Mode 2 XA: user data starts at +24; Mode 1: +16
                    val dataOffset = if (mode == 2) 24 else 16
                    SectorLayout(2352L, dataOffset.toLong())
                } else {
                    SectorLayout(SECTOR_2048, 0L)
                }
            }
        }.getOrDefault(SectorLayout(SECTOR_2048, 0L))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UL conversion helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun normalizeGameId(gameId: String): String =
        gameId.replace("_", "").replace(".", "").uppercase()

    private fun openPartFile(dir: File, normalizedId: String, part: Int, offset: Long): RandomAccessFile {
        val f = File(dir, "ul.$normalizedId.${part.toString(16).padStart(2, '0')}")
        val raf = RandomAccessFile(f, "rw")
        if (offset > 0 && f.length() >= offset) raf.seek(offset) else raf.setLength(0L)
        return raf
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun le32(data: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    /** Try to extract a PS2 game ID from the filename (last resort). */
    private fun deriveIdFromFilename(name: String): String =
        GAME_ID_REGEX.find(name)?.value?.uppercase() ?: ""

    /** Find [pattern] byte array inside [haystack], return -1 if not found. */
    private fun ByteArray.indexOf(pattern: ByteArray, start: Int = 0): Int {
        outer@ for (i in start..size - pattern.size) {
            for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal data classes
    // ─────────────────────────────────────────────────────────────────────────

    private data class DirRecord(
        val lba: Long,
        val dataLen: Long,
        val isDir: Boolean,
        val name: String
    )

    /**
     * Describes the byte layout of sectors in this ISO file.
     * @param sectorSize  2048 (standard ISO) or 2352 (raw CD sectors)
     * @param dataOffset  byte offset of user data within each physical sector
     */
    private data class SectorLayout(val sectorSize: Long, val dataOffset: Long)

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    companion object {
        const val PART_SIZE: Long = 1_073_741_824L            // 1 GiB per UL part
        const val BUFFER_SIZE: Int = 4 * 1024 * 1024          // 4 MiB streaming buffer

        private const val SECTOR_2048 = 2048L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val CD_MAX_BYTES = 734_003_200L         // ~700 MB
        private const val MIN_ISO_SIZE = 32_768L              // < 32 KB can't be a valid ISO
        private const val MAX_DIR_READ = 65_536L              // max directory data to read
        private const val MAX_READ_BYTES = 65_536L            // max bytes to read in one readSectors call
        private const val MAX_DIR_ENTRIES = 2048              // safety cap for directory traversal
        private const val RAW_SCAN_LIMIT_BYTES = 128L * 1024 * 1024  // scan at most 128 MB
        private const val OVERLAP_SIZE = 512                  // overlap between scan chunks

        /**
         * Matches all known PS2 game serials in a BOOT/BOOT2 line.
         * Handles:
         *   BOOT2 = cdrom0:\SLUS_012.34;1
         *   BOOT  = cdrom0:\SCES_533.98;1
         *   BOOT2=cdrom0:\SLPS_123.45
         *   BOOT2 = cdrom0:/SCAJ_900.00
         */
        private val BOOT_LINE_REGEX = Regex(
            """BOOT2?\s*=\s*[^\\/]*[\\/]([A-Z]{4}[_]\d{3}\.\d{2})""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )

        /** Matches a bare PS2 serial anywhere (used for filename fallback). */
        private val GAME_ID_REGEX = Regex(
            """[A-Z]{4}[_]\d{3}\.\d{2}""",
            RegexOption.IGNORE_CASE
        )
    }
}
