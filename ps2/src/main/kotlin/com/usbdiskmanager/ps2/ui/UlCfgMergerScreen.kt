package com.usbdiskmanager.ps2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var isSuccess by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var editedNames by remember { mutableStateOf<Map<Pair<Int, Int>, String>>(emptyMap()) }
    var editingKey by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var editDialogText by remember { mutableStateOf("") }

    // Tab index: 0..N-1 = sources, last = preview
    var selectedTab by remember { mutableIntStateOf(0) }

    val defaultCfgFile = remember { File(IsoScanner.DEFAULT_UL_DIR, "ul.cfg") }

    val destFile by remember(destPath) {
        derivedStateOf {
            if (destPath != null) File(destPath!!, "ul.cfg") else defaultCfgFile
        }
    }

    LaunchedEffect(destFile) {
        withContext(Dispatchers.IO) {
            destEntries = if (destFile.exists()) cfgManager.readAllEntries(destFile) else emptyList()
        }
    }

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

    // Adjust selected tab if sources list changes
    LaunchedEffect(sources.size) {
        if (selectedTab > sources.size) selectedTab = 0
    }

    fun mergeAll() {
        if (sources.isEmpty()) { resultMessage = "Ajoutez au moins un fichier source."; isSuccess = false; return }
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
                    resultMessage = "Fusion terminée — $totalAdded nouveau(x) jeu(x). Total: ${destEntries.size}."
                    isSuccess = true
                    selectedTab = sources.size // go to preview tab
                } catch (e: Exception) {
                    resultMessage = "Erreur: ${e.message}"
                    isSuccess = false
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
                            isSuccess = false
                        } else {
                            val raw = uri.lastPathSegment ?: "source_$idx.cfg"
                            val name = raw.substringAfterLast('/').substringAfterLast(':')
                            sources = sources + SourceCfg(name, temp, entries)
                            selectedTab = sources.size - 1
                            resultMessage = null
                        }
                    } catch (e: Exception) {
                        resultMessage = "Erreur lecture: ${e.message}"
                        isSuccess = false
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

    editingKey?.let { key ->
        AlertDialog(
            onDismissRequest = { editingKey = null },
            icon = { Icon(Icons.Default.Edit, null) },
            title = { Text("Renommer le jeu") },
            text = {
                OutlinedTextField(
                    value = editDialogText,
                    onValueChange = { if (it.length <= 32) editDialogText = it },
                    label = { Text("Nom (max 32 caractères)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Text(
                            "${editDialogText.length}/32",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (editDialogText.length >= 30)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
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

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Result banner ──
        resultMessage?.let { msg ->
            Surface(
                color = if (isSuccess) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                        null,
                        tint = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer
                               else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSuccess) MaterialTheme.colorScheme.onPrimaryContainer
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

        // ── Tabs: Source 1, Source 2, ..., Aperçu ──
        val tabCount = sources.size + 1 // +1 for preview
        val previewTabIndex = sources.size

        ScrollableTabRow(
            selectedTabIndex = selectedTab.coerceIn(0, tabCount - 1),
            edgePadding = 12.dp,
            divider = {},
            indicator = { tabPositions ->
                val idx = selectedTab.coerceIn(0, tabCount - 1)
                if (idx < tabPositions.size) {
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[idx]),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            sources.forEachIndexed { si, source ->
                Tab(
                    selected = selectedTab == si,
                    onClick = { selectedTab = si },
                    text = {
                        Text(
                            "Source ${si + 1}",
                            fontWeight = if (selectedTab == si) FontWeight.Bold else FontWeight.Normal
                        )
                    },
                    icon = { Icon(Icons.Default.FileOpen, null, modifier = Modifier.size(16.dp)) }
                )
            }
            Tab(
                selected = selectedTab == previewTabIndex,
                onClick = { selectedTab = previewTabIndex },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Aperçu final",
                            fontWeight = if (selectedTab == previewTabIndex) FontWeight.Bold else FontWeight.Normal
                        )
                        if (destEntries.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "${destEntries.size}",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                },
                icon = { Icon(Icons.Default.Visibility, null, modifier = Modifier.size(16.dp)) }
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ── Tab content ──
        val tabIdx = selectedTab.coerceIn(0, tabCount - 1)

        if (tabIdx < sources.size) {
            // Source tab content
            val si = tabIdx
            val source = sources[si]
            val sourceGames = source.entries.mapIndexed { gi, e -> Triple(si, gi, e) }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                item(key = "src_header_$si") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                source.fileName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                "${source.entries.size} jeu(x)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                sources = sources.toMutableList().also { it.removeAt(si) }
                                editedNames = editedNames
                                    .filterKeys { (k, _) -> k != si }
                                    .mapKeys { (k, v) ->
                                        if (k.first > si) (k.first - 1 to k.second) else k
                                    }
                                if (selectedTab >= sources.size) selectedTab = maxOf(0, sources.size)
                            }
                        ) {
                            Icon(Icons.Default.DeleteSweep, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                if (duplicateKeys.isNotEmpty()) {
                    item(key = "dup_warning_$si") {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Warning, null,
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    "${duplicateKeys.size} doublon(s) détecté(s) — ils seront ignorés lors de la fusion.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                itemsIndexed(
                    sourceGames,
                    key = { _, t -> "sg_${t.first}_${t.second}" }
                ) { _, triple ->
                    val (srcIdx, gi, entry) = triple
                    val displayName = editedNames[srcIdx to gi]
                        ?: entry.gameName.trimEnd('\u0000').ifBlank { entry.gameIdClean }
                    val id = entry.gameIdClean.trim().uppercase()
                    val isDup = id in duplicateKeys

                    Surface(
                        color = if (isDup)
                            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        else
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.VideogameAsset, null,
                                modifier = Modifier.size(18.dp),
                                tint = if (isDup) MaterialTheme.colorScheme.error
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                                        color = MaterialTheme.colorScheme.primary
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
                                    editingKey = srcIdx to gi
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Edit, null,
                                    modifier = Modifier.size(16.dp),
                                    tint = if (editedNames.containsKey(srcIdx to gi))
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        } else {
            // Preview tab
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                item(key = "preview_dest") {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Storage, null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Destination",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    destFile.absolutePath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp
                                )
                                if (destEntries.isNotEmpty()) {
                                    Text(
                                        "${destEntries.size} jeu(x) actuellement présent(s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = { destFolderPicker.launch(null) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Changer", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                if (destEntries.isNotEmpty()) {
                    item(key = "preview_header") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Games, null, modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "ul.cfg actuel (${destEntries.size} jeux)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    itemsIndexed(destEntries, key = { i, _ -> "preview_$i" }) { _, entry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle, null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        entry.gameName.trimEnd('\u0000').ifBlank { entry.gameIdClean },
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        entry.gameIdClean.trim(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                } else if (sources.isEmpty()) {
                    item(key = "preview_empty") {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.FolderOpen, null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Aucune source ajoutée",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Ajoutez des fichiers source pour commencer",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }

        // ── Bottom action bar ──
        Surface(
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Add source button
                    OutlinedButton(
                        onClick = { filePicker.launch("*/*") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (sources.isEmpty()) "Ajouter source"
                            else "Source ${sources.size + 1}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }

                    // Merge button
                    Button(
                        onClick = ::mergeAll,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        enabled = sources.isNotEmpty() && !isProcessing
                    ) {
                        Icon(Icons.Default.MergeType, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Fusionner (${allSourceGames.size})",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Stats summary
                if (sources.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                "${sources.size} source(s) · ${allSourceGames.size} jeu(x)",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        if (duplicateKeys.isNotEmpty()) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    "${duplicateKeys.size} doublon(s)",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
