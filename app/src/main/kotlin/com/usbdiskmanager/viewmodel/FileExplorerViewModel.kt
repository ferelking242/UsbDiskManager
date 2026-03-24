package com.usbdiskmanager.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.core.model.ClipboardOperation
import com.usbdiskmanager.core.model.ClipboardState
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.core.model.FileItem
import com.usbdiskmanager.core.model.FileSortOrder
import com.usbdiskmanager.storage.api.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class FileExplorerUiState(
    val currentPath: String = "",
    val files: List<FileItem> = emptyList(),
    val selectedFiles: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val operationMessage: String? = null,
    val operationProgress: Int = 0,
    val isOperationRunning: Boolean = false,
    val clipboard: ClipboardState? = null,
    val sortOrder: FileSortOrder = FileSortOrder.NAME_ASC,
    val showHiddenFiles: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<FileItem> = emptyList(),
    val isSearchActive: Boolean = false,
    val pathHistory: List<String> = emptyList()
)

@HiltViewModel
class FileExplorerViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // BUGFIX: Decode "|" back to "/" — the nav route encodes "/" as "|" to avoid path conflicts
    private val initialPath: String =
        (savedStateHandle.get<String>("mountPoint") ?: "").replace("|", "/")

    private val _uiState = MutableStateFlow(FileExplorerUiState(currentPath = initialPath))
    val uiState: StateFlow<FileExplorerUiState> = _uiState.asStateFlow()

    init {
        if (initialPath.isNotEmpty()) {
            Timber.d("FileExplorer init path: $initialPath")
            loadFiles(initialPath)
        }
    }

    fun navigateTo(path: String) {
        val history = _uiState.value.pathHistory.toMutableList()
        if (_uiState.value.currentPath.isNotEmpty()) {
            history.add(_uiState.value.currentPath)
        }
        _uiState.value = _uiState.value.copy(
            currentPath = path,
            pathHistory = history,
            selectedFiles = emptySet(),
            isSearchActive = false,
            searchQuery = ""
        )
        loadFiles(path)
    }

    fun navigateUp(): Boolean {
        val history = _uiState.value.pathHistory
        return if (history.isNotEmpty()) {
            val previousPath = history.last()
            _uiState.value = _uiState.value.copy(
                currentPath = previousPath,
                pathHistory = history.dropLast(1),
                selectedFiles = emptySet()
            )
            loadFiles(previousPath)
            true
        } else {
            false
        }
    }

    fun loadFiles(path: String) {
        if (path.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = "Chemin vide — impossible de charger les fichiers"
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            try {
                val files = fileRepository.listFiles(
                    path,
                    _uiState.value.showHiddenFiles,
                    _uiState.value.sortOrder
                )
                Timber.d("Loaded ${files.size} file(s) from $path")
                _uiState.value = _uiState.value.copy(files = files, isLoading = false)
            } catch (e: Exception) {
                Timber.e(e, "Error loading files from $path")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Erreur: ${e.message}"
                )
            }
        }
    }

    fun refresh() {
        loadFiles(_uiState.value.currentPath)
    }

    fun toggleSelection(filePath: String) {
        val selected = _uiState.value.selectedFiles.toMutableSet()
        if (selected.contains(filePath)) selected.remove(filePath) else selected.add(filePath)
        _uiState.value = _uiState.value.copy(selectedFiles = selected)
    }

    fun selectAll() {
        _uiState.value = _uiState.value.copy(selectedFiles = _uiState.value.files.map { it.path }.toSet())
    }

    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedFiles = emptySet())
    }

    fun copy() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        fileRepository.setClipboard(items, ClipboardOperation.COPY, _uiState.value.currentPath)
        _uiState.value = _uiState.value.copy(
            clipboard = fileRepository.getClipboard(),
            selectedFiles = emptySet(),
            operationMessage = "${items.size} élément(s) copié(s)"
        )
    }

    fun cut() {
        val items = getSelectedItems()
        if (items.isEmpty()) return
        fileRepository.setClipboard(items, ClipboardOperation.CUT, _uiState.value.currentPath)
        _uiState.value = _uiState.value.copy(
            clipboard = fileRepository.getClipboard(),
            selectedFiles = emptySet(),
            operationMessage = "${items.size} élément(s) coupé(s)"
        )
    }

    fun paste() {
        val dest = _uiState.value.currentPath
        _uiState.value = _uiState.value.copy(isOperationRunning = true, operationProgress = 0)
        fileRepository.pasteClipboard(dest)
            .onEach { result ->
                when (result) {
                    is DiskOperationResult.Progress -> _uiState.value = _uiState.value.copy(
                        operationProgress = result.percent, operationMessage = result.message
                    )
                    is DiskOperationResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isOperationRunning = false,
                            clipboard = fileRepository.getClipboard(),
                            operationMessage = result.message
                        )
                        refresh()
                    }
                    is DiskOperationResult.Error -> _uiState.value = _uiState.value.copy(
                        isOperationRunning = false, errorMessage = result.message
                    )
                }
            }
            .catch { e ->
                Timber.e(e, "Paste error")
                _uiState.value = _uiState.value.copy(
                    isOperationRunning = false, errorMessage = "Erreur de collage: ${e.message}"
                )
            }
            .launchIn(viewModelScope)
    }

    fun delete(items: List<FileItem> = getSelectedItems()) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isOperationRunning = true)
            val result = fileRepository.deleteFiles(items)
            when (result) {
                is DiskOperationResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isOperationRunning = false,
                        selectedFiles = emptySet(),
                        operationMessage = result.message
                    )
                    refresh()
                }
                is DiskOperationResult.Error -> _uiState.value = _uiState.value.copy(
                    isOperationRunning = false, errorMessage = result.message
                )
                else -> {}
            }
        }
    }

    fun createDirectory(name: String) {
        viewModelScope.launch {
            val result = fileRepository.createDirectory(_uiState.value.currentPath, name)
            when (result) {
                is DiskOperationResult.Success -> { _uiState.value = _uiState.value.copy(operationMessage = result.message); refresh() }
                is DiskOperationResult.Error -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
                else -> {}
            }
        }
    }

    fun rename(item: FileItem, newName: String) {
        viewModelScope.launch {
            val result = fileRepository.rename(item, newName)
            when (result) {
                is DiskOperationResult.Success -> { _uiState.value = _uiState.value.copy(operationMessage = result.message); refresh() }
                is DiskOperationResult.Error -> _uiState.value = _uiState.value.copy(errorMessage = result.message)
                else -> {}
            }
        }
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query, isSearchActive = query.isNotEmpty())
        if (query.isEmpty()) return
        viewModelScope.launch {
            val results = fileRepository.searchFiles(_uiState.value.currentPath, query)
            _uiState.value = _uiState.value.copy(searchResults = results)
        }
    }

    fun setSortOrder(order: FileSortOrder) {
        _uiState.value = _uiState.value.copy(sortOrder = order)
        loadFiles(_uiState.value.currentPath)
    }

    fun toggleHiddenFiles() {
        _uiState.value = _uiState.value.copy(showHiddenFiles = !_uiState.value.showHiddenFiles)
        loadFiles(_uiState.value.currentPath)
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(errorMessage = null, operationMessage = null)
    }

    private fun getSelectedItems(): List<FileItem> {
        val selectedPaths = _uiState.value.selectedFiles
        return _uiState.value.files.filter { it.path in selectedPaths }
    }
}
