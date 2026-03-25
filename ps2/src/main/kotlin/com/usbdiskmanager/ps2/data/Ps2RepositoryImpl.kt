package com.usbdiskmanager.ps2.data

import android.content.Context
import android.os.Environment
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.usbdiskmanager.ps2.data.converter.UlCfgManager
import com.usbdiskmanager.ps2.data.cover.CoverArtFetcher
import com.usbdiskmanager.ps2.data.db.ConversionJobDao
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import com.usbdiskmanager.ps2.domain.model.ConversionJob
import com.usbdiskmanager.ps2.domain.model.ConversionProgress
import com.usbdiskmanager.ps2.domain.model.ConversionStatus
import com.usbdiskmanager.ps2.domain.model.DiscType
import com.usbdiskmanager.ps2.domain.model.Ps2Game
import com.usbdiskmanager.ps2.domain.repository.Ps2Repository
import com.usbdiskmanager.ps2.engine.IsoEngine
import com.usbdiskmanager.ps2.engine.UL_PART_SIZE
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val Context.ps2DataStore by preferencesDataStore(name = "ps2_prefs")

@Singleton
class Ps2RepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: IsoScanner,
    private val engine: IsoEngine,          // ← engine only, no concrete converter
    private val cfgManager: UlCfgManager,
    private val coverFetcher: CoverArtFetcher,
    private val jobDao: ConversionJobDao
) : Ps2Repository {

    companion object {
        private val SCAN_PATHS_KEY = stringSetPreferencesKey("scan_paths")
    }

    private val _games = MutableStateFlow<List<Ps2Game>>(emptyList())
    override val games: Flow<List<Ps2Game>> = _games.asStateFlow()

    override val conversionJobs: Flow<List<ConversionJob>> = jobDao.observeAll()

    // ─────────────────────────────────────────────────────────────────────────
    // Scanning
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun scanIsoDirectories() {
        val paths = getScanPaths()
        val found = scanner.scanDirectories(paths)
        val resumableJobs = jobDao.getResumable().associate { it.isoPath to it }
        _games.value = found.map { game ->
            resumableJobs[game.isoPath]?.let { job ->
                game.copy(conversionStatus = statusFromJobStatus(job.status))
            } ?: game
        }
        Timber.d("Scan complete: ${found.size} ISO(s)")
    }

    override suspend fun addScanPath(path: String) {
        context.ps2DataStore.edit { prefs ->
            prefs[SCAN_PATHS_KEY] = (prefs[SCAN_PATHS_KEY] ?: setOf(IsoScanner.DEFAULT_ISO_DIR)) + path
        }
    }

    override suspend fun removeScanPath(path: String) {
        context.ps2DataStore.edit { prefs ->
            prefs[SCAN_PATHS_KEY] = (prefs[SCAN_PATHS_KEY] ?: emptySet()) - path
        }
    }

    override suspend fun getScanPaths(): List<String> {
        var paths: Set<String> = setOf(IsoScanner.DEFAULT_ISO_DIR)
        context.ps2DataStore.edit { prefs ->
            if (!prefs.contains(SCAN_PATHS_KEY)) prefs[SCAN_PATHS_KEY] = setOf(IsoScanner.DEFAULT_ISO_DIR)
            paths = prefs[SCAN_PATHS_KEY] ?: setOf(IsoScanner.DEFAULT_ISO_DIR)
        }
        return paths.toList()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Conversion — all streaming via IsoEngine, no concrete UlConverter
    // ─────────────────────────────────────────────────────────────────────────

    override fun convertToUl(isoPath: String, outputDir: String): Flow<ConversionProgress> {
        val game = _games.value.firstOrNull { it.isoPath == isoPath }
        val gameId = game?.gameId ?: File(isoPath).nameWithoutExtension
        val gameName = game?.title ?: File(isoPath).nameWithoutExtension
        val isCD = game?.discType == DiscType.CD

        return engine.convertToUl(isoPath, outputDir, resumeOffset = 0L)
            .onEach { progress ->
                val status = if (progress.isComplete) "DONE" else "RUNNING"
                jobDao.updateProgress(isoPath, progress.bytesWritten, progress.currentPart, status)
                if (progress.isComplete) {
                    val parts = partCount(progress.totalBytes)
                    cfgManager.addOrUpdateEntry(outputDir, gameId, gameName, parts, isCD)
                    updateGameStatus(isoPath, ConversionStatus.COMPLETED)
                } else {
                    updateGameStatus(isoPath, ConversionStatus.IN_PROGRESS)
                }
            }
            .onCompletion { err ->
                if (err != null) {
                    jobDao.updateStatus(isoPath, "ERROR", err.message ?: "Unknown")
                    updateGameStatus(isoPath, ConversionStatus.ERROR)
                }
            }
    }

    override suspend fun pauseConversion(isoPath: String) {
        jobDao.updateStatus(isoPath, "PAUSED")
        updateGameStatus(isoPath, ConversionStatus.PAUSED)
    }

    override fun resumeConversion(isoPath: String): Flow<ConversionProgress> = flow {
        val job = jobDao.getByPath(isoPath) ?: return@flow
        val game = _games.value.firstOrNull { it.isoPath == isoPath }
        val isCD = game?.discType == DiscType.CD

        // Resume offset is computed by the engine (engine knows the UL part naming scheme)
        val resumeOffset = engine.calculateResumeOffset(job.outputDir, job.gameId)

        emitAll(
            engine.convertToUl(isoPath, job.outputDir, resumeOffset)
                .onEach { progress ->
                    val status = if (progress.isComplete) "DONE" else "RUNNING"
                    jobDao.updateProgress(isoPath, progress.bytesWritten, progress.currentPart, status)
                    if (progress.isComplete) {
                        val parts = partCount(progress.totalBytes)
                        cfgManager.addOrUpdateEntry(job.outputDir, job.gameId, job.gameTitle, parts, isCD)
                        updateGameStatus(isoPath, ConversionStatus.COMPLETED)
                    } else {
                        updateGameStatus(isoPath, ConversionStatus.IN_PROGRESS)
                    }
                }
                .onCompletion { err ->
                    if (err != null) {
                        jobDao.updateStatus(isoPath, "ERROR", err.message ?: "")
                        updateGameStatus(isoPath, ConversionStatus.ERROR)
                    }
                }
        )
    }

    override suspend fun cancelConversion(isoPath: String) {
        val job = jobDao.getByPath(isoPath) ?: return
        engine.deletePartFiles(job.outputDir, job.gameId)          // engine cleans its own files
        cfgManager.removeEntry(job.outputDir, job.gameId)
        jobDao.delete(isoPath)
        updateGameStatus(isoPath, ConversionStatus.NOT_CONVERTED)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Cover art
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun fetchCoverArt(gameId: String, region: String, outputDir: String): String? {
        val path = coverFetcher.fetchCover(gameId, region, IsoScanner.DEFAULT_ART_DIR)
        if (path != null) {
            _games.update { list -> list.map { g -> if (g.gameId == gameId) g.copy(coverPath = path) else g } }
        }
        return path
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Misc
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun getGame(isoPath: String): Ps2Game? = _games.value.firstOrNull { it.isoPath == isoPath }
    override suspend fun getJob(isoPath: String): ConversionJob? = jobDao.getByPath(isoPath)

    override suspend fun ensureDirectoryStructure(basePath: String) {
        scanner.ensureStructure(basePath)
    }

    /** Called by the ViewModel before starting a fresh conversion to create the checkpoint row. */
    suspend fun createJob(isoPath: String, outputDir: String) {
        val isoFile = File(isoPath)
        val game = _games.value.firstOrNull { it.isoPath == isoPath }
        val gameId = game?.gameId ?: isoFile.nameWithoutExtension
        jobDao.upsert(
            ConversionJob(
                isoPath = isoPath,
                gameId = gameId,
                gameTitle = game?.title ?: isoFile.nameWithoutExtension,
                outputDir = outputDir,
                totalBytes = isoFile.length(),
                status = "RUNNING"
            )
        )
        updateGameStatus(isoPath, ConversionStatus.IN_PROGRESS)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateGameStatus(isoPath: String, status: ConversionStatus) {
        _games.update { list -> list.map { g -> if (g.isoPath == isoPath) g.copy(conversionStatus = status) else g } }
    }

    private fun statusFromJobStatus(s: String): ConversionStatus = when (s) {
        "DONE"    -> ConversionStatus.COMPLETED
        "RUNNING" -> ConversionStatus.IN_PROGRESS
        "PAUSED"  -> ConversionStatus.PAUSED
        "ERROR"   -> ConversionStatus.ERROR
        else      -> ConversionStatus.NOT_CONVERTED
    }

    private fun partCount(totalBytes: Long): Int =
        ((totalBytes + UL_PART_SIZE - 1) / UL_PART_SIZE).toInt()
}
