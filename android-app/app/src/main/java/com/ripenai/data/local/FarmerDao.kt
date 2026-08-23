package com.ripenai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FarmerDao {
    @Query("SELECT * FROM farmer_containers ORDER BY name COLLATE NOCASE")
    fun observeContainers(): Flow<List<FarmerContainerEntity>>

    @Query("SELECT * FROM farmer_containers WHERE id = :containerId LIMIT 1")
    suspend fun getContainer(containerId: Long): FarmerContainerEntity?

    @Insert
    suspend fun insertContainer(container: FarmerContainerEntity): Long

    @Update
    suspend fun updateContainer(container: FarmerContainerEntity)

    @Query("DELETE FROM farmer_containers WHERE id = :containerId")
    suspend fun deleteContainer(containerId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadings(readings: List<FarmerSensorReadingEntity>)

    @Query("SELECT * FROM farmer_sensor_readings WHERE containerId = :containerId ORDER BY timestamp ASC LIMIT :limit")
    fun observeReadings(containerId: Long, limit: Int = 240): Flow<List<FarmerSensorReadingEntity>>

    @Query("SELECT * FROM farmer_sensor_readings WHERE containerId = :containerId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentReadings(containerId: Long, limit: Int = 240): List<FarmerSensorReadingEntity>

    @Query("DELETE FROM farmer_sensor_readings WHERE containerId = :containerId")
    suspend fun deleteReadings(containerId: Long)
}
