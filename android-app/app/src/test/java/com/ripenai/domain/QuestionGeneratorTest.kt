package com.ripenai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class QuestionGeneratorTest {
    private val generator = QuestionGenerator(ConsumerConfig())
    private val cvResult = ClassificationResult(
        commodity = "Pisang",
        ripeness = "Matang",
        confidence = 72,
        daysEstimate = "Matang optimal sekarang",
        recommendation = "Konsumsi sekarang.",
        rawCommodity = "banana",
        rawStage = "ripe"
    )

    @Test
    fun `parses explicit option evidence from Worker contract`() {
        val result = generator.parseStrict(
            """
            {
              "fruit_type":"banana",
              "questions":[
                {"id":"warna","text":"Bagaimana warna kulit pisang?","options":["Hijau","Kuning"],"option_evidence":["UNRIPE","RIPE"]},
                {"id":"tekstur","text":"Bagaimana tekstur luar pisang?","options":["Keras","Lunak"],"option_evidence":["UNRIPE","OVERRIPE"]},
                {"id":"safety","text":"Bagaimana keamanan permukaan pisang?","options":["Bersih","Ada jamur"],"option_evidence":["NEUTRAL","UNSAFE"]}
              ]
            }
            """.trimIndent(),
            "banana",
            cvResult
        )

        assertEquals(AnswerEvidence.UNSAFE, result?.questions?.get(2)?.evidenceFor("Ada jamur"))
    }

    @Test
    fun `rejects legacy ordinal score payloads`() {
        val result = generator.parseStrict(
            """
            {
              "fruit_type":"banana",
              "questions":[
                {"id":"q1","text":"Warna?","options":["Hijau","Kuning"],"option_scores":[-0.09,0.09]},
                {"id":"q2","text":"Tekstur?","options":["Keras","Lunak"],"option_scores":[-0.09,0.09]},
                {"id":"q3","text":"Bercak?","options":["Tidak ada","Banyak"],"option_scores":[-0.09,0.09]}
              ]
            }
            """.trimIndent(),
            "banana",
            cvResult
        )

        assertNull(result)
    }
}
