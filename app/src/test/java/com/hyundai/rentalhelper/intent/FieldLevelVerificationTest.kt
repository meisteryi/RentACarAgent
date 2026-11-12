package com.hyundai.rentalhelper.intent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Field-level verification (DB flags + official-source overlay): what is spoken, and that nothing else is. */
class FieldLevelVerificationTest {
    private val db = IntentTestFixtures.database
    private val kb = IntentTestFixtures.knowledge
    private val before = IntentTestFixtures.knowledgeDbOnly
    private val ioniq = IntentTestFixtures.car("IONIQ5")
    private val tucson = IntentTestFixtures.car("TUCSON")
    private val casper = IntentTestFixtures.car("CASPER")

    private fun pipeline(intent: String, knowledge: RendyKnowledgeBase = kb) = RendyQuestionPipeline(
        object : IntentClassifier {
            override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext) =
                IntentClassification.Classified(intent, 0.95)
        }, RuleBasedIntentClassifier(knowledge.intents), knowledge
    )

    private val notices = listOf("확인 중이라", "확인된 안내가 없어서")

    @Test
    fun ioniq5LightBeforeAndAfter() = runBlocking {
        val old = pipeline("light_stalk", before).answer("라이트 어디 있어?", ioniq)
        assertEquals(GuidanceTier.EXISTS_ONLY, old.tier)
        assertTrue(old.text.contains("확인 중이라"))

        val p = pipeline("light_stalk")
        val a = p.answerAndCommit("라이트 어디 있어?", ioniq)
        assertEquals(GuidanceTier.OPERATION_ONLY, a.tier)
        assertTrue(a.text, a.text.contains("조명 스위치 끝을 잡고 돌려서"))
        assertTrue(a.text.endsWith(ButtonGuidance.NOTICE_LOCATION_UNCONFIRMED))
        // The DB sentence has an unverified position ("운전대 왼쪽 뒤") and must not be read.
        assertFalse(a.text.contains("운전대 왼쪽 뒤"))
        assertEquals(RendyAnswerComposer.LOCATION_UNKNOWN_TEXT, p.answerAndCommit("못 찾겠어", ioniq).text)
        val steps = kb.overlayFor(ioniq, "light_stalk")!!.operation!!.steps!!
        assertEquals(steps[0], p.answerAndCommit("찾았어", ioniq).text)
        assertEquals(steps[1], p.answerAndCommit("그다음은?", ioniq).text)
        assertTrue(p.answerAndCommit("그다음은?", ioniq).text.startsWith(RendyAnswerComposer.CANNOT_SEE_CAR))
    }

    @Test
    fun overlayLocationAndOperationGiveAFullAnswer() = runBlocking {
        val a = pipeline("light_stalk").answerAndCommit("라이트 어디 있어?", tucson)
        assertEquals(GuidanceTier.FULL, a.tier)
        assertTrue(a.text, a.text.startsWith("라이트는 운전대 왼쪽에 있는 조명 스위치예요."))
        assertFalse(a.text.contains("확인되지 않았어요"))
        val hazard = pipeline("hazard_light").answer("비상등 어디야?", tucson)
        assertTrue(hazard.text.contains("삼각형") && hazard.text.contains("센터페시아"))
    }

    @Test
    fun locationOnlyAnswersLocationAndSaysOperationIsUnconfirmed() = runBlocking {
        val p = pipeline("front_defog")
        val a = p.answerAndCommit("앞유리 김 서렸어", ioniq)
        assertEquals(GuidanceTier.LOCATION_ONLY, a.tier)
        assertEquals("앞유리 서리 제거 버튼은 히터·에어컨 조작부에 있어요. ${ButtonGuidance.NOTICE_OPERATION_UNCONFIRMED}", a.text)
        assertEquals(RendyAnswerComposer.OPERATION_UNKNOWN_TEXT, p.answerAndCommit("찾았어", ioniq).text)
    }

    @Test
    fun operationWithComponentLocationNamesThePanelOnly() = runBlocking {
        val a = pipeline("air_recirculation").answer("외기 차단 어떻게 해", casper)
        assertEquals(GuidanceTier.OPERATION_ONLY, a.tier)
        assertTrue(a.text, a.text.startsWith("내기·외기 선택 버튼은 히터·에어컨 조작부에 있어요."))
        assertTrue(a.text.endsWith(ButtonGuidance.NOTICE_EXACT_POSITION_UNCONFIRMED))
    }

    @Test
    fun parkedOnlyControlWithoutVerifiedLocationDefersEverythingWhileDriving() = runBlocking {
        val p = RendyQuestionPipeline(object : IntentClassifier {
            override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext) =
                IntentClassification.Classified("hood_open", 0.95)
        }, RuleBasedIntentClassifier(kb.intents), kb, drivingState = { DrivingState.DRIVING })
        val a = p.answerAndCommit("보닛 열어야 해", ioniq)
        assertEquals(GuidanceTier.OPERATION_ONLY, a.tier)
        kb.overlayFor(ioniq, "hood_open")!!.operation!!.steps!!.forEach { assertFalse(a.text.contains(it)) }
        assertTrue(a.text.contains(RendyAnswerComposer.PARKED_STEPS_TEXT))
    }

    @Test
    fun overlayIsValidSourcedAndNeverUsesOverseasOrForeignData() {
        assertTrue(kb.rejectedOverlayEntries.toString(), kb.rejectedOverlayEntries.isEmpty())
        val entries = IntentTestFixtures.overlay.entries!!
        assertTrue(entries.size >= 50)
        entries.forEach { e ->
            assertFalse("${e.model}.${e.button}: Avante has only US-manual evidence", e.model == "AVANTE")
            listOfNotNull(e.location, e.operation, e.appearance).forEach { f ->
                val source = f.source!!
                assertTrue("${e.model}.${e.button} source", source.contains("hyundai.com"))
                assertFalse("${e.model}.${e.button} overseas source", source.contains("en_us", ignoreCase = true) || source.contains("en_GN"))
                assertTrue("${e.model}.${e.button} evidence", f.evidence!!.length >= 10)
                assertTrue("${e.model}.${e.button} text <=2 sentences: ${f.text}", ButtonGuidance.sentences(f.text!!).size <= 2)
            }
            // DB-verified buttons keep the DB answer; the overlay never targets them.
            val g = kb.guidance(IntentTestFixtures.car(e.model!!), e.button!!)!!
            assertNotNull("${e.model}.${e.button} overlay unused", g.overlay)
        }
    }

    /**
     * Core features on every car: route to the car's own button; never refuse when a field is verified;
     * never read unverified/other-model text. Writes a before/after table.
     */
    @Test
    fun coreFeaturesAnswerWhenVerifiedAndNeverLeak() = runBlocking {
        val core = listOf("light_stalk", "wiper_stalk", "hazard_light", "gear_type", "electronic_parking_brake", "front_defog",
            "rear_defog", "window_lock", "side_mirror_adjust", "side_mirror_fold", "seat_vent_heat", "heated_steering_wheel", "drive_mode")
        val rows = mutableListOf("model\tintent\tbutton\ttier_before\ttier_after\ttext_after")
        val problems = mutableListOf<String>()
        for (car in db.models) {
            val oracle = SpokenTextOracle(car, kb, db)
            for (intent in core) {
                val beforeAnswer = pipeline(intent, before).answer("질문", car)
                val a = pipeline(intent).answer("질문", car)
                rows += listOf(car.modelId, intent, a.buttonId ?: "", beforeAnswer.tier ?: "-", a.tier ?: "-", a.text).joinToString("\t")
                oracle.violation(a.text)?.let { problems += "${car.modelId}/$intent leak: $it" }
                if (a.tier?.speakable == true && notices.any { a.text.contains(it) }) problems += "${car.modelId}/$intent refused although ${a.tier}"
                if (a.buttonId != null && a.buttonId !in car.buttons) problems += "${car.modelId}/$intent foreign button ${a.buttonId}"
            }
        }
        IntentTestFixtures.reportDir().resolve("core_features_before_after.tsv").writeText(rows.joinToString("\n"), Charsets.UTF_8)
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun answerabilityCountsPerModelReport() {
        val lines = mutableListOf("model\tknowledge\tFULL\tLOCATION_ONLY\tOPERATION_ONLY\tEXISTS_ONLY\tUNVERIFIED\tNOT_APPLICABLE")
        for ((label, k) in listOf("before" to before, "after" to kb)) {
            for (car in db.models) {
                val c = car.buttons.keys.groupingBy { k.guidance(car, it)!!.tier }.eachCount()
                lines += (listOf(car.modelId, label) + GuidanceTier.values().map { (c[it] ?: 0).toString() }).joinToString("\t")
            }
        }
        IntentTestFixtures.reportDir().resolve("answerability_by_model.tsv").writeText(lines.joinToString("\n"), Charsets.UTF_8)
        val total = { k: RendyKnowledgeBase -> db.models.sumOf { car -> car.buttons.keys.count { k.guidance(car, it)!!.tier.speakable } } }
        assertEquals(19, total(before))
        assertTrue("overlay must add speakable buttons", total(kb) > 19)
    }
}
