package com.usbdiskmanager.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.core.model.BenchmarkResult
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.core.model.FileItem
import com.usbdiskmanager.viewmodel.DiskDetailViewModel

private enum class DiskTab { INFO, BENCHMARK, FORMAT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskDetailScreen(
    deviceId: String,
    onNavigateUp: () -> Unit,
    onOpenFileExplorer: (String) -> Unit,
    viewModel: DiskDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(DiskTab.INFO) }
    var showLabelDialog by remember { mutableStateOf(false) }
    var formatLabel by remember { mutableStateOf("") }

    // Format confirmation dialog
    if (uiState.showFormatConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.showFormatConfirmation(false) },
            icon = {
                Icon(
                    Icons.Default.Warning, null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    "Formater le disque ?",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "TOUTES les données seront EFFACÉES définitivement sur :",
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Disque : ${uiState.device?.name}", style = MaterialTheme.typography.bodySmall)
                            Text("Format : ${uiState.selectedFormatType}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            if (formatLabel.isNotBlank()) Text("Label : $formatLabel", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Cette action est IRRÉVERSIBLE.",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.formatDevice(formatLabel) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteForever, null)
                    Spacer(Modifier.width(4.dp))
                    Text("FORMATER")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.showFormatConfirmation(false) }) {
                    Text("Annuler")
                }
            }
        )
    }

    // Volume label dialog
    if (showLabelDialog) {
        AlertDialog(
            onDismissRequest = { showLabelDialog = false },
            icon = { Icon(Icons.Default.Label, null) },
            title = { Text("Nom du volume (optionnel)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = formatLabel,
                        onValueChange = { formatLabel = it.take(11) },
                        label = { Text("Nom (max 11 caractères)") },
                        placeholder = { Text("ex: MAUSBDISK") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) }
                    )
                    Text(
                        "${formatLabel.length}/11 caractères",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showLabelDialog = false
                    viewModel.showFormatConfirmation(true)
                }) { Text("Continuer") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showLabelDialog = false
                    formatLabel = ""
                    viewModel.showFormatConfirmation(true)
                }) { Text("Sans nom") }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(uiState.device?.name ?: "Détails du disque", fontWeight = FontWeight.Bold)
                            uiState.device?.fileSystem?.let { fs ->
                                Text(
                                    "${fs.displayName} • ${if (uiState.device?.isMounted == true) "Monté" else "Non monté"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.Default.ArrowBack, "Retour")
                        }
                    },
                    actions = {
                        uiState.device?.mountPoint?.let { mountPoint ->
                            IconButton(onClick = { onOpenFileExplorer(mountPoint) }) {
                                Icon(Icons.Default.FolderOpen, "Parcourir")
                            }
                        }
                    }
                )

                // ── Tab bar ──
                TabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                    indicator = { tabPositions ->
                        if (selectedTab.ordinal < tabPositions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    divider = {}
                ) {
                    DiskTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = {
                                Text(
                                    when (tab) {
                                        DiskTab.INFO      -> "Informations"
                                        DiskTab.BENCHMARK -> "Benchmark"
                                        DiskTab.FORMAT    -> "Formater"
                                    },
                                    fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 13.sp
                                )
                            },
                            icon = {
                                Icon(
                                    when (tab) {
                                        DiskTab.INFO      -> Icons.Default.Info
                                        DiskTab.BENCHMARK -> Icons.Default.Speed
                                        DiskTab.FORMAT    -> Icons.Default.FormatColorFill
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                return@Box
            }

            val device = uiState.device
            if (device == null) {
                Text(
                    "Périphérique introuvable ou déconnecté",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center)
                )
                return@Box
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Status / progress banner
                uiState.errorMessage?.let { err ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(err, modifier = Modifier.weight(1f), fontSize = 13.sp)
                            IconButton(onClick = { viewModel.clearMessages() }) {
                                Icon(Icons.Default.Close, null)
                            }
                        }
                    }
                }

                if (uiState.isFormatting || uiState.isBenchmarking) {
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(uiState.operationMessage ?: "Traitement...", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            if (uiState.isFormatting) {
                                LinearProgressIndicator(
                                    progress = { uiState.operationProgress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text("${uiState.operationProgress}%")
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }

                uiState.operationMessage?.let { msg ->
                    if (!uiState.isFormatting && !uiState.isBenchmarking) {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.width(8.dp))
                                Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }

                // Tab content
                when (selectedTab) {
                    DiskTab.INFO -> InfoTab(device)
                    DiskTab.BENCHMARK -> BenchmarkTab(
                        device = device,
                        isBenchmarking = uiState.isBenchmarking,
                        benchmarkResult = uiState.benchmarkResult,
                        onStartBenchmark = { viewModel.startBenchmark() }
                    )
                    DiskTab.FORMAT -> FormatTab(
                        device = device,
                        isFormatting = uiState.isFormatting,
                        selectedFormat = uiState.selectedFormatType,
                        onFormatTypeChange = { viewModel.setFormatType(it) },
                        onFormatClick = {
                            formatLabel = ""
                            showLabelDialog = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoTab(device: DiskDevice) {
    // Device info card
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.UsbOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Informations du périphérique", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            InfoRow("Nom", device.name)
            InfoRow("Vendor ID", "0x${device.vendorId.toString(16).uppercase()}")
            InfoRow("Product ID", "0x${device.productId.toString(16).uppercase()}")
            device.serialNumber?.let { InfoRow("N° Série", it) }
            InfoRow("Système de fichiers", device.fileSystem.displayName)
            InfoRow("Point de montage", device.mountPoint ?: "Non monté")
            InfoRow("Statut", if (device.isMounted) "✓ Monté" else "✗ Non monté")
            InfoRow("Accessible en écriture", if (device.isWritable) "Oui" else "Non")
        }
    }

    // Storage card
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Stockage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StorageLabel("Total", FileItem.formatSize(device.totalSpace), MaterialTheme.colorScheme.primary)
                StorageLabel("Utilisé", FileItem.formatSize(device.usedSpace), Color(0xFFFF9800))
                StorageLabel("Libre", FileItem.formatSize(device.freeSpace), Color(0xFF4CAF50))
            }

            if (device.totalSpace > 0) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { device.usedPercent },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = when {
                        device.usedPercent > 0.9f -> MaterialTheme.colorScheme.error
                        device.usedPercent > 0.7f -> Color(0xFFFF9800)
                        else -> MaterialTheme.colorScheme.primary
                    }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "%.1f%% de ${FileItem.formatSize(device.totalSpace)} utilisé".format(device.usedPercent * 100),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Montez le périphérique pour voir l'espace disque",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BenchmarkTab(
    device: DiskDevice,
    isBenchmarking: Boolean,
    benchmarkResult: BenchmarkResult?,
    onStartBenchmark: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Speed, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Benchmark de performance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))

            benchmarkResult?.let { result ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BenchmarkMetric(
                        "Lecture",
                        "%.1f MB/s".format(result.readSpeedMBps),
                        Icons.Default.CloudDownload,
                        Color(0xFF4CAF50)
                    )
                    BenchmarkMetric(
                        "Écriture",
                        "%.1f MB/s".format(result.writeSpeedMBps),
                        Icons.Default.CloudUpload,
                        Color(0xFF2196F3)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Taille de test : ${result.testFileSizeMB} Mo",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
            }

            Button(
                onClick = onStartBenchmark,
                enabled = !isBenchmarking && device.isMounted,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isBenchmarking) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Benchmark en cours...")
                } else {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (benchmarkResult != null) "Relancer le benchmark" else "Démarrer le benchmark")
                }
            }

            if (!device.isMounted) {
                Spacer(Modifier.height(8.dp))
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
                        Text("Montez d'abord le périphérique", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
    }

    // Benchmark info card
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("À propos du benchmark", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("• Teste la vitesse de lecture et d'écriture séquentielle", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("• Utilise un fichier test temporaire de 10 Mo", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("• Les performances varient selon le port USB et la clé", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatTab(
    device: DiskDevice,
    isFormatting: Boolean,
    selectedFormat: String,
    onFormatTypeChange: (String) -> Unit,
    onFormatClick: () -> Unit
) {
    val formatOptions = listOf(
        FormatOption("FAT32", "Compatible PS2/OPL, max 4 Go/fichier", Color(0xFF4CAF50)),
        FormatOption("exFAT", "Fichiers >4 Go, compatible Windows/Mac", Color(0xFF2196F3)),
        FormatOption("NTFS", "Windows natif, nécessite Shizuku", Color(0xFF9C27B0)),
        FormatOption("EXT4", "Linux, nécessite Shizuku", Color(0xFFFF9800))
    )

    // Warning banner
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Text(
                "Le formatage efface DÉFINITIVEMENT toutes les données. Cette opération est irréversible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    // Format type selection
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FormatColorFill, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("Système de fichiers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))

            formatOptions.forEach { option ->
                val selected = selectedFormat == option.name
                Card(
                    onClick = { onFormatTypeChange(option.name) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        RadioButton(selected = selected, onClick = { onFormatTypeChange(option.name) })
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                option.name,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                option.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            color = option.color,
                            shape = CircleShape,
                            modifier = Modifier.size(10.dp)
                        ) {}
                    }
                }
            }
        }
    }

    // Device info recap
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Récapitulatif", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            InfoRow("Disque cible", device.name)
            InfoRow("Format sélectionné", selectedFormat)
            InfoRow("Taille", FileItem.formatSize(device.totalSpace))
        }
    }

    // Format button
    Button(
        onClick = onFormatClick,
        enabled = !isFormatting,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        if (isFormatting) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
            Spacer(Modifier.width(8.dp))
            Text("Formatage en cours...", fontWeight = FontWeight.Bold)
        } else {
            Icon(Icons.Default.DeleteForever, null)
            Spacer(Modifier.width(8.dp))
            Text("Formater en $selectedFormat", fontWeight = FontWeight.Bold)
        }
    }
}

private data class FormatOption(val name: String, val description: String, val color: Color)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (label.contains("ID") || label.contains("Série") || label.contains("Point") || label.contains("montage"))
                FontFamily.Monospace else FontFamily.Default
        )
    }
}

@Composable
private fun StorageLabel(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BenchmarkMetric(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color, modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace, fontSize = 18.sp)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
