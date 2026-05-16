package com.chhanda.ai.data.repository

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadedFileDao {
    @Query("SELECT * FROM uploaded_files WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentFiles(limit: Int): Flow<List<UploadedFileEntity>>

    @Query("SELECT * FROM uploaded_files WHERE isDeleted = 0 ORDER BY timestamp DESC")
    fun getAllFiles(): Flow<List<UploadedFileEntity>>

    @Query("SELECT * FROM uploaded_files WHERE isDeleted = 0 AND timestamp < :threshold")
    suspend fun getFilesOlderThan(threshold: Long): List<UploadedFileEntity>

    @Query("SELECT * FROM uploaded_files WHERE name = :name AND size = :size AND isDeleted = 0 LIMIT 1")
    suspend fun findByNameAndSize(name: String, size: Long): UploadedFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: UploadedFileEntity)

    @Update
    suspend fun updateFile(file: UploadedFileEntity)

    @Query("UPDATE uploaded_files SET isDeleted = 1 WHERE id = :id")
    suspend fun markAsDeleted(id: String)

    @Query("UPDATE uploaded_files SET isDeleted = 1 WHERE id IN (:ids)")
    suspend fun markMultipleAsDeleted(ids: List<String>)

    @Delete
    suspend fun deleteFile(file: UploadedFileEntity)

    @Query("DELETE FROM uploaded_files")
    suspend fun deleteAll()

    @Query("SELECT * FROM uploaded_files WHERE id IN (:ids)")
    suspend fun getFilesByIds(ids: List<String>): List<UploadedFileEntity>

    @Query("UPDATE uploaded_files SET isEnabled = :enabled WHERE id = :id")
    suspend fun toggleEnablement(id: String, enabled: Boolean)

    @Query("SELECT name FROM uploaded_files WHERE isEnabled = 0")
    suspend fun getDisabledFileNames(): List<String>
}
