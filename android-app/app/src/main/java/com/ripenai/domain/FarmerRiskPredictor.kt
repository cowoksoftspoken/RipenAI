package com.ripenai.domain

import android.content.Context
import android.util.Log
import com.ripenai.data.local.FarmerSensorReadingEntity
import org.json.JSONArray
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * Optional on-device assistive model. It returns null for missing assets or
 * insufficient input so the transparent rule engine remains the fallback.
 */
class FarmerRiskPredictor(private val context: Context) : AutoCloseable {
    private var interpreter: Interpreter? = null
    private var featureMean = FloatArray(0)
    private var featureStd = FloatArray(0)
    private var windowSize = 32
    private var featureDim = 105
    private var fruitIds = emptyList<String>()

    init {
        loadMetadata()
        loadModel()
    }

    fun predict(fruitType: String, input: List<FarmerSensorReadingEntity>): FarmerModelPrediction? {
        val model = interpreter ?: return null
        val readings = input.sortedBy { it.timestamp }.takeLast(windowSize)
        if (readings.size < 2 || featureMean.size != featureDim || featureStd.size != featureDim) return null
        return try {
            val features = buildFeatures(fruitType, readings)
            val outputSize = model.getOutputTensor(0).shape().lastOrNull() ?: 4
            val output = Array(1) { FloatArray(outputSize) }
            model.run(features, output)
            if (output[0].size < 4) return null
            val probabilityStart = if (output[0].size >= 5) 2 else 1
            val probabilities = output[0].copyOfRange(probabilityStart, probabilityStart + 3).map { it.coerceIn(0f, 1f) }
            val risk = output[0][0].coerceIn(0f, 1f)
            val confidence = probabilities.maxOrNull() ?: 0f
            val hours = if (output[0].size >= 5) output[0][1].coerceIn(0f, 72f) else null
            FarmerModelPrediction(risk, probabilities, confidence, statusFor(risk), hours)
        } catch (error: Exception) {
            Log.w(TAG, "Farmer model inference failed; rule engine retained", error)
            null
        }
    }

    private fun buildFeatures(fruitType: String, readings: List<FarmerSensorReadingEntity>): Array<FloatArray> {
        val ordered = if (readings.size < windowSize) {
            List(windowSize - readings.size) { readings.first() } + readings
        } else readings.takeLast(windowSize)
        val raw = FloatArray(featureDim)
        ordered.forEachIndexed { index, reading ->
            val offset = index * 3
            raw[offset] = reading.temperature
            raw[offset + 1] = reading.humidity
            raw[offset + 2] = reading.gas
        }
        val fruitIndex = fruitIds.indexOf(normalizeFruit(fruitType))
        if (fruitIndex >= 0 && windowSize * 3 + fruitIndex < raw.size) raw[windowSize * 3 + fruitIndex] = 1f
        raw.indices.forEach { index ->
            raw[index] = (raw[index] - featureMean[index]) / max(featureStd[index], 1e-6f)
        }
        return arrayOf(raw)
    }

    private fun loadMetadata() {
        try {
            val json = context.assets.open("farmer_model_config.json").bufferedReader().use { JSONObject(it.readText()) }
            windowSize = json.optInt("window_size", windowSize)
            featureDim = json.optInt("feature_dim", featureDim)
            fruitIds = json.optJSONArray("fruit_ids").toStringList()
            val meanArray = json.optJSONArray("feature_mean")
            val stdArray = json.optJSONArray("feature_std")
            require(meanArray?.length() == featureDim) { "feature_mean must contain $featureDim values" }
            require(stdArray?.length() == featureDim) { "feature_std must contain $featureDim values" }
            featureMean = meanArray.toFloatArray(featureDim)
            featureStd = stdArray.toFloatArray(featureDim)
        } catch (error: Exception) {
            Log.w(TAG, "Farmer model metadata is not available", error)
        }
    }

    private fun loadModel() {
        try {
            val mappedModel: MappedByteBuffer = context.assets.openFd("farmer_risk.tflite").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            interpreter = Interpreter(mappedModel, Interpreter.Options().apply { setNumThreads(2) })
        } catch (error: Exception) {
            Log.w(TAG, "Farmer model is not available; using rules only", error)
        }
    }

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
        else -> value.trim().lowercase()
    }

    private fun statusFor(score: Float): String = when {
        score < 0.4f -> "Aman"
        score < 0.7f -> "Perhatian"
        else -> "Segera ditangani"
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }

    private fun JSONArray?.toFloatArray(size: Int): FloatArray {
        val result = FloatArray(size) { 0f }
        if (this == null) return result
        for (index in 0 until minOf(length(), size)) result[index] = optDouble(index, 0.0).toFloat()
        return result
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList { for (index in 0 until length()) add(optString(index).lowercase()) }
    }

    private companion object {
        const val TAG = "FarmerRiskPredictor"
    }
}
