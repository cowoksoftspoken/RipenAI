package com.ripenai.domain

import android.content.Context
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Small, bounded on-device adaptation layer for Farmer ML V1.
 *
 * The TFLite weights remain frozen. Only explicit farmer labels update a
 * per-fruit bias, which prevents a few noisy interactions from destroying the
 * base model and keeps all learning local to the phone.
 */
enum class FarmerFeedbackLabel(
    val targetRisk: Float,
    val targetHours: Float,
    val classIndex: Int,
    val displayName: String
) {
    SAFE(0.20f, 72f, 0, "Aman"),
    ATTENTION(0.55f, 36f, 1, "Perhatian"),
    URGENT(0.85f, 8f, 2, "Urgent")
}

class FarmerOnlineCalibrator(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun apply(fruitType: String, prediction: FarmerModelPrediction): FarmerModelPrediction {
        val fruitKey = normalizeFruit(fruitType)
        val sampleCount = sampleCount(fruitType)
        if (sampleCount == 0) return prediction.copy(calibrationSamples = 0)

        val risk = (prediction.riskScore + riskBias(fruitKey)).coerceIn(0f, 1f)
        val probabilities = recalibrateProbabilities(prediction.probabilities, classBiases(fruitKey))
        val hours = prediction.hoursToAction?.let {
            (it + hoursBias(fruitKey)).coerceIn(0f, 72f)
        }
        return prediction.copy(
            riskScore = risk,
            probabilities = probabilities,
            confidence = probabilities.maxOrNull() ?: 0f,
            predictedStatus = statusFor(risk),
            hoursToAction = hours,
            calibrationSamples = sampleCount
        )
    }

    fun update(fruitType: String, prediction: FarmerModelPrediction, label: FarmerFeedbackLabel): Int {
        val fruitKey = normalizeFruit(fruitType)
        val nextCount = (sampleCount(fruitType) + 1).coerceAtMost(MAX_SAMPLES)
        val learningRate = (BASE_LEARNING_RATE / sqrt(nextCount.toFloat()))
            .coerceIn(MIN_LEARNING_RATE, BASE_LEARNING_RATE)

        val newRiskBias = (riskBias(fruitKey) + learningRate * (label.targetRisk - prediction.riskScore))
            .coerceIn(-MAX_RISK_BIAS, MAX_RISK_BIAS)
        val currentProbabilities = prediction.probabilities.map { it.coerceIn(0f, 1f) }
        val newClassBiases = classBiases(fruitKey).mapIndexed { index, bias ->
            val target = if (index == label.classIndex) 1f else 0f
            (bias + learningRate * (target - currentProbabilities.getOrElse(index) { 0f }))
                .coerceIn(-MAX_CLASS_BIAS, MAX_CLASS_BIAS)
        }
        val newHoursBias = prediction.hoursToAction?.let {
            (hoursBias(fruitKey) + learningRate * (label.targetHours - it))
                .coerceIn(-MAX_HOURS_BIAS, MAX_HOURS_BIAS)
        } ?: hoursBias(fruitKey)

        preferences.edit()
            .putInt(key(fruitKey, "samples"), nextCount)
            .putFloat(key(fruitKey, "risk_bias"), newRiskBias)
            .putFloat(key(fruitKey, "hours_bias"), newHoursBias)
            .putFloat(key(fruitKey, "class_bias_0"), newClassBiases[0])
            .putFloat(key(fruitKey, "class_bias_1"), newClassBiases[1])
            .putFloat(key(fruitKey, "class_bias_2"), newClassBiases[2])
            .apply()
        return nextCount
    }

    fun sampleCount(fruitType: String): Int = preferences.getInt(key(normalizeFruit(fruitType), "samples"), 0)

    fun reset(fruitType: String) {
        val fruitKey = normalizeFruit(fruitType)
        preferences.edit()
            .remove(key(fruitKey, "samples"))
            .remove(key(fruitKey, "risk_bias"))
            .remove(key(fruitKey, "hours_bias"))
            .remove(key(fruitKey, "class_bias_0"))
            .remove(key(fruitKey, "class_bias_1"))
            .remove(key(fruitKey, "class_bias_2"))
            .apply()
    }

    private fun riskBias(fruitKey: String): Float = preferences.getFloat(key(fruitKey, "risk_bias"), 0f)

    private fun hoursBias(fruitKey: String): Float = preferences.getFloat(key(fruitKey, "hours_bias"), 0f)

    private fun classBiases(fruitKey: String): List<Float> = listOf(
        preferences.getFloat(key(fruitKey, "class_bias_0"), 0f),
        preferences.getFloat(key(fruitKey, "class_bias_1"), 0f),
        preferences.getFloat(key(fruitKey, "class_bias_2"), 0f)
    )

    private fun recalibrateProbabilities(probabilities: List<Float>, biases: List<Float>): List<Float> {
        if (probabilities.size < 3) return probabilities
        val logits = (0 until 3).map { index -> ln(probabilities[index].coerceIn(0.0001f, 0.9999f)) + biases[index] }
        val maximum = logits.maxOrNull() ?: 0f
        val exponentials = logits.map { exp((it - maximum).toDouble()).toFloat() }
        val total = exponentials.sum().coerceAtLeast(0.0001f)
        return exponentials.map { it / total }
    }

    private fun key(fruitKey: String, suffix: String): String = "${fruitKey}_$suffix"

    private fun normalizeFruit(value: String): String = when (value.trim().lowercase()) {
        "apel", "apple" -> "apple"
        "pisang", "banana" -> "banana"
        "mangga", "mango" -> "mango"
        "jeruk", "orange" -> "orange"
        "pepaya", "papaya" -> "papaya"
        "nanas", "pineapple" -> "pineapple"
        "tomat", "tomato" -> "tomato"
        "alpukat", "avocado" -> "avocado"
        "durian" -> "durian"
        else -> value.trim().lowercase().ifBlank { "default" }
    }

    private fun statusFor(score: Float): String = when {
        score < 0.4f -> "Aman"
        score < 0.7f -> "Perhatian"
        else -> "Segera ditangani"
    }

    private companion object {
        const val PREFERENCES_NAME = "farmer_online_calibration_v1"
        const val BASE_LEARNING_RATE = 0.10f
        const val MIN_LEARNING_RATE = 0.01f
        const val MAX_SAMPLES = 500
        const val MAX_RISK_BIAS = 0.20f
        const val MAX_CLASS_BIAS = 0.75f
        const val MAX_HOURS_BIAS = 24f
    }
}
