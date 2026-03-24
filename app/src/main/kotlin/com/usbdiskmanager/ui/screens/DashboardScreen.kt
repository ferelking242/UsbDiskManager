package com.usbdiskmanager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.shizuku.ShizukuState
import com.usbdiskmanager.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onDeviceClick: (String) -> Unit,
    onLogsClick: () -> Unit,
    onRequestSafPermission: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val devices by viewModel.connectedDevices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Usb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "USB Disk Manager",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    ShizukuStatusIcon(shizukuState)
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Default.Refresh, "Actualiser")
                    }
                    IconButton(onClick = onLogsClick) {
                        Icon(Icons.Default.Terminal, "Logs")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── Shizuku status banner ───────────────────────────────────────
            ShizukuBanner(
                state = shizukuState,
                onRequestPermission = { viewModel.requestShizukuPermission() }
            )

            // ── Operation result banner ─────────────────────────────────────
            AnimatedVisibility(
                visible = uiState.errorMessage != null || uiState.operationMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                val isError = uiState.errorMessage != null
                val msg = uiState.errorMessage ?: uiState.operationMessage ?: ""
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isError)
                            MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                            null,
                            tint = if (isError) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.clearError() },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, "Fermer", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ── Device list ─────────────────────────────────────────────────
            if (uiState.isLoading) {
                Box(
                    Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (devices.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(devices, key = { it.id }) { device ->
                        DeviceCard(
                            device = device,
                            shizukuReady = shizukuState.isReady,
                            onClick = { onDeviceClick(device.id) },
                            onMount = { viewModel.mountDevice(device.id) },
                            onUnmount = { viewModel.unmountDevice(device.id) }
                        )
                    }
                }
            }
        }
    }
}

// ─── Shizuku top-bar icon ─────────────────────────────────────────────────────

@Composable
private fun ShizukuStatusIcon(state: ShizukuState) {
    val (icon, tint) = when (state) {
        is ShizukuState.Ready ->
            Icons.Default.Shield to Color(0xFF4CAF50)
        is ShizukuState.PermissionNotRequested ->
            Icons.Default.Shield to Color(0xFFFF9800)
        is ShizukuState.PermissionDenied ->
            Icons.Default.Shield to MaterialTheme.colorScheme.error
        else ->
            Icons.Default.Shield to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Icon(
        icon,
        contentDescription = "Shizuku: ${state.displayLabel}",
        tint = tint,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(22.dp)
    )
}

// ─── Shizuku banner ─────────────────────────────────────────────────────────

@Composable
private fun ShizukuBanner(
    state: ShizukuState,
    onRequestPermission: () -> Unit
) {
    AnimatedVisibility(
        visible = state !is ShizukuState.Ready,
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val bgColor: Color
        val textColor: Color
        val icon: ImageVector
        val actionLabel: String?
        val detail: String

        when (state) {
            is ShizukuState.PermissionNotRequested -> {
                bgColor = Color(0xFFFFF8E1); textColor = Color(0xFFE65100)
                icon = Icons.Default.Shield; actionLabel = "Autoriser"
                detail = "Shizuku est prêt — cliquer pour activer l'accès privilégié"
            }
            is ShizukuState.PermissionDenied -> {
                bgColor = Color(0xFFFFEBEE); textColor = Color(0xFFC62828)
                icon = Icons.Default.Shield; actionLabel = "Réessayer"
                detail = "Permission refusée — cliquer pour demander à nouveau"
            }
            is ShizukuState.NotRunning -> {
                bgColor = Color(0xFFEDE7F6); textColor = Color(0xFF4527A0)
                icon = Icons.Default.PowerSettingsNew; actionLabel = null
                detail = "Démarrer via ADB:\nadb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"
            }
            is ShizukuState.NotInstalled -> {
                bgColor = Color(0xFFE3F2FD); textColor = Color(0xFF1565C0)
                icon = Icons.Default.GetApp; actionLabel = null
                detail = "Installer l'app Shizuku depuis le Play Store ou GitHub (rikka/Shizuku)"
            }
            is ShizukuState.Ready -> return@AnimatedVisibility
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(10.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, null, tint = textColor, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Shizuku — Accès privilégié",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor
                    )
                    Text(
                        detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontFamily = if (state is ShizukuState.NotRunning) FontFamily.Monospace
                                     else FontFamily.Default
                    )
                }
                if (actionLabel != null) {
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onRequestPermission,
                        modifier = Modifier.height(34.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(actionLabel, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ─── Device card ─────────────────────────────────────────────────────────────

@Composable
private fun DeviceCard(
    device: DiskDevice,
    shizukuReady: Boolean,
    onClick: () -> Unit,
    onMount: () -> Unit,
    onUnmount: () -> Unit
) {
    val headerGradient = if (device.isMounted)
        listOf(Color(0xFF1A237E), Color(0xFF283593))
    else
        listOf(Color(0xFF37474F), Color(0xFF455A64))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Gradient header ─────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Brush.horizontalGradient(headerGradient))
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.Storage, null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            device.name,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            device.mountPoint ?: "Non monté",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        StatusChip(
                            label = if (device.isMounted) "Monté" else "Non monté",
                            color = if (device.isMounted) Color(0xFF4CAF50) else Color(0xFFFF7043)
                        )
                        if (shizukuReady) {
                            StatusChip(label = "Shizuku", color = Color(0xFF66BB6A))
                        }
                    }
                }
            }

            // ── Storage info ────────────────────────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (device.totalSpace > 0) {
                    LinearProgressIndicator(
                        progress = { device.usedPercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = when {
                            device.usedPercent > 0.9f -> MaterialTheme.colorScheme.error
                            device.usedPercent > 0.75f -> Color(0xFFFF9800)
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StorageInfoItem("Total", formatBytes(device.totalSpace))
                    StorageInfoItem("Utilisé", formatBytes(device.usedSpace))
                    StorageInfoItem("Libre", formatBytes(device.freeSpace))
                    StorageInfoItem("Type", device.fileSystem.displayName)
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!device.isMounted) {
                        FilledTonalButton(onClick = onMount, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Link, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Monter")
                        }
                    } else {
                        OutlinedButton(onClick = onUnmount, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Éjecter")
                        }
                    }
                    Button(onClick = onClick, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Détails")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(label: String, color: Color) {
    Surface(color = color, shape = RoundedCornerShape(6.dp)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.UsbOff, null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
            Text(
                "Aucun périphérique USB détecté",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Connectez une clé USB via OTG\npuis appuyez sur Actualiser",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun StorageInfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            fontFamily = if (label == "Type") FontFamily.Default else FontFamily.Monospace
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "–"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var u = 0
    while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
    return "%.1f %s".format(v, units[u])
}
