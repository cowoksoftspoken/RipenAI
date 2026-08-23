package com.ripenai.domain

enum class QuestionSource {
    REMOTE,
    LOCAL_FALLBACK,
    NONE
}

data class DynamicQuestion(
    val id: String,
    val text: String,
    val options: List<String>,
    /** Explicit contribution per option, supplied by the Worker scoring contract. */
    val optionScores: List<Float>? = null
) {
    fun scoreFor(option: String): Float? {
        if (optionScores?.size != options.size) return null
        val index = options.indexOf(option)
        return if (index >= 0) optionScores?.getOrNull(index) else null
    }
}

data class QuestionResponse(
    val fruitType: String,
    val ambiguousBetween: List<String>,
    val questions: List<DynamicQuestion>,
    val source: QuestionSource = QuestionSource.REMOTE
)

data class QuestionGenerationResult(
    val response: QuestionResponse? = null,
    val error: String? = null
)

data class FusionThresholds(
    val unripeMax: Float = 0.25f,
    val nearlyRipeMax: Float = 0.55f,
    val ripeMax: Float = 0.82f
)

data class ConsumerConfig(
    val ambiguityThreshold: Float = 0.70f,
    val confidenceGapThreshold: Float = 0.15f,
    val retakeConfidenceThreshold: Float = 0.20f,
    val questionTimeoutMs: Long = 12000L,
    val llmQuestionsUrl: String = "",
    val requireOnlineQuestions: Boolean = true,
    val requireQuestions: Boolean = true,
    val fusionThresholds: FusionThresholds = FusionThresholds(),
    val fusionWeights: Map<String, Map<String, Float>> = emptyMap(),
    val fallbackQuestions: Map<String, List<DynamicQuestion>> = emptyMap()
)
