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

    fun getFsType(path: String): String? {
        val mounts = readMounts()
        return mounts.filter { path.startsWith(it.mountPoint) }
            .maxByOrNull { it.mountPoint.length }?.fsType
    }

    fun isFat32(path: String): Boolean {
        val fs = getFsType(path)?.lowercase() ?: return false
        return fs in setOf("vfat", "fat32", "msdos")
    }

    fun isExternalMount(path: String): Boolean {
        if (path.contains("/sdcard") || path.contains("/storage/emulated")) return false
        val mounts = readMounts()
        val best = mounts.filter { path.startsWith(it.mountPoint) }
            .maxByOrNull { it.mountPoint.length }
        return best != null && isExternalMountPoint(best.mountPoint, best.fsType)
    }

    /**
     * Returns all external/USB mount points, deduplicated.
     * Uses multiple strategies to maximize detection across Android versions.
     */
    fun listExternalMounts(): List<MountInfo> {
        val all = readMounts().filter { isExternalMountPoint(it.mountPoint, it.fsType) }

        val seenNames = mutableSetOf<String>()
        val seenBlocks = mutableSetOf<String>()
        val deduped = mutableListOf<MountInfo>()

        // First pass: prefer /storage/ mounts (user-accessible, works on most devices)
        for (m in all) {
            val nameKey = File(m.mountPoint).name.lowercase()
            val blockKey = normalizeBlockKey(m.blockDevice)
            val isNewByName = seenNames.add(nameKey)
            val isNewByBlock = if (m.blockDevice != "vold" && m.blockDevice.isNotBlank())
                seenBlocks.add(blockKey) else true
            if (m.mountPoint.startsWith("/storage/") && isNewByName && isNewByBlock) {
                deduped.add(m)
            }
        }
        // Second pass: add other mounts not already covered
        for (m in all) {
            val nameKey = File(m.mountPoint).name.lowercase()
            val blockKey = normalizeBlockKey(m.blockDevice)
            val isNewByName = seenNames.add(nameKey)
            val isNewByBlock = if (m.blockDevice != "vold" && m.blockDevice.isNotBlank())
                seenBlocks.add(blockKey) else true
            if (!m.mountPoint.startsWith("/storage/") && isNewByName && isNewByBlock) {
                deduped.add(m)
            }
        }

        Timber.d("listExternalMounts: ${deduped.map { it.mountPoint }}")
        return deduped
    }

    private fun normalizeBlockKey(blockDevice: String): String {
        return blockDevice.lowercase().trimEnd('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
    }

    private fun isExternalMountPoint(mnt: String, fs: String): Boolean {
        val externalPrefixes = listOf(
            "/storage/", "/mnt/media_rw/", "/mnt/usb", "/mnt/ext",
            "/mnt/sdcard2", "/mnt/external", "/mnt/usbdisk", "/run/media/"
        )
        if (!externalPrefixes.any { mnt.startsWith(it) }) return false
        if (mnt.contains("/emulated") || mnt == "/storage/emulated") return false
        // Filter out read-only system mounts
        if (mnt.startsWith("/mnt/media_rw/0") || mnt == "/mnt/media_rw") return false
        val usbFs = setOf(
            "vfat", "exfat", "fuseblk", "ntfs", "ufsd", "texfat", "sdfat",
            "fuse", "fat32", "msdos", "ext2", "ext3", "ext4", "f2fs"
        )
        return fs.lowercase() in usbFs ||
            mnt.matches(Regex(".*/[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}.*"))
    }

    private fun readMounts(): List<MountInfo> {
        val result = mutableListOf<MountInfo>()
        val seen = mutableSetOf<String>()

        // Strategy 1: /proc/mounts (most reliable)
        for (mountFile in listOf("/proc/mounts", "/proc/self/mounts")) {
            try {
                File(mountFile).forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3 && seen.add(parts[1])) {
                        result.add(MountInfo(parts[1], parts[2], parts[0]))
                    }
                }
                if (result.isNotEmpty()) break
            } catch (e: Exception) {
                Timber.w(e, "Could not read $mountFile")
            }
        }

        // Strategy 2: Scan /storage directory (works even without /proc/mounts)
        scanStorageDir("/storage", result, seen)
        scanStorageDir("/mnt/media_rw", result, seen)

        // Strategy 3: Check known USB mount paths
        for (path in listOf(
            "/mnt/usb_storage",
            "/mnt/usbdisk",
            "/mnt/usb",
            "/mnt/external_sd",
            "/mnt/sdcard2"
        )) {
            try {
                val f = File(path)
                if (f.exists() && f.isDirectory && seen.add(path)) {
                    // Try to read contents to verify it's accessible
                    if (f.listFiles() != null) {
                        result.add(MountInfo(path, "vfat", "vold"))
                    }
                }
            } catch (_: Exception) {}
        }

        // Strategy 4: Scan /mnt for USB subdirectories
        scanMntSubDirs(result, seen)

        return result
    }

    private fun scanStorageDir(root: String, result: MutableList<MountInfo>, seen: MutableSet<String>) {
        try {
            File(root).listFiles()
                ?.filter { dir ->
                    dir.isDirectory && dir.canRead() &&
                        !dir.name.equals("emulated", ignoreCase = true) &&
                        !dir.name.equals("self", ignoreCase = true) &&
                        dir.name != "0"
                }
                ?.forEach { dir ->
                    val path = dir.absolutePath
                    if (seen.add(path)) {
                        result.add(MountInfo(path, "vfat", "vold"))
                    }
                }
        } catch (_: Exception) {}
    }

    private fun scanMntSubDirs(result: MutableList<MountInfo>, seen: MutableSet<String>) {
        try {
            File("/mnt").listFiles()
                ?.filter { dir ->
                    dir.isDirectory && dir.canRead() &&
                        !dir.name.equals("sdcard", ignoreCase = true) &&
                        !dir.name.equals("user", ignoreCase = true) &&
                        !dir.name.equals("vendor", ignoreCase = true) &&
                        !dir.name.equals("asec", ignoreCase = true) &&
                        !dir.name.equals("obb", ignoreCase = true) &&
                        !dir.name.equals("expand", ignoreCase = true) &&
                        !dir.name.equals("runtime", ignoreCase = true)
                }
                ?.forEach { dir ->
                    val path = dir.absolutePath
                    if (seen.add(path) && (path.contains("usb", ignoreCase = true) ||
                            path.contains("ext", ignoreCase = true) ||
                            path.contains("media_rw", ignoreCase = true))) {
                        result.add(MountInfo(path, "vfat", "vold"))
                    }
                }
        } catch (_: Exception) {}
    }

    fun labelFor(mountPoint: String): String {
        val name = File(mountPoint).name
        return "USB ($name)"
    }
}
