package com.hyundai.rentalhelper.intent

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Follow-up dialogue-act accuracy of the REAL model through the proxy (/v1/rendy/turn), on
 * src/test/resources/dialogue_act_cases.json (Korean, unseen by the prompt). There is no offline scorer: the app
 * has no phrase rules for these acts, so this runs only when RENDY_INTENT_PROXY_URL is set (the classify URL; the
 * turn URL is derived). Optional: RENDY_PROXY_TOKEN, RENDY_EVAL_DELAY_MS, RENDY_EVAL_LABEL.
 * The act is scored AFTER the app's own validation (low-confidence CONFIRM/FOUND/DENY -> UNCLEAR), i.e. what the
 * app would act on. Reports: build/reports/rendy-intent/dialogue_act_<label>.tsv / .summary.txt
 */
class DialogueActEvaluationTest {
    private val kb = IntentTestFixtures.knowledge
    private val gson = Gson()

    private companion object { const val NO_INTENT = "NONE" }

    private data class Case(val id: String, val category: String, val state: String, val utterance: String,
                            val expected: List<String>, val expected_intent: String? = null)

    private fun load(): Pair<JsonObject, List<Case>> {
        val root = DialogueActEvaluationTest::class.java.getResourceAsStream("/dialogue_act_cases.json")!!
            .bufferedReader(Charsets.UTF_8).use { gson.fromJson(it, JsonObject::class.java) }
        val cases = root.getAsJsonArray("cases").map { gson.fromJson(it, Case::class.java) }
        return root to cases
    }

    @Test
    fun fixtureIsWellFormed() {
        val (root, cases) = load()
        val states = root.getAsJsonObject("states")
        assertTrue("at least 50 cases", cases.size >= 50)
        assertTrue(cases.map { it.id }.toSet().size == cases.size)
        val acts = DialogueAct.values().map { it.name }.toSet()
        val car = IntentTestFixtures.car(root.get("car").asString)
        val offered = (kb.candidatesFor(car) + RendyAssistantIntents.candidates).map { it.id }.toSet()
        cases.forEach { c ->
            assertTrue(c.id, states.has(c.state))
            assertTrue(c.id, c.expected.isNotEmpty() && acts.containsAll(c.expected))
            c.expected_intent?.takeIf { it != NO_INTENT }?.let { assertTrue("${c.id}: $it", it in offered) }
        }
        // Every act and the context-dependent "same word" group are covered.
        assertTrue(acts == cases.flatMap { it.expected }.toSet())
    }

    @Test
    fun liveDialogueActAccuracyReport() = runBlocking {
        val url = System.getenv("RENDY_INTENT_PROXY_URL").orEmpty()
        assumeTrue("RENDY_INTENT_PROXY_URL not set; live dialogue-act evaluation skipped", url.isNotBlank())
        val token = System.getenv("RENDY_PROXY_TOKEN")?.takeIf { it.isNotBlank() }
        val label = System.getenv("RENDY_EVAL_LABEL")?.takeIf { it.isNotBlank() } ?: "live"
        val delayMs = System.getenv("RENDY_EVAL_DELAY_MS")?.toLongOrNull() ?: 0L
        val classifier = GeminiFlashProxyIntentClassifier(
            endpoint = url, totalTimeoutMs = 15_000,
            transport = HttpUrlConnectionProxyTransport(connectTimeoutMs = 3_000, readTimeoutMs = 12_000, authToken = token),
            allowLocalHttp = true
        )
        val (root, cases) = load()
        val car = IntentTestFixtures.car(root.get("car").asString)
        // Same candidate list the app sends: DB intents plus the non-vehicle assistant topics.
        val candidates = kb.candidatesFor(car) + RendyAssistantIntents.candidates
        val states = root.getAsJsonObject("states")

        val rows = mutableListOf<String>()
        val perAct = sortedMapOf<String, IntArray>() // expected primary act -> [correct, total]
        val perCategory = sortedMapOf<String, IntArray>()
        val latencies = mutableListOf<Long>()
        var correct = 0
        var errors = 0
        var intentChecked = 0
        var intentCorrect = 0
        val failures = mutableListOf<String>()
        for (c in cases) {
            val s = states.getAsJsonObject(c.state)
            val ctx = TurnContext(
                lastRendyUtterance = s.get("last_rendy").asString,
                rendyAskedQuestion = s.get("asked").asBoolean,
                lastDriverQuestion = s.get("last_question").asString,
                currentIntentId = s.get("intent").takeIf { !it.isJsonNull }?.asString,
                stage = DialogueStage.valueOf(s.get("stage").asString),
                pendingDetail = s.get("pending").asBoolean,
                drivingState = DrivingState.valueOf(s.get("driving").asString),
                carModelName = car.modelNameKo
            )
            val started = System.nanoTime()
            val r = classifier.classifyTurn(c.utterance, candidates, ctx)
            val ms = (System.nanoTime() - started) / 1_000_000
            latencies += ms
            val (act, intent, conf) = when (r) {
                is TurnClassification.Act -> Triple(r.act.name, r.intentId, r.confidence)
                is TurnClassification.Failure -> Triple("ERROR:${r.reason}", null, null)
            }
            if (act.startsWith("ERROR")) errors++
            // NO_INTENT: a garbled STT result must not be turned into any feature (the app then asks again).
            val intentOk = c.expected_intent?.let { if (it == NO_INTENT) intent == null else it == intent }
            // NO_INTENT and assistant topics (driver location) are judged on the intent as well as the act.
            val strictIntent = c.expected_intent == NO_INTENT || RendyAssistantIntents.isAssistant(c.expected_intent)
            val ok = act in c.expected && (!strictIntent || intentOk == true)
            if (c.expected_intent != null && act == "NEW_QUESTION") { intentChecked++; if (intentOk == true) intentCorrect++ }
            if (ok) correct++ else failures += "${c.id}\t[${c.state}] ${c.utterance}\texpected=${c.expected.joinToString("|")}\tgot=$act (conf=$conf)"
            perAct.getOrPut(c.expected.first()) { IntArray(2) }.let { it[1]++; if (ok) it[0]++ }
            perCategory.getOrPut(c.category) { IntArray(2) }.let { it[1]++; if (ok) it[0]++ }
            rows += listOf(c.id, c.category, c.state, c.utterance, c.expected.joinToString("|"), act, conf?.toString() ?: "",
                c.expected_intent ?: "", intent ?: "", if (ok) "OK" else "MISS", ms.toString()).joinToString("\t")
            if (delayMs > 0) delay(delayMs)
        }
        // Garbled transcripts on the FIRST-question path too (/classify, no context): must not pick a feature.
        val firstQuestion = cases.filter { it.expected_intent == NO_INTENT }.map { c ->
            val r = classifier.classify(c.utterance, candidates, ClassificationContext())
            val picked = (r as? IntentClassification.Classified)?.intentId
            "${c.id}	${c.utterance}	classify=${r.javaClass.simpleName}${picked?.let { ":$it" } ?: ""}	${if (picked == null) "OK" else "MISS"}"
        }
        // Assistant-topic questions asked as the FIRST question (/classify, no context).
        val firstAssistant = cases.filter { RendyAssistantIntents.isAssistant(it.expected_intent) }.map { c ->
            val r = classifier.classify(c.utterance, candidates, ClassificationContext())
            val picked = (r as? IntentClassification.Classified)?.intentId
            "${c.id}	${c.utterance}	classify=${r.javaClass.simpleName}${picked?.let { ":$it" } ?: ""}	${if (picked == c.expected_intent) "OK" else "MISS"}"
        }
        val sorted = latencies.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        val summary = buildString {
            appendLine("dialogue-act evaluation: label=$label, endpoint=${GeminiFlashProxyIntentClassifier.turnEndpointFor(url)}, car=${car.modelId}, candidates=${candidates.size}")
            appendLine("overall: $correct/${cases.size} = ${"%.1f".format(100.0 * correct / cases.size)}%  (transport/proxy errors: $errors)")
            appendLine("NEW_QUESTION intent (when act was NEW_QUESTION): $intentCorrect/$intentChecked")
            appendLine("latency ms (client round trip incl. proxy + Gemini): p50=${pct(0.5)} p90=${pct(0.9)} max=${sorted.last()} mean=${latencies.average().toLong()}")
            appendLine("per expected act (first listed):")
            perAct.forEach { (k, v) -> appendLine("  $k: ${v[0]}/${v[1]} = ${"%.1f".format(100.0 * v[0] / v[1])}%") }
            appendLine("per category:")
            perCategory.forEach { (k, v) -> appendLine("  $k: ${v[0]}/${v[1]}") }
            appendLine("garbled STT on the first-question path (/classify, must not pick a feature):")
            firstQuestion.forEach { appendLine("  $it") }
            appendLine("assistant topics on the first-question path (/classify, must pick that topic):")
            firstAssistant.forEach { appendLine("  $it") }
            appendLine("misses:")
            failures.forEach { appendLine("  $it") }
        }
        val dir = IntentTestFixtures.reportDir()
        dir.resolve("dialogue_act_$label.tsv").writeText(
            "id\tcategory\tstate\tutterance\texpected\tgot\tconfidence\texpected_intent\tgot_intent\tresult\tms\n" + rows.joinToString("\n"), Charsets.UTF_8)
        dir.resolve("dialogue_act_$label.summary.txt").writeText(summary, Charsets.UTF_8)
        println(summary)
    }
}
