package com.ripenai.domain

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.min

data class ClassificationResult(
    val commodity: String,
    val ripeness: String,
    val confidence: Int,
    val daysEstimate: String,
    val recommendation: String,
    val rawCommodity: String = "",
    val rawStage: String = "unknown",
    val top2Stage: String? = null,
    val top2Confidence: Int = 0,
    val fruitSupport: Int = 100,
    val isAmbiguous: Boolean = false,
    val requiresRetake: Boolean = false,
    val isUnsupportedVisual: Boolean = false,
    val isAnalysisUnavailable: Boolean = false,
    val isCvOnly: Boolean = false,
    val fusionScore: Float? = null,
    /** Agreement strength between visual and answered evidence; not a calibrated probability. */
    val evidenceConsistency: Int? = null,
    /** A direct user-reported spoilage sign must bypass ripeness recommendations. */
    val isSafetyOverride: Boolean = false,
    val analysisSource: String = "TFLite on-device",
    val disclaimer: String? = null,
    val temperature: Float? = null,
    val humidity: Float? = null,
    val gas: Float? = null,
    val gasLevel: String? = null
) {
    val displayConfidence: Int
        get() = evidenceConsistency ?: confidence
}

class TFLiteClassifier(private val context: Context) {
    private var interpreter: Interpreter? = null
    private var rottenInterpreter: Interpreter? = null
    private val labelsMap = mutableMapOf<Int, String>()
    private var ripenessDaysJson: JSONObject = JSONObject()

    init {
        loadModel()
        loadLabels()
        loadRipenessDays()
    }

    private fun loadModel() {
        try {
            val mappedModel: MappedByteBuffer = context.assets.openFd("ripenai.tflite").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                }
            }
        interpreter = Interpreter(mappedModel, Interpreter.Options().apply { setNumThreads(4) })
            Log.d(TAG, "TFLite model loaded")
        } catch (error: Exception) {
            Log.e(TAG, "TFLite model could not be loaded", error)
        }
        try {
            val mappedDetector: MappedByteBuffer = context.assets.openFd("rotten_detector.tflite").use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { stream ->
                    stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
                }
            }
            rottenInterpreter = Interpreter(mappedDetector, Interpreter.Options().apply { setNumThreads(4) })
            Log.d(TAG, "Rotten safety detector loaded")
        } catch (error: Exception) {
            Log.w(TAG, "Rotten safety detector is not available; continuing with primary model", error)
        }
    }

    private fun loadLabels() {
        try {
            val json = context.assets.open("class_labels.json").bufferedReader().use { JSONObject(it.readText()) }
            json.keys().forEach { key -> labelsMap[key.toInt()] = json.getString(key) }
        } catch (error: Exception) {
            Log.e(TAG, "Class labels could not be loaded", error)
            FALLBACK_LABELS.forEachIndexed { index, label -> labelsMap[index] = label }
        }
    }

    private fun loadRipenessDays() {
        try {
            ripenessDaysJson = context.assets.open("ripeness_days.json").bufferedReader().use { JSONObject(it.readText()) }
        } catch (error: Exception) {
            Log.e(TAG, "Ripeness day metadata could not be loaded", error)
        }
    }

    fun classify(
        bitmap: Bitmap,
        selectedCommodity: String?,
        ambiguityThreshold: Float,
        confidenceGapThreshold: Float,
        retakeConfidenceThreshold: Float
    ): ClassificationResult {
        val selected = selectedCommodity?.trim()?.lowercase()
        if (selected == "durian" || selected == "avocado") return unsupportedVisualResult(selected)

        val model = interpreter ?: return unavailableResult(selected)
        val candidateIndices = labelsMap.filterValues { label ->
            selected.isNullOrBlank() || label.substringBefore('_') == selected
        }.keys.sorted()
        if (candidateIndices.isEmpty()) return unavailableResult(selected)

        return try {
            val outputSize = model.getOutputTensor(0).shape().lastOrNull() ?: labelsMap.size
            val output = Array(1) { FloatArray(outputSize) }
            model.run(preprocessBitmap(bitmap), output)
            val probabilities = toProbabilities(output[0])
            val selectedSupport = candidateIndices.sumOf { probabilities.getOrElse(it) { 0f }.toDouble() }.toFloat()
            val conditionalDenominator = selectedSupport.coerceAtLeast(0.0001f)
            val ranked = candidateIndices.sortedByDescending { probabilities.getOrElse(it) { 0f } / conditionalDenominator }
            val bestIndex = ranked.first()
            val secondIndex = ranked.getOrNull(1)
            val bestProbability = probabilities.getOrElse(bestIndex) { 0f } / conditionalDenominator
            val secondProbability = secondIndex?.let { probabilities.getOrElse(it) { 0f } / conditionalDenominator } ?: 0f
            val bestLabel = labelsMap[bestIndex] ?: return unavailableResult(selected)
            val rawCommodity = selected ?: bestLabel.substringBefore('_')
            val rawStage = bestLabel.substringAfter('_', "unknown")
            val stageLabel = rawStage.toDisplayRipeness()
            val isAmbiguous = bestProbability < ambiguityThreshold ||
                (bestProbability - secondProbability) < confidenceGapThreshold || selectedSupport < 0.45f
            val confidence = (bestProbability * 100f).toInt().coerceIn(0, 100)

            val primaryResult = buildResult(
                rawCommodity = rawCommodity,
                rawStage = rawStage,
                stageLabel = stageLabel,
                confidence = confidence,
                top2Stage = secondIndex?.let { labelsMap[it]?.substringAfter('_', "unknown") },
                top2Confidence = (secondProbability * 100f).toInt().coerceIn(0, 100),
                fruitSupport = (selectedSupport * 100f).toInt().coerceIn(0, 100),
                isAmbiguous = isAmbiguous,
                requiresRetake = confidence / 100f < retakeConfidenceThreshold,
                source = "TFLite on-device"
            )
            val rottenProbability = runRottenProbability(bitmap)
            if (rottenProbability != null && rottenProbability >= ROTTEN_THRESHOLD) {
                primaryResult.copy(
                    ripeness = "Busuk",
                    rawStage = "rotten",
                    confidence = (rottenProbability * 100f).toInt().coerceIn(0, 100),
                    daysEstimate = "Jangan dikonsumsi",
                    recommendation = "Jangan dikonsumsi. Pisahkan dari buah lain dan buang dengan aman.",
                    analysisSource = "TFLite on-device + detector keamanan"
                )
            } else primaryResult
        } catch (error: Exception) {
            Log.e(TAG, "TFLite inference failed", error)
            unavailableResult(selected)
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val side = min(bitmap.width, bitmap.height).coerceAtLeast(1)
        val left = (bitmap.width - side) / 2
        val top = (bitmap.height - side) / 2
        val square = Bitmap.createBitmap(bitmap, left, top, side, side)
        val resized = Bitmap.createScaledBitmap(square, INPUT_SIZE, INPUT_SIZE, true)
        val buffer = ByteBuffer.allocateDirect(INPUT_SIZE * INPUT_SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        resized.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        pixels.forEach { pixel ->
            // The exported Keras model contains MobileNetV2 preprocessing and expects [0, 255].
            buffer.putFloat(((pixel shr 16) and 0xFF).toFloat())
            buffer.putFloat(((pixel shr 8) and 0xFF).toFloat())
            buffer.putFloat((pixel and 0xFF).toFloat())
        }
        buffer.rewind()
        return buffer
    }

    private fun toProbabilities(values: FloatArray): FloatArray {
        val sum = values.sum()
        if (values.all { it >= 0f } && sum in 0.98f..1.02f) return values
        val max = values.maxOrNull() ?: 0f
        val exponentials = values.map { exp((it - max).toDouble()).toFloat() }.toFloatArray()
        val exponentialSum = exponentials.sum().coerceAtLeast(0.0001f)
        return exponentials.map { it / exponentialSum }.toFloatArray()
    }

    private fun runRottenProbability(bitmap: Bitmap): Float? {
        val detector = rottenInterpreter ?: return null
        return try {
            val output = Array(1) { FloatArray(detector.getOutputTensor(0).shape().lastOrNull() ?: 2) }
            detector.run(preprocessBitmap(bitmap), output)
            toProbabilities(output[0]).getOrElse(1) { 0f }
        } catch (error: Exception) {
            Log.w(TAG, "Rotten detector inference failed; primary result retained", error)
            null
        }
    }

    private fun buildResult(
        rawCommodity: String,
        rawStage: String,
        stageLabel: String,
        confidence: Int,
        top2Stage: String?,
        top2Confidence: Int,
        fruitSupport: Int,
        isAmbiguous: Boolean,
        requiresRetake: Boolean,
        source: String
    ): ClassificationResult {
        val displayCommodity = when (rawCommodity) {
            "apple" -> "Apel"
            "banana" -> "Pisang"
            "mango" -> "Mangga"
            "orange" -> "Jeruk"
            "papaya" -> "Pepaya"
            "pineapple" -> "Nanas"
            "tomato" -> "Tomat"
            "avocado" -> "Alpukat"
            "durian" -> "Durian"
            else -> rawCommodity.replaceFirstChar { it.uppercase() }
        }
        val days = ripenessDaysJson.optJSONObject(rawCommodity)?.optInt(rawStage, 0) ?: 0
        return ClassificationResult(
            commodity = displayCommodity,
            ripeness = stageLabel,
            confidence = confidence,
            daysEstimate = daysEstimate(rawStage, days),
            recommendation = recommendation(rawStage),
            rawCommodity = rawCommodity,
            rawStage = rawStage,
            top2Stage = top2Stage,
            top2Confidence = top2Confidence,
            fruitSupport = fruitSupport,
            isAmbiguous = isAmbiguous,
            requiresRetake = requiresRetake,
            analysisSource = source
        )
    }

    private fun unsupportedVisualResult(fruit: String) = ClassificationResult(
        commodity = if (fruit == "avocado") "Alpukat" else "Durian",
        ripeness = "Tidak dapat dinilai dari foto",
        confidence = 0,
        daysEstimate = "Perlu pemeriksaan manual",
        recommendation = if (fruit == "avocado") "Cek warna kulit, kelenturan, dan tangkai. Foto saja tidak membawa sinyal yang cukup untuk menilai alpukat." else "Cek bunyi saat diketuk, aroma, dan kelenturan duri. Foto saja tidak membawa sinyal yang cukup untuk menilai durian.",
        rawCommodity = fruit,
        rawStage = "unsupported",
        isUnsupportedVisual = true,
        analysisSource = "Panduan non-visual",
        disclaimer = if (fruit == "avocado") "RipenAI memakai pertanyaan pemeriksaan untuk alpukat karena data visual alpukat belum tersedia di model." else "RipenAI tidak memaksakan prediksi visual untuk durian karena kematangannya perlu dinilai dengan bunyi, aroma, dan tekstur."
    )

    private fun unavailableResult(selected: String?) = ClassificationResult(
        commodity = selected?.replaceFirstChar { it.uppercase() } ?: "Buah",
        ripeness = "Analisis belum tersedia",
        confidence = 0,
        daysEstimate = "Ambil foto ulang",
        recommendation = "Model TFLite tidak dapat dijalankan. Pastikan aset model tersedia lalu coba lagi.",
        rawCommodity = selected.orEmpty(),
        rawStage = "unknown",
        isAnalysisUnavailable = true,
        requiresRetake = true,
        analysisSource = "Model tidak tersedia",
        disclaimer = "Tidak ada hasil yang dibuat-buat ketika model on-device gagal dimuat."
    )

    private fun daysEstimate(stage: String, days: Int): String = when (stage) {
        "ripe" -> "Matang optimal sekarang"
        "overripe" -> "Lewat matang optimal"
        "rotten" -> "Jangan dikonsumsi"
        else -> "Matang dalam \u00B1${days.coerceAtLeast(0)} hari"
    }

    private fun recommendation(stage: String): String = when (stage) {
        "unripe" -> "Simpan pada suhu ruang dan tunggu beberapa hari sebelum dikonsumsi."
        "nearly_ripe" -> "Hampir matang. Simpan pada suhu ruang dan cek kembali dalam 1\u20133 hari."
        "ripe" -> "Kematangan optimal. Baik untuk dikonsumsi sekarang."
        "overripe" -> "Segera konsumsi atau olah menjadi jus/selai agar tidak terbuang."
        "rotten" -> "Jangan dikonsumsi. Pisahkan dari buah lain dan buang dengan aman."
        else -> "Periksa kembali foto dengan pencahayaan yang lebih baik."
    }

    fun close() {
        interpreter?.close()
        interpreter = null
        rottenInterpreter?.close()
        rottenInterpreter = null
    }

    private companion object {
        const val TAG = "TFLiteClassifier"
        const val INPUT_SIZE = 224
        const val ROTTEN_THRESHOLD = 0.50f
        val FALLBACK_LABELS = listOf(
            "apple_overripe", "apple_ripe", "apple_rotten", "apple_unripe",
            "banana_overripe", "banana_ripe", "banana_rotten", "banana_unripe",
            "mango_overripe", "mango_ripe", "mango_rotten", "mango_unripe",
            "orange_overripe", "orange_ripe", "orange_rotten", "orange_unripe",
            "papaya_overripe", "papaya_ripe", "papaya_rotten", "papaya_unripe",
            "pineapple_overripe", "pineapple_ripe", "pineapple_rotten", "pineapple_unripe",
            "tomato_overripe", "tomato_ripe", "tomato_rotten", "tomato_unripe"
        )
    }
}
