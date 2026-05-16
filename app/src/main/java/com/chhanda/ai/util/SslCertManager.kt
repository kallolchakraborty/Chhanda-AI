package com.chhanda.ai.util

import android.content.Context
import io.ktor.network.tls.certificates.*
import java.io.File
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * SslCertManager: Handles on-device generation and management of self-signed 
 * SSL/TLS certificates for the Ktor server.
 */
@Singleton
class SslCertManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val KEYSTORE_FILE = "chhanda_server_keystore.p12"
    private val KEY_ALIAS = "chhanda-gateway"
    private val PASSWORD = "chhanda-secure-password" // In production, this could be generated and stored in Keystore

    /**
     * Retrieves the existing KeyStore or generates a new one if missing.
     */
    fun getOrCreateKeyStore(): KeyStore {
        val file = File(context.filesDir, KEYSTORE_FILE)
        
        return try {
            if (file.exists()) {
                KeyStore.getInstance("PKCS12").apply {
                    file.inputStream().use { load(it, PASSWORD.toCharArray()) }
                }
            } else {
                generateAndSaveCertificate(file)
            }
        } catch (e: Exception) {
            android.util.Log.e("SslCertManager", "Invalid KeyStore format, regenerating: ${e.message}")
            file.delete()
            generateAndSaveCertificate(file)
        }
    }

    private fun generateAndSaveCertificate(file: File): KeyStore {
        return generateCertificate(
            keyAlias = KEY_ALIAS,
            keyPassword = PASSWORD,
            jksPassword = PASSWORD,
            file = file
        )
    }

    /**
     * Returns the configuration details for Ktor SSL connector.
     */
    fun getSslConfig(): SslConfig {
        return SslConfig(
            keyStore = getOrCreateKeyStore(),
            keyAlias = KEY_ALIAS,
            keyPassword = { PASSWORD.toCharArray() },
            jksPassword = { PASSWORD.toCharArray() }
        )
    }

    data class SslConfig(
        val keyStore: KeyStore,
        val keyAlias: String,
        val keyPassword: () -> CharArray,
        val jksPassword: () -> CharArray
    )
}
