package com.usbdiskmanager.ps2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.usbdiskmanager.ps2.data.converter.UlCfgManager
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class SourceCfg(
    val fileName: String,
    val file: File,
    val entries: List<UlCfgManager.UlEntry>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UlCfgMergerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfgManager = remember { UlCfgManager() }

    var sources by remember { mutableStateOf<List<SourceCfg>>(emptyList()) }
    var destEntries by remember { mutableStateOf<List<UlCfgManager.UlEntry>>(emptyList()) }
    var destPath by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var editedNames by remember { mutableStateOf<Map<Pair<Int, Int>, String>>(emptyMap()) }
    var editingKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editDialogText by remember { mutableStateOf("") }

    val defaultCfgFile = remember { File(IsoScanner.DEFAULT_UL_DIR, "ul.cfg") }

    // Compute the destination file from chosen path or default
    val destFile by remember(destPath) {
        derivedStateOf {
            if (destPath != null) File(destPath!!, "ul.cfg") else defaultCfgFile
        }
    }

    // Load dest entries when destFile changes
    LaunchedEffect(destFile) {
        withContext(Dispatchers.IO) {
            destEntries = if (destFile.exists()) cfgManager.readAllEntries(destFile) else emptyList()
        }
    }

    // Precompute duplicate sets (stable, not mutated during composition)
    val destIds: Set<String> = remember(destEntries) {
        destEntries.map { it.gameIdClean.trim().uppercase() }.toSet()
    }
    val allSourceGames: List<Triple<Int, Int, UlCfgManager.UlEntry>> = remember(sources) {
        sources.flatMapIndexed { si, src ->
            src.entries.mapIndexed { gi, e -> Triple(si, gi, e) }
        }
    }
    val duplicateKeys: Set<String> = remember(allSourceGames, destIds) {
        val seen = mutableSetOf<String>()
        val dups = mutableSetOf<String>()
        allSourceGames.forEach { (_, _, e) ->
            val id = e.gameIdClean.trim().uppercase()
            if (id in destIds || !seen.add(id)) dups.add(id)
        }
        dups
    }

    fun mergeAll() {
        if (sources.isEmpty()) { resultMessage = "Ajoutez au moins un fichier source."; return }
        scope.launch {
            isProcessing = true
            withContext(Dispatchers.IO) {
                try {
                    destFile.parentFile?.mkdirs()
                    var totalAdded = 0
                    sources.forEachIndexed { si, source ->
                        val entries = source.entries.mapIndexed { gi, entry ->
                            val newName = editedNames[si to gi]
                            if (newName != null && newName.isNotBlank()) entry.copy(gameName = newName.take(32))
                            else entry
                        }
                        val tempFile = File(context.cacheDir, "ul_merge_temp_$si.cfg")
                        cfgManager.writeEntriesPublic(tempFile, entries)
                        totalAdded += cfgManager.mergeInto(tempFile, destFile)
                        tempFile.delete()
                    }
                    destEntries = cfgManager.readAllEntries(destFile)
                    resultMessage = "✓ Fusion terminée — $totalAdded nouveau(x) jeu(x) ajouté(s). Total: ${destEntries.size}."
                } catch (e: Exception) {
                    resultMessage = "Erreur fusion: ${e.message}"
                }
            }
            isProcessing = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                isProcessing = true
                withContext(Dispatchers.IO) {
                    try {
                        val idx = sources.size
                        val temp = File(context.cacheDir, "ul_src_$idx.cfg")
                        context.contentResolver.openInputStream(uri)?.use { inp ->
                            temp.outputStream().use { out -> inp.copyTo(out) }
                        }
                        val entries = cfgManager.readAllEntries(temp)
                        if (entries.isEmpty()) {
                            resultMessage = "Fichier vide ou format invalide."
                        } else {
                            val raw = uri.lastPathSegment ?: "source_$idx.cfg"
                            val name = raw.substringAfterLast('/').substringAfterLast(':')
                            sources = sources + SourceCfg(name, temp, entries)
                            resultMessage = null
                        }
                    } catch (e: Exception) {
                        resultMessage = "Erreur lecture: ${e.message}"
                    }
                }
                isProcessing = false
            }
        }
    }

    val destFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { treeUri ->
            val path = treeUri.path
            if (path != null) {
                val resolved = when {
                    path.contains("primary:") ->
                        "${android.os.Environment.getExternalStorageDirectory()}/${path.substringAfter("primary:")}"
                    path.contains(":") -> path.substringAfter(":")
                        .let { if (it.startsWith("/")) it else "/$it" }
                    else -> path
                }
                destPath = resolved
                resultMessage = null
            }
        }
    }

    // Edit dialog
    editingKey?.let { key ->
        AlertDialog(
            onDismissRequest = { editingKey = null },
            icon = { Icon(Icons.Default.Edit, null) },
            title = { Text("Renommer le jeu") },
            text = {
                OutlinedTextField(
                    value = editDialogText,
                    onValueChange = { if (it.length <= 32) editDialogText = it },
                    label = { Text("Nom du jeu (max 32)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    editedNames = editedNames + (key to editDialogText)
                    editingKey = null
                }) { Text("Valider") }
            },
            dismissButton = {
                TextButton(onClick = { editingKey = null }) { Text("Annuler") }
            }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {

        // ── Info banner ──
        item {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.MergeType, null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp).padding(top = 2.dp)
                    )
                    Column {
                        Text(
                            "Fusionner plusieurs ul.cfg",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "Ajoutez des fichiers source, vérifiez les doublons, choisissez la destination et fusionnez.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        // ── Source file actions bar ──
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { filePicker.launch("*/*") },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    enabled = !isProcessing
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Chargement…")
                    } else {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (sources.isEmpty()) "Ajouter un fichier source" else "Ajouter un autre fichier")
                    }
                }
                if (sources.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            sources = emptyList()
                            editedNames = emptyMap()
                            resultMessage = null
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        // ── Source files as a horizontal chip bar ──
        if (sources.isNotEmpty()) {
            item(key = "source_chips") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Fichiers source (${sources.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        sources.forEachIndexed { si, source ->
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = {
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text(
                                            source.fileName.take(20),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            "${source.entries.size} jeu(x)",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 10.sp
                                        )
                                    }
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.FileOpen, null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            sources = sources.toMutableList().also { it.removeAt(si) }
                                            editedNames = editedNames
                                                .filterKeys { (k, _) -> k != si }
                                                .mapKeys { (k, v) ->
                                                    if (k.first > si) (k.first - 1 to k.second) else k
                                                }
                                        },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close, null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                },
                                shape = RoundedCornerShape(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── All source games combined ──
        if (allSourceGames.isNotEmpty()) {
            item(key = "games_header") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "Jeux à fusionner",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                "${allSourceGames.size}",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (duplicateKeys.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy, null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        "${duplicateKeys.size} doublon(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "Icône crayon = renommer",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        fontSize = 10.sp
                    )
                }
            }

            itemsIndexed(
                allSourceGames,
                key = { _, t -> "game_${t.first}_${t.second}" }
            ) { _, triple ->
                val (si, gi, entry) = triple
                val displayName = editedNames[si to gi]
                    ?: entry.gameName.trimEnd('\u0000').ifBlank { entry.gameIdClean }
                val id = entry.gameIdClean.trim().uppercase()
                val isDup = id in duplicateKeys

                Surface(
                    color = if (isDup)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                    else
                        MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                displayName,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    entry.gameIdClean.trim(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "• S${si + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                if (isDup) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "doublon",
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                        IconButton(
                            onClick = {
                                editDialogText = displayName
                                editingKey = si to gi
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit, null,
                                modifier = Modifier.size(16.dp),
                                tint = if (editedNames.containsKey(si to gi))
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }

        // ── Destination card ──
        item(key = "dest_card") {
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Storage, null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Destination",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                destFile.absolutePath,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp
                            )
                            Text(
                                if (destEntries.isNotEmpty()) "${destEntries.size} jeu(x) présent(s)"
                                else "Vide ou inexistant",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (destEntries.isNotEmpty()) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                            )
                        }
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    destEntries = if (destFile.exists())
                                        cfgManager.readAllEntries(destFile) else emptyList()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Refresh, null)
                        }
                    }
                    // Pick destination folder
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { destFolderPicker.launch(null) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Choisir le dossier", style = MaterialTheme.typography.labelMedium)
                        }
                        if (destPath != null) {
                            OutlinedButton(
                                onClick = { destPath = null; resultMessage = null },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.RestartAlt, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Par défaut", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        // ── Result message ──
        resultMessage?.let { msg ->
            item(key = "result") {
                val isOk = msg.startsWith("✓")
                Surface(
                    color = if (isOk) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isOk) Icons.Default.CheckCircle else Icons.Default.Error,
                            null,
                            tint = if (isOk) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isOk) MaterialTheme.colorScheme.onSecondaryContainer
                            else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { resultMessage = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // ── Action buttons ──
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val newCount = allSourceGames.count { (_, _, e) ->
                    e.gameIdClean.trim().uppercase() !in duplicateKeys
                }
                Button(
                    onClick = { mergeAll() },
                    enabled = sources.isNotEmpty() && !isProcessing,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Fusion en cours…")
                    } else {
                        Icon(Icons.Default.MergeType, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Fusionner ($newCount nouveau(x))",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                if (allSourceGames.isNotEmpty() && duplicateKeys.isNotEmpty()) {
                    Text(
                        "${duplicateKeys.size} doublon(s) sera(ont) ignoré(s) — $newCount jeu(x) sera(ont) ajouté(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
