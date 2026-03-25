package com.usbdiskmanager.ps2.ui

import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.ps2.data.IsoSearchService
import com.usbdiskmanager.ps2.data.Ps2RepositoryImpl
import com.usbdiskmanager.ps2.data.download.DownloadEngine
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import com.usbdiskmanager.ps2.data.transfer.UsbGameTransferManager
import com.usbdiskmanager.ps2.domain.model.ConversionJob
import com.usbdiskmanager.ps2.domain.model.ConversionProgress
import com.usbdiskmanager.ps2.domain.model.DownloadStatus
import com.usbdiskmanager.ps2.domain.model.IsoSearchResult
import com.usbdiskmanager.ps2.domain.model.OutputDestination
import com.usbdiskmanager.ps2.domain.model.Ps2Download
import com.usbdiskmanager.ps2.domain.model.Ps2Game
import com.usbdiskmanager.ps2.domain.model.UsbGame
import com.usbdiskmanager.ps2.domain.repository.Ps2Repository
import com.usbdiskmanager.ps2.ui.transfer.UsbTransferUiState
import com.usbdiskmanager.ps2.util.FilesystemChecker
import com.usbdiskmanager.ps2.util.MountInfo
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
    val activeProgress: Map<String, ConversionProgress> = emptyMap(),
    val selectedGame: Ps2Game? = null,
    val showConvertDialog: Boolean = false,
    val errorMessage: String? = null,
    val selectedTab: Ps2Tab = Ps2Tab.GAMES,
    val isMultiSelectMode: Boolean = false,
    val multiSelectedIds: Set<String> = emptySet(),
    val pendingDestination: OutputDestination = OutputDestination.Default,
    val availableUsbMounts: List<MountInfo> = emptyList(),
    val showFat32Warning: Boolean = false,
    val fat32WarningMount: MountInfo? = null,
    val pendingBatchConversion: Boolean = false,
    val downloads: List<Ps2Download> = emptyList(),
    // Transfer
    val transferState: UsbTransferUiState = UsbTransferUiState(),
    // ISO Search
    val isoSearchQuery: String = "",
    val isoSearchResults: List<IsoSearchResult> = emptyList(),
    val isoSearchLoading: Boolean = false,
    val isoSearchError: String? = null,
    val resolvedDownload: IsoSearchResult? = null,
    val resolvingId: String? = null
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

    val multiSelectedGames: List<Ps2Game>
        get() = games.filter { it.id in multiSelectedIds }
}

enum class SortMode { TITLE, SIZE, STATUS }

enum class Ps2Tab { GAMES, MERGE_CFG, DOWNLOAD, TRANSFER }

@HiltViewModel
class Ps2ViewModel @Inject constructor(
    private val repository: Ps2Repository,
    private val repositoryImpl: Ps2RepositoryImpl,
    private val fsChecker: FilesystemChecker,
    private val downloadEngine: DownloadEngine,
    private val transferManager: UsbGameTransferManager,
    private val searchService: IsoSearchService
) : ViewModel() {

    private val _uiState = MutableStateFlow(Ps2UiState())
    val uiState = _uiState.asStateFlow()

    private val activeConversionJobs = mutableMapOf<String, Job>()
    private val activeDownloadJobs = mutableMapOf<String, Job>()
    private val activeTransferJobs = mutableMapOf<String, Job>()

    init {
        repository.games.onEach { games ->
            _uiState.update { it.copy(games = games) }
        }.launchIn(viewModelScope)

        repository.conversionJobs.onEach { jobs ->
            _uiState.update { it.copy(jobs = jobs) }
        }.launchIn(viewModelScope)

        viewModelScope.launch {
            repository.ensureDirectoryStructure(IsoScanner.BASE_DIR)
            val paths = repository.getScanPaths()
            _uiState.update { it.copy(scanPaths = paths) }
            refreshUsbMounts()
            scan()
        }
    }

    // ── Scanning ────────────────────────────────────────────────────────────

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

    // ── USB Mounts ──────────────────────────────────────────────────────────

    fun refreshUsbMounts() {
        viewModelScope.launch {
            val mounts = fsChecker.listExternalMounts()
            _uiState.update { it.copy(availableUsbMounts = mounts) }
        }
    }

    // ── Transfer: scan ──────────────────────────────────────────────────────

    fun refreshTransferGames() {
        viewModelScope.launch {
            _uiState.update { s -> s.copy(transferState = s.transferState.copy(isLoading = true)) }
            try {
                val mounts = fsChecker.listExternalMounts()
                _uiState.update { it.copy(availableUsbMounts = mounts) }

                val usbGamesMap = mutableMapOf<String, List<UsbGame>>()
                for (mount in mounts) {
                    usbGamesMap[mount.mountPoint] = transferManager.listGamesOnMount(mount.mountPoint)
                }

                // List internal UL games (read ul.cfg from DEFAULT_UL_DIR)
                val internalGames = transferManager.listGamesOnMount(IsoScanner.DEFAULT_UL_DIR)
                    .map { it.copy(mountPoint = IsoScanner.DEFAULT_UL_DIR) }

                _uiState.update { s ->
                    s.copy(
                        transferState = s.transferState.copy(
                            isLoading = false,
                            usbGames = usbGamesMap,
                            internalGames = internalGames,
                            error = null
                        )
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "refreshTransferGames failed")
                _uiState.update { s ->
                    s.copy(
                        transferState = s.transferState.copy(
                            isLoading = false,
                            error = e.message
                        )
                    )
                }
            }
        }
    }

    fun transferUsbToInternal(game: UsbGame) {
        if (activeTransferJobs.containsKey(game.gameId)) return
        val job = transferManager.copyToInternal(game)
            .onEach { progress ->
                _uiState.update { s ->
                    s.copy(transferState = s.transferState.copy(
                        activeTransfers = s.transferState.activeTransfers + (game.gameId to progress)
                    ))
                }
                if (progress.isDone) {
                    activeTransferJobs.remove(game.gameId)
                    scan()
                }
            }
            .catch { e ->
                Timber.e(e, "USB→Internal transfer error: ${game.gameId}")
                activeTransferJobs.remove(game.gameId)
            }
            .launchIn(viewModelScope)
        activeTransferJobs[game.gameId] = job
    }

    fun transferInternalToUsb(game: UsbGame, toMount: String) {
        if (activeTransferJobs.containsKey(game.gameId)) return
        val job = transferManager.copyFromInternalToUsb(game, toMount)
            .onEach { progress ->
                _uiState.update { s ->
                    s.copy(transferState = s.transferState.copy(
                        activeTransfers = s.transferState.activeTransfers + (game.gameId to progress)
                    ))
                }
                if (progress.isDone) activeTransferJobs.remove(game.gameId)
            }
            .catch { e ->
                Timber.e(e, "Internal→USB transfer error: ${game.gameId}")
                activeTransferJobs.remove(game.gameId)
            }
            .launchIn(viewModelScope)
        activeTransferJobs[game.gameId] = job
    }

    fun transferUsbToUsb(game: UsbGame, toMount: String) {
        if (activeTransferJobs.containsKey(game.gameId)) return
        val job = transferManager.directUsbToUsb(game, toMount)
            .onEach { progress ->
                _uiState.update { s ->
                    s.copy(transferState = s.transferState.copy(
                        activeTransfers = s.transferState.activeTransfers + (game.gameId to progress)
                    ))
                }
                if (progress.isDone) activeTransferJobs.remove(game.gameId)
            }
            .catch { e ->
                Timber.e(e, "USB→USB transfer error: ${game.gameId}")
                activeTransferJobs.remove(game.gameId)
            }
            .launchIn(viewModelScope)
        activeTransferJobs[game.gameId] = job
    }

    // ── ISO Search (archive.org) ─────────────────────────────────────────────

    fun searchIso(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(isoSearchResults = emptyList(), isoSearchError = null) }
            return
        }
        _uiState.update { it.copy(isoSearchLoading = true, isoSearchError = null, isoSearchQuery = query) }
        viewModelScope.launch {
            try {
                val results = searchService.search(query)
                _uiState.update { it.copy(isoSearchResults = results, isoSearchLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isoSearchLoading = false,
                    isoSearchError = "Erreur réseau: ${e.message}") }
            }
        }
    }

    fun resolveAndDownload(result: IsoSearchResult) {
        _uiState.update { it.copy(resolvingId = result.identifier) }
        viewModelScope.launch {
            try {
                val resolved = searchService.resolveDownloadUrl(result.identifier)
                if (resolved != null && resolved.downloadUrl.isNotBlank()) {
                    _uiState.update { it.copy(resolvingId = null) }
                    addDownload(resolved.downloadUrl, resolved.fileName.ifBlank { "${result.title}.iso" })
                } else {
                    _uiState.update { it.copy(resolvingId = null,
                        isoSearchError = "Aucun fichier ISO trouvé pour '${result.title}'") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(resolvingId = null,
                    isoSearchError = "Impossible de résoudre le lien: ${e.message}") }
            }
        }
    }

    fun setIsoSearchQuery(q: String) = _uiState.update { it.copy(isoSearchQuery = q) }
    fun clearIsoSearch() = _uiState.update { it.copy(isoSearchResults = emptyList(), isoSearchQuery = "", isoSearchError = null) }

    // ── Multi-select ────────────────────────────────────────────────────────

    fun toggleMultiSelectMode() {
        _uiState.update {
            if (it.isMultiSelectMode) {
                it.copy(isMultiSelectMode = false, multiSelectedIds = emptySet())
            } else {
                it.copy(isMultiSelectMode = true)
            }
        }
    }

    fun toggleGameSelection(game: Ps2Game) {
        _uiState.update { state ->
            val newSet = if (game.id in state.multiSelectedIds) {
                state.multiSelectedIds - game.id
            } else {
                state.multiSelectedIds + game.id
            }
            state.copy(multiSelectedIds = newSet)
        }
    }

    fun selectAllGames() {
        _uiState.update { state ->
            val allIds = state.filteredGames.map { it.id }.toSet()
            state.copy(multiSelectedIds = allIds)
        }
    }

    fun clearSelection() {
        _uiState.update { it.copy(multiSelectedIds = emptySet()) }
    }

    // ── Destination selection ────────────────────────────────────────────────

    fun setDestination(dest: OutputDestination) {
        _uiState.update { it.copy(pendingDestination = dest) }
    }

    fun selectGame(game: Ps2Game?) {
        _uiState.update { it.copy(selectedGame = game, showConvertDialog = game != null) }
    }

    fun dismissDialog() {
        _uiState.update {
            it.copy(
                showConvertDialog = false,
                selectedGame = null,
                showFat32Warning = false,
                fat32WarningMount = null
            )
        }
    }

    fun requestConversionWithDest(destination: OutputDestination) {
        val state = _uiState.value
        _uiState.update { it.copy(pendingDestination = destination) }

        when (destination) {
            is OutputDestination.UsbDrive -> {
                val isFat32 = fsChecker.isFat32(destination.mountPoint)
                if (!isFat32) {
                    val mountInfo = state.availableUsbMounts
                        .firstOrNull { it.mountPoint == destination.mountPoint }
                    _uiState.update {
                        it.copy(showFat32Warning = true, fat32WarningMount = mountInfo)
                    }
                    return
                }
                proceedWithConversion()
            }
            else -> proceedWithConversion()
        }
    }

    fun proceedDespiteFat32Warning() {
        _uiState.update { it.copy(showFat32Warning = false, fat32WarningMount = null) }
        proceedWithConversion()
    }

    private fun proceedWithConversion() {
        val state = _uiState.value
        val dest = state.pendingDestination
        if (state.isMultiSelectMode && state.multiSelectedIds.isNotEmpty()) {
            startBatchConversion(state.multiSelectedGames, dest)
        } else if (state.selectedGame != null) {
            startConversionInternal(state.selectedGame, dest)
        }
        _uiState.update { it.copy(showConvertDialog = false, selectedGame = null) }
    }

    // ── Conversion ──────────────────────────────────────────────────────────

    fun startConversion(game: Ps2Game) {
        val dest = _uiState.value.pendingDestination
        startConversionInternal(game, dest)
        _uiState.update { it.copy(showConvertDialog = false, selectedGame = null) }
    }

    private fun startBatchConversion(games: List<Ps2Game>, dest: OutputDestination) {
        games.forEach { game -> startConversionInternal(game, dest) }
        _uiState.update { it.copy(isMultiSelectMode = false, multiSelectedIds = emptySet()) }
    }

    private fun startConversionInternal(game: Ps2Game, destination: OutputDestination) {
        val isoPath = game.isoPath
        if (activeConversionJobs.containsKey(isoPath)) return
        val outputDir = destination.resolvedPath(IsoScanner.DEFAULT_UL_DIR)

        viewModelScope.launch { repositoryImpl.createJob(isoPath, outputDir) }

        val job = repository.convertToUl(isoPath, outputDir)
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

    // ── Cover art ────────────────────────────────────────────────────────────

    fun fetchCover(game: Ps2Game) {
        viewModelScope.launch {
            repository.fetchCoverArt(game.gameId, game.region, IsoScanner.DEFAULT_ART_DIR)
        }
    }

    fun fetchAllCovers() {
        viewModelScope.launch {
            _uiState.value.games.forEach { game ->
                if (game.coverPath == null) {
                    try {
                        repository.fetchCoverArt(game.gameId, game.region, IsoScanner.DEFAULT_ART_DIR)
                    } catch (e: Exception) {
                        Timber.w(e, "fetchAllCovers: skipped ${game.gameId}")
                    }
                }
            }
        }
    }

    // ── Download manager ─────────────────────────────────────────────────────

    fun addDownload(url: String, customFileName: String? = null) {
        val fileName = customFileName?.takeIf { it.isNotBlank() }
            ?: downloadEngine.resolveFileName(url)
        val outputPath = downloadEngine.defaultOutputPath(fileName)
        val item = Ps2Download(url = url, fileName = fileName, outputPath = outputPath)
        _uiState.update { it.copy(downloads = it.downloads + item) }
        startDownload(item)
    }

    private fun startDownload(item: Ps2Download) {
        val job = downloadEngine.download(item)
            .onEach { updated ->
                _uiState.update { state ->
                    state.copy(downloads = state.downloads.map {
                        if (it.id == updated.id) updated else it
                    })
                }
                if (updated.status == DownloadStatus.COMPLETED) {
                    activeDownloadJobs.remove(item.id)
                    scan()
                }
            }
            .catch { e ->
                Timber.e(e, "Download error: ${item.url}")
                _uiState.update { state ->
                    state.copy(downloads = state.downloads.map {
                        if (it.id == item.id) it.copy(status = DownloadStatus.ERROR, errorMessage = e.message)
                        else it
                    })
                }
                activeDownloadJobs.remove(item.id)
            }
            .launchIn(viewModelScope)
        activeDownloadJobs[item.id] = job
    }

    fun pauseDownload(id: String) {
        activeDownloadJobs[id]?.cancel()
        activeDownloadJobs.remove(id)
        _uiState.update { state ->
            state.copy(downloads = state.downloads.map {
                if (it.id == id) it.copy(status = DownloadStatus.PAUSED) else it
            })
        }
    }

    fun resumeDownload(id: String) {
        val item = _uiState.value.downloads.firstOrNull { it.id == id } ?: return
        if (activeDownloadJobs.containsKey(id)) return
        startDownload(item.copy(status = DownloadStatus.DOWNLOADING))
    }

    fun removeDownload(id: String) {
        activeDownloadJobs[id]?.cancel()
        activeDownloadJobs.remove(id)
        _uiState.update { state ->
            state.copy(downloads = state.downloads.filter { it.id != id })
        }
    }

    fun retryDownload(id: String) {
        val item = _uiState.value.downloads.firstOrNull { it.id == id } ?: return
        val reset = item.copy(status = DownloadStatus.QUEUED, errorMessage = null)
        _uiState.update { state ->
            state.copy(downloads = state.downloads.map { if (it.id == id) reset else it })
        }
        startDownload(reset)
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    fun setSearch(query: String) = _uiState.update { it.copy(searchQuery = query) }
    fun setSortMode(mode: SortMode) = _uiState.update { it.copy(sortMode = mode) }
    fun setTab(tab: Ps2Tab) {
        _uiState.update { it.copy(selectedTab = tab) }
        if (tab == Ps2Tab.TRANSFER) refreshTransferGames()
    }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }
}
