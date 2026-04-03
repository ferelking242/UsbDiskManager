package com.usbdiskmanager.ps2.ui

import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.ps2.data.IsoSearchService
import com.usbdiskmanager.ps2.data.Ps2RepositoryImpl
import com.usbdiskmanager.ps2.data.converter.UlCfgManager
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
import com.usbdiskmanager.ps2.data.download.TelegramDownloadManager
import com.usbdiskmanager.ps2.data.download.TgDownloadProgress
import com.usbdiskmanager.ps2.telegram.TelegramChannelConfig
import com.usbdiskmanager.ps2.telegram.TelegramChannelService
import com.usbdiskmanager.ps2.telegram.TelegramGamePost
import com.usbdiskmanager.ps2.telegram.TelegramSetupState
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
import java.io.File
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
    val resolvingId: String? = null,
    // UL Manager
    val ulManagerMount: String? = null,
    val ulGames: List<UsbGame> = emptyList(),
    val ulManagerLoading: Boolean = false,
    val ulManagerError: String? = null,
    // Transfer multi-select
    val transferSelectedIds: Set<String> = emptySet(),
    val transferSourceMount: String? = null,
    // Telegram
    val telegramState: TelegramUiState = TelegramUiState()
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

enum class Ps2Tab { GAMES, MERGE_CFG, UL_MANAGER, DOWNLOAD, TRANSFER, TELEGRAM }

data class TelegramUiState(
    val isConfigured: Boolean = false,
    val setupState: TelegramSetupState = TelegramSetupState.NotConfigured,
    val channels: List<TelegramChannelConfig> = emptyList(),
    val selectedChannel: String? = null,
    val allPosts: List<TelegramGamePost> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val downloads: Map<String, TgDownloadProgress> = emptyMap(),
    val usingTDLib: Boolean = false,
    // Last TDLib message ID per channel, used for "load more" pagination
    val lastTdlibIdByChannel: Map<String, Long> = emptyMap()
)

@HiltViewModel
class Ps2ViewModel @Inject constructor(
    private val repository: Ps2Repository,
    private val repositoryImpl: Ps2RepositoryImpl,
    private val fsChecker: FilesystemChecker,
    private val downloadEngine: DownloadEngine,
    private val transferManager: UsbGameTransferManager,
    private val searchService: IsoSearchService,
    private val ulCfgManager: UlCfgManager,
    private val isoScanner: IsoScanner,
    private val telegramService: TelegramChannelService,
    private val telegramDownloadManager: TelegramDownloadManager
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
            scanPhone()
        }

        // Initialize TDLib if credentials already saved
        telegramService.initializeTDLib()

        // Observe TDLib auth state → update UI
        telegramService.tdlibAuthState.onEach { _ ->
            val setupState = telegramService.getSetupState()
            val isReady = setupState is TelegramSetupState.Ready
            _uiState.update { s ->
                s.copy(
                    telegramState = s.telegramState.copy(
                        setupState = setupState,
                        isConfigured = isReady
                    )
                )
            }
            if (isReady && _uiState.value.telegramState.allPosts.isEmpty()) {
                refreshTelegramPosts()
            }
        }.launchIn(viewModelScope)

        // Observe download progress from TDLib
        telegramDownloadManager.downloads.onEach { progressMap ->
            _uiState.update { s ->
                s.copy(telegramState = s.telegramState.copy(downloads = progressMap))
            }
            // Auto-scan when a download completes
            progressMap.values.filter { it.isDone }.forEach { _ -> scan() }
        }.launchIn(viewModelScope)
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

    fun scanPhone() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true) }
            try {
                val phoneGames = isoScanner.scanPhoneStorage()
                val existing = _uiState.value.games
                val existingPaths = existing.map { it.isoPath }.toSet()
                val newGames = phoneGames.filter { it.isoPath !in existingPaths }
                if (newGames.isNotEmpty()) {
                    _uiState.update { s ->
                        s.copy(games = (s.games + newGames).sortedBy { it.title })
                    }
                    newGames.forEach { repository.addScanPath(it.isoPath.substringBeforeLast('/')) }
                }
            } catch (e: Exception) {
                Timber.e(e, "Phone scan failed")
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

    // ── Transfer multi-select ────────────────────────────────────────────────

    fun setTransferSourceMount(mountPoint: String?) {
        _uiState.update { it.copy(transferSourceMount = mountPoint, transferSelectedIds = emptySet()) }
    }

    fun toggleTransferSelection(game: UsbGame) {
        _uiState.update { s ->
            val ids = s.transferSelectedIds.toMutableSet()
            if (game.gameId in ids) ids.remove(game.gameId) else ids.add(game.gameId)
            s.copy(transferSelectedIds = ids)
        }
    }

    fun selectAllTransferGames() {
        val mount = _uiState.value.transferSourceMount ?: return
        val games = _uiState.value.transferState.usbGames[mount] ?: return
        _uiState.update { it.copy(transferSelectedIds = games.map { g -> g.gameId }.toSet()) }
    }

    fun clearTransferSelection() {
        _uiState.update { it.copy(transferSelectedIds = emptySet()) }
    }

    fun batchTransferToInternal() {
        val mount = _uiState.value.transferSourceMount ?: return
        val games = _uiState.value.transferState.usbGames[mount] ?: return
        val selected = _uiState.value.transferSelectedIds
        games.filter { it.gameId in selected }.forEach { transferUsbToInternal(it) }
        _uiState.update { it.copy(transferSelectedIds = emptySet()) }
    }

    fun batchTransferToMount(targetMount: String) {
        val mount = _uiState.value.transferSourceMount ?: return
        val games = _uiState.value.transferState.usbGames[mount] ?: return
        val selected = _uiState.value.transferSelectedIds
        games.filter { it.gameId in selected }.forEach { transferUsbToUsb(it, targetMount) }
        _uiState.update { it.copy(transferSelectedIds = emptySet()) }
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

    // ── UL Manager ───────────────────────────────────────────────────────────

    fun selectUlManagerMount(mountPoint: String) {
        _uiState.update { it.copy(ulManagerMount = mountPoint, ulGames = emptyList(), ulManagerError = null) }
        loadUlGames(mountPoint)
    }

    fun loadUlGames(mountPoint: String) {
        _uiState.update { it.copy(ulManagerLoading = true, ulManagerError = null) }
        viewModelScope.launch {
            try {
                val cfgFile = java.io.File(mountPoint, "ul.cfg")
                if (!cfgFile.exists()) {
                    _uiState.update { it.copy(ulManagerLoading = false, ulGames = emptyList(),
                        ulManagerError = "Aucun fichier ul.cfg trouvé dans $mountPoint") }
                    return@launch
                }
                val entries = ulCfgManager.readAllEntries(cfgFile)
                val games = entries.map { entry ->
                    val partFiles = java.io.File(mountPoint).listFiles { f ->
                        f.name.startsWith("ul.${entry.gameIdClean}.") || f.name.startsWith("ul.${entry.gameId}.")
                    }?.map { it.name } ?: emptyList()
                    val size = partFiles.sumOf { name ->
                        try { java.io.File(mountPoint, name).length() } catch (_: Exception) { 0L }
                    }
                    UsbGame(
                        gameName = entry.gameName,
                        gameId = entry.gameIdClean,
                        numParts = entry.numParts,
                        isCd = entry.isCd,
                        mountPoint = mountPoint,
                        partFiles = partFiles,
                        sizeBytes = size
                    )
                }
                _uiState.update { it.copy(ulManagerLoading = false, ulGames = games) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load UL games from $mountPoint")
                _uiState.update { it.copy(ulManagerLoading = false,
                    ulManagerError = "Erreur de lecture: ${e.message}") }
            }
        }
    }

    fun renameUlGame(game: UsbGame, newName: String) {
        val mountPoint = game.mountPoint
        viewModelScope.launch {
            try {
                ulCfgManager.addOrUpdateEntry(
                    outputDir = mountPoint,
                    gameId = game.gameId,
                    gameName = newName,
                    numParts = game.numParts,
                    isCD = game.isCd
                )
                loadUlGames(mountPoint)
            } catch (e: Exception) {
                _uiState.update { it.copy(ulManagerError = "Erreur renommage: ${e.message}") }
            }
        }
    }

    fun deleteUlGame(game: UsbGame) {
        val mountPoint = game.mountPoint
        viewModelScope.launch {
            try {
                ulCfgManager.removeEntry(mountPoint, game.gameId)
                val dir = java.io.File(mountPoint)
                dir.listFiles { f ->
                    f.name.startsWith("ul.${game.gameId}.") || f.name.startsWith("ul.${game.gameId}")
                }?.forEach { it.delete() }
                loadUlGames(mountPoint)
            } catch (e: Exception) {
                _uiState.update { it.copy(ulManagerError = "Erreur suppression: ${e.message}") }
            }
        }
    }

    fun clearUlManagerError() = _uiState.update { it.copy(ulManagerError = null) }

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
        if (tab == Ps2Tab.TELEGRAM && _uiState.value.telegramState.isConfigured && _uiState.value.telegramState.allPosts.isEmpty()) refreshTelegramPosts()
    }
    fun clearError() = _uiState.update { it.copy(errorMessage = null) }

    // ── Telegram ─────────────────────────────────────────────────────────────

    fun saveTelegramCredentials(apiId: Int, apiHash: String) {
        telegramService.saveCredentials(apiId, apiHash)
        val channels = telegramService.getSavedChannels()
        _uiState.update { s ->
            s.copy(telegramState = s.telegramState.copy(
                setupState = TelegramSetupState.WaitingPhoneNumber,
                channels = channels,
                error = null
            ))
        }
    }

    fun sendTelegramPhone(phone: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            telegramService.sendPhone(phone).fold(
                onSuccess = {
                    _uiState.update { s ->
                        s.copy(telegramState = s.telegramState.copy(
                            setupState = TelegramSetupState.WaitingCode(phone),
                            error = null
                        ))
                    }
                },
                onFailure = { e -> onError(e.message ?: "Erreur envoi téléphone") }
            )
        }
    }

    fun sendTelegramCode(code: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            telegramService.sendCode(code).fold(
                onSuccess = { /* auth state update handled by tdlibAuthState observer */ },
                onFailure = { e -> onError(e.message ?: "Code invalide") }
            )
        }
    }

    fun sendTelegramPassword(password: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            telegramService.sendPassword(password).fold(
                onSuccess = { /* auth state update handled by tdlibAuthState observer */ },
                onFailure = { e -> onError(e.message ?: "Mot de passe invalide") }
            )
        }
    }

    fun disconnectTelegram() {
        viewModelScope.launch {
            telegramService.logOut()
            telegramService.clearSetup()
            _uiState.update { s ->
                s.copy(telegramState = TelegramUiState(isConfigured = false))
            }
        }
    }

    fun addTelegramChannel(username: String, name: String) {
        telegramService.addChannel(username, name)
        val channels = telegramService.getSavedChannels()
        _uiState.update { s ->
            s.copy(telegramState = s.telegramState.copy(channels = channels))
        }
    }

    fun removeTelegramChannel(username: String) {
        telegramService.removeChannel(username)
        val channels = telegramService.getSavedChannels()
        _uiState.update { s ->
            s.copy(telegramState = s.telegramState.copy(
                channels = channels,
                selectedChannel = if (s.telegramState.selectedChannel == username) null
                                  else s.telegramState.selectedChannel,
                allPosts = s.telegramState.allPosts.filter { it.channelUsername != username }
            ))
        }
    }

    fun selectTelegramChannel(username: String?) {
        _uiState.update { s ->
            s.copy(telegramState = s.telegramState.copy(selectedChannel = username))
        }
        if (username != null && _uiState.value.telegramState.allPosts.none { it.channelUsername == username }) {
            loadChannelPosts(username, beforeId = 0)
        }
    }

    fun refreshTelegramPosts() {
        val channels = _uiState.value.telegramState.channels
        if (channels.isEmpty()) return
        val useTDLib = telegramService.getSetupState() is TelegramSetupState.Ready
        _uiState.update { s ->
            s.copy(telegramState = s.telegramState.copy(
                isLoading = true,
                error = null,
                usingTDLib = useTDLib,
                lastTdlibIdByChannel = emptyMap()
            ))
        }
        viewModelScope.launch {
            val allPosts = mutableListOf<TelegramGamePost>()
            val lastIds = mutableMapOf<String, Long>()
            channels.forEach { chan ->
                try {
                    val posts = if (useTDLib) {
                        telegramService.fetchChannelPostsTDLib(chan.username, fromTdlibId = 0L)
                    } else {
                        telegramService.fetchChannelPostsWeb(chan.username)
                    }
                    allPosts.addAll(posts)
                    posts.lastOrNull()?.tdlibMessageId?.takeIf { it > 0L }?.let {
                        lastIds[chan.username] = it
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to load channel ${chan.username}")
                }
            }
            _uiState.update { s ->
                s.copy(telegramState = s.telegramState.copy(
                    allPosts = allPosts.sortedByDescending { it.date },
                    isLoading = false,
                    lastTdlibIdByChannel = lastIds
                ))
            }
            if (useTDLib) loadThumbnailsInBackground(allPosts)
        }
    }

    fun loadMoreTelegramPosts(channelUsername: String, beforeId: Int) {
        val useTDLib = telegramService.getSetupState() is TelegramSetupState.Ready
        val fromTdlibId = if (useTDLib)
            _uiState.value.telegramState.lastTdlibIdByChannel[channelUsername] ?: 0L
        else 0L
        loadChannelPosts(channelUsername, beforeId, fromTdlibId, useTDLib)
    }

    private fun loadChannelPosts(
        channelUsername: String,
        beforeId: Int,
        fromTdlibId: Long = 0L,
        useTDLib: Boolean = false
    ) {
        _uiState.update { s -> s.copy(telegramState = s.telegramState.copy(isLoading = true)) }
        viewModelScope.launch {
            try {
                val posts = if (useTDLib) {
                    telegramService.fetchChannelPostsTDLib(channelUsername, fromTdlibId)
                } else {
                    telegramService.fetchChannelPostsWeb(channelUsername, beforeId)
                }
                _uiState.update { s ->
                    val existing = s.telegramState.allPosts
                        .filter { it.channelUsername != channelUsername || (beforeId == 0 && fromTdlibId == 0L) }
                    val newLastId = posts.lastOrNull()?.tdlibMessageId?.takeIf { it > 0L }
                    val newLastIds = if (newLastId != null)
                        s.telegramState.lastTdlibIdByChannel + (channelUsername to newLastId)
                    else s.telegramState.lastTdlibIdByChannel
                    s.copy(telegramState = s.telegramState.copy(
                        allPosts = (existing + posts).sortedByDescending { it.date },
                        isLoading = false,
                        lastTdlibIdByChannel = newLastIds
                    ))
                }
                if (useTDLib) loadThumbnailsInBackground(posts)
            } catch (e: Exception) {
                _uiState.update { s ->
                    s.copy(telegramState = s.telegramState.copy(
                        isLoading = false,
                        error = "Erreur canal @$channelUsername: ${e.message}"
                    ))
                }
            }
        }
    }

    /** Downloads thumbnails for posts that have a thumbnailFileId but no thumbnailUrl yet. */
    private fun loadThumbnailsInBackground(posts: List<TelegramGamePost>) {
        val pending = posts.filter { it.thumbnailFileId > 0 && it.thumbnailUrl == null }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            pending.forEach { post ->
                try {
                    val file = telegramService.downloadThumbnail(post.thumbnailFileId)
                    if (file != null) {
                        _uiState.update { s ->
                            s.copy(telegramState = s.telegramState.copy(
                                allPosts = s.telegramState.allPosts.map { p ->
                                    if (p.channelUsername == post.channelUsername && p.messageId == post.messageId)
                                        p.copy(coverPhotoUrl = "file://$file")
                                    else p
                                }
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Timber.d("Thumbnail skipped for ${post.fileName}: ${e.message}")
                }
            }
        }
    }

    fun downloadTelegramGame(post: TelegramGamePost) {
        telegramDownloadManager.enqueue(post)
    }

    fun cancelTelegramDownload(id: String) {
        telegramDownloadManager.cancel(id)
    }

    fun clearTelegramError() {
        _uiState.update { s -> s.copy(telegramState = s.telegramState.copy(error = null)) }
    }
}
