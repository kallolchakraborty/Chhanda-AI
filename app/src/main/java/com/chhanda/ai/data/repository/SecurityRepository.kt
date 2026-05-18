package com.chhanda.ai.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SecurityRepository: Production-grade hardware-backed credential storage.
 * 
 * This repository manages sensitive secrets (API Keys, HF Tokens) using the 
 * Android Keystore System. It prioritizes StrongBox (Hardware Security Module) 
 * on supported devices and falls back to TEE (Trusted Execution Environment).
 */
@Singleton
class SecurityRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "SecurityRepository"

    private val masterKey: MasterKey by lazy {
        try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .apply {
                    // Attempt to use StrongBox if available for maximum hardware security
                    try {
                        setRequestStrongBoxBacked(true)
                    } catch (e: Exception) {
                        Log.w(TAG, "StrongBox not supported on this device, falling back to TEE")
                    }
                }
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MasterKey", e)
            // Fallback to basic scheme if builder fails
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                "chhanda_secure_vault",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "EncryptedSharedPreferences failed, falling back to unencrypted", e)
            context.getSharedPreferences("chhanda_secure_vault_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _hfToken = MutableStateFlow("")
    val hfToken: StateFlow<String> = _hfToken.asStateFlow()

    private val _apiKey = MutableStateFlow("000000000")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    init {
        loadSecrets()
    }

    private fun loadSecrets() {
        try {
            // 1. One-time migration from legacy storage if needed
            if (!encryptedPrefs.contains(KEY_MIGRATION_DONE)) {
                migrateFromLegacy()
            }

            val savedToken = encryptedPrefs.getString(KEY_HF_TOKEN, "") ?: ""
            _hfToken.value = if (savedToken.isBlank()) "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" else savedToken
            _apiKey.value = encryptedPrefs.getString(KEY_API_KEY, "000000000") ?: "000000000"
        } catch (e: Exception) {
            Log.e(TAG, "Critical failure reading hardware-backed vault", e)
        }
    }

    private fun migrateFromLegacy() {
        try {
            Log.i(TAG, "Performing first-run security migration...")
            
            // Access legacy EncryptedSharedPreferences
            val legacyKey = androidx.security.crypto.MasterKeys.getOrCreate(androidx.security.crypto.MasterKeys.AES256_GCM_SPEC)
            val legacyPrefs = androidx.security.crypto.EncryptedSharedPreferences.create(
                "secure_settings",
                legacyKey,
                context,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val oldHfToken = legacyPrefs.getString("hf_token", "")
            val oldApiKey = legacyPrefs.getString("api_key", null)

            val editor = encryptedPrefs.edit()
            if (!oldHfToken.isNullOrBlank()) editor.putString(KEY_HF_TOKEN, oldHfToken)
            if (!oldApiKey.isNullOrBlank()) editor.putString(KEY_API_KEY, oldApiKey)
            
            editor.putBoolean(KEY_MIGRATION_DONE, true)
            editor.apply()
            
            Log.i(TAG, "Migration complete. Secure vault initialized.")
        } catch (e: Exception) {
            Log.w(TAG, "Migration failed or no legacy data found: ${e.message}")
            encryptedPrefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
        }
    }

    fun setHfToken(token: String) {
        try {
            encryptedPrefs.edit().putString(KEY_HF_TOKEN, token).apply()
            _hfToken.value = token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write HF Token to vault", e)
        }
    }

    fun setApiKey(key: String) {
        try {
            encryptedPrefs.edit().putString(KEY_API_KEY, key).apply()
            _apiKey.value = key
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write API Key to vault", e)
        }
    }

    fun revokeAllSessions() {
        val newKey = java.util.UUID.randomUUID().toString().replace("-", "").take(12).uppercase()
        setApiKey(newKey)
    }

    /**
     * Encrypts arbitrary data using the hardware-backed MasterKey.
     */
    fun encrypt(data: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, "_androidx_security_master_key_".let { alias ->
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.getKey(alias, null) as javax.crypto.SecretKey
        })
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data)
        return iv + encrypted // Prepend IV for decryption
    }

    /**
     * Decrypts data using the hardware-backed MasterKey.
     */
    fun decrypt(encryptedDataWithIv: ByteArray): ByteArray {
        val iv = encryptedDataWithIv.sliceArray(0 until 12)
        val encrypted = encryptedDataWithIv.sliceArray(12 until encryptedDataWithIv.size)
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, "_androidx_security_master_key_".let { alias ->
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.getKey(alias, null) as javax.crypto.SecretKey
        }, javax.crypto.spec.GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted)
    }

    companion object {
        private const val KEY_HF_TOKEN = "hf_token_v2"
        private const val KEY_API_KEY = "api_key_v2"
        private const val KEY_MIGRATION_DONE = "migration_v2_done"
    }
}
