package com.usbdiskmanager.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.viewmodel.LogsViewModel

private enum class LogCategory(val label: String, val tag: String?) {
    ALL("Tous", null),
    FORMAT("Format", "[FORMAT]"),
    PS2("PS2 Studio", "[PS2]"),
    BENCH("Benchmark", "[BENCH]"),
    USB("USB", "[USB]"),
    DOWNLOAD("Téléchargement", "[DL]"),
    ERROR("Erreurs", "error")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onNavigateUp: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel()
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var filterQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(LogCategory.ALL) }

    val filteredLogs = remember(logs, filterQuery, selectedCategory) {
        var base = logs
        if (selectedCategory != LogCategory.ALL) {
            val tag = selectedCategory.tag
            base = if (selectedCategory == LogCategory.ERROR) {
                base.filter { it.contains("error", ignoreCase = true) || it.contains("erreur", ignoreCase = true) || it.contains("EXCEPTION", ignoreCase = true) || it.contains("✗") }
            } else {
                base.filter { tag != null && it.contains(tag, ignoreCase = true) }
            }
        }
        if (filterQuery.isNotBlank()) {
            base = base.filter { it.contains(filterQuery, ignoreCase = true) }
        }
        base
    }

    // Count per category
    val categoryCounts = remember(logs) {
        LogCategory.entries.associateWith { cat ->
            if (cat == LogCategory.ALL) logs.size
            else if (cat == LogCategory.ERROR)
                logs.count { it.contains("error", ignoreCase = true) || it.contains("erreur", ignoreCase = true) || it.contains("✗") }
            else
                logs.count { cat.tag != null && it.contains(cat.tag, ignoreCase = true) }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            icon = { Icon(Icons.Default.DeleteSweep, null, tint = Color(0xFFEF5350)) },
            title = { Text("Vider tous les logs ?") },
            text = { Text("Cette action est irréversible.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearLogs(); showClearConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                ) { Text("Vider") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirm = false }) { Text("Annuler") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, null, tint = Color(0xFF4FC3F7), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Logs Système")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Retour", tint = Color(0xFF90A4AE))
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Default.DeleteSweep, "Vider", tint = Color(0xFFEF5350))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A0E17),
                    titleContentColor = Color(0xFF90A4AE)
                )
            )
        },
        containerColor = Color(0xFF0A0E17)
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues)
        ) {
            // Search bar
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Rechercher dans les logs…", color = Color(0xFF546E7A)) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color(0xFF546E7A)) },
                trailingIcon = {
                    if (filterQuery.isNotEmpty()) {
                        IconButton(onClick = { filterQuery = "" }) {
                            Icon(Icons.Default.Clear, null, tint = Color(0xFF546E7A))
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF4FC3F7),
                    unfocusedBorderColor = Color(0xFF263238),
                    focusedTextColor = Color(0xFF4FC3F7),
                    unfocusedTextColor = Color(0xFF90A4AE),
                    cursorColor = Color(0xFF4FC3F7)
                ),
                shape = RoundedCornerShape(8.dp),
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            )

            // Category filter chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LogCategory.entries.forEach { cat ->
                    val count = categoryCounts[cat] ?: 0
                    if (count > 0 || cat == LogCategory.ALL) {
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(cat.label, fontSize = 11.sp)
                                    if (count > 0) {
                                        Surface(
                                            color = if (selectedCategory == cat) Color(0xFF4FC3F7).copy(alpha = 0.3f)
                                            else Color(0xFF263238),
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                "$count",
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                                fontSize = 9.sp,
                                                color = if (cat == LogCategory.ERROR) Color(0xFFFF5252)
                                                else Color(0xFF90A4AE)
                                            )
                                        }
                                    }
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF0D1F2D),
                                selectedLabelColor = Color(0xFF4FC3F7)
                            )
                        )
                    }
                }
            }

            // Stats bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF1B2838)) {
                    Text(
                        "${filteredLogs.size} / ${logs.size} entrées",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF78909C)
                    )
                }
                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF0D1F2D)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("LIVE", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF4CAF50))
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    viewModel.getLogFilePath().substringAfterLast('/').take(30),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color(0xFF37474F)
                )
            }

            HorizontalDivider(color = Color(0xFF1B2838), thickness = 0.5.dp)

            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (logs.isEmpty())
                            "Aucun log pour l'instant…\nConnecte une clé USB pour commencer."
                        else if (selectedCategory != LogCategory.ALL)
                            "Aucun log pour la catégorie «${selectedCategory.label}»"
                        else
                            "Aucun résultat pour « $filterQuery »",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color(0xFF37474F),
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(filteredLogs, key = { i, entry -> "$i-$entry" }) { _, entry ->
                        LogEntry(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntry(entry: String) {
    val color = when {
        entry.contains("ERREUR", ignoreCase = true) ||
            entry.contains("EXCEPTION", ignoreCase = true) ||
            entry.contains("failed", ignoreCase = true) ||
            entry.contains("denied", ignoreCase = true) ||
            entry.contains("✗") -> Color(0xFFFF5252)
        entry.contains("WARN", ignoreCase = true) -> Color(0xFFFFD740)
        entry.contains("✓") ||
            entry.contains("Succès", ignoreCase = true) ||
            entry.contains("success", ignoreCase = true) ||
            entry.contains("granted", ignoreCase = true) ||
            entry.contains("mounted", ignoreCase = true) -> Color(0xFF69F0AE)
        entry.contains("[FORMAT]") -> Color(0xFFFFAB40)
        entry.contains("[PS2]") || entry.contains("[CONV]") -> Color(0xFFB39DDB)
        entry.contains("[BENCH]") -> Color(0xFFADD8E6)
        entry.contains("[DL]") || entry.contains("Téléchargement") -> Color(0xFF80DEEA)
        entry.contains("attached", ignoreCase = true) ||
            entry.contains("detached", ignoreCase = true) ||
            entry.contains("[USB]") -> Color(0xFF40C4FF)
        else -> Color(0xFF90A4AE)
    }
    Text(
        text = entry,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp, horizontal = 4.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = color,
        lineHeight = 18.sp
    )
}
