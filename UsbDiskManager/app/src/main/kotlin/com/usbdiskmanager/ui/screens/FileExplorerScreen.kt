package com.usbdiskmanager.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.core.model.ClipboardOperation
import com.usbdiskmanager.core.model.FileItem
import com.usbdiskmanager.core.model.FileSortOrder
import com.usbdiskmanager.viewmodel.FileExplorerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileExplorerScreen(
    mountPoint: String,
    onNavigateUp: () -> Unit,
    viewModel: FileExplorerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var newName by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    // Handle back navigation (either go up a directory or pop screen)
    BackHandler {
        if (!viewModel.navigateUp()) onNavigateUp()
    }

    // Dialogs
    if (showNewFolderDialog) {
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false; newFolderName = "" },
            title = { Text("New Folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    label = { Text("Folder name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newFolderName.isNotEmpty()) {
                        viewModel.createDirectory(newFolderName)
                        newFolderName = ""
                        showNewFolderDialog = false
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showNewFolderDialog = false; newFolderName = "" }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showRenameDialog && renameTarget != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New name") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    renameTarget?.let { viewModel.rename(it, newName) }
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete ${uiState.selectedFiles.size} item(s)?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.delete(); showDeleteConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                currentDirName(uiState.currentPath),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                uiState.currentPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (!viewModel.navigateUp()) onNavigateUp() }) {
                            Icon(Icons.Default.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        // Sort
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, "Sort")
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            FileSortOrder.entries.forEach { order ->
                                DropdownMenuItem(
                                    text = { Text(order.displayName()) },
                                    onClick = { viewModel.setSortOrder(order); showSortMenu = false },
                                    leadingIcon = {
                                        if (uiState.sortOrder == order) Icon(Icons.Default.Check, null)
                                    }
                                )
                            }
                        }
                        IconButton(onClick = { viewModel.toggleHiddenFiles() }) {
                            Icon(
                                if (uiState.showHiddenFiles) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                "Toggle hidden"
                            )
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                    }
                )

                // Search bar
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.search(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = { Text("Search files...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.search("") }) {
                                Icon(Icons.Default.Clear, null)
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        bottomBar = {
            // Context action bar when items selected
            AnimatedVisibility(
                visible = uiState.selectedFiles.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "${uiState.selectedFiles.size} selected",
                        modifier = Modifier.padding(start = 16.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { viewModel.copy() }) {
                        Icon(Icons.Default.ContentCopy, "Copy")
                    }
                    IconButton(onClick = { viewModel.cut() }) {
                        Icon(Icons.Default.ContentCut, "Cut")
                    }
                    if (uiState.selectedFiles.size == 1) {
                        IconButton(onClick = {
                            val item = uiState.files.find { it.path in uiState.selectedFiles }
                            renameTarget = item
                            newName = item?.name ?: ""
                            showRenameDialog = true
                        }) {
                            Icon(Icons.Default.DriveFileRenameOutline, "Rename")
                        }
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(Icons.Default.Close, "Clear selection")
                    }
                }
            }
        },
        floatingActionButton = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.End
            ) {
                // Paste FAB (visible when clipboard has content)
                AnimatedVisibility(visible = uiState.clipboard != null) {
                    ExtendedFloatingActionButton(
                        text = {
                            val op = uiState.clipboard?.operation
                            Text(if (op == ClipboardOperation.COPY) "Paste Copy" else "Paste Move")
                        },
                        icon = { Icon(Icons.Default.ContentPaste, null) },
                        onClick = { viewModel.paste() },
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                }
                FloatingActionButton(
                    onClick = { showNewFolderDialog = true }
                ) {
                    Icon(Icons.Default.CreateNewFolder, "New folder")
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Progress bar for operations
            if (uiState.isOperationRunning) {
                LinearProgressIndicator(
                    progress = { uiState.operationProgress / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            // Messages
            uiState.errorMessage?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text(msg, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearMessages() }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                }
            }
            uiState.operationMessage?.let { msg ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.tertiary)
                        Spacer(Modifier.width(8.dp))
                        Text(msg, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearMessages() }) {
                            Icon(Icons.Default.Close, null)
                        }
                    }
                }
            }

            val displayFiles = if (uiState.isSearchActive) uiState.searchResults else uiState.files

            if (displayFiles.isEmpty() && !uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            if (uiState.isSearchActive) Icons.Default.SearchOff else Icons.Default.FolderOpen,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (uiState.isSearchActive) "No files found" else "Empty folder",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                // Select all button
                if (displayFiles.isNotEmpty()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${displayFiles.size} items",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = {
                            if (uiState.selectedFiles.size == displayFiles.size) viewModel.clearSelection()
                            else viewModel.selectAll()
                        }) {
                            Text(if (uiState.selectedFiles.size == displayFiles.size) "Deselect All" else "Select All")
                        }
                    }
                }

                LazyColumn(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    items(displayFiles, key = { it.path }) { file ->
                        FileItemRow(
                            file = file,
                            isSelected = file.path in uiState.selectedFiles,
                            onClick = {
                                if (uiState.selectedFiles.isNotEmpty()) {
                                    viewModel.toggleSelection(file.path)
                                } else if (file.isDirectory) {
                                    viewModel.navigateTo(file.path)
                                }
                            },
                            onLongClick = { viewModel.toggleSelection(file.path) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItemRow(
    file: FileItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val bgColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelected) {
            Icon(
                Icons.Default.CheckCircle,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Icon(
                if (file.isDirectory) Icons.Default.Folder else fileTypeIcon(file),
                null,
                tint = if (file.isDirectory) Color(0xFFFFB74D) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (file.isDirectory) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1
            )
            Row {
                if (!file.isDirectory) {
                    Text(
                        file.displaySize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        " · ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                        .format(Date(file.lastModified)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (file.isDirectory) {
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

private fun currentDirName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { "Root" }

private fun fileTypeIcon(file: FileItem): androidx.compose.ui.graphics.vector.ImageVector {
    return when (file.extension.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> Icons.Default.Image
        "mp4", "mkv", "avi", "mov", "wmv" -> Icons.Default.VideoFile
        "mp3", "wav", "flac", "aac", "ogg" -> Icons.Default.AudioFile
        "pdf" -> Icons.Default.PictureAsPdf
        "zip", "rar", "7z", "tar", "gz" -> Icons.Default.FolderZip
        "apk" -> Icons.Default.Android
        "txt", "log", "md" -> Icons.Default.Description
        "xml", "json", "yaml", "yml" -> Icons.Default.Code
        "iso" -> Icons.Default.Album
        else -> Icons.Default.InsertDriveFile
    }
}

private fun FileSortOrder.displayName(): String = when (this) {
    FileSortOrder.NAME_ASC -> "Name A→Z"
    FileSortOrder.NAME_DESC -> "Name Z→A"
    FileSortOrder.SIZE_ASC -> "Size ↑"
    FileSortOrder.SIZE_DESC -> "Size ↓"
    FileSortOrder.DATE_ASC -> "Date ↑"
    FileSortOrder.DATE_DESC -> "Date ↓"
}
