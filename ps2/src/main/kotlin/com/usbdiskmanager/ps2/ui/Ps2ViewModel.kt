package com.usbdiskmanager.ps2.ui

import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.ps2.data.Ps2RepositoryImpl
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import com.usbdiskmanager.ps2.domain.model.ConversionJob
import com.usbdiskmanager.ps2.domain.model.ConversionProgress
import com.usbdiskmanager.ps2.domain.model.Ps2Game
import com.usbdiskmanager.ps2.domain.repository.Ps2Repository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class Ps2UiState(
    val games: List<Ps2Game> = emptyList(),
    val jobs: List<ConversionJob> = emptyList(),
    val searchQuery: String = "",
    val sortMode: SortMode = SortMode.TITLE,
    val isScanning: Boolean = false,
    val scanPaths: List<String> = emptyList(),
    val activeProgress: Map<String, ConversionProgress> = emptyMap(), // key = isoPath
    val selectedGame: Ps2Game? = null,
    val showConvertDialog: Boolean = false,
    val errorMessage: String? = null,
    val firstLaunch: Boolean = false
) {
    val filteredGames: List<Ps2Game>
        get() {
            val filtered = if (searchQuery.isBlank()) games
            else games.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.gameId.contains(searchQuery, ignoreCase = true)
            }
            return when (sortMode) {
                SortMode.TITLE  -> filtered.sortedBy { it.title }
                SortMode.SIZE   -> filtered.sortedByDescending { it.sizeMb }
                SortMode.STATUS -> filtered.sortedBy { it.conversionStatus.ordinal }
            }
        }
}

enum class SortMode { TITLE, SIZE, STATUS }

@HiltViewModel
class Ps2ViewModel @Inject constructor(
    private val repository: Ps2Repository,
    private val repositoryImpl: Ps2RepositoryImpl
) : ViewModel() {

    private val _uiState = MutableStateFlow(Ps2UiState())
    val uiState = _uiState.asStateFlow()

    private val activeConversionJobs = mutableMapOf<String, Job>()

    init {
        repository.games.onEach { games ->
            _uiState.update { it.copy(games = games) }
        }.launchIn(viewModelScope)

        repository.conversionJobs.onEach { jobs ->
            _uiState.update { it.copy(jobs = jobs) }
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            repository.ensureDirectoryStructure(BASE_DIR)
            val paths = repository.getScanPaths()
            _uiState.update { it.copy(scanPaths = paths) }
            scan()
        }
    }

    // ────────────────────────────────────────────
    // Scanning
    // ────────────────────────────────────────────

    fun scan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            try {
                repository.scanIsoDirectories()
            } catch (e: Exception) {
                Timber.e(e, "Scan failed")
                _uiState.update { it.copy(errorMessage = e.message) }
            } finally {
                _uiState.update { it.copy(isScanning = false) }
            }
        }
    }

    fun addScanPath(path: String) {
        viewModelScope.launch {
            repository.addScanPath(path)
            val paths = repository.getScanPaths()
            _uiState.update { it.copy(scanPaths = paths) }
            scan()
        }
    }

    fun addScanUri(uri: Uri) {
        // Resolve content:// URI path and add
        val path = uri.path ?: return
        val resolved = when {
            path.contains("primary:") ->
                "${Environment.getExternalStorageDirectory()}/${path.substringAfter("primary:")}"
            else -> path
        }
        addScanPath(resolved)
    }

    fun removeScanPath(path: String) {
        viewModelScope.launch {
            repository.removeScanPath(path)
            val paths = repository.getScanPaths()
            _uiState.update { it.copy(scanPaths = paths) }
        }
    }

    // ────────────────────────────────────────────
    // Conversion
    // ────────────────────────────────────────────

    fun startConversion(game: Ps2Game) {
        val isoPath = game.isoPath
        if (activeConversionJobs.containsKey(isoPath)) return
        viewModelScope.launch {
            repositoryImpl.createJob(isoPath, IsoScanner.DEFAULT_UL_DIR)
        }
        val job = repository.convertToUl(isoPath, IsoScanner.DEFAULT_UL_DIR)
            .onEach { progress ->
                _uiState.update { state ->
                    state.copy(activeProgress = state.activeProgress + (isoPath to progress))
                }
                if (progress.isComplete) {
                    activeConversionJobs.remove(isoPath)
                    _uiState.update { state ->
                        state.copy(activeProgress = state.activeProgress - isoPath)
                    }
                }
            }
            .catch { e ->
                Timber.e(e, "Conversion error for $isoPath")
                _uiState.update { it.copy(errorMessage = e.message) }
                activeConversionJobs.remove(isoPath)
            }
            .launchIn(viewModelScope)

        activeConversionJobs[isoPath] = job
        _uiState.update { it.copy(showConvertDialog = false, selectedGame = null) }
    }

    fun pauseConversion(isoPath: String) {
        activeConversionJobs[isoPath]?.cancel()
        activeConversionJobs.remove(isoPath)
        viewModelScope.launch {
            repository.pauseConversion(isoPath)
            _uiState.update { state ->
                state.copy(activeProgress = state.activeProgress - isoPath)
            }
        }
    }

    fun resumeConversion(isoPath: String) {
        if (activeConversionJobs.containsKey(isoPath)) return
        val job = repository.resumeConversion(isoPath)
            .onEach { progress ->
                _uiState.update { state ->
                    state.copy(activeProgress = state.activeProgress + (isoPath to progress))
                }
                if (progress.isComplete) {
                    activeConversionJobs.remove(isoPath)
                    _uiState.update { state ->
                        state.copy(activeProgress = state.activeProgress - isoPath)
                    }
                }
            }
            .catch { e ->
                Timber.e(e, "Resume error for $isoPath")
                activeConversionJobs.remove(isoPath)
            }
            .launchIn(viewModelScope)

        activeConversionJobs[isoPath] = job
    }

    fun cancelConversion(isoPath: String) {
        activeConversionJobs[isoPath]?.cancel()
        activeConversionJobs.remove(isoPath)
        viewModelScope.launch {
            repository.cancelConversion(isoPath)
            _uiState.update { state ->
                state.copy(activeProgress = state.activeProgress - isoPath)
            }
        }
    }

    // ────────────────────────────────────────────
    // Cover art
    // ────────────────────────────────────────────

    fun fetchCover(game: Ps2Game) {
        viewModelScope.launch {
            repository.fetchCoverArt(game.gameId, game.region, IsoScanner.DEFAULT_ART_DIR)
        }
    }

    // ────────────────────────────────────────────
    // UI state helpers
    // ────────────────────────────────────────────

    fun setSearch(query: String) = _uiState.update { it.copy(searchQuery = query) }
    fun setSortMode(mode: SortMode) = _uiState.update { it.copy(sortMode = mode) }
    fun selectGame(game: Ps2Game?) = _uiState.update { it.copy(selectedGame = game, showConvertDialog = game != null) }
    fun dismissDialog() = _uiState.update { it.copy(showConvertDialog = false, selectedGame = null) }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    companion object {
        private val BASE_DIR =
            "${Environment.getExternalStorageDirectory()}/PS2Manager"
    }
}
