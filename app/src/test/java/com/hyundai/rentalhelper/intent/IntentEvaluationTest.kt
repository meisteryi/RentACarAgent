package com.hyundai.rentalhelper.intent

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Accuracy measurements on the natural-language fixtures (selected car: IONIQ5).
 *  - Rule baseline: the keyword engine; always runs. A measurement, not a pass/fail gate.
 *  - Live Gemini: runs only when RENDY_INTENT_PROXY_URL is set (HTTPS, or http://127.0.0.1 local proxy).
 *    Optional: RENDY_PROXY_TOKEN, RENDY_EVAL_DELAY_MS (pause between calls), RENDY_EVAL_LABEL (report suffix).
 *
 * Two candidate spaces are measured so results stay comparable with the pre-v4.2 runs:
 *  - legacy33: the 33 FAQ intents that have a button on the car (the space of the earlier 98.6~100% runs).
 *  - v42: all 51 intents (33 FAQ + 18 accepted proposals), as the app now sends them.
 * The 72/24 fixtures were labelled in the 33-intent space; in the v42 space a few "unsupported" questions now
 * have a matching intent (e.g. "라디오 볼륨 줄여줘" -> audio_volume). Those rows are reported as
 * `label_space_changed`, not counted as errors or fixed by editing the fixture.
 * Reports: build/reports/rendy-intent/<name>.tsv and <name>.summary.txt
 */
class IntentEvaluationTest {
    private val kb = IntentTestFixtures.knowledge
    private val car = IntentTestFixtures.car("IONIQ5")
    private val legacyIds: Set<String> = IntentTestFixtures.database.faqIntents.filter { intent ->
        val key = if (intent.isGear) RendyKnowledgeBase.GEAR_BUTTON else intent.responseKey
        key != null && key in car.buttons
    }.mapTo(mutableSetOf()) { it.intent }

    /** Questions labelled "no matching intent" in the 33-intent fixtures that a v4.2 intent now covers. */
    private val v42Relabels = mapOf(
        "라디오 볼륨 줄여줘" to "audio_volume",          // new intent audio_volume
        "타이어 공기압 수치를 알려줘" to "cluster_view",  // new intent, keyword "타이어공기압"
        "차 안이 너무 더워" to "temperature",            // new intent, keyword "더워"
        "선루프 여는 버튼 알려줘" to "sunroof_switch"     // now offered on every car; IONIQ5 routes to no_data
    )

    @Test
    fun ruleBaselineAccuracyReport() = runBlocking {
        val rules = RuleBasedIntentClassifier(kb.intents)
        evaluate("rule_v42_legacy33", rules, Space.LEGACY33, IntentTestFixtures.cases, false, 0)
        evaluate("rule_v42_legacy33_holdout", rules, Space.LEGACY33, IntentTestFixtures.holdoutCases, false, 0)
        evaluate("rule_v42_full51", rules, Space.V42, IntentTestFixtures.cases, false, 0)
        evaluate("rule_v42_full51_holdout", rules, Space.V42, IntentTestFixtures.holdoutCases, false, 0)
        evaluate("rule_v42_full51_holdout_v42", rules, Space.V42, IntentTestFixtures.holdoutV42Cases, false, 0)
        // Pre-v4.2 keyword algorithm on the same fixtures, for the regression comparison.
        val legacyRules = object : IntentClassifier {
            override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification {
                val m = KeywordIntentMatcher.legacyBestMatch(IntentTestFixtures.database.faqIntents, query)
                    ?: return IntentClassification.Clarification(null, "rule_no_match")
                if (candidates.none { it.id == m.intent.intent }) return IntentClassification.Clarification(null, "rule_match_not_eligible")
                return IntentClassification.Classified(m.intent.intent, 1.0)
            }
        }
        evaluate("rule_legacy_algorithm_legacy33", legacyRules, Space.LEGACY33, IntentTestFixtures.cases, false, 0)
        evaluate("rule_legacy_algorithm_legacy33_holdout", legacyRules, Space.LEGACY33, IntentTestFixtures.holdoutCases, false, 0)
    }

    @Test
    fun liveGeminiProxyAccuracyReport() = runBlocking {
        val url = System.getenv("RENDY_INTENT_PROXY_URL").orEmpty()
        assumeTrue("RENDY_INTENT_PROXY_URL not set; live Gemini evaluation skipped", url.isNotBlank())
        val token = System.getenv("RENDY_PROXY_TOKEN")?.takeIf { it.isNotBlank() }
        val label = System.getenv("RENDY_EVAL_LABEL")?.takeIf { it.isNotBlank() }?.let { "_$it" } ?: ""
        val classifier = GeminiFlashProxyIntentClassifier(
            endpoint = url,
            totalTimeoutMs = 15_000,
            transport = HttpUrlConnectionProxyTransport(connectTimeoutMs = 3_000, readTimeoutMs = 12_000, authToken = token),
            allowLocalHttp = true
        )
        val delayMs = System.getenv("RENDY_EVAL_DELAY_MS")?.toLongOrNull() ?: 0L
        evaluate("live_gemini${label}_legacy33", classifier, Space.LEGACY33, IntentTestFixtures.cases, true, delayMs)
        evaluate("live_gemini${label}_legacy33_holdout", classifier, Space.LEGACY33, IntentTestFixtures.holdoutCases, true, delayMs)
        evaluate("live_gemini${label}_v42", classifier, Space.V42, IntentTestFixtures.cases, true, delayMs)
        evaluate("live_gemini${label}_v42_holdout", classifier, Space.V42, IntentTestFixtures.holdoutCases, true, delayMs)
        evaluate("live_gemini${label}_v42_holdout_v42", classifier, Space.V42, IntentTestFixtures.holdoutV42Cases, true, delayMs)
    }

    private enum class Space { LEGACY33, V42 }

    /** Records the classifier's raw output and wall time; optionally narrows the offered candidates. */
    private class Recording(private val inner: IntentClassifier, private val allowed: Set<String>?) : IntentClassifier {
        var last: IntentClassification? = null
        var lastMs = 0L
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification {
            val offered = allowed?.let { a -> candidates.filter { it.id in a } } ?: candidates
            val start = System.nanoTime()
            val result = inner.classify(query, offered, context)
            lastMs = (System.nanoTime() - start) / 1_000_000
            last = result
            // Like the app: an id outside the offered set is never accepted.
            if (result is IntentClassification.Classified && offered.none { it.id == result.intentId }) {
                return IntentClassification.Clarification(result.confidence, "intent_not_offered")
            }
            return result
        }
    }

    private suspend fun evaluate(
        name: String,
        classifier: IntentClassifier,
        space: Space,
        cases: List<IntentTestCase>,
        retryOnRateLimit: Boolean,
        delayMs: Long
    ) {
        val recording = Recording(classifier, if (space == Space.LEGACY33) legacyIds else null)
        val router = QuestionIntentRouter(recording, kb)
        val rows = mutableListOf("id\tcategory\tquery\tprevious\texpected\tactual\tmatch\troute\ttier\traw\tconfidence\tms\tretries\tnote")
        val perCategory = linkedMapOf<String, IntArray>()
        val latencies = mutableListOf<Long>()
        var errors = 0
        var wrongAnswers = 0
        var wrongVerifiedSpoken = 0
        var clarifications = 0
        var mappedExpected = 0
        var expectedWithIntent = 0
        var safeVerified = 0
        var relabelled = 0
        for (case in cases) {
            var retries = 0
            var route: QuestionRoute
            while (true) {
                route = router.route(case.query, car, ClassificationContext(case.previousIntent))
                val rateLimited = (route as? QuestionRoute.Fallback)?.reason == "proxy_http_429"
                if (!(retryOnRateLimit && rateLimited && retries < 4)) break
                retries++
                delay(15_000L * retries)
            }
            if (delayMs > 0) delay(delayMs)
            val relabel = if (space == Space.V42) v42Relabels[case.query] else null
            val labelled = relabel ?: case.expectedIntent
            val expected = when (space) {
                Space.LEGACY33 -> labelled?.takeIf { it in legacyIds }
                Space.V42 -> labelled
            } ?: "CLARIFY"
            val actual = when (route) {
                is QuestionRoute.Answer -> route.intentId
                is QuestionRoute.Clarify -> "CLARIFY"
                is QuestionRoute.Fallback -> "ERROR"
            }
            val answer = (route as? QuestionRoute.Answer)?.let {
                RendyAnswerComposer.initial(kb, car, it.route, DrivingState.UNKNOWN, AnswerSource.LLM, "eval")
            }
            val tier = answer?.tier
            val raw = when (val r = recording.last) {
                is IntentClassification.Classified -> r.intentId
                is IntentClassification.Clarification -> "clarify:${r.reason}"
                is IntentClassification.Failure -> "failure:${r.reason}"
                null -> "-"
            }
            val confidence = when (val r = recording.last) {
                is IntentClassification.Classified -> r.confidence.toString()
                is IntentClassification.Clarification -> r.confidence?.toString() ?: ""
                else -> ""
            }
            if (route is QuestionRoute.Fallback) errors++ else latencies += recording.lastMs
            val match = actual == expected
            if (route is QuestionRoute.Clarify) clarifications++
            if (!match && route is QuestionRoute.Answer) {
                wrongAnswers++
                if (tier == GuidanceTier.FULL) wrongVerifiedSpoken++
            }
            if (expected != "CLARIFY") {
                expectedWithIntent++
                val r = (route as? QuestionRoute.Answer)?.route
                if (match && (r is IntentRoute.Button || r is IntentRoute.Redirect)) mappedExpected++
            }
            if (match && tier == GuidanceTier.FULL) safeVerified++
            if (relabel != null) relabelled++
            perCategory.getOrPut(case.category) { IntArray(2) }.let { it[0]++; if (match) it[1]++ }
            rows += listOf(case.id, case.category, case.query, case.previousIntent ?: "", expected, actual, match.toString(),
                (route as? QuestionRoute.Answer)?.route?.javaClass?.simpleName ?: "", tier?.toString() ?: "", raw, confidence,
                recording.lastMs.toString(), retries.toString(), if (relabel != null) "label_space_changed" else "").joinToString("\t")
        }
        val total = perCategory.values.sumOf { it[0] }
        val passed = perCategory.values.sumOf { it[1] }
        val sorted = latencies.sorted()
        fun pct(p: Double) = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt()]
        fun rate(n: Int, d: Int) = if (d == 0) "n/a" else "%.1f%%".format(n * 100.0 / d)
        val summary = buildString {
            appendLine("[$name] space=$space candidates=${if (space == Space.LEGACY33) legacyIds.size else kb.intents.size} car=${car.modelId}")
            appendLine("  intent accuracy ${passed}/${total} (${rate(passed, total)}), wrong-intent answers=$wrongAnswers " +
                "(of which verified text spoken=$wrongVerifiedSpoken), call errors=$errors, relabelled=$relabelled")
            appendLine("  DB mapping success ${mappedExpected}/${expectedWithIntent} (${rate(mappedExpected, expectedWithIntent)}) " +
                "| verified answer spoken ${safeVerified}/${total} (${rate(safeVerified, total)}) " +
                "| clarification rate ${clarifications}/${total} (${rate(clarifications, total)}) " +
                "| wrong-feature rate ${wrongAnswers}/${total} (${rate(wrongAnswers, total)})")
            if (sorted.isNotEmpty()) appendLine("  latency ms: mean=${sorted.average().toLong()} p50=${pct(0.5)} p95=${pct(0.95)} max=${sorted.last()} (n=${sorted.size})")
            perCategory.forEach { (c, v) -> appendLine("  $c: ${v[1]}/${v[0]}") }
        }
        println(summary)
        val dir = IntentTestFixtures.reportDir()
        dir.resolve("$name.tsv").writeText(rows.joinToString("\n"), Charsets.UTF_8)
        dir.resolve("$name.summary.txt").writeText(summary, Charsets.UTF_8)
    }
}
