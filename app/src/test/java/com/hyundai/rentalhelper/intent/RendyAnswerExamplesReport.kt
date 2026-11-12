package com.hyundai.rentalhelper.intent

import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Writes the spoken text for a fixed set of example questions (scripted intent, so only DB/policy/dialogue
 * are exercised) to build/reports/rendy-intent/answer_examples_<label>.tsv. Used for before/after comparison.
 * Label from the RENDY_EXAMPLES_LABEL env var (default "current").
 */
class RendyAnswerExamplesReport {
    private val kb = IntentTestFixtures.knowledge

    private data class Example(val model: String, val intent: String, val query: String, val followUps: List<String> = emptyList())

    private val examples = listOf(
        Example("IONIQ5", "light_stalk", "라이트 어디 있어?", listOf("못 찾겠어", "찾았어", "그다음은?")),
        Example("IONIQ5", "wiper_stalk", "와이퍼 어떻게 켜?"),
        Example("IONIQ5", "gear_type", "기어 어떻게 바꿔?", listOf("찾았어", "그다음은?")),
        Example("IONIQ5", "electronic_parking_brake", "주차 브레이크 어떻게 풀어?"),
        Example("IONIQ5", "side_mirror_fold", "사이드미러 접는 법"),
        Example("TUCSON", "light_stalk", "라이트 어디 있어?", listOf("찾았어")),
        Example("TUCSON", "hazard_light", "비상등 어디야?"),
        Example("TUCSON", "front_defog", "앞유리 김 서렸어"),
        Example("SONATA", "window_lock", "창문 잠금 버튼 어디야?"),
        Example("SONATA", "wiper_stalk", "와이퍼 어떻게 켜?"),
        Example("AVANTE", "light_stalk", "라이트 어디 있어?"),
        Example("CASPER", "electronic_parking_brake", "주차 브레이크 어떻게 걸어?", listOf("찾았어", "그다음은?"))
    )

    @Test
    fun writeExamples() = runBlocking {
        val label = System.getenv("RENDY_EXAMPLES_LABEL")?.takeIf { it.isNotBlank() } ?: "current"
        val rows = mutableListOf("model\tturn\tquery\tintent\tbutton\ttier\tsource\ttext")
        for (e in examples) {
            val car = IntentTestFixtures.car(e.model)
            val scripted = object : IntentClassifier {
                override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext) =
                    IntentClassification.Classified(e.intent, 0.95)
            }
            val p = RendyQuestionPipeline(scripted, scripted, kb)
            (listOf(e.query) + e.followUps).forEachIndexed { i, q ->
                val a = p.answerAndCommit(q, car)
                rows += listOf(e.model, "${i + 1}", q, a.intentId ?: "", a.buttonId ?: "", a.tier?.name ?: "", a.source.name, a.text).joinToString("\t")
            }
        }
        IntentTestFixtures.reportDir().resolve("answer_examples_$label.tsv").writeText(rows.joinToString("\n"), Charsets.UTF_8)
    }
}
