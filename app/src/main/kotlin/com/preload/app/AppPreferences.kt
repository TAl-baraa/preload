package com.preload.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemePreference {
    System,
    Light,
    Dark,
}

enum class LanguagePreference(val languageTag: String) {
    System(""),
    English("en"),
    Arabic("ar"),
}

private val Context.preloadPreferences by preferencesDataStore(name = "app_preferences")

class AppPreferences(context: Context) {
    private val dataStore = context.applicationContext.preloadPreferences

    val theme: Flow<ThemePreference> = dataStore.data.map { preferences ->
        preferences[THEME_KEY]
            ?.let { stored -> ThemePreference.entries.firstOrNull { it.name == stored } }
            ?: ThemePreference.System
    }

    suspend fun setTheme(theme: ThemePreference) {
        dataStore.edit { preferences -> preferences[THEME_KEY] = theme.name }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme")
    }
}
