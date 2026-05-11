package com.chhanda.ai.data.repository

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "connected_devices")
data class DeviceEntity(
    @PrimaryKey val deviceName: String,
    val ipAddress: String,
    val connectionTime: Long,
    val disconnectionTime: Long? = null,
    val durationMs: Long? = null,
    val isCurrentlyConnected: Boolean = false,
    val connectionType: String, // "LOCAL" or "SHARED"
    val userAgent: String = "Unknown",
    val lastActive: Long = System.currentTimeMillis()
)
