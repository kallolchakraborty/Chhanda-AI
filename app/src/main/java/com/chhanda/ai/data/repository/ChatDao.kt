package com.chhanda.ai.data.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

    @Query("SELECT DISTINCT sessionId FROM chat_messages WHERE modelName = :modelName")
    fun getSessionIdsForModel(modelName: String): Flow<List<String>>

    @Query("SELECT DISTINCT sessionId FROM chat_messages WHERE deviceId = :deviceId")
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
}
