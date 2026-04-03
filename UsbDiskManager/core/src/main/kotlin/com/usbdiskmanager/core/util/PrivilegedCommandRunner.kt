package com.usbdiskmanager.core.util

/**
 * Interface for running shell commands with elevated privileges.
 *
 * The default implementation uses the standard Android shell (limited).
 * When Shizuku is available, a privileged implementation runs commands
 * as ADB shell (uid=2000) or root (uid=0), unlocking mount/umount/blkid/mkfs.
 */
interface PrivilegedCommandRunner {
    /** True when a privileged backend (Shizuku / root) is active. */
    val hasPrivilegedAccess: Boolean

    /**
     * Execute a shell command and return the result.
     * Uses elevated privileges when available, falls back to normal shell.
     */
    suspend fun run(command: String, forcePrivileged: Boolean = false): ShellResult
}

/**
 * Default (non-privileged) implementation — runs commands via standard shell.
 * Used when Shizuku is unavailable.
 */
class DefaultCommandRunner : PrivilegedCommandRunner {
    override val hasPrivilegedAccess = false

    override suspend fun run(command: String, forcePrivileged: Boolean): ShellResult =
        executeShellCommand(command)
}
