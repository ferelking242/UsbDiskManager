package com.usbdiskmanager.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.viewmodel.LogsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(
    onNavigateUp: () -> Unit,
    viewModel: LogsViewModel = hiltViewModel()
) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    var filterQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    val filteredLogs = if (filterQuery.isBlank()) logs
    else logs.filter { it.contains(filterQuery, ignoreCase = true) }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all logs?") },
            confirmButton = {
                Button(
                    onClick = { viewModel.clearLogs(); showClearConfirm = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Clear") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Terminal,
                            null,
                            tint = Color(0xFF4FC3F7),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("System Logs")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showClearConfirm = true }) {
                        Icon(Icons.Default.DeleteSweep, "Clear", tint = Color(0xFFEF5350))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0A0E17)
                )
            )
        },
        containerColor = Color(0xFF0A0E17)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = { filterQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Filtrer les logs…", color = Color(0xFF546E7A)) },
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
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF1B2838)) {
                    Text(
                        "${filteredLogs.size} entrées",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFF78909C)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(4.dp), color = Color(0xFF0D1F2D)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "LIVE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    viewModel.getLogFilePath(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = Color(0xFF37474F)
                )
            }

            if (filteredLogs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (logs.isEmpty())
                            "Aucun log pour l'instant…\nConnecte une clé USB pour commencer."
                        else
                            "Aucun résultat pour « $filterQuery »",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = Color(0xFF37474F),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(filteredLogs, key = { it }) { entry ->
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
        entry.contains("error", ignoreCase = true) ||
                entry.contains("failed", ignoreCase = true) ||
                entry.contains("denied", ignoreCase = true) -> Color(0xFFFF5252)
        entry.contains("warn", ignoreCase = true) -> Color(0xFFFFD740)
        entry.contains("success", ignoreCase = true) ||
                entry.contains("granted", ignoreCase = true) ||
                entry.contains("mounted", ignoreCase = true) ||
                entry.contains("✓", ignoreCase = false) -> Color(0xFF69F0AE)
        entry.contains("attached", ignoreCase = true) ||
                entry.contains("detached", ignoreCase = true) -> Color(0xFF40C4FF)
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
