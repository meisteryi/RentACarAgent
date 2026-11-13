package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.RendyVehicleVariants
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live (real Gemini proxy): the 더 뉴 아이오닉 5 smart-cruise conversation through the full pipeline, with both the
 * first question (/classify) and every follow-up (/turn) classified by Gemini. Writes the transcript; the
 * assertions on wording are in Ioniq5DesignVariantTest (scripted). Skipped unless RENDY_INTENT_PROXY_URL is set.
 */
class Ioniq5LiveDialogueTest {
    @Test
    fun liveFaceliftSmartCruiseConversation() = runBlocking {
        val url = System.getenv("RENDY_INTENT_PROXY_URL").orEmpty()
        assumeTrue("RENDY_INTENT_PROXY_URL not set; live IONIQ 5 dialogue skipped", url.isNotBlank())
        val kb = IntentTestFixtures.knowledge
        val gemini = GeminiFlashProxyIntentClassifier(
            endpoint = url, totalTimeoutMs = 15_000,
            transport = HttpUrlConnectionProxyTransport(connectTimeoutMs = 3_000, readTimeoutMs = 12_000),
            allowLocalHttp = true
        )
        val rows = mutableListOf("car\tdriver\tact\tintent\tsource\tbutton\ttier\tdesign\tanswer\treason")
        val problems = mutableListOf<String>()
        fun run(variantId: String, script: List<String>) = runBlocking {
            val car = RendyVehicleVariants.resolve(IntentTestFixtures.database.models, variantId)!!
            val p = RendyQuestionPipeline(gemini, RuleBasedIntentClassifier(kb.intents), kb,
                drivingState = { DrivingState.PARKED }, turnClassifier = gemini)
            val wrongSide = if (car.designGeneration == RendyVehicleVariants.FACELIFT_THE_NEW) "오른쪽" else "왼쪽"
            for (q in script) {
                val a = p.answerAndCommit(q, car)
                val design = a.buttonId?.let { kb.guidance(car, it)?.overlay?.designGeneration } ?: ""
                rows += listOf(car.modelNameKo, q, a.act?.name ?: "-", a.intentId ?: "", a.source.name, a.buttonId ?: "",
                    a.tier?.name ?: "", design, a.text, a.reason).joinToString("\t")
                if (a.text.contains(wrongSide)) problems += "$variantId '$q' -> other design's side: ${a.text}"
                if (a.reason.contains("classifier_failed") || a.reason.contains("turn_failed")) problems += "$variantId '$q' -> proxy fallback: ${a.reason}"
            }
        }
        run("IONIQ5_NE1_FL", listOf("렌디, 스마트 크루즈 어떻게 켜?", "버튼이 어디 있는데?", "아 그거?", "못 찾겠어",
            "속도는 어떻게 바꿔?", "앞차랑 거리는 어떻게 조절해?", "다시 말해 줘"))
        run("IONIQ5_NE1_PRE", listOf("스마트 크루즈 어떻게 켜?", "버튼 어디 있어?"))
        val out = IntentTestFixtures.reportDir().resolve("ioniq5_live_dialogue.tsv")
        out.writeText((rows + listOf("", "problems: ${problems.size}") + problems).joinToString("\n"), Charsets.UTF_8)
        println(out.readText())
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }
}
