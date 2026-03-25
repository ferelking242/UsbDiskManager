package com.usbdiskmanager.ps2.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.usbdiskmanager.ps2.domain.model.ConversionProgress
import com.usbdiskmanager.ps2.domain.model.ConversionStatus
import com.usbdiskmanager.ps2.domain.model.Ps2Game
import java.io.File

@Composable
fun GameCard(
    game: Ps2Game,
    progress: ConversionProgress?,
    onConvertClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onCancelClick: () -> Unit,
    onFetchCoverClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Cover art
                CoverThumbnail(game = game, onFetchCoverClick = onFetchCoverClick)

                // Game info
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (game.gameId.isNotBlank()) {
                        Text(
                            text = game.gameId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        RegionChip(game.region)
                        SizeChip(game.sizeMb)
                    }
                }

                // Status & actions
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StatusIcon(game.conversionStatus)
                    Spacer(Modifier.height(4.dp))
                    ActionButton(
                        game = game,
                        progress = progress,
                        onConvertClick = onConvertClick,
                        onPauseClick = onPauseClick,
                        onResumeClick = onResumeClick,
                        onCancelClick = onCancelClick
                    )
                }
            }

            // Progress bar (shown when converting)
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                ConversionProgressRow(progress)
            }
        }
    }
}

@Composable
private fun CoverThumbnail(game: Ps2Game, onFetchCoverClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 88.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onFetchCoverClick),
        contentAlignment = Alignment.Center
    ) {
        if (game.coverPath != null && File(game.coverPath).exists()) {
            AsyncImage(
                model = File(game.coverPath),
                contentDescription = game.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.VideogameAsset,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "ART",
                    fontSize = 9.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
private fun RegionChip(region: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = region,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

@Composable
private fun SizeChip(sizeBytes: Long) {
    val formatted = when {
        sizeBytes >= 1_073_741_824L -> "%.1f GB".format(sizeBytes / 1_073_741_824.0)
        sizeBytes >= 1_048_576L     -> "%.0f MB".format(sizeBytes / 1_048_576.0)
        else                         -> "${sizeBytes / 1024} KB"
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = formatted,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun StatusIcon(status: ConversionStatus) {
    val (icon, tint) = when (status) {
        ConversionStatus.NOT_CONVERTED -> Icons.Default.RadioButtonUnchecked to MaterialTheme.colorScheme.outline
        ConversionStatus.IN_PROGRESS   -> Icons.Default.Sync to MaterialTheme.colorScheme.primary
        ConversionStatus.PAUSED        -> Icons.Default.Pause to MaterialTheme.colorScheme.tertiary
        ConversionStatus.COMPLETED     -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
        ConversionStatus.ERROR         -> Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(imageVector = icon, contentDescription = status.name, tint = tint, modifier = Modifier.size(20.dp))
}

@Composable
private fun ActionButton(
    game: Ps2Game,
    progress: ConversionProgress?,
    onConvertClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onCancelClick: () -> Unit
) {
    when {
        progress != null -> {
            // Active conversion — show pause
            Row {
                IconButton(onClick = onPauseClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Pause, contentDescription = "Pause", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onCancelClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(18.dp))
                }
            }
        }
        game.conversionStatus == ConversionStatus.PAUSED ||
        game.conversionStatus == ConversionStatus.ERROR -> {
            Row {
                IconButton(onClick = onResumeClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onCancelClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Cancel", modifier = Modifier.size(18.dp))
                }
            }
        }
        game.conversionStatus != ConversionStatus.COMPLETED -> {
            IconButton(onClick = onConvertClick, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Transform, contentDescription = "Convert", modifier = Modifier.size(18.dp))
            }
        }
        else -> {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Done",
                tint = Color(0xFF4CAF50),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun ConversionProgressRow(progress: ConversionProgress) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "%.1f%%  %.1f MB/s".format(progress.percent * 100, progress.speedMbps),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatRemaining(progress.remainingSeconds),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress.percent },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    }
}

private fun formatRemaining(seconds: Long): String = when {
    seconds == Long.MAX_VALUE || seconds < 0 -> "--:--"
    seconds >= 3600 -> "%dh %02dm".format(seconds / 3600, (seconds % 3600) / 60)
    seconds >= 60   -> "%dm %02ds".format(seconds / 60, seconds % 60)
    else            -> "${seconds}s"
}
