package com.usbdiskmanager.ps2.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.usbdiskmanager.ps2.data.scanner.IsoScanner
import com.usbdiskmanager.ps2.domain.model.OutputDestination
import com.usbdiskmanager.ps2.domain.model.Ps2Game
import com.usbdiskmanager.ps2.util.MountInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversionDialog(
    game: Ps2Game,
    availableUsbMounts: List<MountInfo>,
    initialDestination: OutputDestination,
    onDismiss: () -> Unit,
    onConvert: (OutputDestination) -> Unit
) {
    var selectedDest by remember { mutableStateOf(initialDestination) }
    var showDestMenu by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Title
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Transform,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "Convertir en UL",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider()

                // Game info
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow(label = "Jeu", value = game.title)
                    if (game.gameId.isNotBlank()) InfoRow(label = "ID", value = game.gameId)
                    InfoRow(label = "Région", value = game.region)
                    InfoRow(label = "Taille", value = formatBytes(game.sizeMb))
                    val parts = (game.sizeMb + 1_073_741_823L) / 1_073_741_824L
                    InfoRow(label = "Parties", value = "$parts × 1 Go")
                }

                HorizontalDivider()

                // Destination picker
                Text(
                    text = "Destination de sortie",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ExposedDropdownMenuBox(
                    expanded = showDestMenu,
                    onExpandedChange = { showDestMenu = it }
                ) {
                    OutlinedTextField(
                        value = selectedDest.displayLabel(IsoScanner.DEFAULT_UL_DIR),
                        onValueChange = {},
                        readOnly = true,
                        leadingIcon = {
                            Icon(
                                when (selectedDest) {
                                    is OutputDestination.Default -> Icons.Default.Folder
                                    is OutputDestination.UsbDrive -> Icons.Default.Usb
                                    is OutputDestination.Custom -> Icons.Default.FolderOpen
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showDestMenu) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    ExposedDropdownMenu(
                        expanded = showDestMenu,
                        onDismissRequest = { showDestMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        "Défaut (interne)",
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        IsoScanner.DEFAULT_UL_DIR,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Folder, contentDescription = null)
                            },
                            onClick = {
                                selectedDest = OutputDestination.Default
                                showDestMenu = false
                            }
                        )

                        if (availableUsbMounts.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                            availableUsbMounts.forEach { mount ->
                                val label = mount.mountPoint.substringAfterLast('/')
                                    .let { if (it.isBlank()) mount.mountPoint else it }
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                "USB — $label",
                                                fontWeight = FontWeight.Medium
                                            )
                                            Text(
                                                "${mount.mountPoint}  •  ${mount.fsType.uppercase()}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Usb, contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary)
                                    },
                                    onClick = {
                                        selectedDest = OutputDestination.UsbDrive(
                                            mountPoint = mount.mountPoint,
                                            label = label
                                        )
                                        showDestMenu = false
                                    }
                                )
                            }
                        }
                    }
                }

                if (selectedDest is OutputDestination.UsbDrive) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.size(16.dp).padding(top = 1.dp)
                            )
                            Text(
                                text = "Les fichiers UL seront écrits à la racine de la clé USB pour qu'OPL puisse les lire.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }

                // Info note
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "Conversion en flux 4 Mo — aucun chargement en RAM. Reprise automatique activée.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Annuler")
                    }
                    Button(
                        onClick = { onConvert(selectedDest) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.Transform, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Démarrer")
                    }
                }
            }
        }
    }
}

@Composable
fun Fat32WarningDialog(
    mount: com.usbdiskmanager.ps2.util.MountInfo?,
    onProceedAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = {
            Text(
                "USB non FAT32",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "La clé USB ${mount?.mountPoint ?: ""} est en " +
                    "${mount?.fsType?.uppercase() ?: "format inconnu"}."
                )
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "⚠ OPL sur PS2 ne peut lire que les clés USB en FAT32. " +
                               "Si vous continuez, les jeux ne seront pas lisibles par l'OPL.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Text(
                    text = "Formater en FAT32 depuis l'onglet USB de l'application avant de continuer est fortement recommandé.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onProceedAnyway,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Continuer quand même")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Annuler")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchConversionDialog(
    count: Int,
    availableUsbMounts: List<com.usbdiskmanager.ps2.util.MountInfo>,
    initialDestination: OutputDestination,
    onDismiss: () -> Unit,
    onConvert: (OutputDestination) -> Unit
) {
    var selectedDest by remember { mutableStateOf(initialDestination) }
    var showDestMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Transform, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
        },
        title = { Text("Convertir $count jeu(x)", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$count ISO(s) sélectionné(s) seront convertis en format UL.")

                Text("Destination :", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                ExposedDropdownMenuBox(
                    expanded = showDestMenu,
                    onExpandedChange = { showDestMenu = it }
                ) {
                    OutlinedTextField(
                        value = selectedDest.displayLabel(IsoScanner.DEFAULT_UL_DIR),
                        onValueChange = {},
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showDestMenu) },
                        modifier = Modifier.fillMaxWidth().menuAnchor(),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )
                    ExposedDropdownMenu(expanded = showDestMenu, onDismissRequest = { showDestMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Défaut (interne)") },
                            leadingIcon = { Icon(Icons.Default.Folder, null) },
                            onClick = { selectedDest = OutputDestination.Default; showDestMenu = false }
                        )
                        availableUsbMounts.forEach { mount ->
                            val label = mount.mountPoint.substringAfterLast('/').ifBlank { mount.mountPoint }
                            DropdownMenuItem(
                                text = { Text("USB — $label (${mount.fsType.uppercase()})") },
                                leadingIcon = { Icon(Icons.Default.Usb, null) },
                                onClick = {
                                    selectedDest = OutputDestination.UsbDrive(mount.mountPoint, label)
                                    showDestMenu = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConvert(selectedDest) }) {
                Text("Convertir tout")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Annuler") }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "$label :",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f Go".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.0f Mo".format(bytes / 1_048_576.0)
    else                     -> "${bytes / 1024} Ko"
}
