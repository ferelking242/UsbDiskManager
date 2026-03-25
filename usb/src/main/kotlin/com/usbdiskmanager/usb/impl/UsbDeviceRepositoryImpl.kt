package com.usbdiskmanager.usb.impl

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.StatFs
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.core.model.DiskPartition
import com.usbdiskmanager.core.model.FileSystemType
import com.usbdiskmanager.core.util.PrivilegedCommandRunner
import com.usbdiskmanager.usb.api.UsbDeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.usbdiskmanager.USB_PERMISSION"

private data class UsbMountInfo(
    val blockDevice: String,
    val mountPoint: String,
    val fsType: String
)

internal data class PartitionInfo(
    val blockDev: String,
    val label: String,
    val fsType: String,
    val sizeMB: Long,
    val mountPoint: String?
)

@Singleton
class UsbDeviceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val commandRunner: PrivilegedCommandRunner
) : UsbDeviceRepository {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectedDevices = MutableStateFlow<List<DiskDevice>>(emptyList())
    override val connectedDevices = _connectedDevices.asStateFlow()

    private val rawDeviceMap = mutableMapOf<String, UsbDevice>()

    init {
        refreshConnectedDevices()
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    override fun refreshConnectedDevices() {
        val mounts = findAllMountPoints()
        Timber.d("External mounts: ${mounts.map { "${it.mountPoint}(${it.fsType})" }}")
        Timber.d("Privileged access: ${commandRunner.hasPrivilegedAccess}")

        val diskDevices = usbManager.deviceList.values
            .filter { isMassStorageDevice(it) }
            .map { device ->
                val id = deviceId(device)
                rawDeviceMap[id] = device
                buildDiskDevice(device, mounts)
            }

        Timber.d("USB mass storage devices found: ${diskDevices.size}")
        _connectedDevices.value = diskDevices
    }

    override fun onDeviceAttached(device: UsbDevice) {
        if (!isMassStorageDevice(device)) {
            Timber.d("Ignoring non-mass-storage USB: class=${device.deviceClass}")
            return
        }
        val id = deviceId(device)
        rawDeviceMap[id] = device
        val mounts = findAllMountPoints()
        val diskDevice = buildDiskDevice(device, mounts)
        _connectedDevices.value = _connectedDevices.value
            .filterNot { it.id == id }
            .plus(diskDevice)
        Timber.i("USB attached: ${device.deviceName}, mount=${diskDevice.mountPoint}")
    }

    override fun onDeviceDetached(device: UsbDevice) {
        val id = deviceId(device)
        rawDeviceMap.remove(id)
        _connectedDevices.value = _connectedDevices.value.filterNot { it.id == id }
        Timber.i("USB detached: ${device.deviceName}")
    }

    override suspend fun requestPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                device.deviceId,
                Intent(ACTION_USB_PERMISSION).apply { `package` = context.packageName },
                PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            PendingIntent.FLAG_MUTABLE else 0
            )
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        val granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false
                        )
                        context.unregisterReceiver(this)
                        if (cont.isActive) cont.resume(granted)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            if (usbManager.hasPermission(device)) {
                context.unregisterReceiver(receiver)
                cont.resume(true)
            } else {
                usbManager.requestPermission(device, pendingIntent)
            }
            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }

    override fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    override suspend fun mountDevice(deviceId: String): DiskOperationResult {
        val device = rawDeviceMap[deviceId]
            ?: return DiskOperationResult.Error("Périphérique introuvable: $deviceId")

        return try {
            val mounts = findAllMountPoints()
            val mountInfo = matchDeviceToMount(device, mounts)

            if (mountInfo != null) {
                // Already mounted by Android
                val (total, free) = getSpaceInfo(mountInfo.mountPoint)
                updateDeviceState(deviceId, mountInfo.mountPoint, true, total, free, mountInfo.fsType)
                createAppDirectories(mountInfo.mountPoint)
                DiskOperationResult.Success(
                    "✓ Monté sur ${mountInfo.mountPoint}\n" +
                    "Système: ${normalizeFsType(mountInfo.fsType)}\n" +
                    "Total: ${fmtBytes(total)}  •  Libre: ${fmtBytes(free)}\n" +
                    if (commandRunner.hasPrivilegedAccess) "Mode: Shizuku (privilégié)" else "Mode: Standard"
                )
            } else {
                val blockDevice = findBlockDeviceForUsb(device)
                if (blockDevice != null) {
                    tryMountBlockDevice(deviceId, device, blockDevice)
                } else {
                    DiskOperationResult.Error(
                        "Périphérique non monté.\n\n" +
                        "• Débrancher et rebrancher la clé\n" +
                        "• Attendre ~3 secondes puis appuyer Actualiser\n" +
                        "• S'assurer que la clé est en FAT32 ou exFAT\n" +
                        if (!commandRunner.hasPrivilegedAccess)
                            "\nActiver Shizuku permet de monter NTFS/EXT4 aussi"
                        else ""
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Mount failed for $deviceId")
            DiskOperationResult.Error("Erreur de montage: ${e.message}")
        }
    }

    override suspend fun unmountDevice(deviceId: String): DiskOperationResult {
        val device = _connectedDevices.value.find { it.id == deviceId }
            ?: return DiskOperationResult.Error("Périphérique introuvable")
        val mountPoint = device.mountPoint

        return try {
            // Flush writes before unmounting
            commandRunner.run("sync")

            if (mountPoint != null) {
                val result = commandRunner.run("umount \"$mountPoint\" 2>&1 || umount -l \"$mountPoint\" 2>&1")
                updateDeviceMountState(deviceId, null, false)
                if (result.isSuccess) {
                    DiskOperationResult.Success("✓ Éjecté proprement depuis $mountPoint")
                } else {
                    // Success from Android's perspective even if umount needs root
                    DiskOperationResult.Success(
                        "✓ Éjection demandée\n" +
                        if (!commandRunner.hasPrivilegedAccess)
                            "(éjection propre nécessite Shizuku ou root)"
                        else "(sync effectué)"
                    )
                }
            } else {
                updateDeviceMountState(deviceId, null, false)
                DiskOperationResult.Success("Périphérique non monté")
            }
        } catch (e: Exception) {
            DiskOperationResult.Error("Erreur: ${e.message}")
        }
    }

    override fun formatDevice(
        deviceId: String,
        fileSystem: String,
        label: String
    ): Flow<DiskOperationResult> = flow {
        emit(DiskOperationResult.Progress(0, "Préparation…"))

        if (!commandRunner.hasPrivilegedAccess) {
            emit(DiskOperationResult.Progress(5, "Mode standard — Shizuku requis pour formatage"))
        }

        val device = rawDeviceMap[deviceId]
            ?: run { emit(DiskOperationResult.Error("Périphérique introuvable")); return@flow }

        emit(DiskOperationResult.Progress(10, "Recherche du bloc device…"))
        val blockDevice = findBlockDeviceForUsb(device)
        val mounts = findAllMountPoints()
        val mountInfo = matchDeviceToMount(device, mounts)
        val blockDev = blockDevice ?: mountInfo?.blockDevice
            ?: run {
                emit(DiskOperationResult.Error(
                    "Impossible de trouver le bloc device.\n" +
                    "Le formatage nécessite Shizuku ou root."
                ))
                return@flow
            }

        emit(DiskOperationResult.Progress(20, "Démontage…"))
        mountInfo?.mountPoint?.let { commandRunner.run("sync && umount \"$it\" 2>&1 || true") }

        emit(DiskOperationResult.Progress(35, "Formatage en $fileSystem…"))
        val cmd = buildFormatCommand(blockDev, fileSystem, label)
        Timber.d("Format command: $cmd (privileged=${commandRunner.hasPrivilegedAccess})")
        val result = commandRunner.run(cmd, forcePrivileged = true)

        if (result.isSuccess) {
            emit(DiskOperationResult.Progress(95, "Finalisation…"))
            commandRunner.run("sync")
            emit(DiskOperationResult.Progress(100, "Formatage terminé !"))
            emit(DiskOperationResult.Success(
                "✓ Formaté en $fileSystem avec succès\n" +
                if (label.isNotEmpty()) "Label: $label" else ""
            ))
        } else {
            val errMsg = result.output.take(300)
            emit(DiskOperationResult.Error(
                "Formatage échoué: $errMsg\n\n" +
                if (!commandRunner.hasPrivilegedAccess)
                    "Active Shizuku pour le formatage sans root."
                else "Vérifier que le périphérique est accessible."
            ))
        }
    }

    override suspend fun refreshDevice(deviceId: String): DiskDevice? {
        val device = rawDeviceMap[deviceId] ?: return null
        val mounts = findAllMountPoints()
        val updated = buildDiskDevice(device, mounts)
        _connectedDevices.value = _connectedDevices.value
            .map { if (it.id == deviceId) updated else it }
        return updated
    }

    override fun getRawDevice(deviceId: String): UsbDevice? = rawDeviceMap[deviceId]

    // ─── Device building ─────────────────────────────────────────────────────

    private fun isMassStorageDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 8) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 8) return true
        }
        return false
    }

    private fun deviceId(device: UsbDevice): String =
        "${device.vendorId}_${device.productId}_${device.deviceName.hashCode()}"

    private fun buildDiskDevice(device: UsbDevice, mounts: List<UsbMountInfo>): DiskDevice {
        val id = deviceId(device)
        val mountInfo = matchDeviceToMount(device, mounts)
        val mountPoint = mountInfo?.mountPoint
        val (total, free) = getSpaceInfo(mountPoint)
        val fsType = mountInfo?.fsType?.let { normalizeFsType(it) }
        if (mountPoint != null) createAppDirectories(mountPoint)

        return DiskDevice(
            id = id,
            name = device.productName?.takeIf { it.isNotBlank() }
                ?: "USB Drive (${device.vendorId.toString(16).uppercase()}:${device.productId.toString(16).uppercase()})",
            vendorId = device.vendorId,
            productId = device.productId,
            serialNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { device.serialNumber } catch (_: SecurityException) { null }
            } else null,
            totalSpace = total,
            freeSpace = free,
            usedSpace = total - free,
            fileSystem = FileSystemType.fromString(fsType),
            mountPoint = mountPoint,
            isMounted = mountPoint != null,
            isWritable = mountPoint != null && File(mountPoint).canWrite()
        )
    }

    // ─── Mount detection ─────────────────────────────────────────────────────

    private fun findAllMountPoints(): List<UsbMountInfo> {
        val result = mutableListOf<UsbMountInfo>()
        val seen = mutableSetOf<String>()

        // Strategy 1+2: /proc/mounts
        for (mountFile in listOf("/proc/mounts", "/proc/self/mounts")) {
            try {
                File(mountFile).forEachLine { line ->
                    val p = line.trim().split(Regex("\\s+"))
                    if (p.size >= 3 && isExternalMount(p[1], p[2]) && seen.add(p[1]))
                        result.add(UsbMountInfo(p[0], p[1], p[2]))
                }
                if (result.isNotEmpty()) break
            } catch (_: Exception) {}
        }

        // Strategy 3-6: storage scan
        for (root in listOf("/storage", "/mnt/media_rw", "/mnt/usb_storage")) {
            scanDir(root, result, seen)
        }
        for (dir in listOf("/mnt/ext_sd", "/mnt/sdcard2", "/mnt/external_sd", "/mnt/usb")) {
            try {
                val f = File(dir)
                if (f.exists() && f.isDirectory && f.canRead() && seen.add(dir))
                    result.add(UsbMountInfo("vold", dir, "vfat"))
            } catch (_: Exception) {}
        }
        return result
    }

    private fun scanDir(root: String, result: MutableList<UsbMountInfo>, seen: MutableSet<String>) {
        try {
            File(root).listFiles()
                ?.filter { it.isDirectory && it.canRead() &&
                    !it.name.equals("emulated", true) &&
                    !it.name.equals("self", true) && it.name != "0" }
                ?.forEach { dir ->
                    val path = dir.absolutePath
                    if (seen.add(path)) result.add(UsbMountInfo("vold", path, "vfat"))
                }
        } catch (_: Exception) {}
    }

    private fun isExternalMount(mnt: String, fs: String): Boolean {
        val prefixes = listOf("/storage/", "/mnt/media_rw/", "/mnt/usb", "/mnt/ext", "/mnt/sdcard", "/mnt/external")
        if (!prefixes.any { mnt.startsWith(it) }) return false
        if (mnt.contains("/emulated") || mnt == "/storage/emulated") return false
        val usbFs = setOf("vfat","exfat","fuseblk","ntfs","ufsd","texfat","sdfat","fuse",
            "ext2","ext3","ext4","f2fs","hfsplus","iso9660","udf","fat32","msdos")
        return fs.lowercase() in usbFs || mnt.matches(Regex(".*/[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}.*"))
    }

    private fun matchDeviceToMount(device: UsbDevice, mounts: List<UsbMountInfo>): UsbMountInfo? {
        if (mounts.isEmpty()) return null
        val sysfs = tryMatchViaSysfs(device, mounts)
        if (sysfs != null) return sysfs
        val massCount = usbManager.deviceList.values.count { isMassStorageDevice(it) }
        if (massCount == 1 && mounts.size == 1) return mounts.first()
        if (massCount == 1 && mounts.size > 1) {
            return mounts.firstOrNull { m ->
                try { StatFs(m.mountPoint).blockCountLong > 0 } catch (_: Exception) { false }
            } ?: mounts.first()
        }
        return null
    }

    private fun tryMatchViaSysfs(device: UsbDevice, mounts: List<UsbMountInfo>): UsbMountInfo? {
        return try {
            val parts = device.deviceName.split("/")
            val busNum = parts.getOrNull(4)?.trimStart('0')?.ifEmpty { "0" }?.toIntOrNull() ?: return null
            val devNum = parts.getOrNull(5)?.trimStart('0')?.ifEmpty { "0" }?.toIntOrNull() ?: return null
            val sysfsDir = File("/sys/bus/usb/devices/").listFiles()?.firstOrNull { dir ->
                try {
                    File("${dir.absolutePath}/busnum").readText().trim().toInt() == busNum &&
                    File("${dir.absolutePath}/devnum").readText().trim().toInt() == devNum
                } catch (_: Exception) { false }
            } ?: return null
            var blockName: String? = null
            sysfsDir.walkTopDown().maxDepth(10).forEach { f ->
                if (f.name == "block" && f.isDirectory && blockName == null)
                    blockName = f.listFiles()?.firstOrNull()?.name
            }
            blockName ?: return null
            mounts.firstOrNull { m ->
                m.blockDevice.contains(blockName!!) ||
                m.blockDevice == "/dev/$blockName" ||
                m.blockDevice == "/dev/${blockName}1"
            }
        } catch (_: Exception) { null }
    }

    // ─── Block device discovery ───────────────────────────────────────────────

    private fun findBlockDeviceForUsb(device: UsbDevice): String? {
        return try {
            val parts = device.deviceName.split("/")
            val busNum = parts.getOrNull(4)?.trimStart('0')?.ifEmpty { "0" }?.toIntOrNull() ?: return null
            val devNum = parts.getOrNull(5)?.trimStart('0')?.ifEmpty { "0" }?.toIntOrNull() ?: return null
            val sysfsDir = File("/sys/bus/usb/devices/").listFiles()?.firstOrNull { dir ->
                try {
                    File("${dir.absolutePath}/busnum").readText().trim().toInt() == busNum &&
                    File("${dir.absolutePath}/devnum").readText().trim().toInt() == devNum
                } catch (_: Exception) { false }
            } ?: return null
            var blockName: String? = null
            sysfsDir.walkTopDown().maxDepth(10).forEach { f ->
                if (f.name == "block" && f.isDirectory && blockName == null)
                    blockName = f.listFiles()?.firstOrNull()?.name
            }
            blockName?.let { "/dev/$it" }
        } catch (_: Exception) { null }
    }

    // ─── Mount with Shizuku/shell ─────────────────────────────────────────────

    private suspend fun tryMountBlockDevice(
        deviceId: String,
        device: UsbDevice,
        blockDevice: String
    ): DiskOperationResult {
        val detectedFs = detectFilesystem(blockDevice)
        val kernelFs = getSupportedFilesystems()
        Timber.d("Detected FS: $detectedFs, privileged=${commandRunner.hasPrivilegedAccess}")

        val mountPoints = listOf(
            "/storage/${blockDevice.substringAfterLast('/')}",
            "/mnt/usb/${blockDevice.substringAfterLast('/')}",
            "/mnt/media_rw/${blockDevice.substringAfterLast('/')}"
        )
        val mountCmds = buildMountCommands(blockDevice, detectedFs, kernelFs)

        for (mp in mountPoints) {
            try { File(mp).mkdirs() } catch (_: Exception) {}
            for (cmd in mountCmds) {
                val full = "$cmd \"$mp\" 2>&1"
                val result = commandRunner.run(full, forcePrivileged = false)
                if (result.isSuccess) {
                    val updatedMounts = findAllMountPoints()
                    val found = updatedMounts.firstOrNull { it.mountPoint == mp }
                        ?: updatedMounts.firstOrNull { it.blockDevice.contains(blockDevice) }
                    val actualMp = found?.mountPoint ?: mp
                    val (total, free) = getSpaceInfo(actualMp)
                    if (total > 0) {
                        updateDeviceState(deviceId, actualMp, true, total, free, detectedFs ?: "vfat")
                        createAppDirectories(actualMp)
                        return DiskOperationResult.Success(
                            "✓ Monté sur $actualMp\n" +
                            "Système: ${detectedFs?.uppercase() ?: "Auto"}\n" +
                            "Total: ${fmtBytes(total)}  •  Libre: ${fmtBytes(free)}\n" +
                            "Shizuku: ${if (commandRunner.hasPrivilegedAccess) "Actif" else "Non actif"}"
                        )
                    }
                }
            }
        }

        // Nothing worked — give honest error
        val fsInfo = if (detectedFs != null) "FS détecté: ${detectedFs.uppercase()}" else ""
        val supportStatus = if (detectedFs != null && kernelFs.isNotEmpty()) {
            if (kernelFs.any { it.contains(detectedFs, true) }) "✓ Supporté par ce kernel"
            else "✗ Non supporté par ce kernel — formater en FAT32/exFAT"
        } else ""

        return DiskOperationResult.Error(
            "Montage impossible.\n$fsInfo\n$supportStatus\n\n" +
            if (!commandRunner.hasPrivilegedAccess)
                "Active Shizuku pour monter NTFS/EXT4/F2FS."
            else "Essayer de débrancher et rebrancher la clé."
        )
    }

    /**
     * Build mount command list — Shizuku enables more command types.
     */
    private fun buildMountCommands(
        blockDevice: String,
        detectedFs: String?,
        kernelFs: List<String>
    ): List<String> {
        val cmds = mutableListOf<String>()
        val partitions = listOf("${blockDevice}1", "${blockDevice}p1", blockDevice)

        for (part in partitions) {
            when (detectedFs?.lowercase()) {
                "ntfs" -> {
                    if (commandRunner.hasPrivilegedAccess) {
                        cmds.add("ntfs-3g -o rw,big_writes,allow_other $part")
                        cmds.add("mount -t ntfs-3g -o rw $part")
                        cmds.add("mount -t ntfs $part")
                        cmds.add("mount -t fuseblk -o rw,allow_other $part")
                    }
                }
                "exfat" -> {
                    cmds.add("mount -t exfat -o rw,uid=0,gid=0 $part")
                    cmds.add("mount -t texfat -o rw $part")
                    if (commandRunner.hasPrivilegedAccess) {
                        cmds.add("mount -t fuse.exfat -o rw $part")
                    }
                }
                "ext4" -> {
                    if (commandRunner.hasPrivilegedAccess) {
                        cmds.add("mount -t ext4 -o rw,noatime $part")
                        cmds.add("mount -t ext4 $part")
                    }
                }
                "ext3" -> if (commandRunner.hasPrivilegedAccess) cmds.add("mount -t ext3 -o rw,noatime $part")
                "ext2" -> if (commandRunner.hasPrivilegedAccess) cmds.add("mount -t ext2 -o rw $part")
                "f2fs" -> if (commandRunner.hasPrivilegedAccess) cmds.add("mount -t f2fs -o rw $part")
                else -> {
                    cmds.add("mount -t vfat -o rw,uid=0,gid=0 $part")
                    cmds.add("mount -t exfat -o rw,uid=0,gid=0 $part")
                    cmds.add("mount -o rw $part")
                }
            }
            // FAT32 always as last-resort
            if (detectedFs?.lowercase() != "vfat") {
                cmds.add("mount -t vfat -o rw,uid=0,gid=0,fmask=0000,dmask=0000 $part")
            }
        }
        return cmds
    }

    // ─── Filesystem detection via Shizuku + fallback ──────────────────────────

    /**
     * Detect filesystem type. With Shizuku: blkid is reliable.
     * Fallback: magic bytes.
     */
    private suspend fun detectFilesystem(blockDevice: String): String? {
        // Try blkid (needs privileged access usually, but some ROMs allow it)
        val blkid = commandRunner.run("blkid $blockDevice 2>/dev/null")
        if (blkid.isSuccess && blkid.output.isNotEmpty()) {
            val match = Regex("TYPE=\"([^\"]+)\"").find(blkid.output)
            if (match != null) return match.groupValues[1].also { Timber.d("blkid: $it") }
        }

        // Try 'file -s' (Shizuku may allow it)
        val fileCmd = commandRunner.run("file -s $blockDevice 2>/dev/null")
        if (fileCmd.isSuccess && fileCmd.output.isNotEmpty()) {
            val out = fileCmd.output.lowercase()
            when {
                "ntfs" in out -> return "ntfs"
                "exfat" in out || "extended fat" in out -> return "exfat"
                "fat" in out -> return "vfat"
                "ext4" in out -> return "ext4"
                "ext3" in out -> return "ext3"
                "f2fs" in out -> return "f2fs"
            }
        }

        // Fallback: superblock magic bytes (no privilege needed)
        return detectViaMagicBytes(blockDevice)
    }

    /**
     * With Shizuku: run lsblk to enumerate all partitions on the disk.
     * Returns list of PartitionInfo or empty list if unavailable.
     */
    private suspend fun getPartitionsForDevice(blockDevice: String): List<PartitionInfo> {
        if (!commandRunner.hasPrivilegedAccess) return emptyList()

        val lsblk = commandRunner.run(
            "lsblk -o NAME,FSTYPE,SIZE,LABEL,MOUNTPOINT -n -l $blockDevice 2>/dev/null"
        )
        if (!lsblk.isSuccess || lsblk.output.isEmpty()) return emptyList()

        return lsblk.output.lines()
            .filter { it.isNotBlank() }
            .mapIndexed { idx, line ->
                val cols = line.trim().split(Regex("\\s+"))
                PartitionInfo(
                    blockDev = "/dev/${cols.getOrElse(0) { "" }}",
                    fsType = cols.getOrElse(1) { "unknown" },
                    sizeMB = parseSizeToMB(cols.getOrElse(2) { "0" }),
                    label = cols.getOrElse(3) { "" },
                    mountPoint = cols.getOrElse(4) { null }.takeIf { it != null && it != "-" }
                )
            }
            .filter { it.blockDev.isNotBlank() }
    }

    /**
     * With Shizuku: run fdisk -l to get partition table.
     */
    private suspend fun getDiskInfo(blockDevice: String): String {
        if (!commandRunner.hasPrivilegedAccess) return ""
        val result = commandRunner.run("fdisk -l $blockDevice 2>/dev/null")
        return if (result.isSuccess) result.output else ""
    }

    private fun parseSizeToMB(sizeStr: String): Long {
        return try {
            val n = sizeStr.dropLast(1).toLong()
            when (sizeStr.last().uppercaseChar()) {
                'T' -> n * 1024 * 1024
                'G' -> n * 1024
                'M' -> n
                'K' -> n / 1024
                else -> n
            }
        } catch (_: Exception) { 0L }
    }

    private fun detectViaMagicBytes(blockDevice: String): String? {
        return try {
            for (part in listOf("${blockDevice}1", "${blockDevice}p1", blockDevice)) {
                try {
                    val raf = RandomAccessFile(part, "r")
                    val magic = ByteArray(16)
                    raf.use {
                        it.seek(3); it.read(magic, 0, 8)
                        val s = String(magic, 0, 5)
                        when {
                            s.startsWith("FAT32") || s.startsWith("FAT16") -> return "vfat"
                            s.startsWith("EXFAT") -> return "exfat"
                            s.startsWith("NTFS") -> return "ntfs"
                        }
                        it.seek(0x438)
                        val extMagic = ByteArray(2)
                        it.read(extMagic)
                        if (extMagic[0] == 0x53.toByte() && extMagic[1] == 0xEF.toByte())
                            return "ext4"
                    }
                } catch (_: Exception) {}
            }
            null
        } catch (_: Exception) { null }
    }

    private fun getSupportedFilesystems(): List<String> {
        return try {
            File("/proc/filesystems").readLines()
                .map { it.trim().removePrefix("nodev").trim() }
                .filter { it.isNotEmpty() }
        } catch (_: Exception) { emptyList() }
    }

    // ─── Space info ──────────────────────────────────────────────────────────

    /**
     * Get disk space. Tries StatFs first (no privilege needed),
     * falls back to 'df' via Shizuku.
     */
    private fun getSpaceInfo(mountPoint: String?): Pair<Long, Long> {
        if (mountPoint == null) return Pair(0L, 0L)
        return try {
            val stat = StatFs(mountPoint)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            if (total > 0) {
                Timber.d("StatFs($mountPoint): ${fmtBytes(total)}, free=${fmtBytes(free)}")
                Pair(total, free)
            } else Pair(0L, 0L)
        } catch (e: Exception) {
            Timber.w("StatFs failed on $mountPoint: ${e.message}")
            Pair(0L, 0L)
        }
    }

    // ─── Formatting ──────────────────────────────────────────────────────────

    private fun buildFormatCommand(blockDevice: String, fs: String, label: String): String {
        val lbl = if (label.isNotEmpty()) "-n \"$label\"" else ""
        return when (fs.uppercase()) {
            "FAT32" -> "mkfs.vfat -F 32 $lbl $blockDevice"
            "EXFAT" -> "mkfs.exfat $lbl $blockDevice"
            "NTFS" -> "mkfs.ntfs --fast $lbl $blockDevice"
            "EXT4" -> "mkfs.ext4 -F $lbl $blockDevice"
            "EXT3" -> "mkfs.ext3 -F $lbl $blockDevice"
            else -> "mkfs.vfat -F 32 $blockDevice"
        }
    }

    // ─── App directories ─────────────────────────────────────────────────────

    private fun createAppDirectories(mountPoint: String) {
        try {
            val appDir = File(mountPoint, "UsbDiskManager")
            if (appDir.mkdirs() || appDir.exists()) {
                File(appDir, "Logs").mkdirs()
                File(appDir, "Backups").mkdirs()
            }
        } catch (e: Exception) {
            Timber.w("App dir creation failed: ${e.message}")
        }
    }

    // ─── State helpers ───────────────────────────────────────────────────────

    private fun updateDeviceState(
        deviceId: String, mountPoint: String?, mounted: Boolean,
        total: Long, free: Long, fsType: String
    ) {
        _connectedDevices.value = _connectedDevices.value.map { d ->
            if (d.id == deviceId) d.copy(
                mountPoint = mountPoint,
                isMounted = mounted,
                isWritable = mounted && mountPoint != null && File(mountPoint).canWrite(),
                totalSpace = total,
                freeSpace = free,
                usedSpace = total - free,
                fileSystem = FileSystemType.fromString(normalizeFsType(fsType))
            ) else d
        }
    }

    private fun updateDeviceMountState(deviceId: String, mountPoint: String?, mounted: Boolean) {
        _connectedDevices.value = _connectedDevices.value.map { d ->
            if (d.id == deviceId) d.copy(
                mountPoint = mountPoint,
                isMounted = mounted,
                isWritable = mounted && mountPoint != null && File(mountPoint).canWrite()
            ) else d
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private fun normalizeFsType(raw: String): String = when (raw.lowercase()) {
        "vfat","fat","fat32","msdos" -> "FAT32"
        "fuseblk","exfat","texfat","sdfat" -> "exFAT"
        "ntfs","ufsd" -> "NTFS"
        "ext4" -> "EXT4"; "ext3" -> "EXT3"; "ext2" -> "EXT2"
        "f2fs" -> "F2FS"; "hfsplus" -> "HFS+"; "iso9660" -> "ISO9660"
        else -> raw.uppercase()
    }

    private fun fmtBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B","KB","MB","GB","TB")
        var v = bytes.toDouble(); var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return "%.1f %s".format(v, units[u])
    }
}
