package com.usbdiskmanager.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import com.usbdiskmanager.prefs.AppTheme
import com.usbdiskmanager.shizuku.ShizukuState
import com.usbdiskmanager.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateUp: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val currentLanguage by viewModel.language.collectAsStateWithLifecycle()
    val currentTheme by viewModel.theme.collectAsStateWithLifecycle()
    val shizukuState by viewModel.shizukuState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    val appVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) { "1.0.0" }
    }

    if (showLanguageDialog) {
        LanguageDialog(
            currentLanguage = currentLanguage,
            onSelect = { lang ->
                viewModel.setLanguage(lang)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            currentTheme = currentTheme,
            onSelect = { theme ->
                viewModel.setTheme(theme)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.settings_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
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
                .verticalScroll(rememberScrollState())
        ) {
            SettingsSectionHeader(stringResource(R.string.settings_section_general))

            SettingsItem(
                icon = Icons.Default.Language,
                title = stringResource(R.string.settings_language),
                subtitle = languageLabel(currentLanguage),
                onClick = { showLanguageDialog = true }
            )

            SettingsItem(
                icon = Icons.Default.Palette,
                title = stringResource(R.string.settings_theme),
                subtitle = themeLabel(currentTheme),
                onClick = { showThemeDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSectionHeader(stringResource(R.string.settings_section_shizuku))

            ShizukuSettingsCard(
                state = shizukuState,
                isInstalled = viewModel.isShizukuInstalled(),
                onRequestPermission = { viewModel.requestShizukuPermission() },
                onOpenApp = { viewModel.openShizukuApp() },
                onOpenPlayStore = { viewModel.openPlayStore() }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            SettingsSectionHeader(stringResource(R.string.settings_section_about))

            SettingsItem(
                icon = Icons.Default.Info,
                title = stringResource(R.string.settings_version),
                subtitle = "v$appVersion",
                onClick = {}
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        Icons.Default.Usb,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "USB Disk Manager",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_about_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Clean Architecture + MVVM + Hilt + Compose + Shizuku",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ShizukuSettingsCard(
    state: ShizukuState,
    isInstalled: Boolean,
    onRequestPermission: () -> Unit,
    onOpenApp: () -> Unit,
    onOpenPlayStore: () -> Unit
) {
    val (statusColor, statusLabel) = when (state) {
        is ShizukuState.Ready -> Color(0xFF4CAF50) to stringResource(R.string.shizuku_ready)
        is ShizukuState.PermissionNotRequested -> Color(0xFFFF9800) to stringResource(R.string.shizuku_permission_required)
        is ShizukuState.PermissionDenied -> MaterialTheme.colorScheme.error to stringResource(R.string.shizuku_permission_denied)
        is ShizukuState.NotRunning -> Color(0xFF7E57C2) to stringResource(R.string.shizuku_not_running)
        is ShizukuState.NotInstalled -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.shizuku_not_installed)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Icon(
                        painter = painterResource(R.drawable.ic_shizuku_cat),
                        contentDescription = "Shizuku",
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                            .align(Alignment.BottomEnd)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Shizuku",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        statusLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Text(
                stringResource(R.string.shizuku_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            if (state is ShizukuState.NotRunning) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh",
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (!isInstalled || state is ShizukuState.NotInstalled) {
                    Button(
                        onClick = onOpenPlayStore,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.GetApp, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.shizuku_install_playstore),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = onOpenApp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.shizuku_open_app),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                if (state is ShizukuState.PermissionNotRequested ||
                    state is ShizukuState.PermissionDenied
                ) {
                    FilledTonalButton(
                        onClick = onRequestPermission,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Shield, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (state is ShizukuState.PermissionDenied)
                                stringResource(R.string.shizuku_retry)
                            else
                                stringResource(R.string.shizuku_grant),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (state is ShizukuState.Ready) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        "Mount NTFS / EXT4 / F2FS",
                        "Format (mkfs.vfat / mkfs.ext4 / mkfs.ntfs)",
                        "blkid — precise filesystem detection",
                        "fdisk -l — partition listing",
                        "Safe unmount (sync + umount)"
                    ).forEach { feature ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                feature,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

data class LanguageOption(val code: String, val label: String, val flag: String)

private val LANGUAGES = listOf(
    LanguageOption("auto", "Auto (system)", ""),
    LanguageOption("en", "English", "EN"),
    LanguageOption("fr", "Francais", "FR"),
    LanguageOption("es", "Espanol", "ES"),
    LanguageOption("de", "Deutsch", "DE"),
    LanguageOption("pt", "Portugues", "PT")
)

@Composable
private fun LanguageDialog(
    currentLanguage: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Language, null) },
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LANGUAGES.forEach { option ->
                    val selected = currentLanguage == option.code
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelect(option.code) }
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (option.flag.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    option.flag,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                        } else {
                            Icon(
                                Icons.Default.Language,
                                null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        if (selected) {
                            Spacer(Modifier.weight(1f))
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

data class ThemeOption(
    val theme: AppTheme,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val isAvailable: Boolean = true
)

@Composable
private fun ThemeDialog(
    currentTheme: AppTheme,
    onSelect: (AppTheme) -> Unit,
    onDismiss: () -> Unit
) {
    val isDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    val options = listOf(
        ThemeOption(AppTheme.SYSTEM, stringResource(R.string.settings_theme_system), "Suit le système", Icons.Default.Smartphone),
        ThemeOption(AppTheme.LIGHT, stringResource(R.string.settings_theme_light), "Fond blanc, tons clairs", Icons.Default.LightMode),
        ThemeOption(AppTheme.DARK, stringResource(R.string.settings_theme_dark), "Fond sombre, bleu tech", Icons.Default.DarkMode),
        ThemeOption(AppTheme.AMOLED, "AMOLED Noir", "Noir pur, idéal OLED", Icons.Default.Contrast),
        ThemeOption(AppTheme.DYNAMIC, "Material You", "Couleurs dynamiques Android 12+", Icons.Default.AutoAwesome, isDynamic)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Palette, null) },
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { option ->
                    val selected = currentTheme == option.theme
                    val alpha = if (option.isAvailable) 1f else 0.4f
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = option.isAvailable) { onSelect(option.theme) }
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            option.icon, null,
                            modifier = Modifier.size(20.dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha)
                            )
                            Text(
                                if (!option.isAvailable) option.subtitle + " (Android 12+)"
                                else option.subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
                            )
                        }
                        if (selected) {
                            Icon(
                                Icons.Default.Check,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp)
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun languageLabel(code: String): String {
    return LANGUAGES.find { it.code == code }?.label
        ?: stringResource(R.string.settings_language_auto)
}

@Composable
private fun themeLabel(theme: AppTheme): String = when (theme) {
    AppTheme.LIGHT   -> stringResource(R.string.settings_theme_light)
    AppTheme.DARK    -> stringResource(R.string.settings_theme_dark)
    AppTheme.AMOLED  -> "AMOLED Noir"
    AppTheme.DYNAMIC -> "Material You"
    AppTheme.SYSTEM  -> stringResource(R.string.settings_theme_system)
}
