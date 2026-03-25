package com.usbdiskmanager.ps2.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.ps2.domain.model.DownloadStatus
import com.usbdiskmanager.ps2.domain.model.Ps2Download

@Composable
fun Ps2DownloadScreen(viewModel: Ps2ViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    var urlInput by remember { mutableStateOf("") }
    var fileNameInput by remember { mutableStateOf("") }
    var showAddForm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Info banner
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp, 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp).padding(top = 1.dp)
                )
                Text(
                    "Gestionnaire de téléchargement. Entrez l'URL directe d'un fichier ISO. " +
                    "La reprise automatique est supportée (HTTP Range). " +
                    "Les fichiers téléchargés sont sauvegardés dans /usbdiskmanager/PS2Manager/ISO/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // Add download button / form
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!showAddForm) {
                Button(
                    onClick = { showAddForm = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ajouter un téléchargement")
                }
            } else {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            "Nouveau téléchargement",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        OutlinedTextField(
                            value = urlInput,
                            onValueChange = { urlInput = it },
                            label = { Text("URL du fichier ISO") },
                            placeholder = { Text("https://example.com/game.iso") },
                            leadingIcon = { Icon(Icons.Default.Link, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                        )

                        OutlinedTextField(
                            value = fileNameInput,
                            onValueChange = { fileNameInput = it },
                            label = { Text("Nom du fichier (optionnel)") },
                            placeholder = { Text("MonJeu.iso") },
                            leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() })
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            OutlinedButton(onClick = {
                                showAddForm = false
                                urlInput = ""
                                fileNameInput = ""
                            }) { Text("Annuler") }

                            Button(
                                onClick = {
                                    if (urlInput.isNotBlank()) {
                                        viewModel.addDownload(urlInput.trim(), fileNameInput.trim().takeIf { it.isNotBlank() })
                                        showAddForm = false
                                        urlInput = ""
                                        fileNameInput = ""
                                        focusManager.clearFocus()
                                    }
                                },
                                enabled = urlInput.isNotBlank()
                            ) {
                                Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Télécharger")
                            }
                        }
                    }
                }
            }
        }

        // Download list
        if (uiState.downloads.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.DownloadForOffline,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    )
                    Text(
                        "Aucun téléchargement en cours",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items = uiState.downloads, key = { it.id }) { dl ->
                    DownloadCard(
                        download = dl,
                        onPause = { viewModel.pauseDownload(dl.id) },
                        onResume = { viewModel.resumeDownload(dl.id) },
                        onCancel = { viewModel.removeDownload(dl.id) },
                        onRetry = { viewModel.retryDownload(dl.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(
    download: Ps2Download,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(3.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Status icon
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(statusColor(download.status).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when (download.status) {
                            DownloadStatus.QUEUED       -> Icons.Default.Pending
                            DownloadStatus.DOWNLOADING  -> Icons.Default.Download
                            DownloadStatus.PAUSED       -> Icons.Default.Pause
                            DownloadStatus.COMPLETED    -> Icons.Default.CheckCircle
                            DownloadStatus.ERROR        -> Icons.Default.Error
                            DownloadStatus.CANCELLED    -> Icons.Default.Cancel
                        },
                        contentDescription = null,
                        tint = statusColor(download.status),
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = download.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = statusLabel(download),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(download.status)
                    )
                }

                // Actions
                Row {
                    when (download.status) {
                        DownloadStatus.DOWNLOADING -> {
                            IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Pause, null, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.PAUSED -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.ERROR -> {
                            IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.QUEUED -> {
                            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.COMPLETED -> {
                            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Delete, null, modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.outline)
                            }
                        }
                        else -> {}
                    }
                }
            }

            // Progress bar
            if (download.status == DownloadStatus.DOWNLOADING || download.status == DownloadStatus.PAUSED) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = buildString {
                                append(formatBytes(download.downloadedBytes))
                                if (download.totalBytes > 0) append(" / ${formatBytes(download.totalBytes)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (download.status == DownloadStatus.DOWNLOADING && download.speedBps > 0) {
                            Text(
                                text = "${formatSpeed(download.speedBps)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (download.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = { download.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }

            // Error message
            download.errorMessage?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }

            // URL (truncated)
            Text(
                text = download.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun statusColor(status: DownloadStatus): Color = when (status) {
    DownloadStatus.QUEUED      -> MaterialTheme.colorScheme.secondary
    DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.primary
    DownloadStatus.PAUSED      -> MaterialTheme.colorScheme.tertiary
    DownloadStatus.COMPLETED   -> Color(0xFF4CAF50)
    DownloadStatus.ERROR       -> MaterialTheme.colorScheme.error
    DownloadStatus.CANCELLED   -> MaterialTheme.colorScheme.outline
}

private fun statusLabel(dl: Ps2Download): String = when (dl.status) {
    DownloadStatus.QUEUED      -> "En file…"
    DownloadStatus.DOWNLOADING -> if (dl.progress > 0) "%.1f%%".format(dl.progress * 100) else "Connexion…"
    DownloadStatus.PAUSED      -> "En pause — %.1f%%".format(dl.progress * 100)
    DownloadStatus.COMPLETED   -> "Terminé"
    DownloadStatus.ERROR       -> "Erreur"
    DownloadStatus.CANCELLED   -> "Annulé"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f Go".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.0f Mo".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.0f Ko".format(bytes / 1_024.0)
    else                     -> "${bytes} o"
}

private fun formatSpeed(bps: Double): String = when {
    bps >= 1_048_576 -> "%.1f Mo/s".format(bps / 1_048_576)
    bps >= 1_024     -> "%.0f Ko/s".format(bps / 1_024)
    else              -> "%.0f o/s".format(bps)
}
