package com.ripenai.domain

import android.content.Context
import com.ripenai.data.local.FarmerSensorReadingEntity
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

data class RiskBand(val minimum: Float, val maximum: Float, val score: Float)

data class FarmerThresholds(
    val gasRateBands: List<RiskBand>,
    val humidityBands: List<RiskBand>,
    val temperatureBands: List<RiskBand>
)

data class FarmerRiskResult(
    val score: Float?,
    val status: String,
    val recommendation: String,
    val reasons: List<String>,
    val gasRatePerHour: Float?,
    val humidityAverage: Float?,
    val temperatureAverage: Float?,
    val modelScore: Float? = null,
    val modelConfidence: Float? = null,
    val modelHoursToAction: Float? = null,
    val analysisSource: String = "Rule-based v1"
)

/**
 * Transparent v1 risk calculation for the farmer mode. It uses recent sensor
 * trends and a bundled configuration. The optional synthetic model is only an
 * assistive signal and cannot replace this rule-based safety path.
 */
class FarmerRiskEngine(private val thresholdsByFruit: Map<String, FarmerThresholds>) {
    fun calculate(fruitType: String, input: List<FarmerSensorReadingEntity>): FarmerRiskResult {
        val readings = input.sortedBy { it.timestamp }.takeLast(48)
        if (readings.isEmpty()) {
            return FarmerRiskResult(
                score = null,
                status = "Belum ada data",
                recommendation = "Sinkronkan unit untuk mulai menghitung risiko.",
                reasons = listOf("Belum ada pembacaan sensor."),
                gasRatePerHour = null,
                humidityAverage = null,
                temperatureAverage = null
            )
        }

        val config = thresholdsByFruit[fruitType.lowercase()] ?: thresholdsByFruit["default"] ?: error("Default farmer thresholds missing")
        val humidityAverage = readings.map { it.humidity }.average().toFloat()
        val temperatureAverage = readings.map { it.temperature }.average().toFloat()
        val gasRate = if (readings.size < 2) {
            null
        } else {
            val elapsedHours = elapsedHours(readings.first().timestamp, readings.last().timestamp)
            max(0f, (readings.last().gas - readings.first().gas) / elapsedHours)
        }

        val gasScore = gasRate?.let { scoreFor(it, config.gasRateBands) } ?: 0f
        val humidityScore = scoreFor(humidityAverage, config.humidityBands)
        val temperatureScore = scoreFor(temperatureAverage, config.temperatureBands)
        val score = (gasScore + humidityScore + temperatureScore).coerceIn(0f, 1f)
        val status = when {
            score < 0.4f -> "Aman"
            score < 0.7f -> "Perhatian"
            else -> "Segera ditangani"
        }

        val reasons = buildList {
            if (gasRate == null) add("Belum ada tren gas yang cukup; tunggu sinkronisasi berikutnya.")
            else if (gasRate >= 15f) add("Gas meningkat cepat (${format(gasRate)} / jam).")
            else if (gasRate >= 5f) add("Gas mulai meningkat (${format(gasRate)} / jam).")
            if (humidityAverage >= 80f) add("Kelembapan rata-rata tinggi (${format(humidityAverage)}%).")
            else if (humidityAverage >= 60f) add("Kelembapan berada di zona perhatian (${format(humidityAverage)}%).")
            if (temperatureAverage >= 30f) add("Suhu rata-rata tinggi (${format(temperatureAverage)} °C).")
            if (isEmpty()) add("Tren sensor masih stabil.")
        }

        val recommendation = when {
            score < 0.4f -> "Aman untuk disimpan. Periksa kembali besok."
            score < 0.7f -> "Perhatian: rencanakan untuk digunakan atau dijual dalam ±2 hari."
            else -> "Segera gunakan atau jual. Pisahkan dan periksa buah secara visual."
        }
        return FarmerRiskResult(
            score = score,
            status = status,
            recommendation = recommendation,
            reasons = reasons,
            gasRatePerHour = gasRate,
            humidityAverage = humidityAverage,
            temperatureAverage = temperatureAverage
        )
    }

    fun mergeModel(ruleResult: FarmerRiskResult, prediction: FarmerModelPrediction?): FarmerRiskResult {
        if (ruleResult.score == null || prediction == null || prediction.confidence < MODEL_CONFIDENCE_FLOOR) return ruleResult
        val modelScore = prediction.riskScore.coerceIn(0f, 1f)
        val combinedScore = (ruleResult.score * RULE_WEIGHT + modelScore * MODEL_WEIGHT).coerceIn(0f, 1f)
        return ruleResult.copy(
            score = combinedScore,
            status = statusFor(combinedScore),
            recommendation = recommendationFor(combinedScore),
            reasons = ruleResult.reasons + "Model bantu sintetis memperkirakan ${(modelScore * 100).toInt()}% risiko; perlu kalibrasi dengan log sensor nyata.",
            modelScore = modelScore,
            modelConfidence = prediction.confidence,
            modelHoursToAction = prediction.hoursToAction,
            analysisSource = "Rule-based v1 + model sintetis eksperimental"
        )
    }

    private fun scoreFor(value: Float, bands: List<RiskBand>): Float {
        val band = bands.firstOrNull { value >= it.minimum && value < it.maximum }
            ?: bands.lastOrNull { value >= it.minimum }
        return band?.score ?: 0f
    }

    private fun elapsedHours(first: Long, last: Long): Float {
        val difference = (last - first).coerceAtLeast(1L).toDouble()
        val milliseconds = if (maxOf(first, last) < 100_000_000_000L) difference * 1000.0 else difference
        return (milliseconds / 3_600_000.0).toFloat().coerceAtLeast(1f / 60f)
    }

    private fun format(value: Float): String = "%.1f".format(java.util.Locale.US, value)

    private fun statusFor(score: Float): String = when {
        score < 0.4f -> "Aman"
        score < 0.7f -> "Perhatian"
        else -> "Segera ditangani"
    }

    private fun recommendationFor(score: Float): String = when {
        score < 0.4f -> "Aman untuk disimpan. Periksa kembali besok."
        score < 0.7f -> "Perhatian: rencanakan untuk digunakan atau dijual dalam ±2 hari."
        else -> "Segera gunakan atau jual. Pisahkan dan periksa buah secara visual."
    }

    companion object {
        private const val RULE_WEIGHT = 0.75f
        private const val MODEL_WEIGHT = 0.25f
        private const val MODEL_CONFIDENCE_FLOOR = 0.65f

        fun fromAssets(context: Context): FarmerRiskEngine {
            val json = context.assets.open("farmer_config.json").bufferedReader().use { it.readText() }
            val root = JSONObject(json).optJSONObject("iot_recommendation_thresholds") ?: JSONObject()
            val all = mutableMapOf<String, FarmerThresholds>()
            root.keys().forEach { fruit ->
                root.optJSONObject(fruit)?.let { all[fruit.lowercase()] = parseThresholds(it) }
            }
            if (all["default"] == null) all["default"] = fallback()
            return FarmerRiskEngine(all)
        }

        private fun parseThresholds(json: JSONObject): FarmerThresholds {
            return FarmerThresholds(
                gasRateBands = parseBands(json.optJSONArray("gas_rate_bands")),
                humidityBands = parseBands(json.optJSONArray("humidity_bands")),
                temperatureBands = parseBands(json.optJSONArray("temperature_bands"))
            )
        }

        private fun parseBands(array: JSONArray?): List<RiskBand> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val band = array.optJSONArray(index) ?: continue
                    if (band.length() >= 3) add(RiskBand(band.optDouble(0).toFloat(), band.optDouble(1).toFloat(), band.optDouble(2).toFloat()))
                }
            }
        }

        private fun fallback() = FarmerThresholds(
            gasRateBands = listOf(RiskBand(0f, 5f, 0f), RiskBand(5f, 15f, 0.3f), RiskBand(15f, Float.MAX_VALUE, 0.6f)),
            humidityBands = listOf(RiskBand(0f, 60f, 0f), RiskBand(60f, 80f, 0.2f), RiskBand(80f, 100f, 0.4f)),
            temperatureBands = listOf(RiskBand(0f, 24f, 0f), RiskBand(24f, 30f, 0.05f), RiskBand(30f, Float.MAX_VALUE, 0.12f))
        )
    }
}
