package com.ripenai.domain

data class FarmerModelPrediction(
    val riskScore: Float,
    val probabilities: List<Float>,
    val confidence: Float,
    val predictedStatus: String,
    val hoursToAction: Float? = null
)
