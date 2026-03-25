package com.usbdiskmanager.ps2.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.ps2.data.transfer.TransferProgress
import com.usbdiskmanager.ps2.domain.model.UsbGame
import com.usbdiskmanager.ps2.util.MountInfo

@Composable
fun UsbTransferScreen(viewModel: Ps2ViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val transferState = uiState.transferState

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Header info banner ──
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(Icons.Default.Usb, null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp).padding(top = 1.dp))
                Column {
                    Text("Transfert USB ↔ Interne", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "Branchez une clé USB contenant des jeux UL. L'app liste les jeux et vous permet de les copier vers le stockage interne ou une autre USB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // ── USB Mounts status bar ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val mounts = uiState.availableUsbMounts
            if (mounts.isEmpty()) {
                Icon(Icons.Default.UsbOff, null, tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp))
                Text("Aucune USB détectée", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            } else {
                Icon(Icons.Default.Usb, null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
                Text(
                    "${mounts.size} USB : ${mounts.joinToString(" • ") { it.mountPoint.substringAfterLast('/') }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = {
                    viewModel.refreshUsbMounts()
                    viewModel.refreshTransferGames()
                },
                modifier = Modifier.size(32.dp)
            ) {
                if (transferState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, "Rafraîchir", modifier = Modifier.size(18.dp))
                }
            }
        }

        HorizontalDivider()

        // ── Content ──
        if (uiState.availableUsbMounts.isEmpty() && !transferState.isLoading) {
            NoUsbPlaceholder()
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                // ── Internal → USB section (games in internal UL folder) ──
                if (transferState.internalGames.isNotEmpty() && uiState.availableUsbMounts.isNotEmpty()) {
                    item {
                        SectionHeader(
                            icon = Icons.Default.PhoneAndroid,
                            title = "Stockage interne → USB",
                            count = transferState.internalGames.size
                        )
                    }
                    items(transferState.internalGames, key = { "int_${it.gameId}" }) { game ->
                        val progress = transferState.activeTransfers[game.gameId]
                        UsbGameRow(
                            game = game,
                            progress = progress,
                            primaryLabel = "Copier vers USB",
                            primaryIcon = Icons.Default.Upload,
                            availableMounts = uiState.availableUsbMounts.filter { it.mountPoint != game.mountPoint },
                            onTransfer = { mount ->
                                viewModel.transferInternalToUsb(game, mount)
                            }
                        )
                    }
                }

                // ── USB sources ──
                uiState.availableUsbMounts.forEach { mount ->
                    val mountGames = transferState.usbGames[mount.mountPoint] ?: emptyList()
                    val otherMounts = uiState.availableUsbMounts.filter { it.mountPoint != mount.mountPoint }

                    item(key = "header_${mount.mountPoint}") {
                        SectionHeader(
                            icon = Icons.Default.Usb,
                            title = "USB: ${mount.mountPoint.substringAfterLast('/')}",
                            count = mountGames.size,
                            subtitle = mount.fsType.uppercase()
                        )
                    }

                    if (mountGames.isEmpty()) {
                        item(key = "empty_${mount.mountPoint}") {
                            Text("Aucun jeu UL trouvé sur cette clé.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
                        }
                    } else {
                        items(mountGames, key = { "${mount.mountPoint}_${it.gameId}" }) { game ->
                            val progress = transferState.activeTransfers[game.gameId]
                            UsbGameRow(
                                game = game,
                                progress = progress,
                                primaryLabel = "→ Interne",
                                primaryIcon = Icons.Default.Download,
                                availableMounts = otherMounts,
                                onTransfer = { _ ->
                                    viewModel.transferUsbToInternal(game)
                                },
                                onDirectTransfer = if (otherMounts.isNotEmpty()) {
                                    { targetMount -> viewModel.transferUsbToUsb(game, targetMount) }
                                } else null
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: ImageVector,
    title: String,
    count: Int,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (count > 0) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("$count", style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        subtitle?.let {
            Text("($it)", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
        HorizontalDivider(modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun UsbGameRow(
    game: UsbGame,
    progress: TransferProgress?,
    primaryLabel: String,
    primaryIcon: ImageVector,
    availableMounts: List<MountInfo>,
    onTransfer: (String) -> Unit,
    onDirectTransfer: ((String) -> Unit)? = null
) {
    var showMountPicker by remember { mutableStateOf(false) }
    var showDirectMountPicker by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Game icon
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Icon(Icons.Default.VideogameAsset, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(22.dp))
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(game.gameName, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(game.gameId, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text("•", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                        Text(if (game.isCd) "CD" else "DVD",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                        Text("•", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                        Text(formatSize(game.sizeBytes), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            }

            // Transfer progress
            AnimatedVisibility(visible = progress != null && !progress.isDone) {
                progress?.let {
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Partie ${it.currentPart}/${it.totalParts}  ${formatSize(it.bytesTransferred)} / ${formatSize(it.bytesTotal)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("%.0f%%".format(it.fraction * 100),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { it.fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                        )
                    }
                }
            }

            // Done state
            AnimatedVisibility(visible = progress?.isDone == true) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp))
                    Text("Transfert terminé", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50))
                }
            }

            // Error state
            progress?.error?.let { err ->
                Text("Erreur: $err", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error)
            }

            // Action buttons
            if (progress == null || progress.isDone) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Primary action
                    Button(
                        onClick = {
                            if (availableMounts.size == 1) {
                                onTransfer(availableMounts.first().mountPoint)
                            } else if (availableMounts.isEmpty()) {
                                onTransfer("internal")
                            } else {
                                showMountPicker = true
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(primaryIcon, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(primaryLabel, style = MaterialTheme.typography.labelMedium,
                            maxLines = 1)
                    }

                    // USB→USB direct transfer (when 2nd USB is available)
                    if (onDirectTransfer != null && availableMounts.isNotEmpty()) {
                        OutlinedButton(
                            onClick = {
                                if (availableMounts.size == 1) {
                                    onDirectTransfer(availableMounts.first().mountPoint)
                                } else {
                                    showDirectMountPicker = true
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.SwapHoriz, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("USB→USB", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }

    // Mount picker dropdown for primary action
    if (showMountPicker) {
        MountPickerDialog(
            mounts = availableMounts,
            title = primaryLabel,
            onDismiss = { showMountPicker = false },
            onSelect = { mount ->
                showMountPicker = false
                onTransfer(mount.mountPoint)
            }
        )
    }

    // Mount picker dropdown for direct USB→USB
    if (showDirectMountPicker && onDirectTransfer != null) {
        MountPickerDialog(
            mounts = availableMounts,
            title = "Choisir USB destination",
            onDismiss = { showDirectMountPicker = false },
            onSelect = { mount ->
                showDirectMountPicker = false
                onDirectTransfer(mount.mountPoint)
            }
        )
    }
}

@Composable
private fun MountPickerDialog(
    mounts: List<MountInfo>,
    title: String,
    onDismiss: () -> Unit,
    onSelect: (MountInfo) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Usb, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                mounts.forEach { mount ->
                    Card(
                        onClick = { onSelect(mount) },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.UsbOff, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                            Column {
                                Text(mount.mountPoint, style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium)
                                Text(mount.fsType.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
private fun NoUsbPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Usb, null, modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))
        Text("Aucune clé USB détectée", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        Text(
            "Branchez une clé USB contenant des jeux au format UL (ul.cfg + fichiers ul.*)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Android ne gère qu'une USB à la fois en OTG.\nPour transférer vers une 2e USB, copiez d'abord en interne, puis rebranchez la 2e USB.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            fontSize = 11.sp
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f Go".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.0f Mo".format(bytes / 1_048_576.0)
    bytes >= 1024L          -> "%.0f Ko".format(bytes / 1024.0)
    else                    -> "${bytes} o"
}
