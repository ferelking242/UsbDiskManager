package com.usbdiskmanager.usb.api

import android.hardware.usb.UsbDevice
import com.usbdiskmanager.core.model.DiskDevice
import com.usbdiskmanager.core.model.DiskOperationResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository interface for USB device management.
 * Abstracts the low-level USB access behind a clean API.
 */
interface UsbDeviceRepository {

    /**
     * StateFlow of all currently connected USB disk devices.
     * Emits a new list whenever a device is connected or disconnected.
     */
    val connectedDevices: StateFlow<List<DiskDevice>>

    /**
     * Request USB permission for the given device.
     * Returns true if permission was granted.
     */
    suspend fun requestPermission(device: UsbDevice): Boolean

    /**
     * Check if we have permission for the given device.
     */
    fun hasPermission(device: UsbDevice): Boolean

    /**
     * Mount the device and make its file system accessible.
     */
    suspend fun mountDevice(deviceId: String): DiskOperationResult

    /**
     * Safely unmount (eject) the device.
     */
    suspend fun unmountDevice(deviceId: String): DiskOperationResult

    /**
     * Format the device with the specified file system.
     * Emits progress updates via the returned Flow.
     */
    fun formatDevice(
        deviceId: String,
        fileSystem: String,
        label: String = ""
    ): Flow<DiskOperationResult>

    /**
     * Refresh device information (space, file system, etc.)
     */
    suspend fun refreshDevice(deviceId: String): DiskDevice?

    /**
     * Get raw UsbDevice for a given internal device ID.
     */
    fun getRawDevice(deviceId: String): UsbDevice?

    /**
     * Re-scan the UsbManager device list and update the StateFlow.
     */
    fun refreshConnectedDevices()

    /**
     * Called when a USB device is physically attached.
     */
    fun onDeviceAttached(device: UsbDevice)

    /**
     * Called when a USB device is physically detached.
     */
    fun onDeviceDetached(device: UsbDevice)
}
