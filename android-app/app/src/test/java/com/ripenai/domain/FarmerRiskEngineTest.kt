package com.ripenai.domain

import com.ripenai.data.local.FarmerSensorReadingEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FarmerRiskEngineTest {
    private val engine = FarmerRiskEngine(
        mapOf(
            "default" to FarmerThresholds(
                gasRateBands = listOf(RiskBand(0f, 5f, 0f), RiskBand(5f, 15f, 0.3f), RiskBand(15f, 999f, 0.6f)),
                humidityBands = listOf(RiskBand(0f, 60f, 0f), RiskBand(60f, 80f, 0.2f), RiskBand(80f, 100f, 0.4f)),
                temperatureBands = listOf(RiskBand(0f, 24f, 0f), RiskBand(24f, 30f, 0.05f), RiskBand(30f, 100f, 0.12f))
            )
        )
    )

    @Test
    fun risingGasAndHumidityBecomeUrgent() {
        val readings = (0..8).map { index ->
            FarmerSensorReadingEntity(
                containerId = 1,
                timestamp = 1_700_000_000_000L + index * 3 * 60 * 60 * 1000L,
                temperature = 27f + index * 0.5f,
                humidity = 64f + index * 2.25f,
                gas = 20f + index * 67.5f
            )
        }

        val result = engine.calculate("Pisang", readings)

        assertEquals("Segera ditangani", result.status)
        assertTrue((result.score ?: 0f) >= 0.7f)
        assertTrue(result.recommendation.contains("Segera"))
        assertTrue(result.reasons.any { it.contains("Gas") })
    }

    @Test
    fun oneStableReadingDoesNotInventGasTrend() {
        val result = engine.calculate(
            "Apel",
            listOf(FarmerSensorReadingEntity(1, 1_700_000_000_000L, 22f, 45f, 10f))
        )

        assertEquals("Aman", result.status)
        assertEquals(null, result.gasRatePerHour)
        assertTrue(result.reasons.any { it.contains("Belum ada tren gas") })
    }

    @Test
    fun syntheticModelIsOnlyAWeightedAssist() {
        val ruleResult = engine.calculate(
            "Apel",
            listOf(
                FarmerSensorReadingEntity(1, 1_700_000_000_000L, 22f, 45f, 10f),
                FarmerSensorReadingEntity(1, 1_700_003_600_000L, 22f, 45f, 11f)
            )
        )
        val merged = engine.mergeModel(
            ruleResult,
            FarmerModelPrediction(0.95f, listOf(0.01f, 0.04f, 0.95f), 0.95f, "Segera ditangani")
        )

        assertTrue((merged.score ?: 0f) > (ruleResult.score ?: 0f))
        assertTrue((merged.score ?: 1f) < 0.95f)
        assertEquals(0.95f, merged.modelScore)
        assertTrue(merged.analysisSource.contains("sintetis"))
    }
}
