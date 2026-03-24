package com.usbdiskmanager

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.net.toUri
import com.usbdiskmanager.ui.theme.UsbDiskManagerTheme
import com.usbdiskmanager.ui.navigation.AppNavHost
import com.usbdiskmanager.usb.impl.UsbDeviceRepositoryImpl
import com.usbdiskmanager.viewmodel.DashboardViewModel
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var usbRepository: UsbDeviceRepositoryImpl

    private val dashboardViewModel: DashboardViewModel by viewModels()

    // SAF directory picker launcher
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request MANAGE_EXTERNAL_STORAGE on Android 11+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Timber.d("Requesting MANAGE_EXTERNAL_STORAGE")
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = "package:${packageName}".toUri()
                startActivity(intent)
            }
        }

        // Handle USB device attached via intent
        handleUsbIntent(intent)

        setContent {
            UsbDiskManagerTheme {
                AppNavHost(
                    onRequestSafPermission = { directoryPickerLauncher.launch(null) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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
