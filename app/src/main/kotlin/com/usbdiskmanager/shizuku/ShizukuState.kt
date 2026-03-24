package com.usbdiskmanager.shizuku

/**
 * Represents the current state of Shizuku availability and permission.
 */
sealed class ShizukuState {
    /** Shizuku is not installed on this device. */
    object NotInstalled : ShizukuState()

    /** Shizuku is installed but its service is not running. */
    object NotRunning : ShizukuState()

    /** Shizuku is running but we haven't asked for permission yet. */
    object PermissionNotRequested : ShizukuState()

    /** We requested permission but the user denied it. */
    object PermissionDenied : ShizukuState()

    /** Shizuku is active and we have permission — full privileged access. */
    object Ready : ShizukuState()

    val isReady: Boolean get() = this is Ready

    val displayLabel: String get() = when (this) {
        is NotInstalled -> "Shizuku non installé"
        is NotRunning -> "Shizuku non démarré"
        is PermissionNotRequested -> "Permission Shizuku requise"
        is PermissionDenied -> "Permission Shizuku refusée"
        is Ready -> "Shizuku actif — accès privilégié"
    }

    val isActionable: Boolean get() = when (this) {
        is PermissionNotRequested, is PermissionDenied -> true
        else -> false
    }
}
