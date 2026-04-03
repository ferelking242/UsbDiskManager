package com.usbdiskmanager.log

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val logFile: File by lazy {
        val dir = File(context.getExternalFilesDir(null), "logs")
        dir.mkdirs()
        File(dir, "usb_manager.log")
    }

    @Synchronized
    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val entry = "[$timestamp] $message"
        Timber.d(message)

        val current = _logs.value.toMutableList()
        current.add(0, entry)
        if (current.size > 500) current.removeAt(current.size - 1)
        _logs.value = current

        try {
            logFile.appendText("${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())} $message\n")
        } catch (_: Exception) {}
    }

    fun clear() {
        _logs.value = emptyList()
        try { logFile.writeText("") } catch (_: Exception) {}
    }

    fun getLogFilePath(): String = logFile.absolutePath
}
