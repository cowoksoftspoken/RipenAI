package com.ripenai.data.local

import androidx.room.Entity

@Entity(tableName = "farmer_containers")
data class FarmerContainerEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fruitType: String,
    val ipAddress: String,
    val ssid: String = "",
    val lastSyncMillis: Long? = null,
    val lastReadingTimestamp: Long? = null,
    val latestTemperature: Float? = null,
    val latestHumidity: Float? = null,
    val latestGas: Float? = null,
    val latestRiskScore: Float? = null,
    val latestModelScore: Float? = null,
    val latestModelConfidence: Float? = null,
    val latestHoursToAction: Float? = null,
    val latestCalibrationSamples: Int = 0,
    val latestAnalysisSource: String = "Rule-based v1",
    val latestStatus: String = "Belum ada data",
    val latestRecommendation: String = "Sinkronkan unit untuk melihat rekomendasi.",
    val lastError: String? = null
)

@Entity(
    tableName = "farmer_sensor_readings",
    primaryKeys = ["containerId", "timestamp"]
)
data class FarmerSensorReadingEntity(
    val containerId: Long,
    val timestamp: Long,
    val temperature: Float,
    val humidity: Float,
    val gas: Float,
    val riskScore: Float? = null
)
