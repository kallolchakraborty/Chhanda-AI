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

    private val masterKey = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC)
    private val encryptedPrefs = try {
        androidx.security.crypto.EncryptedSharedPreferences.create(
            "secure_settings",
            masterKey,
            context,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.e("SettingsRepository", "EncryptedSharedPreferences failed, falling back to standard", e)
        context.getSharedPreferences("secure_settings_fallback", Context.MODE_PRIVATE)
    }

    private val _hfTokenState = kotlinx.coroutines.flow.MutableStateFlow(encryptedPrefs.getString("hf_token", "") ?: "")
    private val _apiKeyState = kotlinx.coroutines.flow.MutableStateFlow(encryptedPrefs.getString("api_key", null) ?: "000000000")

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
        val VECTOR_DB_CAPACITY = intPreferencesKey("vector_db_capacity")
        val AUTO_DELETE_DAYS = intPreferencesKey("auto_delete_days")
        val AUTO_DELETE_ENABLED = booleanPreferencesKey("auto_delete_enabled")
        val TURBOQUANT_ENABLED = booleanPreferencesKey("turboquant_enabled")
        val SELECTED_VOICE = stringPreferencesKey("selected_voice")
        val RAG_ENABLED = booleanPreferencesKey("rag_enabled")
        val THINKING_MODE_ENABLED = booleanPreferencesKey("thinking_mode_enabled")
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

    val hfTokenFlow: Flow<String> = _hfTokenState

    val maxDevicesFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MAX_DEVICES] ?: 5
    }

    val apiKeyFlow: Flow<String?> = _apiKeyState

    val publicUrlFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PUBLIC_URL] ?: ""
    }

    val appLanguageFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_LANGUAGE] ?: "English"
    }

    val vectorDbCapacityFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.VECTOR_DB_CAPACITY] ?: 1
    }

    val autoDeleteDaysFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.AUTO_DELETE_DAYS] ?: 7
    }

    val autoDeleteEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.AUTO_DELETE_ENABLED] ?: true
    }

    val turboQuantEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.TURBOQUANT_ENABLED] ?: true
    }

    val selectedVoiceFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.SELECTED_VOICE] ?: "Kallol (Indian Male)"
    }

    val ragEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.RAG_ENABLED] ?: true
    }

    val thinkingModeEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.THINKING_MODE_ENABLED] ?: true
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
        encryptedPrefs.edit().putString("hf_token", token).apply()
        _hfTokenState.value = token
    }

    suspend fun setMaxDevices(max: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_DEVICES] = max
        }
    }

    suspend fun setApiKey(key: String) {
        encryptedPrefs.edit().putString("api_key", key).apply()
        _apiKeyState.value = key
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

    suspend fun setVectorDbCapacity(gb: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.VECTOR_DB_CAPACITY] = gb
        }
    }

    suspend fun setAutoDeleteDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_DELETE_DAYS] = days
        }
    }

    suspend fun setAutoDeleteEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUTO_DELETE_ENABLED] = enabled
        }
    }

    suspend fun setTurboQuantEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.TURBOQUANT_ENABLED] = enabled
        }
    }

    suspend fun setSelectedVoice(voice: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_VOICE] = voice
        }
    }

    suspend fun setRagEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.RAG_ENABLED] = enabled
        }
    }

    suspend fun setThinkingModeEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.THINKING_MODE_ENABLED] = enabled
        }
    }
}
