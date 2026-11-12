package com.hyundai.rentalhelper.intent

import com.google.gson.Gson
import com.hyundai.rentalhelper.data.HyundaiCarModel
import com.hyundai.rentalhelper.data.HyundaiDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DB v4.2 integration: parsing, references, verification tiers, and an exhaustive no-unverified-speech sweep. */
class RendyDbIntegrityTest {
    private val db = IntentTestFixtures.database
    private val kb = IntentTestFixtures.knowledge

    @Test
    fun v42StructureIsPreservedAndParsed() {
        assertEquals("4.2.0-final", db.version)
        assertEquals(listOf("IONIQ5", "TUCSON", "SONATA", "AVANTE", "CASPER"), db.models.map { it.modelId })
        assertEquals(listOf(50, 50, 50, 44, 43), db.models.map { it.buttons.size })
        assertEquals(237, db.models.sumOf { it.buttons.size })
        assertEquals(33, db.faqIntents.size)
        val enh = assertNotNullAndGet(db.intentEnhancements)
        assertEquals(18, enh.proposedNewIntents!!.size)
        assertEquals(20, enh.clarificationRules!!.size)
        assertEquals(33, enh.modelRouting!!.size)
        assertEquals(10, enh.keywordRisks!!.size)
        assertEquals(setOf("not_found", "found", "done_check", "repeat", "cancel"), enh.followUpPhrases!!.keys)
        assertTrue(kb.rejectedProposals.toString(), kb.rejectedProposals.isEmpty())
        assertEquals(51, kb.intents.size)
        assertEquals(kb.intents.size, kb.intents.map { it.intent }.toSet().size)
        db.models.forEach { m -> m.buttons.forEach { (id, b) ->
            assertNotNull("${m.modelId}.$id verification", b.verification?.status)
            assertNotNull("${m.modelId}.$id spoken_answer", b.spokenAnswer)
        } }
    }

    @Test
    fun faqIntentsAndIdsAreIdenticalToV40() {
        val v40 = loadV40()
        assertEquals(v40.faqIntents, db.faqIntents)
        v40.models.zip(db.models).forEach { (old, new) ->
            assertEquals(old.modelId, new.modelId)
            assertEquals(old.buttons.keys, new.buttons.keys)
            old.buttons.forEach { (id, b) ->
                assertEquals("${old.modelId}.$id name", b.name, new.buttons.getValue(id).name)
                assertEquals("${old.modelId}.$id zone", b.zone, new.buttons.getValue(id).zone)
            }
        }
    }

    @Test
    fun verificationStatusCountsMatchTheReport() {
        val counts = db.models.flatMap { it.buttons.values }.groupingBy { it.verification!!.status!! }.eachCount()
        assertEquals(mapOf("VERIFIED" to 9, "PARTIAL" to 145, "CONFLICT" to 3, "UNVERIFIED" to 79, "NOT_APPLICABLE" to 1), counts)
    }

    @Test
    fun routingReferencesAreValidAndAcyclic() {
        val actions = setOf("button", "fallback", "redirect", "no_data", "not_applicable_non_ev")
        val routing = db.intentEnhancements!!.modelRouting!!
        db.faqIntents.forEach { intent -> assertTrue("routing for ${intent.intent}", routing.containsKey(intent.intent)) }
        routing.forEach { (intentId, perModel) ->
            assertTrue("routing key $intentId is an intent", kb.intent(intentId) != null)
            assertEquals("$intentId covers every model", db.models.map { it.modelId }.toSet(), perModel.keys)
            perModel.forEach { (modelId, r) ->
                val car = IntentTestFixtures.car(modelId)
                assertTrue("$intentId/$modelId action ${r.action}", r.action in actions)
                if (r.action in setOf("button", "fallback", "redirect")) assertTrue("$intentId/$modelId button ${r.button}", r.button in car.buttons)
                if (r.action == "redirect") assertTrue("$intentId/$modelId to ${r.to}", r.to in car.buttons)
            }
        }
        db.models.forEach { car -> car.buttons.forEach { (id, b) ->
            listOfNotNull(b.redirectTo, b.duplicateOf).forEach { assertTrue("${car.modelId}.$id -> $it", it in car.buttons) }
            assertTrue(kb.canonicalButtonId(car, id) in car.buttons)
        } }
        db.intentEnhancements!!.clarificationRules!!.forEach { r ->
            r.candidates.orEmpty().forEach { c -> assertTrue("clarification ${r.phrase} -> $c", db.models.any { c in it.buttons }) }
        }
    }

    @Test
    fun tierCountsAndPerModelIntentOutcomesReport() {
        val rows = mutableListOf("model\tbutton\tstatus\tloc\top\tpolicy\ttier")
        val tiers = mutableMapOf<String, MutableMap<GuidanceTier, Int>>()
        // DB verification flags only; the overlay's effect is reported by FieldLevelVerificationTest.
        val dbOnly = IntentTestFixtures.knowledgeDbOnly
        db.models.forEach { car -> car.buttons.keys.forEach { id ->
            val g = dbOnly.guidance(car, id)!!
            val v = g.button.verification!!
            tiers.getOrPut(car.modelId) { mutableMapOf() }.merge(g.tier, 1, Int::plus)
            rows += listOf(car.modelId, id, v.status, v.locationVerified, v.operationVerified, v.answerPolicy, g.tier).joinToString("\t")
        } }
        val total = tiers.values.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        // 9 VERIFIED + 8 PARTIAL(loc&op) + 2 CONFLICT(loc&op) = 19 buttons with speakable location+operation.
        assertEquals(19, total[GuidanceTier.FULL])
        assertEquals(1, total[GuidanceTier.NOT_APPLICABLE])
        assertEquals(237, total.values.sum())

        val intentRows = mutableListOf("model\tintent\troute\tbutton\ttier\toutcome")
        val outcomes = mutableMapOf<String, MutableMap<String, Int>>()
        db.models.forEach { car -> kb.intents.forEach { intent ->
            val route = kb.route(car, intent.intent)
            val a = RendyAnswerComposer.initial(kb, car, route, DrivingState.UNKNOWN, AnswerSource.LLM, "report")
            val outcome = when {
                route is IntentRoute.NoData -> "no_data"
                route is IntentRoute.NotApplicableNonEv -> "not_applicable"
                route is IntentRoute.Redirect -> "redirect(${a.tier ?: "-"})"
                a.tier == GuidanceTier.FULL -> "verified_answer"
                a.tier == GuidanceTier.LOCATION_ONLY -> "partial_location"
                a.tier == GuidanceTier.OPERATION_ONLY -> "partial_operation"
                a.tier == GuidanceTier.EXISTS_ONLY -> "exists_only_blocked"
                a.tier == GuidanceTier.NOT_APPLICABLE -> "not_applicable"
                else -> "unverified_blocked"
            }
            outcomes.getOrPut(car.modelId) { sortedMapOf() }.merge(outcome, 1, Int::plus)
            intentRows += listOf(car.modelId, intent.intent, route.javaClass.simpleName, a.buttonId ?: "", a.tier ?: "", outcome).joinToString("\t")
        } }
        val dir = IntentTestFixtures.reportDir()
        dir.resolve("button_tiers.tsv").writeText(rows.joinToString("\n"), Charsets.UTF_8)
        dir.resolve("intent_outcomes_by_model.tsv").writeText(intentRows.joinToString("\n"), Charsets.UTF_8)
        dir.resolve("tier_summary.txt").writeText(buildString {
            appendLine("button tiers per model: $tiers")
            appendLine("button tiers total: $total")
            appendLine("intent outcomes per model (51 intents each): $outcomes")
        }, Charsets.UTF_8)
    }

    @Test
    fun v40DatabaseStillLoadsAndNeverSpeaksUnverifiedText() {
        val v40 = loadV40()
        val kb40 = RendyKnowledgeBase(v40)
        assertEquals(33, kb40.intents.size)
        val car = v40.models.first { it.modelId == "TUCSON" }
        car.buttons.keys.forEach { assertEquals(GuidanceTier.UNVERIFIED, kb40.guidance(car, it)!!.tier) }
        val a = RendyQuestionPipeline.answerWithRulesOnce(kb40, car, "주차 브레이크 어디 있어?")
        assertFalse(a.text.contains(car.buttons.getValue("electronic_parking_brake").actionGuide))
        assertEquals(RendyAnswerComposer.NO_CONTEXT_TEXT, RendyAnswerComposer.followUp(kb40, car, FollowUpKind.FOUND, null, DrivingState.UNKNOWN).text)
    }

    /**
     * Exhaustive sweep: every car x every intent x driving state x a full follow-up sequence. Each spoken text is
     * checked by an oracle that does not use the composer: no sentence from a non-FULL button of this car, no text
     * of another model, no other model's name.
     */
    @Test
    fun noPathSpeaksUnverifiedOrOtherModelText() = runBlocking {
        val sequence = listOf("못 찾겠어", "아직도 안 보여", "찾았어", "그다음은?", "그다음은?", "그다음은?", "됐어?", "다시 말해줘", "그만")
        var checked = 0
        val violations = mutableListOf<String>()
        for (car in db.models) {
            val oracle = SpokenTextOracle(car, kb, db)
            for (intent in kb.intents) {
                for (driving in DrivingState.values()) {
                    val scripted = object : IntentClassifier {
                        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext) =
                            IntentClassification.Classified(intent.intent, 0.99)
                    }
                    val p = RendyQuestionPipeline(scripted, scripted, kb, drivingState = { driving })
                    (listOf("질문") + sequence).forEach { q ->
                        val a = p.answerAndCommit(q, car)
                        checked++
                        oracle.violation(a.text)?.let { violations += "${car.modelId}/${intent.intent}/$driving/'$q': $it -> ${a.text}" }
                        if (driving == DrivingState.DRIVING && q == "찾았어" && a.tier == GuidanceTier.FULL) {
                            val g = kb.guidance(car, a.buttonId!!)!!
                            if (g.requiresParked && a.text != RendyAnswerComposer.PARKED_STEPS_TEXT) violations += "${car.modelId}/${intent.intent}: steps read while driving"
                        }
                    }
                }
            }
        }
        IntentTestFixtures.reportDir().resolve("safety_sweep.txt").writeText("checked=$checked violations=${violations.size}\n" + violations.joinToString("\n"), Charsets.UTF_8)
        assertTrue("checked=$checked\n" + violations.take(20).joinToString("\n"), violations.isEmpty())
    }

    private fun loadV40(): HyundaiDatabase =
        RendyDbIntegrityTest::class.java.getResourceAsStream("/hyundai_buttons_db_v4.0.0.json")!!
            .bufferedReader(Charsets.UTF_8).use { Gson().fromJson(it, HyundaiDatabase::class.java) }

    private fun <T> assertNotNullAndGet(value: T?): T { assertNotNull(value); return value!! }
}
