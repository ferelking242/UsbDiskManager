package com.usbdiskmanager.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.usbdiskmanager.UsbDiskManagerApp
import com.usbdiskmanager.prefs.AppPreferences
import com.usbdiskmanager.prefs.AppTheme
import com.usbdiskmanager.shizuku.ShizukuManager
import com.usbdiskmanager.shizuku.ShizukuState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    application: Application,
    private val prefs: AppPreferences,
    private val shizukuManager: ShizukuManager
) : AndroidViewModel(application) {

    val language: StateFlow<String> = prefs.language
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "auto")

    val theme: StateFlow<AppTheme> = prefs.theme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.SYSTEM)

    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ShizukuState.NotRunning)

    fun setLanguage(lang: String) {
        viewModelScope.launch {
            prefs.setLanguage(lang)
            applyLocale(lang)
        }
    }

    fun setTheme(appTheme: AppTheme) {
        viewModelScope.launch {
            prefs.setTheme(appTheme)
            (getApplication<Application>() as? UsbDiskManagerApp)?.applyNightMode(appTheme)
        }
    }

    fun requestShizukuPermission() {
        shizukuManager.requestPermission()
    }

    fun openShizukuApp() {
        try {
            val intent = getApplication<Application>().packageManager
                .getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            } else {
                openPlayStore()
            }
        } catch (e: Exception) {
            openPlayStore()
        }
    }

    fun openPlayStore() {
        try {
            val uri = Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            getApplication<Application>().startActivity(intent)
        } catch (_: Exception) {}
    }

    fun isShizukuInstalled(): Boolean {
        return try {
            getApplication<Application>().packageManager
                .getPackageInfo("moe.shizuku.privileged.api", 0) != null
        } catch (_: Exception) { false }
    }

    private fun applyLocale(lang: String) {
        val localeList = if (lang == "auto") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(lang)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }
}
