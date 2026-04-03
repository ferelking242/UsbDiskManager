package com.usbdiskmanager.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_prefs")

enum class AppTheme { SYSTEM, LIGHT, DARK, AMOLED, DYNAMIC }

@Singleton
class AppPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        val KEY_LANGUAGE = stringPreferencesKey("language")
        val KEY_THEME = stringPreferencesKey("theme")
    }

    val language: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LANGUAGE] ?: "auto"
    }

    val theme: Flow<AppTheme> = context.dataStore.data.map { prefs ->
        when (prefs[KEY_THEME]) {
            "light"   -> AppTheme.LIGHT
            "dark"    -> AppTheme.DARK
            "amoled"  -> AppTheme.AMOLED
            "dynamic" -> AppTheme.DYNAMIC
            else      -> AppTheme.SYSTEM
        }
    }

    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { it[KEY_LANGUAGE] = lang }
    }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { it[KEY_THEME] = theme.name.lowercase() }
    }
}
