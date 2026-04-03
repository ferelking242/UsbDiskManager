package com.usbdiskmanager.ps2.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.usbdiskmanager.ps2.domain.model.DownloadStatus
import com.usbdiskmanager.ps2.domain.model.IsoSearchResult
import com.usbdiskmanager.ps2.domain.model.Ps2Download

@Composable
fun Ps2DownloadScreen(viewModel: Ps2ViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var showManualInput by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var fileNameInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Search bar (archive.org) ──
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Info badge
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CloudDownload, null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(14.dp))
                        Text(
                            "Recherche via Internet Archive (archive.org) — bibliothèque de préservation numérique publique",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontSize = 10.sp
                        )
                    }
                }

                // Search field
                OutlinedTextField(
                    value = uiState.isoSearchQuery,
                    onValueChange = viewModel::setIsoSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Rechercher un jeu PS2... ex: \"God of War\"") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (uiState.isoSearchQuery.isNotBlank()) {
                            if (uiState.isoSearchLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                IconButton(onClick = viewModel::clearIsoSearch) {
                                    Icon(Icons.Default.Clear, null)
                                }
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        viewModel.searchIso(uiState.isoSearchQuery)
                        focusManager.clearFocus()
                    })
                )
                Button(
                    onClick = {
                        viewModel.searchIso(uiState.isoSearchQuery)
                        focusManager.clearFocus()
                    },
                    enabled = uiState.isoSearchQuery.isNotBlank() && !uiState.isoSearchLoading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Rechercher sur Internet Archive")
                }
            }
        }

        HorizontalDivider()

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f)
        ) {
            // ── Search error ──
            uiState.isoSearchError?.let { err ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp))
                            Text(err, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }

            // ── Search results ──
            if (uiState.isoSearchResults.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 4.dp)) {
                        Icon(Icons.Default.Archive, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp))
                        Text("${uiState.isoSearchResults.size} résultat(s) — Internet Archive",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold)
                    }
                }

                items(uiState.isoSearchResults, key = { it.identifier }) { result ->
                    SearchResultCard(
                        result = result,
                        isResolving = uiState.resolvingId == result.identifier,
                        onDownload = { viewModel.resolveAndDownload(result) }
                    )
                }
            } else if (!uiState.isoSearchLoading && uiState.isoSearchQuery.isBlank()) {
                item {
                    PopularGamesSection(
                        onGameClick = { gameName ->
                            viewModel.setIsoSearchQuery(gameName)
                            viewModel.searchIso(gameName)
                        }
                    )
                }
            }

            // ── Active downloads ──
            if (uiState.downloads.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp))
                        Text("Téléchargements", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }

                items(uiState.downloads, key = { it.id }) { download ->
                    DownloadCard(
                        download = download,
                        onPause = { viewModel.pauseDownload(download.id) },
                        onResume = { viewModel.resumeDownload(download.id) },
                        onRetry = { viewModel.retryDownload(download.id) },
                        onRemove = { viewModel.removeDownload(download.id) }
                    )
                }
            }

            // ── Manual URL input (collapsible) ──
            item {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { showManualInput = !showManualInput },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Link, null, modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("URL directe", style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold)
                            }
                            Icon(if (showManualInput) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, modifier = Modifier.size(20.dp))
                        }
                        AnimatedVisibility(visible = showManualInput) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = urlInput,
                                    onValueChange = { urlInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("URL du fichier ISO/7z") },
                                    placeholder = { Text("https://...") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                OutlinedTextField(
                                    value = fileNameInput,
                                    onValueChange = { fileNameInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Nom du fichier (optionnel)") },
                                    placeholder = { Text("MonJeu.iso") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                Button(
                                    onClick = {
                                        if (urlInput.isNotBlank()) {
                                            viewModel.addDownload(urlInput, fileNameInput)
                                            urlInput = ""
                                            fileNameInput = ""
                                            focusManager.clearFocus()
                                        }
                                    },
                                    enabled = urlInput.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Ajouter le téléchargement")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    result: IsoSearchResult,
    isResolving: Boolean,
    onDownload: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Thumbnail from archive.org
            if (result.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = result.coverUrl,
                    contentDescription = result.title,
                    modifier = Modifier
                        .size(width = 52.dp, height = 68.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.size(width = 52.dp, height = 68.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.VideogameAsset, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(24.dp))
                    }
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(result.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (result.region.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
                            Text(result.region, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Text("archive.org", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline, fontSize = 10.sp)
                }

                if (result.description.isNotBlank()) {
                    Text(result.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 11.sp)
                }

                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = onDownload,
                    enabled = !isResolving,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    if (isResolving) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(6.dp))
                        Text("Résolution...", style = MaterialTheme.typography.labelSmall)
                    } else {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Télécharger", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

private val POPULAR_PS2_GAMES = listOf(
    "God of War" to "Action",
    "Shadow of the Colossus" to "Action/Aventure",
    "Kingdom Hearts" to "RPG",
    "Final Fantasy X" to "RPG",
    "Grand Theft Auto San Andreas" to "Open World",
    "Dragon Ball Z Budokai Tenkaichi 3" to "Combat",
    "Tekken 5" to "Combat",
    "Silent Hill 2" to "Horreur",
    "Resident Evil 4" to "Survival",
    "Metal Gear Solid 3" to "Action",
    "Ico" to "Aventure",
    "Okami" to "Action/Aventure",
    "Devil May Cry 3" to "Action",
    "Burnout 3 Takedown" to "Course",
    "Jak and Daxter" to "Platforme",
    "Ratchet and Clank" to "Platforme",
    "Pro Evolution Soccer 6" to "Sport",
    "Black" to "FPS",
    "Killzone" to "FPS",
    "We Love Katamari" to "Puzzle"
)

@Composable
private fun PopularGamesSection(onGameClick: (String) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Star, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                "Jeux populaires",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            "Cliquez sur un jeu pour le rechercher sur Internet Archive",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Grid of game chips
        val chunkedGames = POPULAR_PS2_GAMES.chunked(2)
        chunkedGames.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowItems.forEach { (gameName, genre) ->
                    Card(
                        onClick = { onGameClick(gameName) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.VideogameAsset, null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text(
                                    gameName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2
                                )
                                Text(
                                    genre,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
                // Fill remaining space if odd number in row
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
        Text(
            "Les résultats proviennent d'Internet Archive,\nune bibliothèque de préservation numérique légale.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 11.sp
        )
    }
}

@Composable
private fun DownloadCard(
    download: Ps2Download,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val (icon, iconTint) = when (download.status) {
                    DownloadStatus.DOWNLOADING -> Icons.Default.Downloading to MaterialTheme.colorScheme.primary
                    DownloadStatus.COMPLETED   -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
                    DownloadStatus.PAUSED      -> Icons.Default.PauseCircle to MaterialTheme.colorScheme.tertiary
                    DownloadStatus.ERROR       -> Icons.Default.Error to MaterialTheme.colorScheme.error
                    else                       -> Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.outline
                }
                Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(download.fileName, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            append(formatSizeDown(download.downloadedBytes))
                            if (download.totalBytes > 0) append(" / ${formatSizeDown(download.totalBytes)}")
                            val speedKb = download.speedBps.toLong()
                            if (speedKb > 0) append("  •  ${formatSizeDown(speedKb.toLong())}/s")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    download.errorMessage?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error, maxLines = 1)
                    }
                }
                // Actions
                when (download.status) {
                    DownloadStatus.DOWNLOADING ->
                        IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Pause, null, modifier = Modifier.size(18.dp))
                        }
                    DownloadStatus.PAUSED ->
                        IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        }
                    DownloadStatus.ERROR ->
                        IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        }
                    else -> {}
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.outline)
                }
            }

            // Progress bar
            if (download.status == DownloadStatus.DOWNLOADING && download.totalBytes > 0) {
                val progress = (download.downloadedBytes.toFloat() / download.totalBytes.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
                Text("%.1f%%".format(progress * 100), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary)
            } else if (download.status == DownloadStatus.DOWNLOADING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                )
            }
        }
    }
}

private fun formatSizeDown(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f Go".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f Mo".format(bytes / 1_048_576.0)
    bytes >= 1024L          -> "%.0f Ko".format(bytes / 1024.0)
    else                    -> "${bytes} o"
}
