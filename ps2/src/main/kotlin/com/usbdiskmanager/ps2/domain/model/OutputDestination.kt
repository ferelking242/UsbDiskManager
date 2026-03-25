package com.usbdiskmanager.ps2.domain.model

/**
 * Where the UL parts and ul.cfg should be written.
 */
sealed class OutputDestination {
    /** Default internal location: usbdiskmanager/PS2Manager/UL */
    data object Default : OutputDestination()

    /**
     * USB drive root (OPL reads ul.cfg from root of the drive).
     * @param mountPoint The mounted path of the USB drive.
     * @param label      Human-readable label for the drive.
     */
    data class UsbDrive(val mountPoint: String, val label: String) : OutputDestination()

    /**
     * Custom user-selected folder.
     */
    data class Custom(val path: String) : OutputDestination()

    fun resolvedPath(defaultUlDir: String): String = when (this) {
        is Default   -> defaultUlDir
        is UsbDrive  -> mountPoint
        is Custom    -> path
    }

    fun displayLabel(defaultUlDir: String): String = when (this) {
        is Default   -> "Défaut — $defaultUlDir"
        is UsbDrive  -> "USB: $label ($mountPoint)"
        is Custom    -> path
    }
}
