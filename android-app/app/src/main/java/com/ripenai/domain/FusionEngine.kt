package com.ripenai.domain

import kotlin.math.absoluteValue

class FusionEngine(private val config: ConsumerConfig) {
    fun fuse(
        cvResult: ClassificationResult,
        answers: Map<String, String>,
        questionSource: QuestionSource,
        questions: List<DynamicQuestion> = emptyList()
    ): ClassificationResult {
        if (cvResult.isAnalysisUnavailable) return cvResult
        if (cvResult.isUnsupportedVisual) return fuseManualAssessment(cvResult, answers, questions, questionSource)

        val baseScore = when (cvResult.rawStage) {
            "unripe" -> 0.12f
            "nearly_ripe" -> 0.42f
            "ripe" -> 0.72f
            "overripe" -> 0.96f
            "rotten" -> 1.00f
            else -> 0.50f
        }
        val cvContribution = (cvResult.confidence / 100f - 0.5f) * 0.20f
        val weights = config.fusionWeights[cvResult.rawCommodity].orEmpty()
        val answerContribution = answers.entries.sumOf { (questionId, option) ->
            questions.firstOrNull { it.id == questionId }?.let { question ->
                question.scoreFor(option)?.toDouble()
                    ?: weights["${questionId}_${option.toSnakeCase()}"]?.toDouble()
                    ?: ordinalContribution(question, option).toDouble()
            }
                ?: weights["${questionId}_${option.toSnakeCase()}"]?.toDouble()
                ?: 0.0
        }.toFloat()
        val score = (baseScore + cvContribution + answerContribution).coerceIn(0f, 1f)
        val finalStage = when {
            cvResult.rawStage == "rotten" && score >= 0.82f -> "rotten"
            score <= config.fusionThresholds.unripeMax -> "unripe"
            score <= config.fusionThresholds.nearlyRipeMax -> "nearly_ripe"
            score <= config.fusionThresholds.ripeMax -> "ripe"
            else -> "overripe"
        }
        val confidence = ((cvResult.confidence / 100f * 0.65f + score * 0.35f) * 100f).toInt().coerceIn(0, 100)
        val sourceText = if (questionSource == QuestionSource.REMOTE) "foto + pertanyaan AI" else "foto + pertanyaan lokal"
        return cvResult.copy(
            ripeness = finalStage.toDisplayRipeness(),
            rawStage = finalStage,
            confidence = confidence,
            daysEstimate = cvResult.daysFor(finalStage),
            recommendation = recommendationFor(finalStage),
            fusionScore = score,
            isCvOnly = false,
            analysisSource = sourceText,
            disclaimer = if (questionSource == QuestionSource.LOCAL_FALLBACK) "Pertanyaan lokal digunakan agar analisis tetap berjalan tanpa layanan AI online." else null
        )
    }

    private fun fuseManualAssessment(
        cvResult: ClassificationResult,
        answers: Map<String, String>,
        questions: List<DynamicQuestion>,
        questionSource: QuestionSource
    ): ClassificationResult {
        val total = questions.sumOf { question ->
            val answer = answers[question.id]
            (question.scoreFor(answer.orEmpty()) ?: ordinalContribution(question, answer)).toDouble()
        }.toFloat()
        val rottenSignal = answers.values.any { answer ->
            answer.lowercase().contains(Regex("busuk|jamur|bulu|berlendir|licin|bocor|bau menyengat|tidak layak"))
        }
        val stage = when {
            rottenSignal -> "rotten"
            total <= -0.10f -> "unripe"
            total >= 0.12f -> "overripe"
            else -> "ripe"
        }
        val confidence = (60f + (total.absoluteValue * 45f)).toInt().coerceIn(55, 88)
        return cvResult.copy(
            ripeness = stage.toDisplayRipeness(),
            rawStage = stage,
            confidence = confidence,
            daysEstimate = if (stage == "rotten") "Jangan dikonsumsi" else cvResult.daysFor(stage),
            recommendation = manualRecommendation(stage),
            isCvOnly = false,
            analysisSource = if (questionSource == QuestionSource.REMOTE) "pemeriksaan manual + pertanyaan AI" else "pemeriksaan manual",
            disclaimer = "${cvResult.commodity} dinilai lewat jawaban pemeriksaan sederhana, bukan diprediksi dari foto."
        )
    }

    private fun ordinalContribution(question: DynamicQuestion, answer: String?): Float {
        if (answer.isNullOrBlank() || question.options.size < 2) return 0f
        val index = question.options.indexOf(answer)
        if (index < 0) return 0f
        val position = index.toFloat() / (question.options.lastIndex.coerceAtLeast(1).toFloat())
        return (position - 0.5f) * 0.18f
    }

    private fun manualRecommendation(stage: String): String = when (stage) {
        "unripe" -> "Belum terlihat siap. Cek kembali aroma dan bunyinya setelah beberapa waktu."
        "overripe" -> "Sebaiknya segera dibuka dan dikonsumsi bila bagian dalam masih baik."
        "rotten" -> "Jangan dikonsumsi. Pisahkan dari buah lain dan buang dengan aman."
        else -> "Tanda pemeriksaan cukup mendukung. Tetap cek bagian dalam sebelum dikonsumsi."
    }

    private fun recommendationFor(stage: String): String = when (stage) {
        "unripe" -> "Sebaiknya tunggu beberapa hari pada suhu ruang. Pilih buah lain bila ingin langsung dikonsumsi."
        "nearly_ripe" -> "Hampir matang. Cocok dibawa pulang dan dikonsumsi dalam 1\u20133 hari."
        "ripe" -> "Kematangan optimal untuk dikonsumsi sekarang."
        "overripe" -> "Segera konsumsi atau olah menjadi jus/selai agar tidak terbuang."
        "rotten" -> "Jangan dikonsumsi. Pisahkan dari buah lain dan buang dengan aman."
        else -> "Periksa kembali buah dengan pencahayaan yang lebih baik."
    }

    private fun String.toSnakeCase(): String = lowercase()
        .replace("\u2013", "-")
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    private fun ClassificationResult.daysFor(stage: String): String {
        return when (stage) {
            "unripe" -> daysEstimate.ifBlank { "Matang dalam beberapa hari" }
            "nearly_ripe" -> "Matang dalam 1\u20133 hari"
            "ripe" -> "Matang optimal sekarang"
            "overripe" -> "Lewat matang optimal"
            "rotten" -> "Jangan dikonsumsi"
            else -> daysEstimate
        }
    }
}

fun String.toDisplayRipeness(): String = when (this) {
    "unripe" -> "Mentah"
    "nearly_ripe" -> "Hampir Matang"
    "ripe" -> "Matang"
    "overripe" -> "Terlalu Matang"
    "rotten" -> "Busuk"
    else -> replace('_', ' ').replaceFirstChar { it.uppercase() }
}
