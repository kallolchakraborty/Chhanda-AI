package com.chhanda.ai.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    object PreferencesKeys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val PORT = stringPreferencesKey("server_port")
        val CONTEXT_LENGTH = stringPreferencesKey("context_length")
        val HF_TOKEN = stringPreferencesKey("hf_token")
        val API_KEY = stringPreferencesKey("api_key")
        val NOTIFICATIONS = booleanPreferencesKey("notifications")
        val MAX_DEVICES = intPreferencesKey("max_devices")
        val PUBLIC_URL = stringPreferencesKey("public_url")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
    }

    val darkModeFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DARK_MODE] ?: true
    }

    val serverPortFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PORT] ?: "8888"
    }

    val contextLengthFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.CONTEXT_LENGTH] ?: "2048"
    }

    val hfTokenFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.HF_TOKEN] ?: "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"
    }

    val maxDevicesFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MAX_DEVICES] ?: 5
    }

    val apiKeyFlow: Flow<String?> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.API_KEY]
    }

    val publicUrlFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PUBLIC_URL] ?: ""
    }

    val appLanguageFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_LANGUAGE] ?: "English"
    }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DARK_MODE] = enabled
        }
    }

    suspend fun setServerPort(port: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PORT] = port
        }
    }

    suspend fun setContextLength(length: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CONTEXT_LENGTH] = length
        }
    }

    suspend fun setHfToken(token: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HF_TOKEN] = token
        }
    }

    suspend fun setMaxDevices(max: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_DEVICES] = max
        }
    }

    suspend fun setApiKey(key: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.API_KEY] = key
        }
    }

    suspend fun setPublicUrl(url: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PUBLIC_URL] = url
        }
    }

    suspend fun setAppLanguage(language: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_LANGUAGE] = language
        }
    }
}
