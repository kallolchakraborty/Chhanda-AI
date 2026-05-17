package com.chhanda.ai.data.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class SessionTitleInfo(
    val sessionId: String,
    val sessionTitle: String?,
    val timestamp: Long
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesSync(): List<MessageEntity>

    @Query("SELECT * FROM chat_messages WHERE modelName = :modelName ORDER BY timestamp ASC")
    fun getAllMessagesForModel(modelName: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesForSession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT sessionId FROM chat_messages WHERE modelName = :modelName GROUP BY sessionId ORDER BY MAX(timestamp) DESC")
    fun getSessionIdsForModel(modelName: String): Flow<List<String>>

    @Query("SELECT sessionId FROM chat_messages WHERE deviceId = :deviceId GROUP BY sessionId ORDER BY MAX(timestamp) DESC")
    fun getSessionIdsForDevice(deviceId: String): Flow<List<String>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<MessageEntity>

    @Query("SELECT * FROM chat_messages WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesForDevice(deviceId: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM chat_messages WHERE deviceId = :deviceId AND modelName = :modelName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesForDeviceAndModel(deviceId: String, modelName: String, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessagesForSession(sessionId: String, limit: Int): List<MessageEntity>

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM chat_messages WHERE sessionId IN (:sessionIds)")
    suspend fun deleteSessions(sessionIds: List<String>)

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Query("DELETE FROM chat_messages WHERE deviceId = :deviceId")
    suspend fun clearHistoryForDevice(deviceId: String)

    @Query("DELETE FROM chat_messages")
    suspend fun clearHistory()

    @Query("DELETE FROM chat_messages WHERE id NOT IN (SELECT id FROM chat_messages ORDER BY timestamp DESC LIMIT :keepLimit)")
    suspend fun pruneMessages(keepLimit: Int)

    @Query("SELECT * FROM chat_messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: Long): MessageEntity?

    @Query("UPDATE chat_messages SET isLiked = :isLiked WHERE id = :messageId")
    suspend fun updateMessageFeedback(messageId: Long, isLiked: Boolean?)

    @Query("UPDATE chat_messages SET sessionTitle = :title WHERE sessionId = :sessionId")
    suspend fun updateSessionTitle(sessionId: String, title: String)

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND timestamp >= :timestamp")
    suspend fun deleteMessagesAfter(sessionId: String, timestamp: Long)

    @Query("SELECT sessionId, MAX(sessionTitle) as sessionTitle, MAX(timestamp) as timestamp FROM chat_messages WHERE modelName = :modelName GROUP BY sessionId ORDER BY timestamp DESC")
    fun getSessionsForModelWithTitle(modelName: String): Flow<List<SessionTitleInfo>>

    @Query("SELECT * FROM chat_messages WHERE isLiked = 1 ORDER BY timestamp DESC LIMIT 5")
    suspend fun getLikedMessagesGlobal(): List<MessageEntity>

    @Query("SELECT * FROM chat_messages WHERE isLiked = 0 ORDER BY timestamp DESC LIMIT 5")
    suspend fun getDislikedMessagesGlobal(): List<MessageEntity>
}
