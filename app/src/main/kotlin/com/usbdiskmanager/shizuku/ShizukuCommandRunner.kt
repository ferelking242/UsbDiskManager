package com.usbdiskmanager.shizuku

import com.usbdiskmanager.core.util.PrivilegedCommandRunner
import com.usbdiskmanager.core.util.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Privileged command runner backed by Shizuku.
 * Runs commands as ADB shell (uid=2000) or root (uid=0).
 * Falls back to normal shell when Shizuku is unavailable.
 * Unlocks: mount, umount, blkid, mkfs, fdisk, lsblk
 */
@Singleton
class ShizukuCommandRunner @Inject constructor(
    private val shizukuManager: ShizukuManager
) : PrivilegedCommandRunner {

    override val hasPrivilegedAccess: Boolean
        get() = shizukuManager.isReady

    override suspend fun run(command: String, forcePrivileged: Boolean): ShellResult =
        withContext(Dispatchers.IO) {
            if (shizukuManager.isReady) {
                runViaShizuku(command)
            } else if (!forcePrivileged) {
                runViaShell(command)
            } else {
                ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Shizuku not available. Start via: " +
                        "adb shell sh /storage/emulated/0/Android/data/" +
                        "moe.shizuku.privileged.api/start.sh"
                )
            }
        }

    private fun runViaShizuku(command: String): ShellResult {
        return try {
            Timber.d("[Shizuku] >>> %s", command)
            val process = Shizuku.newProcess(
                arrayOf("/system/bin/sh", "-c", command),
                null,
                null
            )
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                Timber.d("[Shizuku] exit=%d stderr=%s", exitCode, stderr.take(150))
            } else {
                Timber.d("[Shizuku] OK: %s", stdout.take(100))
            }
            ShellResult(exitCode, stdout, stderr)
        } catch (e: SecurityException) {
            Timber.e(e, "[Shizuku] SecurityException - permission revoked")
            shizukuManager.checkPermission()
            runViaShell(command)
        } catch (e: Exception) {
            Timber.w(e, "[Shizuku] Process failed, fallback to shell")
            runViaShell(command)
        }
    }

    private fun runViaShell(command: String): ShellResult {
        return try {
            Timber.d("[Shell] >>> %s", command)
            val process = Runtime.getRuntime().exec(arrayOf("/system/bin/sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            ShellResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Timber.e(e, "[Shell] Failed: %s", command)
            ShellResult(-1, "", e.message ?: "Unknown error")
        }
    }
}
