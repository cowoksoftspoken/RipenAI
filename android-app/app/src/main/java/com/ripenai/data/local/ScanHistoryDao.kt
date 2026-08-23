package com.ripenai.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanHistoryDao {
    @Query("SELECT * FROM scan_history ORDER BY dateMillis DESC")
    fun getAllHistory(): Flow<List<ScanHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: ScanHistory): Long

    @Query("DELETE FROM scan_history WHERE id = :id")
    suspend fun deleteHistoryById(id: Int)

    @Query("DELETE FROM scan_history")
    suspend fun clearHistory()
}
