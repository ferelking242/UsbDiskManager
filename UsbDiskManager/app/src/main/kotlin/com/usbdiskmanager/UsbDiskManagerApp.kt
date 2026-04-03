package com.usbdiskmanager

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.usbdiskmanager.prefs.AppPreferences
import com.usbdiskmanager.prefs.AppTheme
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class UsbDiskManagerApp : Application() {

    @Inject
    lateinit var appPreferences: AppPreferences

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.i("UsbDiskManager started")
        restorePreferences()
    }

    private fun restorePreferences() {
        try {
            val lang = runBlocking { appPreferences.language.first() }
            val theme = runBlocking { appPreferences.theme.first() }
            applyLocale(lang)
            applyNightMode(theme)
        } catch (e: Exception) {
            Timber.w("Failed to restore preferences: ${e.message}")
        }
    }

    private fun applyLocale(lang: String) {
        val localeList = if (lang == "auto" || lang.isEmpty()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(lang)
        }
        AppCompatDelegate.setApplicationLocales(localeList)
    }

    fun applyNightMode(theme: AppTheme) {
        val mode = when (theme) {
            AppTheme.LIGHT   -> AppCompatDelegate.MODE_NIGHT_NO
            AppTheme.DARK    -> AppCompatDelegate.MODE_NIGHT_YES
            AppTheme.AMOLED  -> AppCompatDelegate.MODE_NIGHT_YES
            AppTheme.DYNAMIC -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            AppTheme.SYSTEM  -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
