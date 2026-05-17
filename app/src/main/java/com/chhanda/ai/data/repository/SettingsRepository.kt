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

    
    init {
        // Initialization for standard settings if needed
    }

    object PreferencesKeys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val PORT = stringPreferencesKey("server_port")
        val CONTEXT_LENGTH = stringPreferencesKey("context_length")
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
        val PRIVACY_SHIELD_ENABLED = booleanPreferencesKey("privacy_shield_enabled")
        val ACTIVE_MODEL = stringPreferencesKey("active_model")
        val APP_SECURITY_ENABLED = booleanPreferencesKey("app_security_enabled")
        val HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val CLOUD_SYNC_FREQUENCY = stringPreferencesKey("cloud_sync_frequency")
    }

    val darkModeFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.DARK_MODE] ?: true
    }

    val cloudSyncFrequencyFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.CLOUD_SYNC_FREQUENCY] ?: "daily"
    }

    val serverPortFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PORT] ?: "8888"
    }

    fun getDefaultContextLength(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            if (activityManager != null) {
                activityManager.getMemoryInfo(memoryInfo)
                val totalMemoryGB = memoryInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
                when {
                    totalMemoryGB >= 12.0 -> "8192"
                    totalMemoryGB >= 8.0 -> "4096"
                    totalMemoryGB >= 6.0 -> "2048"
                    else -> "1024"
                }
            } else {
                "2048"
            }
        } catch (e: Exception) {
            "2048"
        }
    }

    fun getHardwareRecommendationDetails(): String {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            if (activityManager != null) {
                activityManager.getMemoryInfo(memoryInfo)
                val totalMemoryGB = memoryInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
                val totalGBFormatted = String.format(java.util.Locale.US, "%.1f GB", totalMemoryGB)
                val recommended = when {
                    totalMemoryGB >= 12.0 -> "8192 (High-End)"
                    totalMemoryGB >= 8.0 -> "4096 (Mid-High)"
                    totalMemoryGB >= 6.0 -> "2048 (Standard)"
                    else -> "1024 (Power Saving)"
                }
                "System RAM: $totalGBFormatted | Recommended: $recommended tokens"
            } else {
                "Recommended: 2048 tokens"
            }
        } catch (e: Exception) {
            "Recommended: 2048 tokens"
        }
    }

    val contextLengthFlow: Flow<String> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.CONTEXT_LENGTH] ?: getDefaultContextLength()
    }

    val maxDevicesFlow: Flow<Int> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.MAX_DEVICES] ?: 5
    }

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

    val privacyShieldEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.PRIVACY_SHIELD_ENABLED] ?: true
    }

    val activeModelFlow: Flow<String?> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.ACTIVE_MODEL]
    }

    val appSecurityEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.APP_SECURITY_ENABLED] ?: false
    }

    val hapticsEnabledFlow: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[PreferencesKeys.HAPTICS_ENABLED] ?: true
    }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DARK_MODE] = enabled
        }
    }

    suspend fun setHapticsEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAPTICS_ENABLED] = enabled
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

    suspend fun setMaxDevices(max: Int) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.MAX_DEVICES] = max
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

    suspend fun setPrivacyShieldEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.PRIVACY_SHIELD_ENABLED] = enabled
        }
    }

    suspend fun setActiveModel(modelName: String?) {
        dataStore.edit { preferences ->
            if (modelName == null) {
                preferences.remove(PreferencesKeys.ACTIVE_MODEL)
            } else {
                preferences[PreferencesKeys.ACTIVE_MODEL] = modelName
            }
        }
    }

    suspend fun setAppSecurityEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.APP_SECURITY_ENABLED] = enabled
        }
    }

    suspend fun setCloudSyncFrequency(frequency: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CLOUD_SYNC_FREQUENCY] = frequency
        }
    }
}
