package com.usbdiskmanager.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.usbdiskmanager.prefs.AppTheme

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF004879),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFF64B5F6),
    onSecondary = Color(0xFF003256),
    secondaryContainer = Color(0xFF00487A),
    onSecondaryContainer = Color(0xFFCDE5FF),
    tertiary = Color(0xFF81C784),
    onTertiary = Color(0xFF003919),
    tertiaryContainer = Color(0xFF005227),
    onTertiaryContainer = Color(0xFF9CF4A3),
    error = Color(0xFFFF5252),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0E17),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF111827),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFB0BEC5),
    outline = Color(0xFF37474F),
    outlineVariant = Color(0xFF263238)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0277BD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE5FF),
    onPrimaryContainer = Color(0xFF001E31),
    secondary = Color(0xFF1565C0),
    onSecondary = Color.White,
    background = Color(0xFFF0F4F8),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFECEFF1),
    onSurfaceVariant = Color(0xFF455A64)
)

private val AmoledColorScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF003A5C),
    onPrimaryContainer = Color(0xFFCDE5FF),
    secondary = Color(0xFF64B5F6),
    onSecondary = Color(0xFF003256),
    secondaryContainer = Color(0xFF00324E),
    onSecondaryContainer = Color(0xFFCDE5FF),
    tertiary = Color(0xFF81C784),
    onTertiary = Color(0xFF003919),
    tertiaryContainer = Color(0xFF003D1A),
    onTertiaryContainer = Color(0xFF9CF4A3),
    error = Color(0xFFFF5252),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A0009),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF0D1117),
    onSurfaceVariant = Color(0xFFB0BEC5),
    outline = Color(0xFF263238),
    outlineVariant = Color(0xFF1A1F26),
    surfaceContainerHighest = Color(0xFF111520),
    surfaceContainerHigh = Color(0xFF0D1117),
    surfaceContainer = Color(0xFF08090E),
    surfaceContainerLow = Color(0xFF050508),
    surfaceContainerLowest = Color(0xFF000000)
)

@Composable
fun UsbDiskManagerTheme(
    appTheme: AppTheme = AppTheme.DARK,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    val colorScheme = when (appTheme) {
        AppTheme.AMOLED -> AmoledColorScheme
        AppTheme.DYNAMIC -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (systemDark) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            } else {
                DarkColorScheme
            }
        }
        AppTheme.DARK -> DarkColorScheme
        AppTheme.LIGHT -> LightColorScheme
        AppTheme.SYSTEM -> if (systemDark) DarkColorScheme else LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
