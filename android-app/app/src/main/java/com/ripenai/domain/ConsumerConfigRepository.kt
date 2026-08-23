package com.ripenai.domain

import android.content.Context
import com.ripenai.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

class ConsumerConfigRepository(private val context: Context) {
    fun load(): ConsumerConfig {
        return try {
            val config = context.assets.open("consumer_config.json").bufferedReader().use { JSONObject(it.readText()) }
            val questions = context.assets.open("consumer_questions.json").bufferedReader().use { JSONObject(it.readText()) }
            parse(config, questions)
        } catch (_: Exception) {
            ConsumerConfig(fallbackQuestions = defaultQuestions())
        }
    }

    private fun parse(config: JSONObject, questions: JSONObject): ConsumerConfig {
        val thresholdJson = config.optJSONObject("fusion_score_thresholds")
        val weightsJson = config.optJSONObject("fusion_weights")
        val weightMap = mutableMapOf<String, Map<String, Float>>()
        weightsJson?.keys()?.forEach { fruit ->
            val fruitWeights = weightsJson.optJSONObject(fruit) ?: JSONObject()
            val parsed = mutableMapOf<String, Float>()
            fruitWeights.keys().forEach { key -> parsed[key] = fruitWeights.optDouble(key, 0.0).toFloat() }
            weightMap[fruit] = parsed
        }

        val fallbackMap = mutableMapOf<String, List<DynamicQuestion>>()
        questions.keys().forEach { fruit ->
            fallbackMap[fruit] = parseQuestions(questions.optJSONArray(fruit))
        }

        return ConsumerConfig(
            ambiguityThreshold = config.optDouble("ambiguity_threshold", 0.70).toFloat(),
            confidenceGapThreshold = config.optDouble("confidence_gap_threshold", 0.15).toFloat(),
            retakeConfidenceThreshold = config.optDouble("retake_confidence_threshold", 0.20).toFloat(),
            questionTimeoutMs = config.optLong("question_timeout_ms", 12000L),
            llmQuestionsUrl = config.optString("llm_questions_url", "").trim().ifBlank { BuildConfig.QUESTION_API_URL.trim() },
            requireOnlineQuestions = config.optBoolean("require_online_questions", true),
            requireQuestions = config.optBoolean("require_questions", true),
            fusionThresholds = FusionThresholds(
                unripeMax = thresholdJson?.optDouble("unripe_max", 0.25)?.toFloat() ?: 0.25f,
                nearlyRipeMax = thresholdJson?.optDouble("nearly_ripe_max", 0.55)?.toFloat() ?: 0.55f,
                ripeMax = thresholdJson?.optDouble("ripe_max", 0.82)?.toFloat() ?: 0.82f
            ),
            fusionWeights = weightMap,
            fallbackQuestions = fallbackMap
        )
    }

    private fun parseQuestions(array: JSONArray?): List<DynamicQuestion> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val options = item.optJSONArray("options") ?: return@mapNotNull null
            val parsedOptions = (0 until options.length()).map { options.optString(it).trim() }.filter { it.isNotBlank() }
            val id = item.optString("id").trim()
            val text = item.optString("text").trim()
            if (id.isBlank() || text.isBlank() || parsedOptions.size !in 2..4) null
            else DynamicQuestion(id, text, parsedOptions)
        }.take(3)
    }

    private fun defaultQuestions(): Map<String, List<DynamicQuestion>> = mapOf(
        "banana" to listOf(DynamicQuestion("tekstur", "Saat ditekan pelan, teksturnya?", listOf("Keras", "Agak lunak", "Lunak"))),
        "mango" to listOf(DynamicQuestion("tekstur", "Saat ditekan pelan, teksturnya?", listOf("Keras", "Agak lunak", "Lunak"))),
        "tomato" to listOf(DynamicQuestion("tekstur", "Saat ditekan pelan, teksturnya?", listOf("Keras", "Agak lunak", "Lunak")))
    )
}
