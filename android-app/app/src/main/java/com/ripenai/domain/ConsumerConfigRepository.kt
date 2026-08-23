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
            fallbackQuestions = fallbackMap
        )
    }

    private fun parseQuestions(array: JSONArray?): List<DynamicQuestion> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val options = item.optJSONArray("options") ?: return@mapNotNull null
            val parsedOptions = (0 until options.length()).map { options.optString(it).trim() }
            val evidence = item.optJSONArray("option_evidence")?.let { evidenceJson ->
                if (evidenceJson.length() != parsedOptions.size) return@let null
                (0 until evidenceJson.length()).map { evidenceIndex ->
                    runCatching {
                        AnswerEvidence.valueOf(evidenceJson.optString(evidenceIndex).trim().uppercase())
                    }.getOrNull()
                }.takeIf { values -> values.all { it != null } }?.filterNotNull()
            }
            val id = item.optString("id").trim()
            val text = item.optString("text").trim()
            if (id.isBlank() || text.isBlank() || parsedOptions.size !in 2..4 || parsedOptions.any { it.isBlank() }) null
            else DynamicQuestion(id, text, parsedOptions, evidence)
        }.take(3)
    }

    private fun defaultQuestions(): Map<String, List<DynamicQuestion>> = mapOf(
        "banana" to listOf(DynamicQuestion("tekstur", "Saat ditekan pelan, teksturnya?", listOf("Keras", "Agak lunak", "Lunak"), listOf(AnswerEvidence.UNRIPE, AnswerEvidence.RIPE, AnswerEvidence.OVERRIPE))),
        "mango" to listOf(DynamicQuestion("tekstur", "Saat ditekan pelan, teksturnya?", listOf("Keras", "Agak lunak", "Lunak"), listOf(AnswerEvidence.UNRIPE, AnswerEvidence.RIPE, AnswerEvidence.OVERRIPE))),
        "tomato" to listOf(DynamicQuestion("tekstur", "Saat ditekan pelan, teksturnya?", listOf("Keras", "Agak lunak", "Lunak"), listOf(AnswerEvidence.UNRIPE, AnswerEvidence.RIPE, AnswerEvidence.OVERRIPE)))
    )
}
