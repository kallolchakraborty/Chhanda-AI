package com.chhanda.ai.data.repository

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val role: String, // "user" or "model"
    val timestamp: Long = System.currentTimeMillis(),
    val deviceId: String = "local",
    val modelName: String = "unknown",
    val sessionId: String = "default_session",
    val tps: Double = 0.0,
    val isRagUsed: Boolean = false,
    val responseTimeMs: Long = 0,
    val generatedFilePath: String? = null,
    val source: String = "Local" // "API", "Web", or "Local"
)
