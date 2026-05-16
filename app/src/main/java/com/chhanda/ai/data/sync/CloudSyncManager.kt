package com.chhanda.ai.data.sync

import com.chhanda.ai.data.repository.ChatDao
import com.chhanda.ai.data.repository.SecurityRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncManager @Inject constructor(
    private val chatDao: ChatDao,
    private val googleDriveRepository: GoogleDriveRepository,
    private val securityRepository: SecurityRepository
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun backupHistory(account: GoogleSignInAccount): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch all sessions and messages
            val sessions = chatDao.getSessionIdsForDevice("local").first()
            val allMessages = sessions.flatMap { chatDao.getMessagesForSession(it).first() }
            
            // 2. Serialize to JSON
            val rawData = json.encodeToString(allMessages)
            
            // 3. Encrypt data
            val encryptedData = securityRepository.encrypt(rawData.toByteArray(Charsets.UTF_8))
            
            // 4. Upload to Drive
            val fileId = googleDriveRepository.uploadFile(
                account = account,
                fileName = "chhanda_history_backup.enc",
                content = encryptedData,
                mimeType = "application/octet-stream"
            )
            
            fileId != null
        } catch (e: Exception) {
            false
        }
    }

    suspend fun restoreHistory(account: GoogleSignInAccount): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Download from Drive
            val encryptedData = googleDriveRepository.downloadFile(account, "chhanda_history_backup.enc") 
                ?: return@withContext false
            
            // 2. Decrypt data
            val decryptedBytes = securityRepository.decrypt(encryptedData)
            val rawData = String(decryptedBytes, Charsets.UTF_8)
            
            // 3. Deserialize
            val messages = json.decodeFromString<List<com.chhanda.ai.data.repository.MessageEntity>>(rawData)
            
            // 4. Import to DB (Avoid duplicates if possible)
            messages.forEach { msg ->
                // Basic deduplication could be based on timestamp and text
                chatDao.insertMessage(msg)
            }
            
            true
        } catch (e: Exception) {
            false
        }
    }
}
