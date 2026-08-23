package com.ripenai.data.repository

import android.content.Context
import com.ripenai.data.local.AppDatabase
import com.ripenai.data.local.FarmerContainerEntity
import com.ripenai.data.local.FarmerSensorReadingEntity
import com.ripenai.data.remote.Esp32Service
import com.ripenai.data.remote.SensorReadingResponse
import com.ripenai.data.remote.SensorStatusResponse
import com.ripenai.data.wifi.FarmerWifiConnector
import com.ripenai.domain.FarmerRiskEngine
import com.ripenai.domain.FarmerRiskPredictor
import com.ripenai.domain.FarmerRiskResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

data class FarmerSyncResult(
    val success: Boolean,
    val message: String,
    val risk: FarmerRiskResult? = null,
    val syncedReadings: Int = 0
)

class FarmerRepository(context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val dao = database.farmerDao()
    private val wifiConnector = FarmerWifiConnector(context)
    private val modelPredictor = FarmerRiskPredictor(context)
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val services = mutableMapOf<String, Esp32Service>()

    val containers: Flow<List<FarmerContainerEntity>> = dao.observeContainers()

    fun observeReadings(containerId: Long): Flow<List<FarmerSensorReadingEntity>> = dao.observeReadings(containerId)

    suspend fun getContainer(containerId: Long): FarmerContainerEntity? = dao.getContainer(containerId)

    suspend fun addContainer(name: String, fruitType: String, ipAddress: String, ssid: String): Long {
        return dao.insertContainer(
            FarmerContainerEntity(
                name = name.trim(),
                fruitType = fruitType.trim().ifBlank { "Buah" },
                ipAddress = ipAddress.trim(),
                ssid = ssid.trim()
            )
        )
    }

    suspend fun deleteContainer(containerId: Long) {
        dao.deleteReadings(containerId)
        dao.deleteContainer(containerId)
    }

    suspend fun syncContainer(containerId: Long, riskEngine: FarmerRiskEngine): FarmerSyncResult {
        val container = dao.getContainer(containerId) ?: return FarmerSyncResult(false, "Wadah tidak ditemukan.")
        return try {
            wifiConnector.withLocalNetwork(container.ssid, suspend {
                val service = serviceFor(container.ipAddress)
                val status = service.getStatus()
                val history = service.getSensorHistory(container.lastReadingTimestamp ?: 0L)
                val fallbackTimestamp = status.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
                val freshReadings = history.data.mapIndexedNotNull { index, reading ->
                    reading.toEntity(containerId, fallbackTimestamp + index)
                }.ifEmpty {
                    status.toReading(containerId, fallbackTimestamp)?.let(::listOf).orEmpty()
                }

                if (freshReadings.isNotEmpty()) dao.insertReadings(freshReadings)
                val allRecent = dao.getRecentReadings(containerId).asReversed()
                val ruleRisk = riskEngine.calculate(container.fruitType, allRecent)
                val risk = riskEngine.mergeModel(ruleRisk, modelPredictor.predict(container.fruitType, allRecent))
                val newest = freshReadings.maxByOrNull { it.timestamp }
                    ?: allRecent.maxByOrNull { it.timestamp }
                val latestTimestamp = newest?.timestamp ?: status.timestamp.takeIf { it > 0L } ?: container.lastReadingTimestamp
                dao.updateContainer(
                    container.copy(
                        lastSyncMillis = System.currentTimeMillis(),
                        lastReadingTimestamp = latestTimestamp,
                        latestTemperature = newest?.temperature ?: status.temperature ?: container.latestTemperature,
                        latestHumidity = newest?.humidity ?: status.humidity ?: container.latestHumidity,
                        latestGas = newest?.gas ?: status.gas ?: container.latestGas,
                        latestRiskScore = risk.score ?: status.riskScore ?: container.latestRiskScore,
                        latestModelScore = risk.modelScore,
                        latestModelConfidence = risk.modelConfidence,
                        latestHoursToAction = risk.modelHoursToAction,
                        latestAnalysisSource = risk.analysisSource,
                        latestStatus = risk.status,
                        latestRecommendation = risk.recommendation,
                        lastError = null
                    )
                )
                FarmerSyncResult(true, if (freshReadings.isEmpty()) "Data sudah terbaru." else "${freshReadings.size} pembacaan disimpan.", risk, freshReadings.size)
            })
        } catch (error: Exception) {
            dao.updateContainer(container.copy(lastError = friendlyError(error)))
            FarmerSyncResult(false, friendlyError(error))
        }
    }

    suspend fun seedDemoContainer(riskEngine: FarmerRiskEngine): Long {
        val existing = dao.observeContainers().first().firstOrNull { it.ipAddress == DEMO_IP }
        val containerId = existing?.id ?: addContainer("Wadah Demo", "Pisang", DEMO_IP, "RipenAI-Wadah-Demo")
        val now = System.currentTimeMillis()
        val readings = (0..8).map { index ->
            FarmerSensorReadingEntity(
                containerId = containerId,
                timestamp = now - (8 - index) * 3 * 60 * 60 * 1000L,
                temperature = 27f + index * 0.5f,
                humidity = 64f + index * 2.25f,
                gas = 20f + index * 67.5f
            )
        }
        dao.insertReadings(readings)
        val latest = readings.last()
        val ruleRisk = riskEngine.calculate("Pisang", readings)
        val risk = riskEngine.mergeModel(ruleRisk, modelPredictor.predict("Pisang", readings))
        val current = dao.getContainer(containerId) ?: return containerId
        dao.updateContainer(
            current.copy(
                lastSyncMillis = now,
                lastReadingTimestamp = latest.timestamp,
                latestTemperature = latest.temperature,
                latestHumidity = latest.humidity,
                latestGas = latest.gas,
                latestRiskScore = risk.score,
                latestModelScore = risk.modelScore,
                latestModelConfidence = risk.modelConfidence,
                latestHoursToAction = risk.modelHoursToAction,
                latestAnalysisSource = risk.analysisSource,
                latestStatus = risk.status,
                latestRecommendation = risk.recommendation,
                lastError = null
            )
        )
        return containerId
    }

    private fun serviceFor(ipAddress: String): Esp32Service {
        val normalized = ipAddress.trim()
        services[normalized]?.let { return it }
        val baseUrl = when {
            normalized.startsWith("http://") || normalized.startsWith("https://") -> if (normalized.endsWith('/')) normalized else "$normalized/"
            else -> "http://$normalized/"
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
        val service = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(Esp32Service::class.java)
        services[normalized] = service
        return service
    }

    private fun SensorReadingResponse.toEntity(containerId: Long, fallbackTimestamp: Long): FarmerSensorReadingEntity? {
        val timestamp = timestamp.takeIf { it > 0L } ?: fallbackTimestamp
        return FarmerSensorReadingEntity(containerId, timestamp, temperature, humidity, gas)
    }

    private fun SensorStatusResponse.toReading(containerId: Long, fallbackTimestamp: Long): FarmerSensorReadingEntity? {
        if (temperature == null || humidity == null || gas == null) return null
        return FarmerSensorReadingEntity(containerId, timestamp.takeIf { it > 0L } ?: fallbackTimestamp, temperature, humidity, gas, riskScore)
    }

    private fun friendlyError(error: Exception): String {
        return when (error) {
            is java.net.ConnectException, is java.net.SocketTimeoutException -> "Unit tidak terjangkau. Dekati wadah dan sambungkan WiFi unit."
            else -> "Sinkronisasi gagal: ${error.message?.take(100) ?: "respons unit tidak valid"}"
        }
    }

    fun close() {
        modelPredictor.close()
    }

    companion object {
        const val DEMO_IP = "192.168.4.1"
    }
}
