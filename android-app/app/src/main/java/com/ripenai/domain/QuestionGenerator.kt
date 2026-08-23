package com.ripenai.domain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Calls the Worker only. A local question bank is retained for tests and
 * future farmer/offline flows, but consumer mode never silently uses it. */
class QuestionGenerator(private val config: ConsumerConfig) {
    suspend fun generate(
        fruitType: String,
        cvResult: ClassificationResult,
        online: Boolean
    ): QuestionGenerationResult = withContext(Dispatchers.IO) {
        if (config.requireOnlineQuestions && !online) {
            return@withContext QuestionGenerationResult(error = "Mode konsumen membutuhkan koneksi internet untuk memastikan hasil.")
        }
        if (config.llmQuestionsUrl.isBlank()) {
            return@withContext QuestionGenerationResult(error = "Layanan pertanyaan belum terhubung. Isi QUESTION_API_URL saat build aplikasi.")
        }

        val request = JSONObject()
            .put("fruit_type", fruitType)
            .put("cv_stage", cvResult.rawStage)
            .put("cv_confidence", cvResult.confidence / 100.0)
            .put("top2_stage", cvResult.top2Stage ?: JSONObject.NULL)
            .put("top2_confidence", cvResult.top2Confidence / 100.0)
            .put("language", "id-ID")

        try {
            val connection = (URL(config.llmQuestionsUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = config.questionTimeoutMs.toInt()
                readTimeout = config.questionTimeoutMs.toInt()
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                return@withContext QuestionGenerationResult(error = "Layanan pertanyaan sedang tidak tersedia. Coba lagi sebentar.")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val parsed = parseStrict(body, fruitType, cvResult)
                ?: return@withContext QuestionGenerationResult(error = "Pertanyaan dari layanan belum valid. Coba lagi.")
            QuestionGenerationResult(response = parsed)
        } catch (_: Exception) {
            QuestionGenerationResult(error = "Belum bisa terhubung ke layanan pertanyaan. Periksa internet lalu coba lagi.")
        }
    }

    private fun parseStrict(body: String, fruitType: String, cvResult: ClassificationResult): QuestionResponse? {
        val root = try { JSONObject(body) } catch (_: Exception) { return null }
        val payload = if (root.has("choices")) {
            val content = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
            if (content.isNullOrBlank()) return null
            try { JSONObject(content.removeMarkdownFence()) } catch (_: Exception) { return null }
        } else root

        val questionsJson = payload.optJSONArray("questions") ?: return null
        if (questionsJson.length() != 3) return null
        val questions = (0 until questionsJson.length()).map { index ->
            val item = questionsJson.optJSONObject(index) ?: return null
            val optionsJson = item.optJSONArray("options") ?: return null
            if (optionsJson.length() !in 2..4) return null
            val options = (0 until optionsJson.length()).map { optionsJson.optString(it).trim() }
            val id = item.optString("id").trim()
            val text = item.optString("text").trim()
            if (id.isBlank() || text.isBlank() || text.length > 160 || options.any { it.isBlank() }) return null
            DynamicQuestion(id, text, options)
        }
        return QuestionResponse(
            fruitType = payload.optString("fruit_type", fruitType),
            ambiguousBetween = listOfNotNull(cvResult.rawStage.takeIf { it != "unknown" }, cvResult.top2Stage),
            questions = questions,
            source = QuestionSource.REMOTE
        )
    }

    private fun String.removeMarkdownFence(): String = trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
