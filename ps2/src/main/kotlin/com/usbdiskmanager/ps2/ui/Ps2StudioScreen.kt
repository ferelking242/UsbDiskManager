package com.usbdiskmanager.ps2.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.ps2.domain.model.OutputDestination
import com.usbdiskmanager.ps2.ui.components.BatchConversionDialog
import com.usbdiskmanager.ps2.ui.components.ConversionDialog
import com.usbdiskmanager.ps2.ui.components.Fat32WarningDialog
import com.usbdiskmanager.ps2.ui.components.GameCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Ps2StudioScreen(
    viewModel: Ps2ViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.addScanUri(it) }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.scan() }

    val hasStorageAccess = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else true
    }

    var showBatchDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                LargeTopAppBar(
                    title = {
                        Column {
                            Text("PS2 Studio", fontWeight = FontWeight.ExtraBold)
                            Text(
                                "${uiState.games.size} ISO détecté(s)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        Icon(
                            Icons.Default.VideogameAsset,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp).size(32.dp)
                        )
                    },
                    actions = {
                        // Multi-select toggle
                        if (uiState.selectedTab == Ps2Tab.GAMES) {
                            IconButton(onClick = viewModel::toggleMultiSelectMode) {
                                Icon(
                                    if (uiState.isMultiSelectMode) Icons.Default.Close
                                    else Icons.Default.Checklist,
                                    contentDescription = "Sélection multiple",
                                    tint = if (uiState.isMultiSelectMode)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (uiState.isMultiSelectMode && uiState.multiSelectedIds.isNotEmpty()) {
                                IconButton(onClick = { showBatchDialog = true }) {
                                    Icon(Icons.Default.Transform, contentDescription = "Convertir sélection",
                                        tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        var showSortMenu by remember { mutableStateOf(false) }
                        if (uiState.selectedTab == Ps2Tab.GAMES) {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Default.Sort, contentDescription = "Trier")
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                SortMode.entries.forEach { mode ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (mode) {
                                                    SortMode.TITLE  -> "Par titre"
                                                    SortMode.SIZE   -> "Par taille"
                                                    SortMode.STATUS -> "Par statut"
                                                },
                                                fontWeight = if (uiState.sortMode == mode)
                                                    FontWeight.Bold else FontWeight.Normal
                                            )
                                        },
                                        onClick = { viewModel.setSortMode(mode); showSortMenu = false }
                                    )
                                }
                            }
                            IconButton(onClick = viewModel::fetchAllCovers) {
                                Icon(Icons.Default.Image, contentDescription = "Récupérer toutes les pochettes")
                            }
                            IconButton(onClick = { folderPickerLauncher.launch(null) }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Ajouter dossier")
                            }
                            IconButton(onClick = viewModel::scan) {
                                if (uiState.isScanning) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "Scanner")
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior
                )

                // ── Telegram-style horizontal sub-tab bar ──
                ScrollableTabRow(
                    selectedTabIndex = uiState.selectedTab.ordinal,
                    edgePadding = 16.dp,
                    divider = {},
                    indicator = { tabPositions ->
                        if (uiState.selectedTab.ordinal < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(
                                    tabPositions[uiState.selectedTab.ordinal]
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Ps2Tab.entries.forEach { tab ->
                        Tab(
                            selected = uiState.selectedTab == tab,
                            onClick = { viewModel.setTab(tab) },
                            text = {
                                Text(
                                    text = when (tab) {
                                        Ps2Tab.GAMES     -> "Jeux"
                                        Ps2Tab.MERGE_CFG -> "Fusionner CFG"
                                        Ps2Tab.DOWNLOAD  -> "Télécharger"
                                        Ps2Tab.TRANSFER  -> "Transfert USB"
                                    },
                                    fontWeight = if (uiState.selectedTab == tab)
                                        FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            icon = {
                                Icon(
                                    when (tab) {
                                        Ps2Tab.GAMES     -> Icons.Default.VideogameAsset
                                        Ps2Tab.MERGE_CFG -> Icons.Default.MergeType
                                        Ps2Tab.DOWNLOAD  -> Icons.Default.Download
                                        Ps2Tab.TRANSFER  -> Icons.Default.SwapHoriz
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!hasStorageAccess) {
                StoragePermissionPrompt(
                    modifier = Modifier.align(Alignment.Center),
                    onGrant = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            manageStorageLauncher.launch(intent)
                        }
                    }
                )
            } else {
                when (uiState.selectedTab) {
                    Ps2Tab.GAMES     -> GameListTab(
                        uiState = uiState,
                        viewModel = viewModel,
                        onPickFolder = { folderPickerLauncher.launch(null) }
                    )
                    Ps2Tab.MERGE_CFG -> UlCfgMergerScreen()
                    Ps2Tab.DOWNLOAD  -> Ps2DownloadScreen(viewModel = viewModel)
                    Ps2Tab.TRANSFER  -> UsbTransferScreen(viewModel = viewModel)
                }
            }
        }
    }

    // ── Dialogs ──

    // Single game conversion dialog
    if (uiState.showConvertDialog && uiState.selectedGame != null) {
        ConversionDialog(
            game = uiState.selectedGame!!,
            availableUsbMounts = uiState.availableUsbMounts,
            initialDestination = uiState.pendingDestination,
            onDismiss = viewModel::dismissDialog,
            onConvert = { dest -> viewModel.requestConversionWithDest(dest) }
        )
    }

    // FAT32 warning
    if (uiState.showFat32Warning) {
        Fat32WarningDialog(
            mount = uiState.fat32WarningMount,
            onProceedAnyway = viewModel::proceedDespiteFat32Warning,
            onDismiss = viewModel::dismissDialog
        )
    }

    // Batch conversion dialog
    if (showBatchDialog) {
        BatchConversionDialog(
            count = uiState.multiSelectedIds.size,
            availableUsbMounts = uiState.availableUsbMounts,
            initialDestination = uiState.pendingDestination,
            onDismiss = { showBatchDialog = false },
            onConvert = { dest ->
                showBatchDialog = false
                viewModel.requestConversionWithDest(dest)
            }
        )
    }

    uiState.errorMessage?.let {
        LaunchedEffect(it) { viewModel.clearError() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameListTab(
    uiState: Ps2UiState,
    viewModel: Ps2ViewModel,
    onPickFolder: () -> Unit
) {
    val scrollState = rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Multi-select action bar
        AnimatedVisibility(visible = uiState.isMultiSelectMode) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${uiState.multiSelectedIds.size} sélectionné(s)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = viewModel::selectAllGames,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) { Text("Tout sélect.") }
                        TextButton(
                            onClick = viewModel::clearSelection,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) { Text("Vider") }
                    }
                }
            }
        }

        // Search bar
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = viewModel::setSearch,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Rechercher un jeu...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (uiState.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setSearch("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Effacer")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        if (uiState.games.isEmpty() && !uiState.isScanning) {
            EmptyState(
                modifier = Modifier.fillMaxSize().weight(1f),
                onPickFolder = onPickFolder
            )
        } else {
            LazyColumn(
                state = scrollState,
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(items = uiState.filteredGames, key = { it.id }) { game ->
                    val progress = uiState.activeProgress[game.isoPath]
                    val isSelected = game.id in uiState.multiSelectedIds

                    Box(
                        modifier = Modifier
                            .combinedClickable(
                                onClick = {
                                    if (uiState.isMultiSelectMode) viewModel.toggleGameSelection(game)
                                },
                                onLongClick = {
                                    if (!uiState.isMultiSelectMode) {
                                        viewModel.toggleMultiSelectMode()
                                        viewModel.toggleGameSelection(game)
                                    }
                                }
                            )
                    ) {
                        GameCard(
                            game = game,
                            progress = progress,
                            isSelected = isSelected,
                            isMultiSelectMode = uiState.isMultiSelectMode,
                            onConvertClick = {
                                if (!uiState.isMultiSelectMode) viewModel.selectGame(game)
                                else viewModel.toggleGameSelection(game)
                            },
                            onPauseClick = { viewModel.pauseConversion(game.isoPath) },
                            onResumeClick = { viewModel.resumeConversion(game.isoPath) },
                            onCancelClick = { viewModel.cancelConversion(game.isoPath) },
                            onFetchCoverClick = { viewModel.fetchCover(game) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier, onPickFolder: () -> Unit) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.VideogameAsset,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Aucun ISO détecté",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Placez vos ISO dans /usbdiskmanager/PS2Manager/ISO/\nou sélectionnez un dossier manuellement",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFolder) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Choisir un dossier")
        }
    }
}

@Composable
private fun StoragePermissionPrompt(modifier: Modifier = Modifier, onGrant: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text("Accès stockage requis", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
        Text(
            "PS2 Studio nécessite l'accès complet au stockage pour lire/écrire les fichiers ISO et UL.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Button(onClick = onGrant) { Text("Accorder l'accès") }
    }
}
