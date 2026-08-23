package com.ripenai.data.remote

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
data class LedRequest(
    val ripeness: String
)
