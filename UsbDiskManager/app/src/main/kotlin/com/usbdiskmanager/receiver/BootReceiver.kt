package com.usbdiskmanager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.usbdiskmanager.service.UsbMonitorService
import timber.log.Timber

/**
 * Redémarre le service de surveillance USB après le reboot du téléphone.
 * Déclaré dans AndroidManifest avec RECEIVE_BOOT_COMPLETED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                Timber.i("Boot/update detected → starting UsbMonitorService")
                try {
                    val serviceIntent = Intent(context, UsbMonitorService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start UsbMonitorService on boot")
                }
            }
        }
    }
}
