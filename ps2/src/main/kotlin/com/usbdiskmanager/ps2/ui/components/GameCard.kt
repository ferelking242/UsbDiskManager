package com.usbdiskmanager.ps2.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onConvertClick: () -> Unit,
    onSelectClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onCancelClick: () -> Unit,
    onFetchCoverClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "border"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        else
            Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "bg"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(
                if (isSelected) Modifier.border(2.dp, borderColor, RoundedCornerShape(16.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 6.dp else 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.background(bgColor)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isMultiSelectMode) {
                        Box(
                            modifier = Modifier
                                .size(width = 40.dp, height = 40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceContainerHighest
                                )
                                .clickable(onClick = onConvertClick),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Sélectionné",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    } else {
                        CoverThumbnail(game = game, onFetchCoverClick = onFetchCoverClick)
                    }

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

                    if (!isMultiSelectMode) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            StatusIcon(game.conversionStatus)
                            Spacer(Modifier.height(2.dp))
                            ActionButton(
                                game = game,
                                progress = progress,
                                onConvertClick = onConvertClick,
                                onPauseClick = onPauseClick,
                                onResumeClick = onResumeClick,
                                onCancelClick = onCancelClick
                            )
                            // Select button
                            IconButton(
                                onClick = onSelectClick,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = "Sélectionner",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                            // Delete button
                            IconButton(
                                onClick = onDeleteClick,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = "Supprimer",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }

                if (progress != null && !isMultiSelectMode) {
                    Spacer(Modifier.height(8.dp))
                    ConversionProgressRow(progress)
                }
            }
        }
    }
}

@Composable
fun GameGridCard(
    game: Ps2Game,
    progress: ConversionProgress?,
    isSelected: Boolean = false,
    isMultiSelectMode: Boolean = false,
    onSelectClick: () -> Unit,
    onConvertClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onFetchCoverClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "border"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isSelected) Modifier.border(2.dp, borderColor, RoundedCornerShape(16.dp))
                else Modifier
            ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 6.dp else 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            // Cover image
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            text = "COVER",
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        )
                    }
                }

                // Selection overlay
                if (isMultiSelectMode) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                            )
                            .clickable(onClick = onSelectClick),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // Status badge
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    shape = CircleShape,
                    color = when (game.conversionStatus) {
                        ConversionStatus.COMPLETED -> Color(0xFF4CAF50)
                        ConversionStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                        ConversionStatus.ERROR -> MaterialTheme.colorScheme.error
                        ConversionStatus.PAUSED -> MaterialTheme.colorScheme.tertiary
                        ConversionStatus.NOT_CONVERTED -> MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                    }
                ) {
                    Icon(
                        when (game.conversionStatus) {
                            ConversionStatus.COMPLETED -> Icons.Default.CheckCircle
                            ConversionStatus.IN_PROGRESS -> Icons.Default.Sync
                            ConversionStatus.ERROR -> Icons.Default.ErrorOutline
                            ConversionStatus.PAUSED -> Icons.Default.Pause
                            ConversionStatus.NOT_CONVERTED -> Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp).padding(2.dp)
                    )
                }
            }

            // Progress bar (if active)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.percent },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                )
            }

            // Info & action buttons
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = game.title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 16.sp
                )
                if (game.gameId.isNotBlank()) {
                    Text(
                        text = game.gameId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RegionChip(game.region)
                    if (!isMultiSelectMode) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            // Select
                            IconButton(
                                onClick = onSelectClick,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckBoxOutlineBlank,
                                    null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                            }
                            // Convert
                            if (game.conversionStatus != ConversionStatus.COMPLETED) {
                                IconButton(
                                    onClick = onConvertClick,
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Transform,
                                        null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            // Delete
                            IconButton(
                                onClick = onDeleteClick,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
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
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(4.dp)) {
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
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp)) {
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
            Row {
                IconButton(onClick = onPauseClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Pause, "Pause", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onCancelClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "Cancel", modifier = Modifier.size(18.dp))
                }
            }
        }
        game.conversionStatus == ConversionStatus.PAUSED ||
        game.conversionStatus == ConversionStatus.ERROR -> {
            Row {
                IconButton(onClick = onResumeClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.PlayArrow, "Resume", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onCancelClick, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "Cancel", modifier = Modifier.size(18.dp))
                }
            }
        }
        game.conversionStatus != ConversionStatus.COMPLETED -> {
            IconButton(onClick = onConvertClick, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Transform, "Convert", modifier = Modifier.size(18.dp))
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
