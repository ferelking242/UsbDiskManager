package com.usbdiskmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.usbdiskmanager.MainActivity
import com.usbdiskmanager.R
import com.usbdiskmanager.usb.api.UsbDeviceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Persistent foreground service — survives background, never killed.
 *
 * Anti-kill strategy (same as Termux):
 *   1. PARTIAL_WAKE_LOCK  -- prevents CPU from sleeping during I/O
 *   2. START_STICKY        -- Android restarts it if killed by OS
 *   3. Foreground ongoing  -- Android does not kill foreground services
 *   4. BootReceiver        -- restarts after device reboot
 *   5. onTaskRemoved       -- reschedules when user swipes app away
 *   6. BroadcastReceiver   -- listens for USB attach/detach inside the service
 */
@AndroidEntryPoint
class UsbMonitorService : Service() {

    @Inject
    lateinit var usbRepository: UsbDeviceRepository

    companion object {
        const val CHANNEL_ID = "usb_monitor"
        const val NOTIFICATION_ID = 1000
        const val ACTION_STOP = "com.usbdiskmanager.action.STOP_MONITOR"
        const val ACTION_REFRESH = "com.usbdiskmanager.action.REFRESH"

        fun start(context: Context) {
            val intent = Intent(context, UsbMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, UsbMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var usbReceiver: BroadcastReceiver? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        registerUsbReceiver()
        Timber.i("UsbMonitorService created — persistent monitoring started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Timber.d("UsbMonitorService: stop requested")
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                Timber.d("UsbMonitorService: refresh requested")
                usbRepository.refreshConnectedDevices()
            }
            else -> {
                Timber.d("UsbMonitorService: starting foreground")
            }
        }

        val notification = buildNotification()
        startForegroundCompat(notification)
        usbRepository.refreshConnectedDevices()
        autoMountAllConnectedDevices()

        // START_STICKY: Android automatically restarts the service if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // When user swipes the app away -> reschedule the service (like Termux)
        Timber.d("UsbMonitorService: task removed, rescheduling")
        val restartIntent = Intent(applicationContext, UsbMonitorService::class.java)
        restartIntent.`package` = packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unregisterUsbReceiver()
        releaseWakeLock()
        serviceScope.cancel()
        Timber.i("UsbMonitorService destroyed")
        super.onDestroy()
    }

    // ─── Wake Lock ────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "UsbDiskManager::UsbMonitorWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 12 hour max
            }
            Timber.d("WakeLock acquired")
        } catch (e: Exception) {
            Timber.w("WakeLock acquisition failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            Timber.d("WakeLock released")
        } catch (e: Exception) {
            Timber.w("WakeLock release failed: ${e.message}")
        }
    }

    // ─── Auto-mount on startup ────────────────────────────────────────────────

    private fun autoMountAllConnectedDevices() {
        serviceScope.launch {
            delay(3000) // wait for Android to enumerate devices
            val devices = usbRepository.connectedDevices.value
            Timber.i("Auto-mounting ${devices.size} already-connected USB device(s)")
            for (device in devices) {
                if (!device.isMounted) {
                    Timber.i("Auto-mounting on startup: ${device.name} (${device.id})")
                    usbRepository.mountDevice(device.id)
                }
            }
            updateNotification()
        }
    }

    // ─── USB BroadcastReceiver ────────────────────────────────────────────────

    private fun registerUsbReceiver() {
        usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }

                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        Timber.i("USB attached (monitor): ${device?.deviceName}")
                        device?.let { d ->
                            usbRepository.onDeviceAttached(d)
                            serviceScope.launch {
                                delay(2500)
                                val id = "${d.vendorId}_${d.productId}_${d.deviceName.hashCode()}"
                                Timber.i("Auto-mounting USB: ${d.deviceName}")
                                usbRepository.mountDevice(id)
                                updateNotification()
                            }
                        }
                        updateNotification()
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Timber.i("USB detached (monitor): ${device?.deviceName}")
                        device?.let { usbRepository.onDeviceDetached(it) }
                        updateNotification()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbReceiver, filter)
        }
        Timber.d("USB BroadcastReceiver registered in UsbMonitorService")
    }

    private fun unregisterUsbReceiver() {
        try {
            usbReceiver?.let { unregisterReceiver(it) }
            usbReceiver = null
        } catch (_: Exception) {}
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val deviceCount = try { usbRepository.connectedDevices.value.size } catch (_: Exception) { 0 }
        val contentText = if (deviceCount > 0) {
            resources.getQuantityString(R.plurals.notif_devices_connected_plural, deviceCount, deviceCount)
        } else {
            getString(R.string.notif_waiting)
        }

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }
}
