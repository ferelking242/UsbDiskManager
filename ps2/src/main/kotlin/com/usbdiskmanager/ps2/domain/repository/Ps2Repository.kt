package com.usbdiskmanager.ps2.domain.repository

import com.usbdiskmanager.ps2.domain.model.ConversionJob
import com.usbdiskmanager.ps2.domain.model.ConversionProgress
import com.usbdiskmanager.ps2.domain.model.Ps2Game
import kotlinx.coroutines.flow.Flow

interface Ps2Repository {
    /** All games found in the configured scan paths. */
    val games: Flow<List<Ps2Game>>

    /** All conversion jobs (active + history). */
    val conversionJobs: Flow<List<ConversionJob>>

    /** Scan all registered ISO directories and update the games list. */
    suspend fun scanIsoDirectories()

    /** Add a directory path to the scan list. */
    suspend fun addScanPath(path: String)

    /** Remove a scan path. */
    suspend fun removeScanPath(path: String)

    /** Get all registered scan paths. */
    suspend fun getScanPaths(): List<String>

    /** Start converting an ISO to UL format. Returns a flow of progress updates. */
    fun convertToUl(isoPath: String, outputDir: String): Flow<ConversionProgress>

    /** Pause an active conversion. */
    suspend fun pauseConversion(isoPath: String)

    /** Resume a paused conversion. Returns flow of progress. */
    fun resumeConversion(isoPath: String): Flow<ConversionProgress>

    /** Cancel and clean up a conversion job. */
    suspend fun cancelConversion(isoPath: String)

    /** Download cover art for a game and store locally. */
    suspend fun fetchCoverArt(gameId: String, region: String, outputDir: String): String?

    /** Get a game by its ISO path. */
    suspend fun getGame(isoPath: String): Ps2Game?

    /** Get a conversion job by ISO path. */
    suspend fun getJob(isoPath: String): ConversionJob?

    /** Ensure the default PS2Manager folder structure exists. */
    suspend fun ensureDirectoryStructure(basePath: String)
}
