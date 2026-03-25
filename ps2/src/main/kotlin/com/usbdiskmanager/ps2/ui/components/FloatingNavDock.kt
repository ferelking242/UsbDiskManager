package com.usbdiskmanager.ps2.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.VideogameAsset
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

enum class AppTab { USB, PS2_STUDIO }

/**
 * Semi-transparent floating navigation dock.
 *
 * Auto-hides after [autoHideDelayMs] ms of inactivity.
 * Re-appears on any touch interaction (caller should call [resetHideTimer]).
 * Slides up on scroll-up, slides down on scroll-down.
 */
@Composable
fun FloatingNavDock(
    currentTab: AppTab,
    onTabSelected: (AppTab) -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.88f),
                tonalElevation = 8.dp,
                shadowElevation = 12.dp,
                modifier = Modifier.shadow(elevation = 12.dp, shape = RoundedCornerShape(32.dp))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockTab(
                        label = "USB",
                        icon = Icons.Default.SdStorage,
                        selected = currentTab == AppTab.USB,
                        onClick = { onTabSelected(AppTab.USB) }
                    )
                    DockTab(
                        label = "PS2 Studio",
                        icon = Icons.Default.VideogameAsset,
                        selected = currentTab == AppTab.PS2_STUDIO,
                        onClick = { onTabSelected(AppTab.PS2_STUDIO) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DockTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        Color.Transparent
    val contentColor = if (selected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = label,
                fontSize = 13.sp,
                color = contentColor,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/**
 * State holder for the floating nav dock.
 *
 * Manages:
 * - scroll-direction visibility (show on up, hide on down)
 * - auto-hide after inactivity
 * - re-show on touch
 */
class FloatingNavDockState {
    var visible by mutableStateOf(true)
        private set

    private var lastScrollOffset = 0
    private var touchHandled = false

    fun onScrollChanged(firstVisibleItem: Int, firstVisibleItemScrollOffset: Int) {
        val currentOffset = firstVisibleItem * 1000 + firstVisibleItemScrollOffset
        val delta = currentOffset - lastScrollOffset
        if (delta > 30) visible = false   // scrolling down
        if (delta < -30) visible = true   // scrolling up
        lastScrollOffset = currentOffset
    }

    fun show() { visible = true }
    fun hide() { visible = false }
}

@Composable
fun rememberFloatingNavDockState(autoHideDelayMs: Long = 3000L): FloatingNavDockState {
    val state = remember { FloatingNavDockState() }

    // Auto-hide after inactivity
    LaunchedEffect(state.visible) {
        if (state.visible) {
            delay(autoHideDelayMs)
            state.hide()
        }
    }

    return state
}
