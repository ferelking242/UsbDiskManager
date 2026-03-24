package com.usbdiskmanager.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.R
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.shizuku.ShizukuState
import com.usbdiskmanager.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onDeviceClick: (String) -> Unit,
    onLogsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRequestSafPermission: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val devices by viewModel.connectedDevices.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    var showShizukuDialog by remember { mutableStateOf(false) }

    if (showShizukuDialog) {
        ShizukuQuickDialog(
            state = shizukuState,
            onRequestPermission = {
                viewModel.requestShizukuPermission()
                showShizukuDialog = false
            },
            onDismiss = { showShizukuDialog = false }
        )
    }

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
                            stringResource(R.string.dashboard_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, stringResource(R.string.settings))
                    }
                    IconButton(onClick = { viewModel.refreshDevices() }) {
                        Icon(Icons.Default.Refresh, stringResource(R.string.refresh))
                    }
                    // Shizuku cat icon — shows status dot + opens quick dialog
                    ShizukuCatButton(
                        state = shizukuState,
                        onClick = { showShizukuDialog = true }
                    )
                    IconButton(onClick = onLogsClick) {
                        Icon(Icons.Default.Terminal, stringResource(R.string.logs))
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
            // ── Shizuku status banner ────────────────────────────────────────
            ShizukuBanner(
                state = shizukuState,
                onRequestPermission = { viewModel.requestShizukuPermission() }
            )

            // ── Operation result banner ──────────────────────────────────────
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
                            Icon(Icons.Default.Close, stringResource(R.string.close), modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // ── Device list ──────────────────────────────────────────────────
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

// ─── Shizuku cat header button ────────────────────────────────────────────────

@Composable
private fun ShizukuCatButton(
    state: ShizukuState,
    onClick: () -> Unit
) {
    val dotColor = when (state) {
        is ShizukuState.Ready -> Color(0xFF4CAF50)
        is ShizukuState.PermissionNotRequested -> Color(0xFFFF9800)
        is ShizukuState.PermissionDenied -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_shizuku_cat),
            contentDescription = "Shizuku: ${state.displayLabel}",
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
        // Status dot in top-right corner
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
                .align(Alignment.TopEnd)
                .offset(x = (-4).dp, y = 4.dp)
        )
    }
}

// ─── Shizuku quick dialog ─────────────────────────────────────────────────────

@Composable
private fun ShizukuQuickDialog(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_shizuku_cat),
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurface
            )
        },
        title = { Text("Shizuku") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Status
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (color, label) = when (state) {
                        is ShizukuState.Ready ->
                            Color(0xFF4CAF50) to state.displayLabel
                        is ShizukuState.PermissionNotRequested ->
                            Color(0xFFFF9800) to state.displayLabel
                        is ShizukuState.PermissionDenied ->
                            MaterialTheme.colorScheme.error to state.displayLabel
                        is ShizukuState.NotRunning ->
                            Color(0xFF7E57C2) to state.displayLabel
                        else -> MaterialTheme.colorScheme.onSurfaceVariant to state.displayLabel
                    }
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodySmall, color = color)
                }

                Text(
                    stringResource(R.string.shizuku_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (state is ShizukuState.Ready) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf("NTFS / EXT4 mount", "Format (mkfs.*)", "blkid / fdisk / lsblk").forEach {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(13.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(it, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (state is ShizukuState.PermissionNotRequested || state is ShizukuState.PermissionDenied) {
                Button(onClick = onRequestPermission) {
                    Text(stringResource(R.string.shizuku_grant))
                }
            } else if (state is ShizukuState.NotInstalled || state is ShizukuState.NotRunning) {
                Button(onClick = {
                    try {
                        val uri = Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {}
                    onDismiss()
                }) {
                    Text(stringResource(R.string.shizuku_install_playstore))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

// ─── Shizuku banner ───────────────────────────────────────────────────────────

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
                icon = Icons.Default.Shield
                actionLabel = stringResource(R.string.shizuku_grant)
                detail = stringResource(R.string.shizuku_detail_permission_pending)
            }
            is ShizukuState.PermissionDenied -> {
                bgColor = Color(0xFFFFEBEE); textColor = Color(0xFFC62828)
                icon = Icons.Default.Shield
                actionLabel = stringResource(R.string.shizuku_retry)
                detail = stringResource(R.string.shizuku_detail_permission_denied)
            }
            is ShizukuState.NotRunning -> {
                bgColor = Color(0xFFEDE7F6); textColor = Color(0xFF4527A0)
                icon = Icons.Default.PowerSettingsNew; actionLabel = null
                detail = stringResource(R.string.shizuku_detail_not_running)
            }
            is ShizukuState.NotInstalled -> {
                bgColor = Color(0xFFE3F2FD); textColor = Color(0xFF1565C0)
                icon = Icons.Default.GetApp; actionLabel = null
                detail = stringResource(R.string.shizuku_detail_not_installed)
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
                        stringResource(R.string.shizuku_title),
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

// ─── Device card ──────────────────────────────────────────────────────────────

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
            // ── Gradient header ──────────────────────────────────────────────
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
                            device.mountPoint ?: stringResource(R.string.device_not_mounted),
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatusChip(
                            label = if (device.isMounted)
                                stringResource(R.string.device_mounted)
                            else
                                stringResource(R.string.device_not_mounted),
                            color = if (device.isMounted) Color(0xFF4CAF50) else Color(0xFFFF7043)
                        )
                        if (shizukuReady) {
                            StatusChip(label = "Shizuku", color = Color(0xFF66BB6A))
                        }
                    }
                }
            }

            // ── Storage info ─────────────────────────────────────────────────
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
                    StorageInfoItem(stringResource(R.string.storage_total), formatBytes(device.totalSpace))
                    StorageInfoItem(stringResource(R.string.storage_used), formatBytes(device.usedSpace))
                    StorageInfoItem(stringResource(R.string.storage_free), formatBytes(device.freeSpace))
                    StorageInfoItem(stringResource(R.string.storage_type), device.fileSystem.displayName)
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!device.isMounted) {
                        FilledTonalButton(onClick = onMount, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Link, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_mount))
                        }
                    } else {
                        OutlinedButton(onClick = onUnmount, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.LinkOff, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.action_eject))
                        }
                    }
                    Button(onClick = onClick, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.action_details))
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
                stringResource(R.string.no_devices_found),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.no_devices_hint),
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
            fontFamily = FontFamily.Monospace
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "--"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var v = bytes.toDouble(); var u = 0
    while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
    return "%.1f %s".format(v, units[u])
}
