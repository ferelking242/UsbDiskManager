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
import com.usbdiskmanager.core.model.FileSystemType
import com.usbdiskmanager.core.util.executeShellCommand
import com.usbdiskmanager.usb.api.UsbDeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.usbdiskmanager.USB_PERMISSION"

private data class UsbMountInfo(
    val blockDevice: String,
    val mountPoint: String,
    val fsType: String
)

@Singleton
class UsbDeviceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
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
        Timber.d("External mounts found: ${mounts.map { "${it.mountPoint}(${it.fsType})" }}")

        val devices = usbManager.deviceList.values
        val diskDevices = devices.mapNotNull { usbDevice ->
            if (isMassStorageDevice(usbDevice)) {
                val id = deviceId(usbDevice)
                rawDeviceMap[id] = usbDevice
                buildDiskDevice(usbDevice, mounts)
            } else null
        }
        Timber.d("USB mass storage devices: ${diskDevices.size}")
        _connectedDevices.value = diskDevices
    }

    override fun onDeviceAttached(usbDevice: UsbDevice) {
        if (isMassStorageDevice(usbDevice)) {
            val id = deviceId(usbDevice)
            rawDeviceMap[id] = usbDevice
            val mounts = findAllMountPoints()
            val diskDevice = buildDiskDevice(usbDevice, mounts)
            val current = _connectedDevices.value.toMutableList()
            current.removeAll { it.id == id }
            current.add(diskDevice)
            _connectedDevices.value = current
            Timber.i("USB mass storage attached: ${usbDevice.deviceName}, " +
                     "mount=${diskDevice.mountPoint}, total=${diskDevice.totalSpace}")
        } else {
            Timber.d("Ignoring non-mass-storage USB: ${usbDevice.deviceName} class=${usbDevice.deviceClass}")
        }
    }

    override fun onDeviceDetached(usbDevice: UsbDevice) {
        val id = deviceId(usbDevice)
        rawDeviceMap.remove(id)
        val current = _connectedDevices.value.toMutableList()
        current.removeAll { it.id == id }
        _connectedDevices.value = current
        Timber.i("USB device detached: ${usbDevice.deviceName}")
    }

    override suspend fun requestPermission(device: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val permissionIntent = PendingIntent.getBroadcast(
                context,
                device.deviceId,
                Intent(ACTION_USB_PERMISSION).apply { `package` = context.packageName },
                PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            )

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
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
                usbManager.requestPermission(device, permissionIntent)
            }

            cont.invokeOnCancellation {
                try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            }
        }

    override fun hasPermission(device: UsbDevice): Boolean =
        usbManager.hasPermission(device)

    /**
     * "Mount" in our context = vérifier si Android a auto-monté le périphérique,
     * lire l'espace réel, créer le dossier app, et mettre à jour l'état.
     *
     * Android monte automatiquement les clés USB compatibles (FAT32/exFAT).
     * Pour NTFS/EXT4, on tente plusieurs stratégies.
     */
    override suspend fun mountDevice(deviceId: String): DiskOperationResult {
        val device = rawDeviceMap[deviceId]
            ?: return DiskOperationResult.Error("Périphérique introuvable: $deviceId")

        return try {
            val mounts = findAllMountPoints()
            val mountInfo = matchDeviceToMount(device, mounts)

            if (mountInfo != null) {
                // Déjà monté par Android — mettre à jour l'état
                updateDeviceMountState(deviceId, mountInfo.mountPoint, true)
                val (total, free) = getSpaceInfo(mountInfo.mountPoint)
                updateDeviceSpaceInfo(deviceId, total, free, mountInfo.fsType)
                createAppDirectoriesOnDevice(mountInfo.mountPoint)
                DiskOperationResult.Success(
                    "✓ Monté sur ${mountInfo.mountPoint}\n" +
                    "Total: ${formatBytes(total)}  •  Libre: ${formatBytes(free)}\n" +
                    "Système: ${normalizeFsType(mountInfo.fsType)}"
                )
            } else {
                // Pas encore monté → détecter le block device et essayer de monter
                val blockDevice = findBlockDeviceForUsb(device)
                if (blockDevice != null) {
                    val mountResult = tryMountBlockDevice(deviceId, device, blockDevice)
                    mountResult
                } else {
                    DiskOperationResult.Error(
                        "Périphérique non monté par le système.\n\n" +
                        "Solutions :\n" +
                        "• Débrancher et rebrancher la clé\n" +
                        "• Attendre ~3 secondes puis appuyer sur Actualiser\n" +
                        "• Vérifier que la clé est en FAT32 ou exFAT"
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

        return try {
            val mountPoint = device.mountPoint
            if (mountPoint != null) {
                executeShellCommand("sync")
                val result = executeShellCommand(
                    "umount \"$mountPoint\" 2>&1 || umount -l \"$mountPoint\" 2>&1"
                )
                updateDeviceMountState(deviceId, null, false)
                if (result.isSuccess) {
                    DiskOperationResult.Success("✓ Périphérique éjecté en toute sécurité")
                } else {
                    DiskOperationResult.Success(
                        "Périphérique marqué comme non monté\n" +
                        "(éjection propre nécessite root)"
                    )
                }
            } else {
                updateDeviceMountState(deviceId, null, false)
                DiskOperationResult.Success("Périphérique non monté")
            }
        } catch (e: Exception) {
            Timber.e(e, "Unmount failed for $deviceId")
            DiskOperationResult.Error("Erreur d'éjection: ${e.message}")
        }
    }

    override fun formatDevice(
        deviceId: String,
        fileSystem: String,
        label: String
    ): Flow<DiskOperationResult> = flow {
        emit(DiskOperationResult.Progress(0, "Préparation du formatage…"))
        val device = rawDeviceMap[deviceId]
        if (device == null) {
            emit(DiskOperationResult.Error("Périphérique introuvable"))
            return@flow
        }

        emit(DiskOperationResult.Progress(10, "Recherche du bloc device…"))
        val blockDevice = findBlockDeviceForUsb(device)
        if (blockDevice == null) {
            val mounts = findAllMountPoints()
            val mountInfo = matchDeviceToMount(device, mounts)
            if (mountInfo == null) {
                emit(DiskOperationResult.Error(
                    "Bloc device introuvable. Le formatage nécessite root ou accès kernel."
                ))
                return@flow
            }
        }

        val mounts = findAllMountPoints()
        val mountInfo = matchDeviceToMount(device, mounts)
        val blockDev = blockDevice ?: mountInfo?.blockDevice ?: run {
            emit(DiskOperationResult.Error("Impossible de trouver le périphérique bloc"))
            return@flow
        }

        emit(DiskOperationResult.Progress(20, "Démontage…"))
        mountInfo?.mountPoint?.let { mp ->
            executeShellCommand("sync && umount \"$mp\" 2>&1 || true")
        }

        emit(DiskOperationResult.Progress(30, "Formatage en $fileSystem…"))
        val cmd = buildFormatCommand(blockDev, fileSystem, label)
        Timber.d("Format command: $cmd")
        val result = executeShellCommand(cmd)

        if (result.isSuccess) {
            emit(DiskOperationResult.Progress(100, "Formatage terminé !"))
            emit(DiskOperationResult.Success("✓ Formaté en $fileSystem avec succès"))
        } else {
            emit(DiskOperationResult.Error(
                "Formatage échoué: ${result.output.take(200)}\n\n" +
                "Note: le formatage nécessite root."
            ))
        }
    }

    override suspend fun refreshDevice(deviceId: String): DiskDevice? {
        val device = rawDeviceMap[deviceId] ?: return null
        val mounts = findAllMountPoints()
        val diskDevice = buildDiskDevice(device, mounts)
        val current = _connectedDevices.value.toMutableList()
        val index = current.indexOfFirst { it.id == deviceId }
        if (index >= 0) current[index] = diskDevice else current.add(diskDevice)
        _connectedDevices.value = current
        return diskDevice
    }

    override fun getRawDevice(deviceId: String): UsbDevice? = rawDeviceMap[deviceId]

    // ─── Device building ─────────────────────────────────────────────────────

    private fun deviceId(device: UsbDevice): String =
        "${device.vendorId}_${device.productId}_${device.deviceName.hashCode()}"

    /**
     * Vérifie si le périphérique USB est un Mass Storage (class 8).
     * Vérifie aussi les interfaces (certains hubs rapportent class 0 au niveau device).
     */
    private fun isMassStorageDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 8) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 8) return true
        }
        return false
    }

    private fun buildDiskDevice(device: UsbDevice, mounts: List<UsbMountInfo>): DiskDevice {
        val id = deviceId(device)
        val mountInfo = matchDeviceToMount(device, mounts)
        val mountPoint = mountInfo?.mountPoint
        val (total, free) = getSpaceInfo(mountPoint)
        val fsType = mountInfo?.fsType?.let { normalizeFsType(it) }

        if (mountPoint != null) {
            createAppDirectoriesOnDevice(mountPoint)
        }

        return DiskDevice(
            id = id,
            name = device.productName?.takeIf { it.isNotBlank() }
                ?: "USB Drive (${device.vendorId.toString(16).uppercase()}:${device.productId.toString(16).uppercase()})",
            vendorId = device.vendorId,
            productId = device.productId,
            serialNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) device.serialNumber else null,
            totalSpace = total,
            freeSpace = free,
            usedSpace = total - free,
            fileSystem = FileSystemType.fromString(fsType),
            mountPoint = mountPoint,
            isMounted = mountPoint != null,
            isWritable = mountPoint != null && File(mountPoint).canWrite()
        )
    }

    // ─── Mount detection — stratégie multi-couche ────────────────────────────

    /**
     * Cherche tous les points de montage externes possibles.
     *
     * Ordre de priorité :
     *   1. /proc/mounts  → lecture directe du kernel (toujours exact)
     *   2. /proc/self/mounts → alternative si /proc/mounts inaccessible
     *   3. Scan /storage/ → dossiers XXXX-XXXX créés par vold
     *   4. Scan /mnt/media_rw/ → autre chemin courant
     *   5. Scan /mnt/usb_storage/ → certains OEM (Samsung, etc.)
     */
    private fun findAllMountPoints(): List<UsbMountInfo> {
        val result = mutableListOf<UsbMountInfo>()
        val seenMountPoints = mutableSetOf<String>()

        // Stratégie 1 & 2 : lire /proc/mounts
        val mountFiles = listOf("/proc/mounts", "/proc/self/mounts")
        for (mountFile in mountFiles) {
            try {
                File(mountFile).forEachLine { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        val blk = parts[0]
                        val mnt = parts[1]
                        val fs = parts[2]
                        if (isExternalMount(mnt, fs) && seenMountPoints.add(mnt)) {
                            result.add(UsbMountInfo(blk, mnt, fs))
                            Timber.v("Mount from $mountFile: $mnt ($fs)")
                        }
                    }
                }
                if (result.isNotEmpty()) break
            } catch (e: Exception) {
                Timber.w("Cannot read $mountFile: ${e.message}")
            }
        }

        // Stratégie 3 : scan /storage/ pour XXXX-XXXX
        scanStorageDir("/storage", result, seenMountPoints)

        // Stratégie 4 : /mnt/media_rw/
        scanStorageDir("/mnt/media_rw", result, seenMountPoints)

        // Stratégie 5 : /mnt/usb_storage/ (OEM Samsung, Xiaomi, etc.)
        scanStorageDir("/mnt/usb_storage", result, seenMountPoints)

        // Stratégie 6 : /mnt/ext_sd/, /mnt/sdcard2/, /mnt/external_sd/
        listOf("/mnt/ext_sd", "/mnt/sdcard2", "/mnt/external_sd", "/mnt/usb").forEach { dir ->
            try {
                val f = File(dir)
                if (f.exists() && f.isDirectory && f.canRead() && seenMountPoints.add(dir)) {
                    result.add(UsbMountInfo("vold", dir, "vfat"))
                    Timber.v("OEM mount dir found: $dir")
                }
            } catch (_: Exception) {}
        }

        Timber.d("Total external mount points found: ${result.size}")
        return result
    }

    private fun scanStorageDir(
        rootPath: String,
        result: MutableList<UsbMountInfo>,
        seen: MutableSet<String>
    ) {
        try {
            File(rootPath).listFiles()
                ?.filter { dir ->
                    dir.isDirectory && dir.canRead() &&
                    !dir.name.equals("emulated", ignoreCase = true) &&
                    !dir.name.equals("self", ignoreCase = true) &&
                    dir.name != "0"
                }
                ?.forEach { dir ->
                    val path = dir.absolutePath
                    if (seen.add(path)) {
                        val fs = guessFsTypeFromPath(path)
                        result.add(UsbMountInfo("vold", path, fs))
                        Timber.v("Storage scan found: $path ($fs)")
                    }
                }
        } catch (e: Exception) {
            Timber.v("Cannot scan $rootPath: ${e.message}")
        }
    }

    /**
     * Détermine si un point de montage est un périphérique externe USB/SD.
     */
    private fun isExternalMount(mountPath: String, fsType: String): Boolean {
        val externalPrefixes = listOf(
            "/storage/", "/mnt/media_rw/", "/mnt/usb", "/mnt/ext",
            "/mnt/sdcard", "/mnt/external", "/mnt/usb_storage"
        )
        val isExternalPath = externalPrefixes.any { mountPath.startsWith(it) }
        if (!isExternalPath) return false

        if (mountPath.contains("/emulated")) return false
        if (mountPath == "/storage/emulated") return false

        val usbFileSystems = setOf(
            "vfat", "exfat", "fuseblk", "ntfs", "ufsd", "texfat", "sdfat",
            "fuse", "ext2", "ext3", "ext4", "f2fs", "hfsplus", "iso9660",
            "udf", "nfs", "fat32", "msdos"
        )
        return fsType.lowercase() in usbFileSystems ||
               mountPath.matches(Regex(".*/[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}.*"))
    }

    /**
     * Associe un UsbDevice à son point de montage.
     * Stratégies dans l'ordre :
     *   1. Sysfs (bus/device numbers → block device → /proc/mounts)
     *   2. Single device heuristic (1 USB, 1 mont = ça correspond)
     *   3. Première entrée disponible (best effort)
     */
    private fun matchDeviceToMount(device: UsbDevice, mounts: List<UsbMountInfo>): UsbMountInfo? {
        if (mounts.isEmpty()) return null

        // Stratégie 1: Sysfs
        val sysfsMatch = tryMatchViaSysfs(device, mounts)
        if (sysfsMatch != null) return sysfsMatch

        // Stratégie 2: Seul périphérique USB = seul point de montage
        val massStorageCount = usbManager.deviceList.values.count { isMassStorageDevice(it) }
        if (massStorageCount == 1 && mounts.size == 1) {
            Timber.d("Single USB+single mount → assigning ${mounts.first().mountPoint}")
            return mounts.first()
        }

        // Stratégie 3: Un seul USB, plusieurs mounts → prendre le premier qui a de l'espace
        if (massStorageCount == 1 && mounts.size > 1) {
            val withSpace = mounts.firstOrNull { mount ->
                try {
                    val stat = StatFs(mount.mountPoint)
                    stat.blockCountLong > 0
                } catch (_: Exception) { false }
            }
            if (withSpace != null) {
                Timber.d("Single USB, found mount with space: ${withSpace.mountPoint}")
                return withSpace
            }
            return mounts.first()
        }

        return null
    }

    private fun tryMatchViaSysfs(device: UsbDevice, mounts: List<UsbMountInfo>): UsbMountInfo? {
        return try {
            // device.deviceName = "/dev/bus/usb/001/002"
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
            sysfsDir.walkTopDown().maxDepth(10).forEach { file ->
                if (file.name == "block" && file.isDirectory && blockName == null) {
                    blockName = file.listFiles()?.firstOrNull()?.name
                }
            }

            if (blockName == null) return null
            Timber.v("Sysfs block device for ${device.deviceName}: $blockName")

            mounts.firstOrNull { mount ->
                mount.blockDevice.contains(blockName!!) ||
                mount.blockDevice.endsWith("/$blockName") ||
                mount.blockDevice.endsWith("/${blockName}1") ||
                mount.blockDevice.endsWith("/${blockName}p1") ||
                mount.blockDevice == "/dev/$blockName" ||
                mount.blockDevice == "/dev/${blockName}1"
            }
        } catch (e: Exception) {
            Timber.v("Sysfs matching failed: ${e.message}")
            null
        }
    }

    // ─── Block device discovery ───────────────────────────────────────────────

    /**
     * Trouve le bloc device (/dev/sdX) associé à un UsbDevice via sysfs.
     * Retourne null si non trouvé (Android sans root l'empêche souvent).
     */
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
            sysfsDir.walkTopDown().maxDepth(10).forEach { file ->
                if (file.name == "block" && file.isDirectory && blockName == null) {
                    blockName = file.listFiles()?.firstOrNull()?.name
                }
            }

            blockName?.let { "/dev/$it" }
        } catch (e: Exception) {
            Timber.v("findBlockDevice failed: ${e.message}")
            null
        }
    }

    /**
     * Essaie de monter un bloc device avec toutes les stratégies disponibles.
     * Android sans root ne peut pas monter directement, mais on essaie quand même.
     */
    private suspend fun tryMountBlockDevice(
        deviceId: String,
        device: UsbDevice,
        blockDevice: String
    ): DiskOperationResult {
        // Détecter le type de filesystem via superblock
        val detectedFs = detectFilesystem(blockDevice)
        Timber.d("Detected filesystem for $blockDevice: $detectedFs")

        // Vérifier si le kernel supporte ce FS
        val kernelFsList = getSupportedFilesystems()
        Timber.d("Kernel filesystems: $kernelFsList")

        // Construire la liste des points de montage à essayer
        val mountPoints = listOf(
            "/storage/${blockDevice.substringAfterLast('/')}",
            "/mnt/usb/${blockDevice.substringAfterLast('/')}",
            "/mnt/media_rw/${blockDevice.substringAfterLast('/')}"
        )

        // Construire la liste des commandes de montage à essayer
        val mountCommands = buildMountCommands(blockDevice, detectedFs, kernelFsList)

        for (mountPoint in mountPoints) {
            try { File(mountPoint).mkdirs() } catch (_: Exception) {}

            for (cmd in mountCommands) {
                val fullCmd = "$cmd \"$mountPoint\" 2>&1"
                Timber.d("Trying: $fullCmd")
                val result = executeShellCommand(fullCmd)

                if (result.isSuccess) {
                    // Vérifier que c'est vraiment monté
                    val mounts = findAllMountPoints()
                    val mountInfo = mounts.firstOrNull { it.mountPoint == mountPoint }
                        ?: mounts.firstOrNull { it.blockDevice.contains(blockDevice) }

                    if (mountInfo != null || File(mountPoint).list()?.isNotEmpty() == true) {
                        val actualMount = mountInfo?.mountPoint ?: mountPoint
                        updateDeviceMountState(deviceId, actualMount, true)
                        val (total, free) = getSpaceInfo(actualMount)
                        updateDeviceSpaceInfo(deviceId, total, free, detectedFs ?: "unknown")
                        createAppDirectoriesOnDevice(actualMount)
                        return DiskOperationResult.Success(
                            "✓ Monté sur $actualMount\n" +
                            "Système: ${detectedFs?.uppercase() ?: "Inconnu"}\n" +
                            "Total: ${formatBytes(total)}  •  Libre: ${formatBytes(free)}"
                        )
                    }
                }
            }
        }

        // Rien n'a marché → informer l'utilisateur clairement
        val fsInfo = if (detectedFs != null) "Filesystem détecté: ${detectedFs.uppercase()}" else ""
        val supported = if (detectedFs != null && kernelFsList.isNotEmpty()) {
            if (kernelFsList.any { it.contains(detectedFs, ignoreCase = true) })
                "✓ Supporté par le kernel"
            else
                "✗ PAS supporté par ce kernel Android\nFormater en FAT32/exFAT pour compatibilité maximale"
        } else ""

        return DiskOperationResult.Error(
            "Montage impossible sans root.\n\n" +
            "$fsInfo\n$supported\n\n" +
            "Le montage de périphériques USB non-FAT32/exFAT\n" +
            "nécessite les droits root sur Android.\n\n" +
            "Solution: formater la clé en FAT32 ou exFAT."
        )
    }

    /**
     * Construit la liste des commandes de montage selon le FS détecté
     * et ce que le kernel supporte.
     */
    private fun buildMountCommands(
        blockDevice: String,
        detectedFs: String?,
        kernelFs: List<String>
    ): List<String> {
        val cmds = mutableListOf<String>()
        val partitions = listOf("${blockDevice}1", "${blockDevice}p1", blockDevice)

        for (partition in partitions) {
            when (detectedFs?.lowercase()) {
                "ntfs" -> {
                    cmds.add("ntfs-3g -o rw,big_writes $partition")
                    cmds.add("mount -t ntfs-3g -o rw $partition")
                    cmds.add("mount -t ntfs $partition")
                    cmds.add("mount -t fuseblk -o rw,allow_other $partition")
                }
                "exfat" -> {
                    cmds.add("mount -t exfat -o rw,uid=0,gid=0 $partition")
                    cmds.add("mount -t texfat -o rw $partition")
                    cmds.add("mount -t fuse.exfat -o rw $partition")
                    cmds.add("mount -t sdcardfs -o rw $partition")
                }
                "ext4" -> {
                    cmds.add("mount -t ext4 -o rw,noatime $partition")
                    cmds.add("mount -t ext4 $partition")
                }
                "ext3" -> cmds.add("mount -t ext3 -o rw,noatime $partition")
                "ext2" -> cmds.add("mount -t ext2 -o rw,noatime $partition")
                "f2fs" -> cmds.add("mount -t f2fs -o rw $partition")
                "hfsplus" -> cmds.add("mount -t hfsplus -o rw $partition")
                else -> {
                    // Inconnu → essayer tous les types courants
                    cmds.add("mount -t vfat -o rw,uid=0,gid=0 $partition")
                    cmds.add("mount -t exfat -o rw,uid=0,gid=0 $partition")
                    cmds.add("mount -o rw $partition")
                }
            }
            // Toujours essayer vfat comme fallback (le plus compatible)
            if (detectedFs?.lowercase() != "vfat") {
                cmds.add("mount -t vfat -o rw,uid=0,gid=0,fmask=0000,dmask=0000 $partition")
            }
        }

        return cmds
    }

    /**
     * Détecte le type de filesystem en lisant le superblock du périphérique.
     * Utilise blkid si disponible, sinon lecture directe des octets magiques.
     */
    private suspend fun detectFilesystem(blockDevice: String): String? {
        // Essayer blkid d'abord (le plus précis)
        val blkidResult = executeShellCommand("blkid $blockDevice 2>/dev/null")
        if (blkidResult.isSuccess && blkidResult.output.isNotEmpty()) {
            val typeMatch = Regex("TYPE=\"([^\"]+)\"").find(blkidResult.output)
            if (typeMatch != null) {
                val fs = typeMatch.groupValues[1]
                Timber.d("blkid detected: $fs")
                return fs
            }
        }

        // Fallback: file command
        val fileResult = executeShellCommand("file -s $blockDevice 2>/dev/null")
        if (fileResult.isSuccess) {
            val out = fileResult.output.lowercase()
            return when {
                "ntfs" in out -> "ntfs"
                "exfat" in out || "extended fat" in out -> "exfat"
                "fat" in out || "fat32" in out -> "vfat"
                "ext4" in out -> "ext4"
                "ext3" in out -> "ext3"
                "ext2" in out -> "ext2"
                "f2fs" in out -> "f2fs"
                else -> null
            }
        }

        // Fallback: lire les magic bytes du superblock
        return detectFsViaMagicBytes(blockDevice)
    }

    /**
     * Lit les magic bytes du superblock pour identifier le filesystem.
     * Fonctionne même sans root sur beaucoup de devices.
     */
    private fun detectFsViaMagicBytes(blockDevice: String): String? {
        return try {
            val partitions = listOf("${blockDevice}1", "${blockDevice}p1", blockDevice)
            for (part in partitions) {
                val file = java.io.RandomAccessFile(part, "r")
                val magic = ByteArray(16)
                try {
                    // FAT32: bytes 3-10 = "FAT32   "
                    file.seek(3)
                    file.read(magic, 0, 8)
                    if (String(magic, 0, 5) == "FAT32") return "vfat"
                    if (String(magic, 0, 5) == "FAT16") return "vfat"

                    // exFAT: bytes 3-10 = "EXFAT   "
                    if (String(magic, 0, 5) == "EXFAT") return "exfat"

                    // NTFS: bytes 3-10 = "NTFS    "
                    if (String(magic, 0, 4) == "NTFS") return "ntfs"

                    // EXT2/3/4: magic at offset 0x438 = 0xEF53
                    file.seek(0x438)
                    val extMagic = ByteArray(2)
                    file.read(extMagic)
                    if (extMagic[0] == 0x53.toByte() && extMagic[1] == 0xEF.toByte()) {
                        // EXT2/3/4 — différencier via le journal et les features
                        file.seek(0x45C) // s_rev_level
                        val rev = ByteArray(4)
                        file.read(rev)
                        return "ext4" // Approximation (ext2/3/4 ont même magic)
                    }
                } finally {
                    file.close()
                }
            }
            null
        } catch (e: Exception) {
            Timber.v("Magic bytes detection failed: ${e.message}")
            null
        }
    }

    /**
     * Lit /proc/filesystems pour savoir ce que le kernel supporte.
     */
    private fun getSupportedFilesystems(): List<String> {
        return try {
            File("/proc/filesystems")
                .readLines()
                .map { it.trim().removePrefix("nodev").trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Timber.v("Cannot read /proc/filesystems: ${e.message}")
            emptyList()
        }
    }

    // ─── Space info ──────────────────────────────────────────────────────────

    /**
     * Lit la taille totale et libre via StatFs (uniquement sur le mount point, pas le bloc device).
     * Essaie aussi les partitions si le premier essai échoue.
     */
    private fun getSpaceInfo(mountPoint: String?): Pair<Long, Long> {
        if (mountPoint == null) return Pair(0L, 0L)
        return try {
            val stat = StatFs(mountPoint)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            if (total > 0) {
                Timber.d("StatFs($mountPoint): total=${formatBytes(total)}, free=${formatBytes(free)}")
                return Pair(total, free)
            }
            Pair(0L, 0L)
        } catch (e: Exception) {
            Timber.w("StatFs failed on $mountPoint: ${e.message}")
            Pair(0L, 0L)
        }
    }

    // ─── Filesystem naming ───────────────────────────────────────────────────

    private fun guessFsTypeFromPath(path: String): String {
        // On ne peut pas savoir avec certitude depuis le chemin seul
        return "vfat"
    }

    private fun normalizeFsType(raw: String): String = when (raw.lowercase()) {
        "vfat", "fat", "fat32", "msdos" -> "FAT32"
        "fuseblk", "exfat", "texfat", "sdfat" -> "exFAT"
        "ntfs", "ufsd" -> "NTFS"
        "ext4" -> "EXT4"
        "ext3" -> "EXT3"
        "ext2" -> "EXT2"
        "f2fs" -> "F2FS"
        "hfsplus" -> "HFS+"
        "iso9660" -> "ISO9660"
        "udf" -> "UDF"
        else -> raw.uppercase()
    }

    // ─── App directory setup ──────────────────────────────────────────────────

    /**
     * Crée les dossiers de l'app à la racine de la clé USB.
     * Visible dans l'explorateur de fichiers de l'utilisateur.
     *   {mountPoint}/UsbDiskManager/
     *   {mountPoint}/UsbDiskManager/Logs/
     *   {mountPoint}/UsbDiskManager/Backups/
     */
    private fun createAppDirectoriesOnDevice(mountPoint: String) {
        try {
            val appDir = File(mountPoint, "UsbDiskManager")
            if (appDir.mkdirs() || appDir.exists()) {
                File(appDir, "Logs").mkdirs()
                File(appDir, "Backups").mkdirs()
                Timber.d("App dirs created/verified at: ${appDir.absolutePath}")
            } else {
                Timber.w("Cannot create app dir at: ${appDir.absolutePath} (read-only?)")
            }
        } catch (e: Exception) {
            Timber.w("App dir creation failed: ${e.message}")
        }
    }

    // ─── State updates ───────────────────────────────────────────────────────

    private fun updateDeviceMountState(deviceId: String, mountPoint: String?, isMounted: Boolean) {
        val current = _connectedDevices.value.toMutableList()
        val index = current.indexOfFirst { it.id == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(
                mountPoint = mountPoint,
                isMounted = isMounted,
                isWritable = isMounted && mountPoint != null && File(mountPoint).canWrite()
            )
            _connectedDevices.value = current
        }
    }

    private fun updateDeviceSpaceInfo(deviceId: String, total: Long, free: Long, fsType: String) {
        val current = _connectedDevices.value.toMutableList()
        val index = current.indexOfFirst { it.id == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(
                totalSpace = total,
                freeSpace = free,
                usedSpace = total - free,
                fileSystem = FileSystemType.fromString(normalizeFsType(fsType))
            )
            _connectedDevices.value = current
        }
    }

    // ─── Format helpers ──────────────────────────────────────────────────────

    private fun buildFormatCommand(blockDevice: String, fileSystem: String, label: String): String {
        val labelFlag = if (label.isNotEmpty()) "-n \"$label\"" else ""
        return when (fileSystem.uppercase()) {
            "FAT32" -> "mkfs.vfat -F 32 $labelFlag $blockDevice"
            "EXFAT" -> "mkfs.exfat $labelFlag $blockDevice"
            "NTFS" -> "mkfs.ntfs --fast $labelFlag $blockDevice"
            "EXT4" -> "mkfs.ext4 -F $labelFlag $blockDevice"
            "EXT3" -> "mkfs.ext3 -F $labelFlag $blockDevice"
            else -> "mkfs.vfat -F 32 $blockDevice"
        }
    }

    // ─── Utility ─────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) { value /= 1024; unit++ }
        return "%.1f %s".format(value, units[unit])
    }
}
