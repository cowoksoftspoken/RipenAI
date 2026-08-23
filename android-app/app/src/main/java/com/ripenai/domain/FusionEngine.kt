package com.ripenai.domain

import kotlin.math.abs

/**
 * Deterministic fusion of two observation sources. It is intentionally not a
 * probability model: [fusionScore] represents a position on the ripeness
 * scale, while [ClassificationResult.evidenceConsistency] represents how well
 * the visual and answered observations agree.
 */
class FusionEngine(private val config: ConsumerConfig) {
    fun fuse(
        cvResult: ClassificationResult,
        answers: Map<String, String>,
        questionSource: QuestionSource,
        questions: List<DynamicQuestion> = emptyList()
    ): ClassificationResult {
        if (cvResult.isAnalysisUnavailable) return cvResult

        val userReportedUnsafe = hasUnsafeAnswer(answers, questions)
        if (cvResult.rawStage == "rotten" || userReportedUnsafe) {
            return safetyResult(cvResult, questionSource, userReportedUnsafe)
        }

        val stageEvidence = answeredStageEvidence(answers, questions)
        if (cvResult.isUnsupportedVisual) {
            return fuseManualAssessment(cvResult, stageEvidence, questions.size, questionSource)
        }

        val visualScore = stageScore(cvResult.rawStage)
        val score = combineVisualAndEvidence(
            visualScore = visualScore,
            visualConfidence = cvResult.confidence / 100f,
            answeredEvidence = stageEvidence
        )
        val finalStage = stageFor(score)
        val consistency = evidenceConsistency(
            visualConfidence = cvResult.confidence / 100f,
            visualScore = visualScore,
            answeredEvidence = stageEvidence,
            questionCount = questions.size
        )
        val sourceText = if (questionSource == QuestionSource.REMOTE) "foto + pertanyaan AI" else "foto + pertanyaan lokal"

        return cvResult.copy(
            ripeness = finalStage.toDisplayRipeness(),
            rawStage = finalStage,
            daysEstimate = cvResult.daysFor(finalStage),
            recommendation = recommendationFor(finalStage),
            fusionScore = score,
            evidenceConsistency = consistency,
            isSafetyOverride = false,
            isCvOnly = false,
            analysisSource = sourceText,
            disclaimer = "Skor kematangan menggabungkan observasi foto dan jawaban. Nilai konsistensi bukan probabilitas keamanan atau akurasi terkalibrasi."
        )
    }

    private fun fuseManualAssessment(
        cvResult: ClassificationResult,
        stageEvidence: List<Float>,
        questionCount: Int,
        questionSource: QuestionSource
    ): ClassificationResult {
        val score = if (stageEvidence.isEmpty()) NEUTRAL_SCORE else stageEvidence.average().toFloat()
        val stage = stageFor(score)
        val consistency = evidenceConsistency(
            visualConfidence = null,
            visualScore = null,
            answeredEvidence = stageEvidence,
            questionCount = questionCount
        )

        return cvResult.copy(
            ripeness = stage.toDisplayRipeness(),
            rawStage = stage,
            daysEstimate = if (stage == "rotten") "Jangan dikonsumsi" else cvResult.daysFor(stage),
            recommendation = manualRecommendation(stage),
            fusionScore = score,
            evidenceConsistency = consistency,
            isSafetyOverride = false,
            isCvOnly = false,
            analysisSource = if (questionSource == QuestionSource.REMOTE) "pemeriksaan manual + pertanyaan AI" else "pemeriksaan manual",
            disclaimer = "Hasil ini berasal dari observasi pemeriksaan sederhana, bukan prediksi visual. Nilai konsistensi bukan probabilitas keamanan atau akurasi terkalibrasi."
        )
    }

    private fun safetyResult(
        cvResult: ClassificationResult,
        questionSource: QuestionSource,
        userReportedUnsafe: Boolean
    ): ClassificationResult {
        val sourceText = if (questionSource == QuestionSource.REMOTE) "foto + pertanyaan AI" else "foto + pertanyaan lokal"
        val reason = if (userReportedUnsafe) {
            "Peringatan keamanan dipicu oleh tanda jamur, lendir, kebocoran, atau bau busuk yang dilaporkan."
        } else {
            "Peringatan keamanan dipicu oleh detektor visual yang menemukan indikasi pembusukan."
        }
        return cvResult.copy(
            ripeness = "Busuk",
            rawStage = "rotten",
            daysEstimate = "Jangan dikonsumsi",
            recommendation = "Jangan dikonsumsi. Pisahkan dari buah lain dan buang dengan aman.",
            fusionScore = 1f,
            evidenceConsistency = null,
            isSafetyOverride = true,
            isCvOnly = false,
            analysisSource = sourceText,
            disclaimer = "$reason Status Busuk tidak dapat diturunkan oleh jawaban kematangan lain."
        )
    }

    private fun combineVisualAndEvidence(
        visualScore: Float,
        visualConfidence: Float,
        answeredEvidence: List<Float>
    ): Float {
        if (answeredEvidence.isEmpty()) return visualScore
        // Confidence only decides how strongly the visual stage is trusted. It
        // never changes the score direction (e.g. low confidence != unripe).
        val visualWeight = visualConfidence.coerceIn(MIN_VISUAL_WEIGHT, 1f)
        val evidenceWeight = answeredEvidence.size.coerceAtMost(MAX_EVIDENCE_WEIGHT.toInt()).toFloat()
        val evidenceMean = answeredEvidence.average().toFloat()
        return ((visualScore * visualWeight) + (evidenceMean * evidenceWeight)) /
            (visualWeight + evidenceWeight)
    }

    private fun evidenceConsistency(
        visualConfidence: Float?,
        visualScore: Float?,
        answeredEvidence: List<Float>,
        questionCount: Int
    ): Int {
        val coverage = (answeredEvidence.size.toFloat() / questionCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        val mean = answeredEvidence.takeIf { it.isNotEmpty() }?.average()?.toFloat()
        val answerAgreement = when {
            mean == null -> 0f
            answeredEvidence.size == 1 -> 0.65f
            else -> (1f - answeredEvidence.map { abs(it - mean) }.average().toFloat() / MAX_STAGE_DISTANCE).coerceIn(0f, 1f)
        }
        val crossSourceAgreement = if (visualScore != null && mean != null) {
            (1f - abs(visualScore - mean) / MAX_STAGE_DISTANCE).coerceIn(0f, 1f)
        } else if (mean != null) {
            1f
        } else {
            0f
        }

        val consistency = if (visualConfidence == null) {
            (coverage * 0.55f) + (answerAgreement * 0.45f)
        } else {
            (visualConfidence.coerceIn(0f, 1f) * 0.35f) +
                (coverage * 0.25f) +
                (answerAgreement * 0.25f) +
                (crossSourceAgreement * 0.15f)
        }
        return (consistency * 100f).toInt().coerceIn(0, if (visualConfidence == null) 88 else 95)
    }

    private fun answeredStageEvidence(
        answers: Map<String, String>,
        questions: List<DynamicQuestion>
    ): List<Float> = questions.mapNotNull { question ->
        answers[question.id]?.let(question::evidenceFor)?.toRipenessScore()
    }

    private fun hasUnsafeAnswer(
        answers: Map<String, String>,
        questions: List<DynamicQuestion>
    ): Boolean = answers.any { (questionId, answer) ->
        val evidence = questions.firstOrNull { it.id == questionId }?.evidenceFor(answer)
        evidence == AnswerEvidence.UNSAFE || (evidence == null && containsUnsafeText(answer))
    }

    private fun containsUnsafeText(answer: String): Boolean {
        val normalized = answer.lowercase().trim()
        if (normalized.startsWith("tidak ada") || normalized.startsWith("tanpa") || normalized.startsWith("belum ada")) return false
        return UNSAFE_TEXT.containsMatchIn(normalized)
    }

    private fun AnswerEvidence.toRipenessScore(): Float? = when (this) {
        AnswerEvidence.UNRIPE -> 0.12f
        AnswerEvidence.RIPE -> 0.72f
        AnswerEvidence.OVERRIPE -> 0.96f
        AnswerEvidence.NEUTRAL, AnswerEvidence.UNSAFE -> null
    }

    private fun stageScore(stage: String): Float = when (stage) {
        "unripe" -> 0.12f
        "nearly_ripe" -> 0.42f
        "ripe" -> 0.72f
        "overripe" -> 0.96f
        "rotten" -> 1f
        else -> NEUTRAL_SCORE
    }

    private fun stageFor(score: Float): String = when {
        score <= config.fusionThresholds.unripeMax -> "unripe"
        score <= config.fusionThresholds.nearlyRipeMax -> "nearly_ripe"
        score <= config.fusionThresholds.ripeMax -> "ripe"
        else -> "overripe"
    }

    private fun manualRecommendation(stage: String): String = when (stage) {
        "unripe" -> "Belum terlihat siap. Cek kembali aroma dan bunyinya setelah beberapa waktu."
        "nearly_ripe" -> "Mulai mendekati matang. Periksa lagi sebelum dikonsumsi."
        "overripe" -> "Sebaiknya segera dibuka dan dikonsumsi bila bagian dalam masih baik."
        "rotten" -> "Jangan dikonsumsi. Pisahkan dari buah lain dan buang dengan aman."
        else -> "Tanda pemeriksaan cukup mendukung. Tetap cek bagian dalam sebelum dikonsumsi."
    }

    private fun recommendationFor(stage: String): String = when (stage) {
        "unripe" -> "Sebaiknya tunggu beberapa hari pada suhu ruang. Pilih buah lain bila ingin langsung dikonsumsi."
        "nearly_ripe" -> "Hampir matang. Cocok dibawa pulang dan dikonsumsi dalam 1–3 hari."
        "ripe" -> "Kematangan optimal untuk dikonsumsi sekarang."
        "overripe" -> "Segera konsumsi atau olah menjadi jus/selai agar tidak terbuang."
        "rotten" -> "Jangan dikonsumsi. Pisahkan dari buah lain dan buang dengan aman."
        else -> "Periksa kembali buah dengan pencahayaan yang lebih baik."
    }

    private fun ClassificationResult.daysFor(stage: String): String = when (stage) {
        "unripe" -> daysEstimate.ifBlank { "Matang dalam beberapa hari" }
        "nearly_ripe" -> "Matang dalam 1–3 hari"
        "ripe" -> "Matang optimal sekarang"
        "overripe" -> "Lewat matang optimal"
        "rotten" -> "Jangan dikonsumsi"
        else -> daysEstimate
    }

    private companion object {
        const val NEUTRAL_SCORE = 0.50f
        const val MIN_VISUAL_WEIGHT = 0.15f
        const val MAX_EVIDENCE_WEIGHT = 2f
        const val MAX_STAGE_DISTANCE = 0.84f
        val UNSAFE_TEXT = Regex("\\b(jamur|bulu|berlendir|lendir|licin|bocor|kebocoran|bau\\s+busuk|bau\\s+menyengat|tidak\\s+layak|busuk)\\b")
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
