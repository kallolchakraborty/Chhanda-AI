package com.chhanda.ai.data.repository

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MessageEntity::class, DeviceEntity::class, VectorChunkEntity::class, UploadedFileEntity::class], version = 15, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun deviceDao(): DeviceDao
    abstract fun vectorChunkDao(): VectorChunkDao
    abstract fun uploadedFileDao(): UploadedFileDao
}
