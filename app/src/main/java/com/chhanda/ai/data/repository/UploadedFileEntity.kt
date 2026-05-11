package com.chhanda.ai.data.repository

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "uploaded_files")
data class UploadedFileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val format: String,
    val size: Long,
    val path: String,
    val isDeleted: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)
