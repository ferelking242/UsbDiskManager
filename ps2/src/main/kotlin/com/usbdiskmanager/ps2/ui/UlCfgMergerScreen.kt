package com.usbdiskmanager.ps2.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.usbdiskmanager.ps2.data.converter.UlCfgManager
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun UlCfgMergerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfgManager = remember { UlCfgManager() }

    var sourceFile by remember { mutableStateOf<File?>(null) }
    var sourceEntries by remember { mutableStateOf<List<UlCfgManager.UlEntry>>(emptyList()) }
    var destEntries by remember { mutableStateOf<List<UlCfgManager.UlEntry>>(emptyList()) }
    var sourceFileName by remember { mutableStateOf<String?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var expandSource by remember { mutableStateOf(false) }
    var expandDest by remember { mutableStateOf(false) }

    val defaultCfgFile = remember { File(IsoScanner.DEFAULT_UL_DIR, "ul.cfg") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (defaultCfgFile.exists()) {
                destEntries = cfgManager.readAllEntries(defaultCfgFile)
            }
        }
    }

    // File picker — copies the selected ul.cfg to cache
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            scope.launch {
                isProcessing = true
                withContext(Dispatchers.IO) {
                    try {
                        val temp = File(context.cacheDir, "ul_merge_source.cfg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use { out -> input.copyTo(out) }
                        }
                        sourceFile = temp
                        sourceEntries = cfgManager.readAllEntries(temp)
                        sourceFileName = uri.lastPathSegment ?: "ul.cfg (source)"
                        resultMessage = null
                    } catch (e: Exception) {
                        resultMessage = "Erreur lecture: ${e.message}"
                    }
                }
                isProcessing = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        // Info banner
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.MergeType, null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp).padding(top = 1.dp)
                )
                Column {
                    Text("Fusionner ul.cfg", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text(
                        "Importez un ul.cfg source (par exemple depuis une clé USB) et fusionnez ses jeux dans votre ul.cfg principal. " +
                        "Les doublons (même Game ID) ne sont pas écrasés.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        // Source card
        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Source", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(
                            sourceFileName ?: "Aucun fichier sélectionné",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (sourceEntries.isNotEmpty()) {
                            Text("${sourceEntries.size} jeu(x)", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { filePicker.launch("*/*") },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Parcourir", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (sourceEntries.isNotEmpty()) {
                    TextButton(
                        onClick = { expandSource = !expandSource },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            if (expandSource) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (expandSource) "Masquer" else "Voir les jeux",
                            style = MaterialTheme.typography.labelMedium)
                    }
                    if (expandSource) UlEntryList(entries = sourceEntries)
                }
            }
        }

        // Destination card
        Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Destination (ul.cfg principal)",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(defaultCfgFile.path, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (destEntries.isNotEmpty()) "${destEntries.size} jeu(x) présent(s)" else "Vide",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (destEntries.isNotEmpty()) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                destEntries = if (defaultCfgFile.exists())
                                    cfgManager.readAllEntries(defaultCfgFile) else emptyList()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Refresh, "Rafraîchir")
                    }
                }

                if (destEntries.isNotEmpty()) {
                    TextButton(
                        onClick = { expandDest = !expandDest },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            if (expandDest) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            null, modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(if (expandDest) "Masquer" else "Voir les jeux",
                            style = MaterialTheme.typography.labelMedium)
                    }
                    if (expandDest) UlEntryList(entries = destEntries)
                }
            }
        }

        // Result message
        resultMessage?.let { msg ->
            Surface(
                color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (msg.startsWith("✓")) Icons.Default.CheckCircle else Icons.Default.Error,
                        null,
                        tint = if (msg.startsWith("✓")) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (msg.startsWith("✓")) MaterialTheme.colorScheme.onSecondaryContainer
                        else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Merge button
        Button(
            onClick = {
                val src = sourceFile
                if (src == null || sourceEntries.isEmpty()) {
                    resultMessage = "Sélectionnez d'abord un fichier source."
                    return@Button
                }
                scope.launch {
                    isProcessing = true
                    withContext(Dispatchers.IO) {
                        try {
                            defaultCfgFile.parentFile?.mkdirs()
                            val added = cfgManager.mergeInto(src, defaultCfgFile)
                            destEntries = cfgManager.readAllEntries(defaultCfgFile)
                            resultMessage = "✓ Fusion terminée — $added nouveau(x) jeu(x) ajouté(s). Total: ${destEntries.size}."
                        } catch (e: Exception) {
                            resultMessage = "Erreur fusion: ${e.message}"
                        }
                    }
                    isProcessing = false
                }
            },
            enabled = sourceEntries.isNotEmpty() && !isProcessing,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (isProcessing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Default.MergeType, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Fusionner dans ul.cfg principal", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun UlEntryList(entries: List<UlCfgManager.UlEntry>) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.take(20).forEach { entry ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            entry.gameName.ifBlank { entry.gameIdClean },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            "${entry.gameIdClean}  •  ${entry.numParts} partie(s)  •  ${if (entry.isCd) "CD" else "DVD"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        if (entries.size > 20) {
            Text("… et ${entries.size - 20} autre(s)", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 10.dp, top = 4.dp))
        }
    }
}
