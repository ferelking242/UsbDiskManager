package com.usbdiskmanager

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbdiskmanager.prefs.AppPreferences
import com.usbdiskmanager.prefs.AppTheme
import com.usbdiskmanager.service.UsbMonitorService
import com.usbdiskmanager.ui.navigation.AppNavHost
import com.usbdiskmanager.ui.theme.UsbDiskManagerTheme
import com.usbdiskmanager.usb.api.UsbDeviceRepository
import com.usbdiskmanager.viewmodel.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var usbRepository: UsbDeviceRepository

    @Inject
    lateinit var appPreferences: AppPreferences

    private val dashboardViewModel: DashboardViewModel by viewModels()

    private val directoryPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            dashboardViewModel.onSafUriGranted(it)
            Timber.i("SAF URI granted: $it")
        }
    }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Timber.d("POST_NOTIFICATIONS granted: $granted")
        if (granted) startUsbMonitorService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enableImmersiveMode()

        requestStoragePermission()
        requestNotificationPermission()
        startUsbMonitorService()
        handleUsbIntent(intent)

        setContent {
            val theme by appPreferences.theme.collectAsStateWithLifecycle(initialValue = AppTheme.DARK)
            UsbDiskManagerTheme(appTheme = theme) {
                AppNavHost(
                    onRequestSafPermission = { directoryPickerLauncher.launch(null) }
                )
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        usbRepository.refreshConnectedDevices()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.navigationBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Timber.d("Requesting MANAGE_EXTERNAL_STORAGE")
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = "package:${packageName}".toUri()
                    startActivity(intent)
                } catch (e: Exception) {
                    Timber.w("Could not open all-files permission screen: ${e.message}")
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startUsbMonitorService() {
        try {
            UsbMonitorService.start(this)
            Timber.d("UsbMonitorService started from MainActivity")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start UsbMonitorService")
        }
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let {
                Timber.i("USB device attached via intent: ${it.deviceName}")
                usbRepository.onDeviceAttached(it)
                dashboardViewModel.onUsbDeviceAttached(it)
            }
        }
    }
}
