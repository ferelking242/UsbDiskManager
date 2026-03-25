package com.usbdiskmanager.ps2.engine

import com.usbdiskmanager.ps2.domain.model.ConversionProgress
import com.usbdiskmanager.ps2.domain.model.DiscType
import kotlinx.coroutines.flow.Flow

/**
 * Metadata extracted from an ISO file.
 */
data class IsoInfo(
    val gameId: String,      // PS2 serial, e.g. "SLUS_012.34"
    val title: String,       // Derived from filename or CNF header
    val discType: DiscType,  // CD or DVD
    val sizeBytes: Long,     // Raw ISO size in bytes
    val region: String       // "NTSC-U" | "NTSC-J" | "PAL" | "Unknown"
)

/** Maximum size of a single UL part file (1 GiB). Part of the UL format spec. */
const val UL_PART_SIZE: Long = 1_073_741_824L

/**
 * Engine abstraction for ISO operations.
 *
 * Decouples the repository and scanner from any concrete implementation.
 * Current implementation: [KotlinIsoEngine] (pure JVM, no NDK).
 * Future: [NativeIsoEngine] backed by libcdvd / Open PS2 Loader code via JNI —
 *   swap the Hilt binding in [com.usbdiskmanager.ps2.di.Ps2Module],
 *   zero changes required in the repository or UI.
 */
interface IsoEngine {

    /**
     * Extract the PS2 game serial from the ISO's SYSTEM.CNF.
     * Returns an empty string if not found or on any parsing error.
     */
    suspend fun extractGameId(path: String): String

    /**
     * Return full metadata for an ISO without loading it into memory.
     */
    suspend fun getIsoInfo(path: String): IsoInfo

    /**
     * Convert an ISO to USBExtreme (UL) format by streaming 4 MB chunks.
     *
     * @param input        Absolute path to the source .iso file.
     * @param output       Directory where UL part files will be written.
     * @param resumeOffset Byte offset to resume from; 0 = fresh conversion.
     *
     * Emits [ConversionProgress] updates approximately every 500 ms.
     * The flow completes when all bytes have been written or when the
     * collecting coroutine is cancelled (pause / cancel).
     */
    fun convertToUl(
        input: String,
        output: String,
        resumeOffset: Long = 0L
    ): Flow<ConversionProgress>

    /**
     * Calculate the number of bytes already written to [outputDir] for [gameId].
     * Used by the repository to determine the resume offset after a crash or pause.
     */
    fun calculateResumeOffset(outputDir: String, gameId: String): Long

    /**
     * Delete all UL part files for [gameId] in [outputDir].
     * Called when a conversion is cancelled.
     */
    fun deletePartFiles(outputDir: String, gameId: String)
}
