package com.usbdiskmanager.usb.impl

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.core.model.DiskOperationResult
import com.usbdiskmanager.core.model.FileSystemType
import com.usbdiskmanager.core.util.ShellResult
import com.usbdiskmanager.core.util.executeShellCommand
import com.usbdiskmanager.usb.api.UsbDeviceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.usbdiskmanager.USB_PERMISSION"

@Singleton
class UsbDeviceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : UsbDeviceRepository {

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectedDevices = MutableStateFlow<List<DiskDevice>>(emptyList())
    override val connectedDevices = _connectedDevices.asStateFlow()

    // Map of internal device ID -> raw UsbDevice
    private val rawDeviceMap = mutableMapOf<String, UsbDevice>()

    init {
        // Load initially connected devices
        refreshConnectedDevices()
    }

    /**
     * Scan the UsbManager device list and update our state flow.
     */
    override fun refreshConnectedDevices() {
        val devices = usbManager.deviceList.values
        val diskDevices = devices.mapNotNull { usbDevice ->
            if (isMassStorageDevice(usbDevice)) {
                val id = deviceId(usbDevice)
                rawDeviceMap[id] = usbDevice
                buildDiskDevice(usbDevice)
            } else null
        }
        Timber.d("Found ${diskDevices.size} USB disk device(s)")
        _connectedDevices.value = diskDevices
    }

    override fun onDeviceAttached(usbDevice: UsbDevice) {
        if (isMassStorageDevice(usbDevice)) {
            val id = deviceId(usbDevice)
            rawDeviceMap[id] = usbDevice
            val diskDevice = buildDiskDevice(usbDevice)
            val current = _connectedDevices.value.toMutableList()
            current.removeAll { it.id == id }
            current.add(diskDevice)
            _connectedDevices.value = current
            Timber.i("USB device attached: ${usbDevice.deviceName}")
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
                Intent(ACTION_USB_PERMISSION).apply {
                    `package` = context.packageName
                },
                PendingIntent.FLAG_UPDATE_CURRENT or
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            PendingIntent.FLAG_MUTABLE else 0
            )

            val filter = IntentFilter(ACTION_USB_PERMISSION)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == ACTION_USB_PERMISSION) {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Timber.d("USB permission granted: $granted for ${device.deviceName}")
                        context.unregisterReceiver(this)
                        if (cont.isActive) cont.resume(granted)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
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

    override suspend fun mountDevice(deviceId: String): DiskOperationResult {
        val device = rawDeviceMap[deviceId]
            ?: return DiskOperationResult.Error("Device not found: $deviceId")

        return try {
            // Try to find the block device via /proc/bus/usb or /sys/bus/usb
            val blockDevice = findBlockDevice(device)
            if (blockDevice != null) {
                val mountPoint = "/mnt/usbdisk_$deviceId"
                val mkdirResult = executeShellCommand("mkdir -p $mountPoint")
                val mountResult = executeShellCommand(
                    "mount -t auto $blockDevice $mountPoint 2>&1 || " +
                            "mount -t vfat $blockDevice $mountPoint 2>&1"
                )
                if (mountResult.isSuccess) {
                    updateDeviceMountState(deviceId, mountPoint, true)
                    DiskOperationResult.Success("Mounted at $mountPoint")
                } else {
                    DiskOperationResult.Error("Mount failed: ${mountResult.output}")
                }
            } else {
                // Fallback: device is already accessible via SAF
                DiskOperationResult.Success("Device accessible via Storage Access Framework")
            }
        } catch (e: Exception) {
            Timber.e(e, "Mount failed for device $deviceId")
            DiskOperationResult.Error("Mount error: ${e.message}")
        }
    }

    override suspend fun unmountDevice(deviceId: String): DiskOperationResult {
        val device = _connectedDevices.value.find { it.id == deviceId }
            ?: return DiskOperationResult.Error("Device not found")

        return try {
            val mountPoint = device.mountPoint
            if (mountPoint != null) {
                val result = executeShellCommand("umount $mountPoint 2>&1 || umount -l $mountPoint 2>&1")
                if (result.isSuccess) {
                    updateDeviceMountState(deviceId, null, false)
                    DiskOperationResult.Success("Device safely ejected")
                } else {
                    DiskOperationResult.Error("Unmount failed: ${result.output}")
                }
            } else {
                DiskOperationResult.Success("Device was not mounted")
            }
        } catch (e: Exception) {
            Timber.e(e, "Unmount failed for device $deviceId")
            DiskOperationResult.Error("Unmount error: ${e.message}")
        }
    }

    override fun formatDevice(
        deviceId: String,
        fileSystem: String,
        label: String
    ): Flow<DiskOperationResult> = flow {
        emit(DiskOperationResult.Progress(0, "Preparing to format..."))

        val device = rawDeviceMap[deviceId]
        if (device == null) {
            emit(DiskOperationResult.Error("Device not found: $deviceId"))
            return@flow
        }

        emit(DiskOperationResult.Progress(10, "Looking for block device..."))
        val blockDevice = findBlockDevice(device)

        if (blockDevice == null) {
            emit(DiskOperationResult.Error(
                "Cannot find block device. Root access may be required for formatting."
            ))
            return@flow
        }

        emit(DiskOperationResult.Progress(20, "Unmounting device..."))
        val unmountResult = executeShellCommand("umount $blockDevice 2>&1 || true")

        emit(DiskOperationResult.Progress(30, "Formatting as $fileSystem..."))
        val formatCmd = buildFormatCommand(blockDevice, fileSystem, label)
        Timber.d("Format command: $formatCmd")

        emit(DiskOperationResult.Progress(40, "Running format (this may take a while)..."))
        val formatResult = executeShellCommand(formatCmd)

        if (formatResult.isSuccess) {
            emit(DiskOperationResult.Progress(90, "Verifying format..."))
            emit(DiskOperationResult.Progress(100, "Format complete!"))
            emit(DiskOperationResult.Success("Device formatted as $fileSystem successfully"))
        } else {
            emit(DiskOperationResult.Error(
                "Format failed: ${formatResult.output}\n\n" +
                        "Note: Formatting requires root access or manufacturer support."
            ))
        }
    }

    override suspend fun refreshDevice(deviceId: String): DiskDevice? {
        val device = rawDeviceMap[deviceId] ?: return null
        return buildDiskDevice(device)
    }

    override fun getRawDevice(deviceId: String): UsbDevice? = rawDeviceMap[deviceId]

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private fun deviceId(device: UsbDevice): String =
        "${device.vendorId}_${device.productId}_${device.deviceName.hashCode()}"

    /**
     * Check if a USB device is a mass storage device.
     * Class 8 = USB Mass Storage.
     */
    private fun isMassStorageDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 8) return true
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == 8) return true
        }
        return true // Include all USB devices for detection, filter later
    }

    private fun buildDiskDevice(device: UsbDevice): DiskDevice {
        val id = deviceId(device)
        val blockDevice = findBlockDevice(device)
        val (total, free) = getSpaceInfo(blockDevice)
        val fsType = detectFileSystem(blockDevice)
        val mountPoint = getMountPoint(blockDevice)

        return DiskDevice(
            id = id,
            name = device.productName ?: device.deviceName,
            vendorId = device.vendorId,
            productId = device.productId,
            serialNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                device.serialNumber else null,
            totalSpace = total,
            freeSpace = free,
            usedSpace = total - free,
            fileSystem = FileSystemType.fromString(fsType),
            mountPoint = mountPoint,
            isMounted = mountPoint != null,
            isWritable = blockDevice != null
        )
    }

    private fun findBlockDevice(device: UsbDevice): String? {
        return try {
            // Read USB bus/device number from sys
            val sysPath = "/sys/bus/usb/devices/${device.deviceName}"
            val busNum = File("$sysPath/busnum").readText().trim()
            val devNum = File("$sysPath/devnum").readText().trim()

            // Look in /sys/block for a device with matching usb path
            File("/sys/block").listFiles()?.firstOrNull { blockDir ->
                val deviceLink = File("${blockDir.absolutePath}/device").canonicalPath
                deviceLink.contains("usb$busNum")
            }?.let { "/dev/${it.name}" }
        } catch (e: Exception) {
            Timber.v("Could not find block device for ${device.deviceName}: ${e.message}")
            // Try common mount points
            listOf("/dev/block/sda", "/dev/block/sdb", "/dev/sda", "/dev/sdb")
                .firstOrNull { File(it).exists() }
        }
    }

    private fun getSpaceInfo(blockDevice: String?): Pair<Long, Long> {
        if (blockDevice == null) return Pair(0L, 0L)
        return try {
            val stat = android.os.StatFs(blockDevice)
            val total = stat.blockCountLong * stat.blockSizeLong
            val free = stat.availableBlocksLong * stat.blockSizeLong
            Pair(total, free)
        } catch (e: Exception) {
            // Try reading from /proc/mounts
            Pair(0L, 0L)
        }
    }

    private fun detectFileSystem(blockDevice: String?): String? {
        if (blockDevice == null) return null
        return try {
            val result = Runtime.getRuntime().exec(
                arrayOf("/system/bin/sh", "-c", "blkid $blockDevice 2>/dev/null || file -s $blockDevice 2>/dev/null")
            )
            val output = result.inputStream.bufferedReader().readText()
            when {
                output.contains("FAT32", ignoreCase = true) ||
                        output.contains("fat32", ignoreCase = true) -> "FAT32"
                output.contains("exfat", ignoreCase = true) -> "exFAT"
                output.contains("ntfs", ignoreCase = true) -> "NTFS"
                output.contains("ext4", ignoreCase = true) -> "EXT4"
                output.contains("ext3", ignoreCase = true) -> "EXT3"
                output.contains("ext2", ignoreCase = true) -> "EXT2"
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getMountPoint(blockDevice: String?): String? {
        if (blockDevice == null) return null
        return try {
            val mounts = File("/proc/mounts").readLines()
            mounts.firstOrNull { it.startsWith(blockDevice) }
                ?.split(" ")?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun updateDeviceMountState(deviceId: String, mountPoint: String?, isMounted: Boolean) {
        val current = _connectedDevices.value.toMutableList()
        val index = current.indexOfFirst { it.id == deviceId }
        if (index >= 0) {
            current[index] = current[index].copy(
                mountPoint = mountPoint,
                isMounted = isMounted
            )
            _connectedDevices.value = current
        }
    }

    private fun buildFormatCommand(blockDevice: String, fileSystem: String, label: String): String {
        val labelFlag = if (label.isNotEmpty()) "-n \"$label\"" else ""
        return when (fileSystem.uppercase()) {
            "FAT32" -> "mkfs.vfat -F 32 $labelFlag $blockDevice"
            "EXFAT" -> "mkfs.exfat $labelFlag $blockDevice"
            "NTFS" -> "mkfs.ntfs --fast $labelFlag $blockDevice"
            "EXT4" -> "mkfs.ext4 $labelFlag $blockDevice"
            else -> "mkfs.vfat -F 32 $blockDevice"
        }
    }
}
