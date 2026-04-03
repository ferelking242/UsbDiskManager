package com.usbdiskmanager.shizuku

import android.content.pm.PackageManager
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton manager for Shizuku lifecycle and permission handling.
 *
 * Shizuku enables privileged shell access (ADB uid=2000 or root) without
 * requiring the user to be fully rooted. This unlocks:
 *   - Mounting NTFS / EXT4 / F2FS drives
 *   - Safe unmount (sync + umount)
 *   - Formatting (mkfs.*)
 *   - blkid / fdisk / lsblk without root
 *   - Accurate partition detection
 */
@Singleton
class ShizukuManager @Inject constructor() {

    private val _state = MutableStateFlow<ShizukuState>(ShizukuState.NotRunning)
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    val isReady: Boolean get() = _state.value.isReady

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Timber.i("Shizuku binder received — service is running")
        checkPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Timber.w("Shizuku binder died — service stopped")
        _state.value = ShizukuState.NotRunning
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == PERMISSION_REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    Timber.i("Shizuku permission GRANTED")
                    _state.value = ShizukuState.Ready
                } else {
                    Timber.w("Shizuku permission DENIED")
                    _state.value = ShizukuState.PermissionDenied
                }
            }
        }

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            Timber.d("ShizukuManager initialized — listeners registered")
        } catch (e: Exception) {
            Timber.w("Shizuku not available on this device: ${e.message}")
            _state.value = ShizukuState.NotInstalled
        }
    }

    /** Call once on app startup to establish current state. */
    fun initialize() {
        try {
            if (!Shizuku.pingBinder()) {
                Timber.d("Shizuku binder not available")
                _state.value = ShizukuState.NotRunning
                return
            }
            checkPermission()
        } catch (e: Exception) {
            Timber.w("Shizuku ping failed: ${e.message}")
            _state.value = ShizukuState.NotInstalled
        }
    }

    /** Request Shizuku permission from the user (shows Shizuku UI). */
    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                _state.value = ShizukuState.NotRunning
                return
            }
            if (Shizuku.isPreV11()) {
                Timber.w("Shizuku pre-v11 — cannot request permission programmatically")
                return
            }
            Timber.d("Requesting Shizuku permission…")
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            Timber.e(e, "Failed to request Shizuku permission")
        }
    }

    /** Check current permission state and update _state accordingly. */
    fun checkPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                _state.value = ShizukuState.NotRunning
                return
            }
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            _state.value = if (granted) {
                Timber.i("Shizuku permission already granted — Ready")
                ShizukuState.Ready
            } else {
                Timber.d("Shizuku running but no permission yet")
                ShizukuState.PermissionNotRequested
            }
        } catch (e: Exception) {
            Timber.w("Shizuku permission check failed: ${e.message}")
            _state.value = ShizukuState.NotInstalled
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {}
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}
