package com.ripenai.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SensorData(
    val temperature: Float = 24.5f,
    val humidity: Float = 68.0f,
    val gas: Float = 42.0f,
    val gasLevel: String? = "low"
) {
    fun getDisplayGasLevel(): String {
        if (!gasLevel.isNullOrBlank()) return gasLevel
        return when {
            gas < 100 -> "low"
            gas < 300 -> "medium"
            else -> "high"
        }
    }
}

@JsonClass(generateAdapter = true)
data class SensorStatusResponse(
    @Json(name = "wadah_id") val containerId: String? = null,
    @Json(name = "ts") val timestamp: Long = 0L,
    @Json(name = "temp") val temperature: Float? = null,
    @Json(name = "hum") val humidity: Float? = null,
    @Json(name = "gas_level") val gas: Float? = null,
    @Json(name = "risk_score") val riskScore: Float? = null,
    val recommendation: String? = null
)

@JsonClass(generateAdapter = true)
data class SensorReadingResponse(
    @Json(name = "ts") val timestamp: Long = 0L,
    @Json(name = "temp") val temperature: Float = 0f,
    @Json(name = "hum") val humidity: Float = 0f,
    @Json(name = "gas_level") val gas: Float = 0f
)

@JsonClass(generateAdapter = true)
data class SensorHistoryResponse(
    val data: List<SensorReadingResponse> = emptyList(),
    @Json(name = "last_ts") val lastTimestamp: Long = 0L
)

@JsonClass(generateAdapter = true)
data class LedRequest(
    val ripeness: String
)
