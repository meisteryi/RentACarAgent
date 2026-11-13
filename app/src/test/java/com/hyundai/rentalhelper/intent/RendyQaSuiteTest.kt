package com.hyundai.rentalhelper.intent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The v4.2 QA suite (133 multi-turn scenarios), kept as its own asset next to the 72/24 fixtures.
 * Outcomes are classified per turn instead of one pass rate, because some expectations in the suite need
 * content the verification policy does not allow (e.g. "must include 삼각형" for a PARTIAL button):
 *  PASS_VERIFIED            right intent/button, verified text, expectations met
 *  PASS_BLOCKED_SAFE        right intent/button, verification insufficient, safe notice spoken
 *  BLOCKED_EXPECTS_UNVERIFIED  right intent/button, but the expectation needs unverified content -> not spoken
 *  PASS_CLARIFY / PASS_NOT / PASS_NO_DATA / PASS_FOLLOW_UP / PASS_FOLLOW_UP_BLOCKED
 *  FAIL_INTENT / FAIL_BUTTON / FAIL_CONTENT / FAIL_SAFETY
 * Modes: mock (classifier returns the labelled intent: routing + policy + dialogue only), rules (keyword engine),
 * live (Gemini proxy, when RENDY_INTENT_PROXY_URL is set).
 */
class RendyQaSuiteTest {
    private val db = IntentTestFixtures.database
    private val kb = IntentTestFixtures.knowledge
    private val suite: JsonObject = RendyQaSuiteTest::class.java.getResourceAsStream("/rendy_qa_test_cases_final.json")!!
        .bufferedReader(Charsets.UTF_8).use { JsonParser.parseReader(it).asJsonObject }
    private val cases: JsonArray = suite.getAsJsonArray("cases")

    private class Switchable(var inner: IntentClassifier? = null, var scripted: String? = null) : IntentClassifier {
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification =
            inner?.classify(query, candidates, context)
                ?: scripted?.let { IntentClassification.Classified(it, 0.95) }
                ?: IntentClassification.Clarification(0.3, "scripted_unknown")
    }

    @Test
    fun suiteHas133Cases() {
        assertEquals(133, cases.size())
        assertEquals("4.2.0-final", suite.get("db_version").asString)
    }

    @Test
    fun mockClassifierRoutingPolicyAndDialogue() = runBlocking {
        val counts = run("qa133_mock", Mode.MOCK, null)
        assertEquals("safety failures", 0, counts["FAIL_SAFETY"] ?: 0)
        assertEquals("intent failures (mock)", 0, counts["FAIL_INTENT"] ?: 0)
        assertEquals("button failures (mock)", 0, counts["FAIL_BUTTON"] ?: 0)
    }

    /** [mockClassifierRoutingPolicyAndDialogue] with a PARKED signal (see [ruleEngineOnQaSuiteParked]). */
    @Test
    fun mockClassifierParked() = runBlocking {
        val counts = run("qa133_mock_parked", Mode.MOCK, null, notRestricted = DrivingState.PARKED)
        assertEquals("safety failures", 0, counts["FAIL_SAFETY"] ?: 0)
        assertEquals("intent failures (mock)", 0, counts["FAIL_INTENT"] ?: 0)
        assertEquals("button failures (mock)", 0, counts["FAIL_BUTTON"] ?: 0)
    }

    @Test
    fun ruleEngineOnQaSuite() = runBlocking {
        val counts = run("qa133_rules", Mode.RULES, RuleBasedIntentClassifier(kb.intents))
        assertEquals("safety failures", 0, counts["FAIL_SAFETY"] ?: 0)
    }

    /**
     * Same as [ruleEngineOnQaSuite] but with a PARKED vehicle signal for turns that are not ux_restricted. Since the
     * dialogue manager, step-by-step follow-ups (location walk-through, operation steps) are given only when parked;
     * with UNKNOWN (the default run) they are held. This run shows the follow-up content itself is unchanged.
     */
    @Test
    fun ruleEngineOnQaSuiteParked() = runBlocking {
        val counts = run("qa133_rules_parked", Mode.RULES, RuleBasedIntentClassifier(kb.intents), notRestricted = DrivingState.PARKED)
        assertEquals("safety failures", 0, counts["FAIL_SAFETY"] ?: 0)
    }

    @Test
    fun liveGeminiOnQaSuite() = runBlocking {
        val url = System.getenv("RENDY_INTENT_PROXY_URL").orEmpty()
        assumeTrue("RENDY_INTENT_PROXY_URL not set; live QA run skipped", url.isNotBlank())
        val token = System.getenv("RENDY_PROXY_TOKEN")?.takeIf { it.isNotBlank() }
        val label = System.getenv("RENDY_EVAL_LABEL")?.takeIf { it.isNotBlank() }?.let { "_$it" } ?: ""
        val classifier = GeminiFlashProxyIntentClassifier(
            endpoint = url, totalTimeoutMs = 15_000, allowLocalHttp = true,
            transport = HttpUrlConnectionProxyTransport(connectTimeoutMs = 3_000, readTimeoutMs = 12_000, authToken = token)
        )
        val counts = run("qa133_live$label", Mode.LIVE, classifier, System.getenv("RENDY_EVAL_DELAY_MS")?.toLongOrNull() ?: 0L)
        assertEquals("safety failures", 0, counts["FAIL_SAFETY"] ?: 0)
    }

    private enum class Mode { MOCK, RULES, LIVE }

    private suspend fun run(
        name: String, mode: Mode, classifier: IntentClassifier?, delayMs: Long = 0,
        notRestricted: DrivingState = DrivingState.UNKNOWN
    ): Map<String, Int> {
        val rows = mutableListOf("case\tturn\tcategory\tmodel\tuser\texpected_intent\texpected_button\tsource\tintent\tbutton\ttier\toutcome\tdetail\ttext")
        val counts = sortedMapOf<String, Int>()
        val byCategory = sortedMapOf<String, MutableMap<String, Int>>()
        for (c in cases.map { it.asJsonObject }) {
            val id = c.get("id").asString
            val category = c.get("category").asString
            val modelId = c.get("model").asString
            val turns = c.getAsJsonArray("turns").map { it.asJsonObject }
            if (modelId == "*") {
                counts.merge("STATIC_LINT", 1, Int::plus)
                rows += listOf(id, "1", category, "*", turns.first().get("user").asString, "DATA_RULE", "", "", "", "", "", "STATIC_LINT", "rendy_db_lint.py", "").joinToString("\t")
                continue
            }
            val car = IntentTestFixtures.car(modelId)
            val oracle = SpokenTextOracle(car, kb, db)
            val switchable = Switchable(classifier)
            var driving = DrivingState.UNKNOWN
            val pipeline = RendyQuestionPipeline(switchable, RuleBasedIntentClassifier(kb.intents), kb, drivingState = { driving })
            turns.forEachIndexed { index, turn ->
                val user = turn.get("user").asString
                val rawExpected = turn.str("expected_intent") ?: ""
                val expected = resolveExpectedIntent(rawExpected)
                val expectedButton = turn.str("expected_button")
                val expect = turn.getAsJsonObject("expect") ?: JsonObject()
                driving = if (expect.bool("ux_restricted")) DrivingState.DRIVING else notRestricted
                if (mode == Mode.MOCK) switchable.scripted = expected.takeIf { kb.intent(it) != null }
                val answer = pipeline.answer(user, car)
                pipeline.commit(answer)
                if (mode == Mode.LIVE && delayMs > 0) delay(delayMs)
                val (outcome, detail) = if (expected == NO_INTENT) {
                    // The suite expects a control that no intent (33 FAQ + 18 proposed) leads to.
                    oracle.violation(answer.text)?.let { "FAIL_SAFETY" to it }
                        ?: ("DATA_GAP_NO_INTENT" to "no intent routes to '$rawExpected'; answered ${answer.source}")
                } else judge(car, expected, expectedButton, expect, answer, oracle)
                counts.merge(outcome, 1, Int::plus)
                byCategory.getOrPut(category) { sortedMapOf() }.merge(outcome, 1, Int::plus)
                rows += listOf(id, "${index + 1}", category, modelId, user, expected, expectedButton ?: "", answer.source.name,
                    answer.intentId ?: "", answer.buttonId ?: "", answer.tier?.name ?: "", outcome, detail, answer.text.replace("\t", " ")).joinToString("\t")
            }
        }
        val summary = buildString {
            appendLine("[$name] turns=${counts.values.sum()} outcomes=$counts")
            byCategory.forEach { (cat, v) -> appendLine("  $cat: $v") }
        }
        println(summary)
        val dir = IntentTestFixtures.reportDir()
        dir.resolve("$name.tsv").writeText(rows.joinToString("\n"), Charsets.UTF_8)
        dir.resolve("$name.summary.txt").writeText(summary, Charsets.UTF_8)
        return counts
    }

    private fun judge(
        car: com.hyundai.rentalhelper.data.HyundaiCarModel,
        expected: String,
        expectedButton: String?,
        expect: JsonObject,
        a: VoiceAnswer,
        oracle: SpokenTextOracle
    ): Pair<String, String> {
        oracle.violation(a.text)?.let { return "FAIL_SAFETY" to it }
        val mustNot = expect.list("must_not_include")
        mustNot.firstOrNull { a.text.contains(it) }?.let { return "FAIL_SAFETY" to "contains forbidden '$it'" }
        if (expect.bool("must_not_claim_success") && listOf("됐어요", "완료됐", "성공했").any { a.text.contains(it) }) {
            return "FAIL_SAFETY" to "claims success"
        }
        when {
            expected == "CLARIFY" -> {
                return if (a.source == AnswerSource.CLARIFICATION) "PASS_CLARIFY" to (if (a.reason.contains("db_clarification_rule")) "db_question" else "generic")
                else "FAIL_INTENT" to "answered ${a.intentId} instead of clarifying"
            }
            expected.startsWith("NOT:") -> {
                val forbiddenIntent = expected.removePrefix("NOT:")
                return if (a.intentId == forbiddenIntent && a.source != AnswerSource.CLARIFICATION) "FAIL_INTENT" to "matched $forbiddenIntent"
                else "PASS_NOT" to "${a.source}/${a.intentId}"
            }
            expected.startsWith("FOLLOW_UP") -> {
                if (a.source != AnswerSource.FOLLOW_UP) return "FAIL_INTENT" to "not handled as follow-up (${a.source})"
                if (expected == "FOLLOW_UP_REPEAT") {
                    return if (a.reason.contains("|repeat")) "PASS_FOLLOW_UP" to "repeat" else "FAIL_CONTENT" to "not a repeat"
                }
                if (a.tier?.speakable != true) {
                    return if (a.text == RendyAnswerComposer.ESCALATE_TEXT) "PASS_FOLLOW_UP_BLOCKED" to "unverified control -> escalate"
                    else "FAIL_CONTENT" to "unexpected follow-up text"
                }
                val field = expect.str("source_field") ?: ""
                val g = kb.guidance(car, a.buttonId!!)!!
                val any = expect.list("must_include_any")
                // Field not verified for this button: the honest "not confirmed" reply is the correct outcome.
                val locationUnknown = a.text == RendyAnswerComposer.LOCATION_UNKNOWN_TEXT ||
                    a.text.endsWith(ButtonGuidance.NOTICE_LOCATION_UNCONFIRMED)
                val operationUnknown = a.text == RendyAnswerComposer.OPERATION_UNKNOWN_TEXT
                if ((field == "detailed_location" || field.startsWith("visual_landmarks")) && g.locationSentence == null && locationUnknown) {
                    return "PASS_FOLLOW_UP_PARTIAL" to "location not verified for ${a.tier}"
                }
                if (field.startsWith("operation_steps") && g.steps.isEmpty() && operationUnknown) {
                    return "PASS_FOLLOW_UP_PARTIAL" to "operation not verified for ${a.tier}"
                }
                if (any.isNotEmpty() && any.none { a.text.contains(it) }) return "FAIL_CONTENT" to "missing any of $any"
                val ok = when {
                    field == "detailed_location" -> g.locationSentence?.let { a.text.contains(it) } == true
                    field.startsWith("visual_landmarks") -> a.text == RendyAnswerComposer.NOT_FOUND_AGAIN_TEXT
                    field.startsWith("operation_steps") -> g.steps.any { a.text.endsWith(it) } || a.text.startsWith(RendyAnswerComposer.CANNOT_SEE_CAR) ||
                        a.text == RendyAnswerComposer.PARKED_STEPS_TEXT
                    field == "expected_result" -> a.text.startsWith(RendyAnswerComposer.CANNOT_SEE_CAR)
                    else -> true
                }
                val note = when {
                    field == "detailed_location" -> "verified location sentence instead of unverified detailed_location"
                    field.startsWith("visual_landmarks") -> "landmarks unverified -> escalate"
                    else -> field
                }
                return (if (ok) "PASS_FOLLOW_UP" else "FAIL_CONTENT") to note
            }
        }
        // A real intent is expected.
        if (a.source == AnswerSource.CLARIFICATION || a.source == AnswerSource.UNAVAILABLE) return "FAIL_INTENT" to "clarified (${a.reason})"
        val redirectedOk = a.reason.contains("redirect") && kb.route(car, expected) is IntentRoute.Redirect
        if (a.intentId != expected && !redirectedOk) return "FAIL_INTENT" to "got ${a.intentId}"
        if (expect.bool("must_say_no_data") || expect.bool("must_say_not_available_for_model")) {
            return if (a.text.contains("없어요")) "PASS_NO_DATA" to a.reason.substringAfterLast("|") else "FAIL_CONTENT" to "no absence/no-data notice"
        }
        if (expectedButton != null) {
            val canonical = kb.canonicalButtonId(car, expectedButton)
            if (a.buttonId != canonical && a.buttonId != expectedButton) return "FAIL_BUTTON" to "got ${a.buttonId}, expected $canonical"
        }
        val include = expect.list("must_include")
        val includeAny = expect.list("must_include_any")
        val problems = mutableListOf<String>()
        include.filterNot { a.text.contains(it) }.takeIf { it.isNotEmpty() }?.let { problems += "missing $it" }
        if (includeAny.isNotEmpty() && includeAny.none { a.text.contains(it) }) problems += "missing any of $includeAny"
        expect.int("max_sentences")?.let { max -> ButtonGuidance.sentences(a.text).size.takeIf { it > max }?.let { problems += "sentences=$it>$max" } }
        if (expect.bool("must_include_safety_note")) {
            val notes = car.buttons[a.buttonId]?.safetyNotes.orEmpty()
            if (notes.none { a.text.contains(it) }) problems += "no safety note"
        }
        if (expect.bool("must_defer_steps_until_parked")) {
            val steps = car.buttons[a.buttonId]?.operationSteps.orEmpty()
            if (steps.any { a.text.contains(it) }) problems += "steps spoken while driving"
        }
        val policyNote = expect.str("answer_policy")?.let { want ->
            val have = car.buttons[a.buttonId]?.verification?.answerPolicy
            if (want != have) " (suite expects policy $want, DB has $have)" else ""
        } ?: ""
        return if (a.tier == GuidanceTier.FULL) {
            if (problems.isEmpty()) "PASS_VERIFIED" to "ok$policyNote" else "FAIL_CONTENT" to problems.joinToString("; ") + policyNote
        } else if (a.tier?.speakable == true) {
            // Verified field spoken, the other declared unconfirmed.
            when {
                problems.any { it == "steps spoken while driving" } -> "FAIL_SAFETY" to problems.joinToString("; ")
                problems.isEmpty() -> "PASS_PARTIAL" to "tier=${a.tier}$policyNote"
                problems.all { it.startsWith("missing") } -> "PARTIAL_EXPECTS_UNVERIFIED" to "tier=${a.tier}; ${problems.joinToString("; ")}$policyNote"
                else -> "FAIL_CONTENT" to "tier=${a.tier}; ${problems.joinToString("; ")}$policyNote"
            }
        } else {
            val needsContent = problems.any { it.startsWith("missing") }
            when {
                problems.any { it == "steps spoken while driving" } -> "FAIL_SAFETY" to problems.joinToString("; ")
                needsContent -> "BLOCKED_EXPECTS_UNVERIFIED" to "tier=${a.tier}; ${problems.joinToString("; ")}$policyNote"
                // e.g. suite expects hedged_safety (safety note) but the DB marks the button direct_with_caution.
                problems == listOf("no safety note") && policyNote.isNotEmpty() ->
                    "QA_DB_POLICY_MISMATCH" to "tier=${a.tier}$policyNote"
                problems.isNotEmpty() -> "FAIL_CONTENT" to "tier=${a.tier}; ${problems.joinToString("; ")}$policyNote"
                else -> "PASS_BLOCKED_SAFE" to "tier=${a.tier}$policyNote"
            }
        }
    }

    /**
     * A few suite turns name a button ID as `expected_intent` (e.g. battery_reset_12v). Map it to the intent that
     * routes to that button on any model; [NO_INTENT] when none does (e.g. seat_relaxation_comfort).
     */
    private fun resolveExpectedIntent(raw: String): String {
        if (raw.isEmpty() || raw == "CLARIFY" || raw == "DATA_RULE" || raw.startsWith("NOT:") || raw.startsWith("FOLLOW_UP")) return raw
        if (kb.intent(raw) != null) return raw
        val routing = db.intentEnhancements?.modelRouting.orEmpty()
        return kb.intents.firstOrNull { intent ->
            intent.responseKey == raw || routing[intent.intent]?.values?.any { it.button == raw } == true
        }?.intent ?: NO_INTENT
    }

    private companion object {
        const val NO_INTENT = "<no-intent>"
    }

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { !it.isJsonNull }?.asString
    private fun JsonObject.bool(key: String): Boolean = get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == true
    private fun JsonObject.int(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive }?.asInt
    private fun JsonObject.list(key: String): List<String> = get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()
}
