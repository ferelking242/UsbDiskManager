package com.usbdiskmanager.ps2.engine

import com.usbdiskmanager.ps2.domain.model.ConversionProgress
import com.usbdiskmanager.ps2.domain.model.DiscType
import com.usbdiskmanager.ps2.domain.model.GameRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext
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
 * Pure-Kotlin [IsoEngine] implementation.
 *
 * All logic that was previously scattered between [Iso9660Parser] and [UlConverter]
 * is now consolidated here. The repository and scanner interact only through the
 * [IsoEngine] interface, so this class can be swapped for [NativeIsoEngine] by
 * changing a single Hilt binding — zero impact on the UI or repository layer.
 *
 * @see NativeIsoEngine (future — JNI/NDK bridge to libcdvd / OPL code)
 */
@Singleton
class KotlinIsoEngine @Inject constructor() : IsoEngine {

    // ─────────────────────────────────────────────────────────────────────────
    // IsoEngine — public API
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun extractGameId(path: String): String =
        withContext(Dispatchers.IO) {
            readGameId(File(path)) ?: ""
        }

    override suspend fun getIsoInfo(path: String): IsoInfo =
        withContext(Dispatchers.IO) {
            val file = File(path)
            val gameId = readGameId(file) ?: deriveIdFromFilename(file.nameWithoutExtension)
            val discType = if (file.length() < 734_003_200L) DiscType.CD else DiscType.DVD
            val region = GameRegion.fromGameId(gameId)
            IsoInfo(
                gameId = gameId,
                title = file.nameWithoutExtension,
                discType = discType,
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
        if (!isoFile.exists()) {
            Timber.e("ISO not found: $input")
            return@flow
        }
        val info = getIsoInfo(input)
        val gameId = info.gameId.ifEmpty { isoFile.nameWithoutExtension }
        val totalBytes = isoFile.length()
        val outputDir = File(output).also { it.mkdirs() }
        val normalizedId = normalizeGameId(gameId)
        val buffer = ByteArray(BUFFER_SIZE)
        var bytesWritten = resumeOffset
        var currentPart = (resumeOffset / PART_SIZE).toInt()
        var positionInPart = resumeOffset % PART_SIZE
        var lastEmit = System.currentTimeMillis()
        var lastBytes = resumeOffset

        Timber.d("KotlinIsoEngine: convert $gameId total=$totalBytes resume=$resumeOffset")

        FileInputStream(isoFile).use { fis ->
            BufferedInputStream(fis, BUFFER_SIZE).use { bis ->
                // Skip already-converted bytes
                if (resumeOffset > 0) {
                    var skipped = 0L
                    while (skipped < resumeOffset) {
                        val actual = bis.skip(resumeOffset - skipped)
                        if (actual <= 0) break
                        skipped += actual
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
                        buffer.size.toLong(),
                        totalBytes - bytesWritten,
                        PART_SIZE - positionInPart
                    ).toInt()
                    if (toRead <= 0) { partFile.close(); break }

                    val read = bis.read(buffer, 0, toRead)
                    if (read <= 0) { partFile.close(); break }

                    partFile.write(buffer, 0, read)
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
                    if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                        val elapsed = (now - lastEmit).coerceAtLeast(1L)
                        val delta = bytesWritten - lastBytes
                        val speedMbps = (delta.toDouble() / 1_048_576.0) / (elapsed / 1000.0)
                        val remaining = if (speedMbps > 0.0)
                            ((totalBytes - bytesWritten) / 1_048_576.0 / speedMbps).toLong()
                        else Long.MAX_VALUE
                        emit(ConversionProgress(gameId, input, bytesWritten, totalBytes, currentPart, speedMbps, remaining))
                        lastEmit = now
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
        val dir = File(outputDir)
        if (!dir.exists()) return 0L
        return dir.listFiles { f -> f.name.startsWith("ul.$normalizedId.") }
            ?.sortedBy { it.name }
            ?.sumOf { it.length() }
            ?: 0L
    }

    override fun deletePartFiles(outputDir: String, gameId: String) {
        val normalizedId = normalizeGameId(gameId)
        File(outputDir).listFiles { f -> f.name.startsWith("ul.$normalizedId.") }
            ?.forEach { it.delete() }
        Timber.d("Deleted UL parts for $gameId")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ISO 9660 parsing (formerly Iso9660Parser)
    // ─────────────────────────────────────────────────────────────────────────

    private fun readGameId(file: File): String? {
        if (!file.exists()) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val root = readRootDirRecord(raf) ?: return null
                val cnf = findFile(raf, root.lba, root.length, "SYSTEM.CNF")
                    ?: findFile(raf, root.lba, root.length, "SYSTEM.INI")
                    ?: return null
                val text = readFileBytes(raf, cnf.lba, cnf.length)
                parseBootLine(text)
            }
        } catch (e: Exception) {
            Timber.w(e, "ISO 9660 parse error: ${file.name}")
            null
        }
    }

    private data class DirRecord(val lba: Long, val length: Long, val isDir: Boolean, val name: String)

    private fun readRootDirRecord(raf: RandomAccessFile): DirRecord? {
        raf.seek(PVD_SECTOR * SECTOR_SIZE)
        val pvd = ByteArray(SECTOR_SIZE)
        raf.readFully(pvd)
        if (pvd[0].toInt() != 0x01 || String(pvd, 1, 5) != "CD001") return null
        return parseDirRecord(pvd, 156)
    }

    private fun parseDirRecord(data: ByteArray, offset: Int): DirRecord? {
        val len = data.getOrNull(offset)?.toInt()?.and(0xFF) ?: return null
        if (len < 33 || offset + len > data.size) return null
        val lba = ByteBuffer.wrap(data, offset + 2, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val dataLen = ByteBuffer.wrap(data, offset + 10, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
        val flags = data[offset + 25].toInt() and 0xFF
        val idLen = data[offset + 32].toInt() and 0xFF
        val name = if (idLen > 0 && offset + 33 + idLen <= data.size)
            String(data, offset + 33, idLen, Charsets.ISO_8859_1).trimEnd('\u0000', '\u0001').substringBefore(';')
        else ""
        return DirRecord(lba, dataLen, (flags and 0x02) != 0, name)
    }

    private fun findFile(raf: RandomAccessFile, dirLba: Long, dirLen: Long, target: String): DirRecord? {
        val buf = ByteArray(dirLen.coerceAtMost(65536L).toInt())
        raf.seek(dirLba * SECTOR_SIZE)
        raf.read(buf)
        var pos = 0
        while (pos < buf.size) {
            val recLen = buf[pos].toInt() and 0xFF
            if (recLen == 0) {
                val next = ((pos / SECTOR_SIZE) + 1) * SECTOR_SIZE
                if (next >= buf.size) break
                pos = next; continue
            }
            val rec = parseDirRecord(buf, pos)
            if (rec != null && !rec.isDir && rec.name.equals(target, ignoreCase = true)) return rec
            pos += recLen
        }
        return null
    }

    private fun readFileBytes(raf: RandomAccessFile, lba: Long, length: Long): String {
        val buf = ByteArray(length.coerceAtMost(4096L).toInt())
        raf.seek(lba * SECTOR_SIZE)
        raf.read(buf)
        return String(buf, Charsets.ISO_8859_1)
    }

    private fun parseBootLine(content: String): String? {
        val regex = Regex("""BOOT2?\s*=\s*cdrom[^\\]*\\([A-Z_]{4}\d{3}\.\d{2})""", RegexOption.IGNORE_CASE)
        return regex.find(content)?.groupValues?.getOrNull(1)?.trim()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UL format helpers (formerly UlConverter internals)
    // ─────────────────────────────────────────────────────────────────────────

    private fun normalizeGameId(gameId: String): String =
        gameId.replace("_", "").replace(".", "").uppercase()

    private fun openPartFile(dir: File, normalizedId: String, part: Int, offset: Long): RandomAccessFile {
        val f = File(dir, "ul.$normalizedId.${part.toString(16).padStart(2, '0')}")
        val raf = RandomAccessFile(f, "rw")
        if (offset > 0 && f.length() >= offset) raf.seek(offset) else raf.setLength(0L)
        return raf
    }

    private fun deriveIdFromFilename(name: String): String =
        Regex("[A-Z]{4}[_]\\d{3}\\.\\d{2}", RegexOption.IGNORE_CASE).find(name)?.value?.uppercase() ?: ""

    companion object {
        private const val SECTOR_SIZE = 2048L
        private const val PVD_SECTOR = 16L
        const val PART_SIZE: Long = 1_073_741_824L          // 1 GiB
        const val BUFFER_SIZE: Int = 4 * 1024 * 1024         // 4 MiB
        private const val PROGRESS_INTERVAL_MS: Long = 500L
    }
}
