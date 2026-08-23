package com.ripenai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionEngineTest {
    private val engine = FusionEngine(
        ConsumerConfig(
            fusionWeights = mapOf(
                "banana" to mapOf("warna_kuning_penuh" to 0.12f)
            )
        )
    )

    private val preliminary = ClassificationResult(
        commodity = "Pisang",
        ripeness = "Hampir Matang",
        confidence = 60,
        daysEstimate = "Matang dalam 2 hari",
        recommendation = "Cek kembali.",
        rawCommodity = "banana",
        rawStage = "nearly_ripe",
        top2Stage = "ripe",
        top2Confidence = 40,
        isAmbiguous = true
    )

    @Test
    fun `answers contribute to final stage and preserve local source`() {
        val result = engine.fuse(
            cvResult = preliminary,
            answers = mapOf("warna" to "Kuning penuh"),
            questionSource = QuestionSource.LOCAL_FALLBACK
        )

        assertEquals("Matang", result.ripeness)
        assertTrue((result.fusionScore ?: 0f) > 0.5f)
        assertEquals("foto + pertanyaan lokal", result.analysisSource)
        assertTrue(result.disclaimer.orEmpty().contains("lokal"))
        assertTrue(!result.isCvOnly)
    }

    @Test
    fun `unsupported visual result uses manual answers`() {
        val durian = ClassificationResult(
            commodity = "Durian",
            ripeness = "Tidak dapat dinilai dari foto",
            confidence = 0,
            daysEstimate = "Perlu pemeriksaan manual",
            recommendation = "Periksa manual.",
            rawCommodity = "durian",
            rawStage = "unsupported",
            isUnsupportedVisual = true
        )

        val questions = listOf(
            DynamicQuestion("aroma", "Aromanya?", listOf("Belum tercium", "Harum kuat")),
            DynamicQuestion("bunyi", "Bunyinya?", listOf("Padat", "Bergaung"))
        )
        val result = engine.fuse(
            durian,
            mapOf("aroma" to "Harum kuat", "bunyi" to "Bergaung"),
            QuestionSource.REMOTE,
            questions
        )

        assertEquals("Terlalu Matang", result.ripeness)
        assertEquals("pemeriksaan manual + pertanyaan AI", result.analysisSource)
        assertTrue(result.disclaimer.orEmpty().contains("jawaban"))
        assertTrue(!result.isCvOnly)
    }

    @Test
    fun `rotten visual signal remains a safety result after questions`() {
        val rotten = preliminary.copy(
            ripeness = "Busuk",
            rawStage = "rotten",
            confidence = 96
        )

        val result = engine.fuse(
            cvResult = rotten,
            answers = mapOf("safety_surface" to "Ada jamur"),
            questionSource = QuestionSource.REMOTE,
            questions = listOf(DynamicQuestion("safety_surface", "Ada jamur?", listOf("Tidak", "Ada jamur")))
        )

        assertEquals("Busuk", result.ripeness)
        assertEquals("rotten", result.rawStage)
        assertTrue(result.recommendation.contains("Jangan dikonsumsi"))
    }

    @Test
    fun `remote option scores are used for dynamic question ids`() {
        val questions = listOf(
            DynamicQuestion(
                id = "q1",
                text = "Bagaimana warna pisang?",
                options = listOf("Hijau", "Kuning", "Bercak coklat"),
                optionScores = listOf(-0.25f, 0.0f, 0.25f)
            )
        )

        val unripe = engine.fuse(
            cvResult = preliminary,
            answers = mapOf("q1" to "Hijau"),
            questionSource = QuestionSource.REMOTE,
            questions = questions
        )
        val overripe = engine.fuse(
            cvResult = preliminary,
            answers = mapOf("q1" to "Bercak coklat"),
            questionSource = QuestionSource.REMOTE,
            questions = questions
        )

        assertEquals("Mentah", unripe.ripeness)
        assertEquals("Matang", overripe.ripeness)
        assertTrue((overripe.fusionScore ?: 0f) - (unripe.fusionScore ?: 0f) > 0.45f)
        assertEquals("foto + pertanyaan AI", overripe.analysisSource)
    }
}
