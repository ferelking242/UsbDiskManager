package com.usbdiskmanager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.core.model.FileItem
import com.usbdiskmanager.viewmodel.DiskDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiskDetailScreen(
    deviceId: String,
    onNavigateUp: () -> Unit,
    onOpenFileExplorer: (String) -> Unit,
    viewModel: DiskDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showLabelDialog by remember { mutableStateOf(false) }
    var formatLabel by remember { mutableStateOf("") }

    // Format confirmation dialog
    if (uiState.showFormatConfirmation) {
        AlertDialog(
            onDismissRequest = { viewModel.showFormatConfirmation(false) },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp)
                )
            },
            title = {
                Text(
                    "⚠️ Format Disk?",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column {
                    Text(
                        "This will PERMANENTLY ERASE all data on:\n\n" +
                                "${uiState.device?.name}\n\n" +
                                "Format: ${uiState.selectedFormatType}\n\n" +
                                "⚡ This action CANNOT be undone!",
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.formatDevice(formatLabel) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.DeleteForever, null)
                    Spacer(Modifier.width(4.dp))
                    Text("FORMAT NOW")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.showFormatConfirmation(false) }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.device?.name ?: "Disk Details") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    uiState.device?.mountPoint?.let { mountPoint ->
                        IconButton(onClick = { onOpenFileExplorer(mountPoint) }) {
                            Icon(Icons.Default.FolderOpen, "Browse Files")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            val device = uiState.device
            if (device == null) {
                Text("Device not found or disconnected", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            // Error/Success banner
            uiState.errorMessage?.let { err ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
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

            // Operation progress
            if (uiState.isFormatting || uiState.isBenchmarking) {
                Card {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            uiState.operationMessage ?: "Processing...",
                            style = MaterialTheme.typography.bodyMedium
                        )
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

            // Device Info Card
            DeviceInfoCard(device)

            // Storage Usage Card
            StorageUsageCard(device)

            // Benchmark Card
            BenchmarkCard(
                device = device,
                isBenchmarking = uiState.isBenchmarking,
                benchmarkResult = uiState.benchmarkResult,
                onStartBenchmark = { viewModel.startBenchmark() }
            )

            // Format Card
            FormatCard(
                isFormatting = uiState.isFormatting,
                selectedFormat = uiState.selectedFormatType,
                onFormatTypeChange = { viewModel.setFormatType(it) },
                onFormatClick = {
                    showLabelDialog = true
                }
            )

            // Label dialog
            if (showLabelDialog) {
                AlertDialog(
                    onDismissRequest = { showLabelDialog = false },
                    title = { Text("Volume Label (optional)") },
                    text = {
                        OutlinedTextField(
                            value = formatLabel,
                            onValueChange = { formatLabel = it.take(11) },
                            label = { Text("Label (max 11 chars)") },
                            singleLine = true
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            showLabelDialog = false
                            viewModel.showFormatConfirmation(true)
                        }) { Text("Continue") }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = { showLabelDialog = false }) { Text("Skip") }
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(device: DiskDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Device Information",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            InfoRow("Device Name", device.name)
            InfoRow("Vendor ID", "0x${device.vendorId.toString(16).uppercase()}")
            InfoRow("Product ID", "0x${device.productId.toString(16).uppercase()}")
            device.serialNumber?.let { InfoRow("Serial Number", it) }
            InfoRow("File System", device.fileSystem.displayName)
            InfoRow("Mount Point", device.mountPoint ?: "Not mounted")
            InfoRow("Status", if (device.isMounted) "✓ Mounted" else "✗ Not Mounted")
            InfoRow("Writable", if (device.isWritable) "Yes" else "No")
        }
    }
}

@Composable
private fun StorageUsageCard(device: DiskDevice) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Storage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StorageLabel("Total", FileItem.formatSize(device.totalSpace), MaterialTheme.colorScheme.primary)
                StorageLabel("Used", FileItem.formatSize(device.usedSpace), Color(0xFFFF9800))
                StorageLabel("Free", FileItem.formatSize(device.freeSpace), Color(0xFF4CAF50))
            }

            Spacer(Modifier.height(12.dp))

            if (device.totalSpace > 0) {
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
                    "%.1f%% of ${FileItem.formatSize(device.totalSpace)} used".format(device.usedPercent * 100),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BenchmarkCard(
    device: DiskDevice,
    isBenchmarking: Boolean,
    benchmarkResult: com.usbdiskmanager.core.model.BenchmarkResult?,
    onStartBenchmark: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Performance Benchmark",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(12.dp))

            benchmarkResult?.let { result ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BenchmarkMetric(
                        "Read",
                        "%.1f MB/s".format(result.readSpeedMBps),
                        Icons.Default.CloudDownload,
                        Color(0xFF4CAF50)
                    )
                    BenchmarkMetric(
                        "Write",
                        "%.1f MB/s".format(result.writeSpeedMBps),
                        Icons.Default.CloudUpload,
                        Color(0xFF2196F3)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Test size: ${result.testFileSizeMB}MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = onStartBenchmark,
                enabled = !isBenchmarking && device.isMounted,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isBenchmarking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Running...")
                } else {
                    Icon(Icons.Default.Speed, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (benchmarkResult != null) "Re-run Benchmark" else "Start Benchmark")
                }
            }

            if (!device.isMounted) {
                Text(
                    "Mount device first to run benchmark",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatCard(
    isFormatting: Boolean,
    selectedFormat: String,
    onFormatTypeChange: (String) -> Unit,
    onFormatClick: () -> Unit
) {
    val formatOptions = listOf("FAT32", "exFAT", "NTFS", "EXT4")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Warning,
                    null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Format Disk",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Permanently erase all data and reformat the disk.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            // File system picker
            Text("File System:", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formatOptions.forEach { fs ->
                    FilterChip(
                        selected = selectedFormat == fs,
                        onClick = { onFormatTypeChange(fs) },
                        label = { Text(fs) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Button(
                onClick = onFormatClick,
                enabled = !isFormatting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isFormatting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Formatting...")
                } else {
                    Icon(Icons.Default.DeleteForever, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Format as $selectedFormat")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = if (label.contains("ID") || label.contains("Serial") || label.contains("Point"))
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
private fun BenchmarkMetric(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(4.dp))
        Text(value, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

