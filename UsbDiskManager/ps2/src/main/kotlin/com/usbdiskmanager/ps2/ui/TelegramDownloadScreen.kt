package com.usbdiskmanager.ps2.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.usbdiskmanager.ps2.data.download.TelegramDownloadManager
import com.usbdiskmanager.ps2.data.download.TgDownloadProgress
import com.usbdiskmanager.ps2.data.download.TgDownloadStatus
import com.usbdiskmanager.ps2.telegram.TelegramChannelConfig
import com.usbdiskmanager.ps2.telegram.TelegramGamePost
import com.usbdiskmanager.ps2.telegram.TelegramSetupState

// ──────────────────────────────────────────────────────────────────────────────
// Root screen — routes based on auth state
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramDownloadScreen(viewModel: Ps2ViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tgState = uiState.telegramState

    AnimatedContent(targetState = tgState.setupState, label = "tg_auth") { state ->
        when (state) {
            is TelegramSetupState.NotConfigured ->
                TelegramCredentialsScreen(viewModel)
            is TelegramSetupState.WaitingPhoneNumber ->
                TelegramPhoneScreen(viewModel)
            is TelegramSetupState.WaitingCode ->
                TelegramCodeScreen(viewModel, state.phoneNumber)
            is TelegramSetupState.WaitingPassword ->
                TelegramPasswordScreen(viewModel)
            is TelegramSetupState.Ready ->
                TelegramBrowserScreen(viewModel, tgState)
            is TelegramSetupState.Error ->
                TelegramErrorScreen(state.message) { viewModel.disconnectTelegram() }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Step 1 — Credentials (api_id + api_hash)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelegramCredentialsScreen(viewModel: Ps2ViewModel) {
    var apiIdText by remember { mutableStateOf("") }
    var apiHash by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { TelegramHeader("Connexion Telegram", "Entrez vos identifiants API pour activer les téléchargements TDLib.") }

        item {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                Column(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Comment obtenir api_id & api_hash ?", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                    StepItem("1", "Allez sur my.telegram.org")
                    StepItem("2", "Connectez-vous avec votre numéro de téléphone")
                    StepItem("3", "Cliquez sur « API development tools »")
                    StepItem("4", "Créez une app (nom libre) → copiez api_id & api_hash")
                    OutlinedButton(
                        onClick = { uriHandler.openUri("https://my.telegram.org/apps") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.OpenInBrowser, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ouvrir my.telegram.org")
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("api_id", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = apiIdText,
                    onValueChange = { apiIdText = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Numéro entier (ex: 1234567)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    leadingIcon = { Icon(Icons.Default.Numbers, null) }
                )
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("api_hash", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = apiHash,
                    onValueChange = { apiHash = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Chaîne hexadécimale (32 chars)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Default.Key, null) }
                )
            }
        }

        error?.let { err ->
            item { ErrorBanner(err) }
        }

        item {
            Button(
                onClick = {
                    val id = apiIdText.trim().toIntOrNull()
                    when {
                        id == null || id <= 0 -> error = "api_id invalide (nombre entier requis)"
                        apiHash.trim().length < 10 -> error = "api_hash trop court"
                        else -> viewModel.saveTelegramCredentials(id, apiHash.trim())
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(13.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC))
            ) {
                Icon(Icons.Default.ArrowForward, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Suivant", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Step 2 — Phone number
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelegramPhoneScreen(viewModel: Ps2ViewModel) {
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        TelegramHeader("Numéro de téléphone", "Entrez le numéro associé à votre compte Telegram. Un code vous sera envoyé.")

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ex: +33612345678") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            leadingIcon = { Icon(Icons.Default.Phone, null) },
            isError = error != null
        )

        error?.let { ErrorBanner(it) }

        Button(
            onClick = {
                val p = phone.trim()
                if (p.length < 7) { error = "Numéro trop court"; return@Button }
                loading = true
                viewModel.sendTelegramPhone(p) { err ->
                    error = err
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC)),
            enabled = !loading
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Send, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Envoyer le code", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        TextButton(onClick = { viewModel.disconnectTelegram() }) {
            Text("Annuler / Changer les credentials")
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Step 3 — OTP code
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelegramCodeScreen(viewModel: Ps2ViewModel, phone: String) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        TelegramHeader("Code de vérification", "Un code a été envoyé à $phone via Telegram.")

        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter { c -> c.isDigit() }; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Code à 5 chiffres") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            leadingIcon = { Icon(Icons.Default.Pin, null) },
            isError = error != null
        )

        error?.let { ErrorBanner(it) }

        Button(
            onClick = {
                if (code.length < 4) { error = "Code trop court"; return@Button }
                loading = true
                viewModel.sendTelegramCode(code.trim()) { err ->
                    error = err
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC)),
            enabled = !loading
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.Check, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Valider le code", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        TextButton(onClick = { viewModel.disconnectTelegram() }) {
            Text("Recommencer")
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Step 4 — 2FA password (optional)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelegramPasswordScreen(viewModel: Ps2ViewModel) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        TelegramHeader("Mot de passe 2FA", "Votre compte a la vérification en deux étapes activée.")

        OutlinedTextField(
            value = password,
            onValueChange = { password = it; error = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Mot de passe cloud Telegram") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            leadingIcon = { Icon(Icons.Default.Lock, null) },
            isError = error != null
        )

        error?.let { ErrorBanner(it) }

        Button(
            onClick = {
                if (password.isBlank()) { error = "Mot de passe vide"; return@Button }
                loading = true
                viewModel.sendTelegramPassword(password) { err ->
                    error = err
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(13.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC)),
            enabled = !loading
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.LockOpen, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Se connecter", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Error state screen
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelegramErrorScreen(message: String, onReset: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.Error, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text("Erreur Telegram", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onReset) { Text("Recommencer") }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Browser Screen (main screen when ready)
// ──────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TelegramBrowserScreen(viewModel: Ps2ViewModel, tgState: TelegramUiState) {
    var showAddChannel by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<TelegramChannelConfig?>(null) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    var isGridView by remember { mutableStateOf(true) }
    val uriHandler = LocalUriHandler.current

    if (showAddChannel) {
        AddChannelDialog(
            onAdd = { username, name -> viewModel.addTelegramChannel(username, name); showAddChannel = false },
            onDismiss = { showAddChannel = false }
        )
    }

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

    if (showDisconnectConfirm) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirm = false },
            icon = { Icon(Icons.Default.LinkOff, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Déconnecter Telegram ?") },
            text = { Text("La session TDLib sera supprimée.") },
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
                    Text(
                        "Telegram — Jeux PS2",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        buildString {
                            append("${tgState.channels.size} canal(aux) • ${tgState.allPosts.size} jeux")
                            if (tgState.usingTDLib) append(" • TDLib natif ⚡") else append(" • Web")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
                IconButton(onClick = { isGridView = !isGridView }) {
                    Icon(
                        if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                        null, tint = Color.White
                    )
                }
                IconButton(onClick = { viewModel.refreshTelegramPosts() }) {
                    if (tgState.isLoading)
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else
                        Icon(Icons.Default.Refresh, null, tint = Color.White)
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
                        IconButton(onClick = { showDeleteConfirm = chan }, modifier = Modifier.size(18.dp)) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(13.dp))
                        }
                    }
                )
            }
            InputChip(
                selected = false,
                onClick = { showAddChannel = true },
                label = { Text("+ Ajouter", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = { Icon(Icons.Default.Add, null, modifier = Modifier.size(13.dp)) }
            )
        }

        HorizontalDivider()

        tgState.error?.let { err ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
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
            Column(
                modifier = Modifier.weight(1f).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Spacer(Modifier.height(16.dp))
                Text("Aucun jeu trouvé", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { viewModel.refreshTelegramPosts() }) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Actualiser")
                }
            }
        } else {
            // ── Compteur + CDN label ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
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
                    if (tgState.usingTDLib) "TDLib natif ⚡" else "Web",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF0088CC)
                )
            }

            if (isGridView) {
                // ── Vue grille 2x2 ──
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(displayPosts, key = { "${it.channelUsername}_${it.messageId}" }) { post ->
                        val dlId = TelegramDownloadManager.downloadId(post.channelUsername, post.messageId)
                        val dlProgress = tgState.downloads[dlId]
                        TelegramGameGridCard(
                            post = post,
                            downloadProgress = dlProgress,
                            onDownload = { viewModel.downloadTelegramGame(post) },
                            onCancel = { viewModel.cancelTelegramDownload(dlId) },
                            onOpenTelegram = { uriHandler.openUri("https://t.me/${post.channelUsername}/${post.messageId}") }
                        )
                    }
                    item {
                        tgState.selectedChannel?.let { ch ->
                            TextButton(
                                onClick = { viewModel.loadMoreTelegramPosts(ch, displayPosts.lastOrNull()?.messageId ?: 0) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Plus")
                            }
                        }
                    }
                }
            } else {
                // ── Vue liste ──
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(displayPosts, key = { "${it.channelUsername}_${it.messageId}" }) { post ->
                        val dlId = TelegramDownloadManager.downloadId(post.channelUsername, post.messageId)
                        val dlProgress = tgState.downloads[dlId]
                        TelegramGameCard(
                            post = post,
                            downloadProgress = dlProgress,
                            onDownload = { viewModel.downloadTelegramGame(post) },
                            onCancel = { viewModel.cancelTelegramDownload(dlId) },
                            onOpenTelegram = { uriHandler.openUri("https://t.me/${post.channelUsername}/${post.messageId}") }
                        )
                    }
                    item {
                        tgState.selectedChannel?.let { ch ->
                            TextButton(
                                onClick = { viewModel.loadMoreTelegramPosts(ch, displayPosts.lastOrNull()?.messageId ?: 0) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ExpandMore, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Charger plus")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Game card
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelegramGameCard(
    post: TelegramGamePost,
    downloadProgress: TgDownloadProgress?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpenTelegram: () -> Unit
) {
    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Surface(
                color = Color(0xFF0088CC).copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(52.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.VideogameAsset, null, tint = Color(0xFF0088CC), modifier = Modifier.size(28.dp))
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
                    if (post.region.isNotBlank()) RegionBadge(post.region)
                    if (post.gameId.isNotBlank()) {
                        Text(post.gameId, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
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
                        Text(post.sizeFormatted, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    Text("@${post.channelUsername}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF0088CC), fontSize = 10.sp)
                }

                // Progress
                downloadProgress?.let { prog ->
                    Spacer(Modifier.height(6.dp))
                    when {
                        prog.status == TgDownloadStatus.DONE -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                                Text("Téléchargé ✓", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                            }
                        }
                        prog.status == TgDownloadStatus.ERROR -> {
                            Text("Erreur: ${prog.error}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                        prog.status == TgDownloadStatus.QUEUED -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                                Text("En attente…", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        else -> {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("%.0f%%".format(prog.fraction * 100), style = MaterialTheme.typography.labelSmall, color = Color(0xFF0088CC), fontWeight = FontWeight.Bold)
                                    Text(prog.speedFormatted, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                                LinearProgressIndicator(
                                    progress = { prog.fraction },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = Color(0xFF0088CC)
                                )
                                if (prog.etaSeconds > 0) {
                                    val etaText = if (prog.etaSeconds > 60) "${prog.etaSeconds / 60}m ${prog.etaSeconds % 60}s"
                                    else "${prog.etaSeconds}s"
                                    Text("ETA: $etaText", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                }
            }

            // Action column
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val isActive = downloadProgress?.status == TgDownloadStatus.DOWNLOADING ||
                    downloadProgress?.status == TgDownloadStatus.QUEUED
                val isDone = downloadProgress?.status == TgDownloadStatus.DONE

                if (!isDone && !isActive) {
                    FilledTonalIconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF0088CC).copy(alpha = 0.15f))
                    ) {
                        Icon(Icons.Default.Download, "Télécharger", modifier = Modifier.size(18.dp), tint = Color(0xFF0088CC))
                    }
                }
                if (isActive) {
                    FilledTonalIconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Icon(Icons.Default.Stop, "Annuler", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
                FilledTonalIconButton(onClick = onOpenTelegram, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.OpenInNew, "Ouvrir Telegram", modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Grid card (2x2 layout with thumbnail photo)
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelegramGameGridCard(
    post: TelegramGamePost,
    downloadProgress: TgDownloadProgress?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onOpenTelegram: () -> Unit
) {
    val isActive = downloadProgress?.status == TgDownloadStatus.DOWNLOADING ||
        downloadProgress?.status == TgDownloadStatus.QUEUED
    val isDone = downloadProgress?.status == TgDownloadStatus.DONE

    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // ── Thumbnail / placeholder ──
            if (!post.thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = post.thumbnailUrl,
                    contentDescription = post.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF0D1B2A), Color(0xFF1A3550))
                            ),
                            RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.VideogameAsset,
                            null,
                            tint = Color(0xFF0088CC).copy(alpha = 0.6f),
                            modifier = Modifier.size(42.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            post.fileName.substringAfterLast('.').uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF0088CC).copy(alpha = 0.8f),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // ── Download button overlay (top-right) ──
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                if (!isDone && !isActive) {
                    FilledTonalIconButton(
                        onClick = onDownload,
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = Color(0xFF0088CC)
                        )
                    ) {
                        Icon(Icons.Default.Download, null, modifier = Modifier.size(17.dp), tint = Color.White)
                    }
                } else if (isActive) {
                    FilledTonalIconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    // done
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(Color(0xFF4CAF50), RoundedCornerShape(50)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(17.dp))
                    }
                }
            }

            // ── Region badge overlay (top-left) ──
            if (post.region.isNotBlank()) {
                Box(modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                    RegionBadge(post.region)
                }
            }
        }

        // ── Info bar below image ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                post.title.ifBlank { "Jeu PS2 #${post.messageId}" },
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp
            )
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    post.sizeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    "@${post.channelUsername}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF0088CC),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Progress bar ──
            downloadProgress?.let { prog ->
                Spacer(Modifier.height(5.dp))
                when {
                    prog.status == TgDownloadStatus.DONE -> {
                        Text("Téléchargé ✓", style = MaterialTheme.typography.labelSmall, color = Color(0xFF4CAF50))
                    }
                    prog.status == TgDownloadStatus.ERROR -> {
                        Text("Erreur", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1)
                    }
                    else -> {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("%.0f%%".format(prog.fraction * 100), style = MaterialTheme.typography.labelSmall, color = Color(0xFF0088CC), fontWeight = FontWeight.Bold)
                                Text(prog.speedFormatted, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                            LinearProgressIndicator(
                                progress = { prog.fraction },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                                color = Color(0xFF0088CC)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────────────────────
// Shared components
// ──────────────────────────────────────────────────────────────────────────────

@Composable
private fun TelegramHeader(title: String, subtitle: String) {
    Surface(color = Color(0xFF1A237E).copy(alpha = 0.15f), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).background(Color(0xFF0088CC), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Send, null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Column {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(10.dp)) {
        Row(modifier = Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun StepItem(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RegionBadge(region: String) {
    val color = when (region) {
        "NTSC-U" -> Color(0xFF1565C0)
        "NTSC-J" -> Color(0xFFC62828)
        "PAL" -> Color(0xFF2E7D32)
        else -> Color(0xFF757575)
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
private fun AddChannelDialog(onAdd: (String, String) -> Unit, onDismiss: () -> Unit) {
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
                    onValueChange = { username = it.removePrefix("https://t.me/").removePrefix("@").trim() },
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
                    "Le canal doit être public. La navigation utilise le web, le téléchargement se fait via TDLib (CDN multi-connexion).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (username.isNotBlank()) onAdd(username.trim(), displayName.ifBlank { "@$username" }) },
                enabled = username.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0088CC))
            ) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}
