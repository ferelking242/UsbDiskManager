package com.usbdiskmanager.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

/**
 * Foreground service to keep disk I/O operations alive even when the app is backgrounded.
 * Used for large copy/move/format operations.
 */
@AndroidEntryPoint
class DiskOperationService : Service() {

    companion object {
        const val CHANNEL_ID = "disk_operations"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.usbdiskmanager.STOP_SERVICE"

        fun buildStartIntent(context: android.content.Context, operationName: String): Intent {
            return Intent(context, DiskOperationService::class.java).apply {
                putExtra("operation_name", operationName)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Timber.d("DiskOperationService: stopping")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val operationName = intent?.getStringExtra("operation_name") ?: "Disk Operation"
        Timber.d("DiskOperationService started: $operationName")

        val notification = buildNotification(operationName, 0)
        startForeground(NOTIFICATION_ID, notification)

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun updateProgress(operationName: String, progress: Int) {
        val notification = buildNotification(operationName, progress)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(operationName: String, progress: Int): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("USB Disk Manager")
            .setContentText("$operationName ($progress%)")
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Disk Operations",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows progress of disk copy, format, and other operations"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
