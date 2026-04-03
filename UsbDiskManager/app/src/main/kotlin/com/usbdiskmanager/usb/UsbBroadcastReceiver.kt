package com.usbdiskmanager.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.usbdiskmanager.usb.api.UsbDeviceRepository
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives USB attach/detach broadcasts and USB permission results.
 */
@AndroidEntryPoint
class UsbBroadcastReceiver : BroadcastReceiver() {

    @Inject
    lateinit var usbRepository: UsbDeviceRepository

    override fun onReceive(context: Context, intent: Intent) {
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        when (intent.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                device?.let {
                    Timber.i("USB device attached: ${it.deviceName} (${it.vendorId}:${it.productId})")
                    usbRepository.onDeviceAttached(it)
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                device?.let {
                    Timber.i("USB device detached: ${it.deviceName}")
                    usbRepository.onDeviceDetached(it)
                }
            }
            "com.usbdiskmanager.USB_PERMISSION" -> {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Timber.d("USB permission result: granted=$granted, device=${device?.deviceName}")
            }
        }
    }
}
