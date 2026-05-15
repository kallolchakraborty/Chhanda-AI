package com.chhanda.ai.util

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Singleton
import javax.inject.Inject

@Singleton
class EncryptionUtil @Inject constructor() {

    private val ALGORITHM = "AES/GCM/NoPadding"
    private val TAG_LENGTH_BIT = 128
    private val IV_LENGTH_BYTE = 12
    private val SALT_LENGTH_BYTE = 16
    private val ITERATIONS = 10000
    private val KEY_LENGTH_BIT = 256

    fun encrypt(data: String, password: String): String {
        val salt = ByteArray(SALT_LENGTH_BYTE)
        SecureRandom().nextBytes(salt)
        
        val iv = ByteArray(IV_LENGTH_BYTE)
        SecureRandom().nextBytes(iv)
        
        val secretKey = deriveKey(password, salt)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(TAG_LENGTH_BIT, iv))
        
        val ciphertext = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        
        // Format: SALT + IV + CIPHERTEXT
        val combined = salt + iv + ciphertext
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH_BIT)
        val key = factory.generateSecret(spec).encoded
        return SecretKeySpec(key, "AES")
    }
}
