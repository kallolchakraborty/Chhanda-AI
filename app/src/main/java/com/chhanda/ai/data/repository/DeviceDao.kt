package com.chhanda.ai.data.repository

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM connected_devices ORDER BY connectionTime DESC")
    fun getAllDevices(): Flow<List<DeviceEntity>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: DeviceEntity): Long

    @Update
    suspend fun updateDevice(device: DeviceEntity)

    @Query("DELETE FROM connected_devices")
    suspend fun clearDeviceHistory()

    @Query("SELECT * FROM connected_devices WHERE isCurrentlyConnected = 1")
    suspend fun getActiveConnections(): List<DeviceEntity>

    @Query("SELECT * FROM connected_devices WHERE ipAddress = :ip LIMIT 1")
    suspend fun getDeviceByIp(ip: String): DeviceEntity?

    @Query("UPDATE connected_devices SET isCurrentlyConnected = :status, lastActive = :time WHERE deviceName = :name")
    suspend fun updateDeviceStatus(name: String, status: Boolean, time: Long)
}
