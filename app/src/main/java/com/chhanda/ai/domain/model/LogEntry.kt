package com.chhanda.ai.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class LogEntry(
    val id: String, 
    val timestamp: String, 
    val tag: String, 
    val message: String, 
    val status: String
)
