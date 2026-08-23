package com.ripenai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FusionEngineTest {
    private val engine = FusionEngine(ConsumerConfig())

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
    fun `explicit ripe evidence changes the final stage`() {
        val result = engine.fuse(
            cvResult = preliminary,
            answers = mapOf("warna" to "Kuning penuh"),
            questionSource = QuestionSource.LOCAL_FALLBACK,
            questions = listOf(
                DynamicQuestion(
                    "warna",
                    "Warna kulit pisang paling mendekati?",
                    listOf("Hijau", "Kuning penuh"),
                    listOf(AnswerEvidence.UNRIPE, AnswerEvidence.RIPE)
                )
            )
        )

        assertEquals("Matang", result.ripeness)
        assertTrue((result.fusionScore ?: 0f) > 0.55f)
        assertEquals("foto + pertanyaan lokal", result.analysisSource)
        assertTrue(result.disclaimer.orEmpty().contains("bukan probabilitas"))
        assertFalse(result.isCvOnly)
    }

    @Test
    fun `unsupported visual result uses explicit manual evidence`() {
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
            DynamicQuestion("aroma", "Aromanya?", listOf("Belum tercium", "Harum kuat"), listOf(AnswerEvidence.UNRIPE, AnswerEvidence.RIPE)),
            DynamicQuestion("bunyi", "Bunyinya?", listOf("Padat", "Sangat matang"), listOf(AnswerEvidence.UNRIPE, AnswerEvidence.OVERRIPE))
        )

        val result = engine.fuse(
            durian,
            mapOf("aroma" to "Harum kuat", "bunyi" to "Sangat matang"),
            QuestionSource.REMOTE,
            questions
        )

        assertEquals("Terlalu Matang", result.ripeness)
        assertEquals("pemeriksaan manual + pertanyaan AI", result.analysisSource)
        assertNotNull(result.evidenceConsistency)
        assertFalse(result.isCvOnly)
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
            answers = mapOf("safety_surface" to "Tidak ada"),
            questionSource = QuestionSource.REMOTE,
            questions = listOf(DynamicQuestion("safety_surface", "Ada jamur?", listOf("Tidak ada", "Ada jamur"), listOf(AnswerEvidence.NEUTRAL, AnswerEvidence.UNSAFE)))
        )

        assertEquals("Busuk", result.ripeness)
        assertEquals("rotten", result.rawStage)
        assertTrue(result.isSafetyOverride)
        assertTrue(result.recommendation.contains("Jangan dikonsumsi"))
    }

    @Test
    fun `top two rotten plus reported mold is always unsafe`() {
        val nonRottenPrimary = preliminary.copy(
            ripeness = "Matang",
            rawStage = "ripe",
            top2Stage = "rotten",
            top2Confidence = 37
        )
        val result = engine.fuse(
            cvResult = nonRottenPrimary,
            answers = mapOf("safety_surface" to "Ada banyak jamur"),
            questionSource = QuestionSource.REMOTE,
            questions = listOf(
                DynamicQuestion(
                    "safety_surface",
                    "Apakah ada jamur?",
                    listOf("Tidak ada", "Ada banyak jamur"),
                    listOf(AnswerEvidence.NEUTRAL, AnswerEvidence.UNSAFE)
                )
            )
        )

        assertEquals("Busuk", result.ripeness)
        assertEquals("rotten", result.rawStage)
        assertTrue(result.isSafetyOverride)
        assertTrue(result.recommendation.contains("Jangan dikonsumsi"))
    }

    @Test
    fun `evidence code wins when options are intentionally reversed`() {
        val questions = listOf(
            DynamicQuestion("warna", "Warna?", listOf("Sangat gelap", "Kuning", "Hijau"), listOf(AnswerEvidence.OVERRIPE, AnswerEvidence.RIPE, AnswerEvidence.UNRIPE)),
            DynamicQuestion("tekstur", "Tekstur?", listOf("Sangat lembek", "Sedikit lunak", "Keras"), listOf(AnswerEvidence.OVERRIPE, AnswerEvidence.RIPE, AnswerEvidence.UNRIPE))
        )

        val unripe = engine.fuse(
            preliminary,
            mapOf("warna" to "Hijau", "tekstur" to "Keras"),
            QuestionSource.REMOTE,
            questions
        )
        val overripe = engine.fuse(
            preliminary,
            mapOf("warna" to "Sangat gelap", "tekstur" to "Sangat lembek"),
            QuestionSource.REMOTE,
            questions
        )

        assertEquals("Mentah", unripe.ripeness)
        assertEquals("Terlalu Matang", overripe.ripeness)
        assertTrue((overripe.fusionScore ?: 0f) > (unripe.fusionScore ?: 1f))
    }

    @Test
    fun `conflicting answers lower evidence consistency`() {
        val consistentQuestions = listOf(
            DynamicQuestion("warna", "Warna?", listOf("Hijau", "Kuning"), listOf(AnswerEvidence.UNRIPE, AnswerEvidence.RIPE)),
            DynamicQuestion("tekstur", "Tekstur?", listOf("Keras", "Lunak"), listOf(AnswerEvidence.UNRIPE, AnswerEvidence.RIPE)),
            DynamicQuestion("bercak", "Bercak?", listOf("Tidak ada", "Banyak"), listOf(AnswerEvidence.RIPE, AnswerEvidence.OVERRIPE))
        )
        val coherent = engine.fuse(
            preliminary,
            mapOf("warna" to "Kuning", "tekstur" to "Lunak", "bercak" to "Tidak ada"),
            QuestionSource.REMOTE,
            consistentQuestions
        )
        val conflicting = engine.fuse(
            preliminary,
            mapOf("warna" to "Hijau", "tekstur" to "Lunak", "bercak" to "Banyak"),
            QuestionSource.REMOTE,
            consistentQuestions
        )

        assertTrue((conflicting.evidenceConsistency ?: 100) < (coherent.evidenceConsistency ?: 0))
    }

    @Test
    fun `matching unripe and overripe evidence have equal consistency at equal cv confidence`() {
        val unripeQuestions = listOf(
            DynamicQuestion("warna", "Warna?", listOf("Hijau"), listOf(AnswerEvidence.UNRIPE)),
            DynamicQuestion("tekstur", "Tekstur?", listOf("Keras"), listOf(AnswerEvidence.UNRIPE))
        )
        val overripeQuestions = listOf(
            DynamicQuestion("warna", "Warna?", listOf("Gelap"), listOf(AnswerEvidence.OVERRIPE)),
            DynamicQuestion("tekstur", "Tekstur?", listOf("Sangat lembek"), listOf(AnswerEvidence.OVERRIPE))
        )
        val unripe = engine.fuse(
            preliminary.copy(rawStage = "unripe", ripeness = "Mentah"),
            mapOf("warna" to "Hijau", "tekstur" to "Keras"),
            QuestionSource.REMOTE,
            unripeQuestions
        )
        val overripe = engine.fuse(
            preliminary.copy(rawStage = "overripe", ripeness = "Terlalu Matang"),
            mapOf("warna" to "Gelap", "tekstur" to "Sangat lembek"),
            QuestionSource.REMOTE,
            overripeQuestions
        )

        assertEquals(unripe.evidenceConsistency, overripe.evidenceConsistency)
    }

    @Test
    fun `neutral no mold answer does not become a false safety override`() {
        val questions = listOf(
            DynamicQuestion("safety", "Ada jamur?", listOf("Tidak ada jamur", "Ada jamur"), listOf(AnswerEvidence.NEUTRAL, AnswerEvidence.UNSAFE)),
            DynamicQuestion("warna", "Warna?", listOf("Kuning"), listOf(AnswerEvidence.RIPE)),
            DynamicQuestion("tekstur", "Tekstur?", listOf("Sedikit lunak"), listOf(AnswerEvidence.RIPE))
        )
        val result = engine.fuse(
            preliminary,
            mapOf("safety" to "Tidak ada jamur", "warna" to "Kuning", "tekstur" to "Sedikit lunak"),
            QuestionSource.REMOTE,
            questions
        )

        assertFalse(result.isSafetyOverride)
        assertEquals("Matang", result.ripeness)
    }
}
