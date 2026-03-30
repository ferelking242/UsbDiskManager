package com.usbdiskmanager.ps2.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.ps2.telegram.TelegramChannelConfig
import com.usbdiskmanager.ps2.telegram.TelegramGamePost

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramDownloadScreen(viewModel: Ps2ViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tgState = uiState.telegramState

    when {
        !tgState.isConfigured -> TelegramSetupScreen(viewModel)
        else                  -> TelegramBrowserScreen(viewModel, tgState)
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Setup Screen
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelegramSetupScreen(viewModel: Ps2ViewModel) {
    var sessionString by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var showSession by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showGuide by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            // Header
            Surface(
                color = Color(0xFF1A237E).copy(alpha = 0.15f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(Color(0xFF0088CC), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    }
                    Column {
                        Text("Telegram — Jeux PS2", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Téléchargez des ISOs PS2 depuis des canaux Telegram. Une session est requise.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Guide card
        item {
            Card(
                onClick = { showGuide = !showGuide },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp).animateContentSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.HelpOutline, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                            Text("Comment obtenir les credentials ?", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Icon(
                            if (showGuide) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    AnimatedVisibility(showGuide) {
                        Column(
                            modifier = Modifier.padding(top = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StepCard("1", "Obtenez api_id & api_hash",
                                "Allez sur my.telegram.org → API development tools → créez une app. Copiez l'api_id et l'api_hash.")
                            StepCard("2", "Générez une Session String",
                                "Installez Python + Pyrogram :\npip install pyrogram\n\nPuis exécutez ce script :")
                            // Script code block
                            Surface(
                                color = Color(0xFF1E1E1E),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    val code = """from pyrogram import Client
app = Client(
  "my_session",
  api_id=VOTRE_API_ID,
  api_hash="VOTRE_API_HASH"
)
with app:
  print(app.export_session_string())"""
                                    Text(code, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFF4FC3F7))
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = { clipboard.setText(AnnotatedString(code)) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copier", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            StepCard("3", "Collez les infos ci-dessous",
                                "Collez la session string générée et votre api_hash, puis appuyez sur Valider.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { uriHandler.openUri("https://my.telegram.org/apps") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("my.telegram.org", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Session string input
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Session String", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = sessionString,
                    onValueChange = { sessionString = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Coller la session string Pyrogram ici") },
                    visualTransformation = if (showSession) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row {
                            IconButton(onClick = {
                                val clip = clipboard.getText()?.text
                                if (!clip.isNullOrBlank()) sessionString = clip.trim()
                            }) {
                                Icon(Icons.Default.ContentPaste, "Coller")
                            }
                            IconButton(onClick = { showSession = !showSession }) {
                                Icon(if (showSession) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                            }
                        }
                    },
                    isError = error != null && sessionString.isNotBlank(),
                    minLines = 2,
                    maxLines = 4
                )
                Text(
                    "Format Pyrogram v2 (275 bytes encodés en base64url)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        // API Hash input
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("api_hash", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = apiHash,
                    onValueChange = { apiHash = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Votre api_hash (my.telegram.org)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (showSession) VisualTransformation.None else PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Key, null) }
                )
            }
        }

        // Error
        error?.let { err ->
            item {
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
                    Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }

        // Validate button
        item {
            Button(
                onClick = {
                    if (sessionString.isBlank()) { error = "La session string est vide."; return@Button }
                    if (apiHash.isBlank()) { error = "L'api_hash est requis."; return@Button }
                    viewModel.setupTelegram(sessionString.trim(), apiHash.trim()) { err ->
                        error = err
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC))
            ) {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Valider la session", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun StepCard(number: String, title: String, body: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Browser Screen
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelegramBrowserScreen(viewModel: Ps2ViewModel, tgState: TelegramUiState) {
    var showAddChannel by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<TelegramChannelConfig?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current

    // Add channel dialog
    if (showAddChannel) {
        AddChannelDialog(
            onAdd = { username, name ->
                viewModel.addTelegramChannel(username, name)
                showAddChannel = false
            },
            onDismiss = { showAddChannel = false }
        )
    }

    // Delete channel dialog
    showDeleteConfirm?.let { chan ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            icon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Supprimer le canal ?") },
            text = { Text("@${chan.username}") },
            confirmButton = {
                Button(
                    onClick = { viewModel.removeTelegramChannel(chan.username); showDeleteConfirm = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Supprimer") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = null }) { Text("Annuler") } }
        )
    }

    // Disconnect dialog
    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            icon = { Icon(Icons.Default.LinkOff, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Déconnecter Telegram ?") },
            text = { Text("La session sera supprimée. Vous devrez recoller votre session string.") },
            confirmButton = {
                Button(
                    onClick = { viewModel.disconnectTelegram(); showDisconnectConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Déconnecter") }
            },
            dismissButton = { TextButton(onClick = { showDisconnectConfirm = false }) { Text("Annuler") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Header ──
        Surface(color = Color(0xFF0088CC), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Telegram — Jeux PS2", fontWeight = FontWeight.Bold, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${tgState.channels.size} canal(aux) • ${tgState.allPosts.size} jeux indexés",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                IconButton(onClick = { viewModel.refreshTelegramPosts() }) {
                    if (tgState.isLoading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, null, tint = Color.White)
                }
                IconButton(onClick = { showDisconnectConfirm = true }) {
                    Icon(Icons.Default.Settings, null, tint = Color.White)
                }
            }
        }

        // ── Channel tabs ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tgState.channels.forEach { chan ->
                FilterChip(
                    selected = tgState.selectedChannel == chan.username,
                    onClick = { viewModel.selectTelegramChannel(chan.username) },
                    label = {
                        Text(
                            "@${chan.username}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (tgState.selectedChannel == chan.username) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    leadingIcon = { Icon(Icons.Default.Send, null, modifier = Modifier.size(13.dp)) },
                    trailingIcon = {
                        IconButton(
                            onClick = { showDeleteConfirm = chan },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(13.dp))
                        }
                    }
                )
            }
            // Add channel button
            InputChip(
                selected = false,
                onClick = { showAddChannel = true },
                label = { Text("+ Ajouter", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(13.dp)) }
            )
        }

        HorizontalDivider()

        // ── Error ──
        tgState.error?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearTelegramError() }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        val displayPosts = if (tgState.selectedChannel.isNullOrBlank()) tgState.allPosts
        else tgState.allPosts.filter { it.channelUsername == tgState.selectedChannel }

        if (displayPosts.isEmpty() && !tgState.isLoading) {
            // Empty state
            Column(
                modifier = Modifier.weight(1f).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(Modifier.height(16.dp))
                Text("Aucun jeu trouvé", style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Appuyez sur le bouton actualiser ou\nchoisissez un autre canal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.refreshTelegramPosts() }) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Actualiser")
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${displayPosts.size} jeu(x) disponible(s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Web • MTProto pour télécharger",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                items(displayPosts, key = { "${it.channelUsername}_${it.messageId}" }) { post ->
                    val dlProgress = tgState.downloads[post.messageId.toString()]
                    TelegramGameCard(
                        post = post,
                        downloadProgress = dlProgress,
                        onDownload = { viewModel.downloadTelegramGame(post) },
                        onOpenTelegram = {
                            uriHandler.openUri("https://t.me/${post.channelUsername}/${post.messageId}")
                        },
                        onLoadMore = { viewModel.loadMoreTelegramPosts(post.channelUsername, post.messageId) }
                    )
                }
                // Load more
                item {
                    tgState.selectedChannel?.let { ch ->
                        TextButton(
                            onClick = { viewModel.loadMoreTelegramPosts(ch, displayPosts.lastOrNull()?.messageId ?: 0) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Charger plus de messages")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelegramGameCard(
    post: TelegramGamePost,
    downloadProgress: com.usbdiskmanager.ps2.telegram.TelegramDownloadProgress?,
    onDownload: () -> Unit,
    onOpenTelegram: () -> Unit,
    onLoadMore: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Icon / cover area
            Surface(
                color = Color(0xFF0088CC).copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VideogameAsset, null,
                        tint = Color(0xFF0088CC),
                        modifier = Modifier.size(28.dp))
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    post.title.ifBlank { "Jeu PS2 #${post.messageId}" },
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (post.region.isNotBlank()) {
                        RegionBadge(post.region)
                    }
                    if (post.gameId.isNotBlank()) {
                        Text(post.gameId, style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(3.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(4.dp)) {
                        Text(
                            post.fileName.substringAfterLast('.').uppercase(),
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (post.sizeFormatted != "?") {
                        Text(post.sizeFormatted, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                    Text("@${post.channelUsername}", style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF0088CC), fontSize = 10.sp)
                }

                // Download progress
                downloadProgress?.let { prog ->
                    Spacer(Modifier.height(6.dp))
                    if (prog.isDone) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                            Text("Téléchargé ✓", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                        }
                    } else if (prog.error != null) {
                        Text("Erreur: ${prog.error}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Téléchargement…", style = MaterialTheme.typography.labelSmall)
                                Text("%.0f%%".format(prog.fraction * 100), style = MaterialTheme.typography.labelSmall, color = Color(0xFF0088CC))
                            }
                            LinearProgressIndicator(
                                progress = { prog.fraction },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = Color(0xFF0088CC)
                            )
                        }
                    }
                }
            }

            // Action buttons
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (downloadProgress?.isDone != true && downloadProgress?.error == null && downloadProgress?.fraction ?: 0f == 0f) {
                    FilledTonalIconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF0088CC).copy(alpha = 0.15f)
                        )
                    ) {
                        Icon(Icons.Default.Download, "Télécharger", modifier = Modifier.size(18.dp), tint = Color(0xFF0088CC))
                    }
                }
                FilledTonalIconButton(
                    onClick = onOpenTelegram,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.OpenInNew, "Ouvrir Telegram", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun RegionBadge(region: String) {
    val color = when (region) {
        "NTSC-U" -> Color(0xFF1565C0)
        "NTSC-J" -> Color(0xFFC62828)
        "PAL"    -> Color(0xFF2E7D32)
        else     -> Color(0xFF757575)
    }
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
        Text(
            region,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddChannelDialog(
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var username by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Add, null, tint = Color(0xFF0088CC)) },
        title = { Text("Ajouter un canal Telegram") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = {
                        username = it.removePrefix("https://t.me/").removePrefix("@").trim()
                    },
                    label = { Text("@username ou lien t.me/...") },
                    singleLine = true,
                    leadingIcon = { Text("@", modifier = Modifier.padding(start = 12.dp), fontWeight = FontWeight.Bold) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text("Nom affiché (optionnel)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Le canal doit être public. La navigation utilise la prévisualisation web, le téléchargement nécessite votre session Telegram.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (username.isNotBlank()) {
                        onAdd(username.trim(), displayName.ifBlank { "@$username" })
                    }
                },
                enabled = username.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC))
            ) { Text("Ajouter") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}
