package com.ripenai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_history")
data class ScanHistory(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val commodity: String,
    val ripeness: String,
    val confidence: Int,
    val daysEstimate: String,
    val recommendation: String,
    val temperature: Float? = null,
    val humidity: Float? = null,
    val gas: Float? = null,
    val gasLevel: String? = null,
    val dateMillis: Long = System.currentTimeMillis(),
    val imagePath: String? = null
)
