package com.chhanda.ai.data.repository

import androidx.room.Entity
import androidx.room.PrimaryKey

import kotlinx.serialization.Serializable

@Serializable
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
    val attachmentPaths: String? = null, // Comma-separated paths of user attachments
    val source: String = "Local", // "API", "Web", or "Local"
    val contextSource: String? = null, // "Attachment", "Knowledge Base", or "Multi-Source"
    val thinking: String? = null, // Extracted reasoning content (e.g. from <think> tags)
    val isLiked: Boolean? = null,
    val sessionTitle: String? = null
)
