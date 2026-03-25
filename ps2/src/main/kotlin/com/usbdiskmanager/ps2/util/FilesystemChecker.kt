package com.usbdiskmanager.ps2.util

import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class MountInfo(
    val mountPoint: String,
    val fsType: String,
    val blockDevice: String
)

@Singleton
class FilesystemChecker @Inject constructor() {

    /**
     * Returns the filesystem type for the given path (e.g. "vfat", "exfat", "ntfs").
     * Reads /proc/mounts to find the best matching mount point.
     */
    fun getFsType(path: String): String? {
        val mounts = readMounts()
        val best = mounts
            .filter { path.startsWith(it.mountPoint) }
            .maxByOrNull { it.mountPoint.length }
        return best?.fsType
    }

    /**
     * Returns true if the path is on a FAT32 (vfat) filesystem.
     */
    fun isFat32(path: String): Boolean {
        val fs = getFsType(path)?.lowercase() ?: return false
        return fs in setOf("vfat", "fat32", "msdos")
    }

    /**
     * Returns true if the path is on an external/USB drive (not internal storage).
     */
    fun isExternalMount(path: String): Boolean {
        if (path.contains("/sdcard") || path.contains("/storage/emulated")) return false
        val mounts = readMounts()
        val best = mounts
            .filter { path.startsWith(it.mountPoint) }
            .maxByOrNull { it.mountPoint.length }
        return best != null && isExternalMountPoint(best.mountPoint, best.fsType)
    }

    /**
     * Returns a list of all external/USB mount points (usable as UL destinations).
     */
    fun listExternalMounts(): List<MountInfo> {
        return readMounts().filter { isExternalMountPoint(it.mountPoint, it.fsType) }
    }

    private fun isExternalMountPoint(mnt: String, fs: String): Boolean {
        val externalPrefixes = listOf(
            "/storage/", "/mnt/media_rw/", "/mnt/usb", "/mnt/ext", "/mnt/sdcard2",
            "/mnt/external", "/mnt/usbdisk"
        )
        if (!externalPrefixes.any { mnt.startsWith(it) }) return false
        if (mnt.contains("/emulated") || mnt == "/storage/emulated") return false
        val usbFs = setOf(
            "vfat", "exfat", "fuseblk", "ntfs", "ufsd", "texfat", "sdfat",
            "fuse", "fat32", "msdos"
        )
        return fs.lowercase() in usbFs ||
            mnt.matches(Regex(".*/[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}.*"))
    }

    private fun readMounts(): List<MountInfo> {
        val result = mutableListOf<MountInfo>()
        for (mountFile in listOf("/proc/mounts", "/proc/self/mounts")) {
            try {
                File(mountFile).forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        result.add(MountInfo(parts[1], parts[2], parts[0]))
                    }
                }
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                Timber.w(e, "Could not read $mountFile")
            }
        }
        // Fallback: scan /storage
        scanStorageDir("/storage", result)
        scanStorageDir("/mnt/media_rw", result)
        return result
    }

    private fun scanStorageDir(root: String, result: MutableList<MountInfo>) {
        try {
            File(root).listFiles()
                ?.filter { it.isDirectory && it.canRead() &&
                    !it.name.equals("emulated", true) &&
                    !it.name.equals("self", true) }
                ?.forEach { dir ->
                    result.add(MountInfo(dir.absolutePath, "vfat", "vold"))
                }
        } catch (_: Exception) {}
    }

    /**
     * Label for a mount point — tries to derive a human readable name.
     */
    fun labelFor(mountPoint: String): String {
        val name = File(mountPoint).name
        return if (name.matches(Regex("[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}"))) {
            "USB ($name)"
        } else {
            "USB ($name)"
        }
    }
}
