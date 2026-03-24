package com.usbdiskmanager.viewmodel

import androidx.lifecycle.ViewModel
import com.usbdiskmanager.log.LogManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class LogsViewModel @Inject constructor(
    private val logManager: LogManager
) : ViewModel() {

    val logs: StateFlow<List<String>> = logManager.logs

    fun clearLogs() {
        logManager.clear()
    }

    fun getLogFilePath(): String = logManager.getLogFilePath()
}
