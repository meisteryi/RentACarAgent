package com.hyundai.rentalhelper.intent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pipeline = classifier + per-car routing + verification policy + dialogue. Scripted classifiers are used,
 * so these tests cover routing/safety/dialogue, not language understanding.
 */
class RendyQuestionPipelineTest {
    private val kb = IntentTestFixtures.knowledge
    private val rules = RuleBasedIntentClassifier(kb.intents)
    private val tucson = IntentTestFixtures.car("TUCSON")
    private val ioniq = IntentTestFixtures.car("IONIQ5")
    private val sonata = IntentTestFixtures.car("SONATA")
    private val avante = IntentTestFixtures.car("AVANTE")
    private val casper = IntentTestFixtures.car("CASPER")

    /** Classifier returning whatever [next] is set to (null = UNKNOWN/clarification). */
    private class Scripted : IntentClassifier {
        var next: String? = null
        val contexts = mutableListOf<ClassificationContext>()
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification {
            contexts += context
            val id = next ?: return IntentClassification.Clarification(0.3, "model_requires_clarification")
            return IntentClassification.Classified(id, 0.95)
        }
    }

    private fun failing(reason: String = "proxy_timeout") = object : IntentClassifier {
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext) =
            IntentClassification.Failure(reason)
    }

    private var now = 0L
    private fun pipeline(classifier: IntentClassifier, driving: DrivingState = DrivingState.UNKNOWN, restrict: Set<String> = emptySet()) =
        RendyQuestionPipeline(classifier, rules, kb, contextWindowMs = 60_000, clock = { now }, restrictToIntents = restrict, drivingState = { driving })

    private fun button(car: com.hyundai.rentalhelper.data.HyundaiCarModel, id: String) = car.buttons.getValue(id)

    // ---------------------------------------------------------------- verification policy

    @Test
    fun verifiedButtonAnswerIsTheVerbatimSpokenAnswer() = runBlocking {
        val s = Scripted().apply { next = "electronic_parking_brake" }
        val a = pipeline(s).answer("주차 브레이크 어디 있어?", tucson)
        assertEquals(AnswerSource.LLM, a.source)
        assertEquals(GuidanceTier.FULL, a.tier)
        assertEquals(button(tucson, "electronic_parking_brake").spokenAnswer, a.text)
    }

    @Test
    fun partialButtonNeverSpeaksItsStoredLocationOrSteps() = runBlocking {
        val s = Scripted().apply { next = "lane_follow_assist" }
        val a = pipeline(s).answer("차로 유지 어떻게 켜?", ioniq)
        val b = button(ioniq, "lane_follow_assist")
        assertEquals(GuidanceTier.EXISTS_ONLY, a.tier)
        assertTrue(a.text, a.text.contains("확인 중"))
        ButtonGuidance.sentences(b.spokenAnswer!!).forEach { assertFalse(it, a.text.contains(it)) }
        b.operationSteps!!.forEach { assertFalse(it, a.text.contains(it)) }
        assertFalse(a.text.contains(b.actionGuide))
    }

    @Test
    fun unverifiedSafetyItemAddsItsSafetyNoteAndNoLocation() = runBlocking {
        val s = Scripted().apply { next = "smart_cruise" }
        val a = pipeline(s).answer("크루즈 어떻게 써?", avante)
        val b = button(avante, "smart_cruise")
        assertEquals(GuidanceTier.UNVERIFIED, a.tier)
        assertTrue(a.text.contains(b.safetyNotes!!.first()))
        assertTrue(a.text.contains("세운"))
        assertFalse(a.text.contains(b.spokenAnswer!!))
    }

    @Test
    fun correctedConflictUsesTheCorrectedAnswerOnly() = runBlocking {
        val s = Scripted().apply { next = "smart_cruise" }
        val a = pipeline(s).answer("투싼 크루즈 버튼 오른쪽에 있지?", tucson)
        assertEquals(GuidanceTier.FULL, a.tier)
        assertTrue(a.text.contains("왼쪽"))
        assertFalse(a.text.contains("오른쪽'에 있습니다"))
    }

    @Test
    fun nonEvRedirectAndNoDataAreTruthfulAndModelSpecific() = runBlocking {
        val s = Scripted()
        s.next = "charge_door"
        assertTrue(pipeline(s).answer("충전구 열어줘", sonata).text.contains("전기차가 아니어서"))
        s.next = "fuel_door"
        val redirect = pipeline(s).answer("주유구 어디 있어?", ioniq)
        assertTrue(redirect.text, redirect.text.contains("전기차") && redirect.text.contains("충전"))
        s.next = "sunroof_switch"
        val noData = pipeline(s).answer("선루프 열어줘", casper)
        assertTrue(noData.text, noData.text.contains("정보는 아직 없어요"))
        IntentTestFixtures.database.models.filter { it.modelId != "CASPER" }.forEach { other ->
            other.buttons["sunroof_switch"]?.spokenAnswer?.let { assertFalse(noData.text.contains(it)) }
        }
    }

    @Test
    fun extraAllowlistOnlyNarrowsVerifiedAnswers() = runBlocking {
        val s = Scripted().apply { next = "electronic_parking_brake" }
        val a = pipeline(s, restrict = setOf("hazard_light")).answer("주차 브레이크", tucson)
        assertEquals(GuidanceTier.EXISTS_ONLY, (a.stateUpdate as DialogueStateUpdate.Set).tier)
        assertFalse(a.text.contains(button(tucson, "electronic_parking_brake").spokenAnswer!!))
    }

    // ---------------------------------------------------------------- dialogue

    @Test
    fun fullFollowUpDialogueOnAVerifiedButton() = runBlocking {
        val s = Scripted().apply { next = "electronic_parking_brake" }
        // Detailed guidance (location walk-through, steps) needs a PARKED signal; see detailedGuidanceIsHeldUnlessParked.
        val p = pipeline(s, DrivingState.PARKED)
        val b = button(tucson, "electronic_parking_brake")
        val g = kb.guidance(tucson, "electronic_parking_brake")!!

        assertEquals(b.spokenAnswer, p.answerAndCommit("주차 브레이크 어디 있어?", tucson).text)
        val nf1 = p.answerAndCommit("못 찾겠어", tucson)
        assertEquals(AnswerSource.FOLLOW_UP, nf1.source)
        assertTrue(nf1.text.startsWith(g.locationSentence!!))
        assertTrue(nf1.text.endsWith(RendyDialogueText.FOUND_QUESTION))
        assertFalse("unverified detailed_location must not be read", nf1.text.contains(b.detailedLocation!!))
        val nf2 = p.answerAndCommit("아직도 안 보여", tucson)
        assertEquals(RendyAnswerComposer.NOT_FOUND_AGAIN_TEXT, nf2.text)
        val step1 = p.answerAndCommit("찾았어", tucson)
        assertEquals(b.operationSteps!![0], step1.text)
        assertTrue(p.currentState()!!.userConfirmedFound)
        val after = p.answerAndCommit("그다음은?", tucson)
        assertEquals("${RendyAnswerComposer.CANNOT_SEE_CAR} ${b.expectedResult}", after.text)
        val done = p.answerAndCommit("됐어?", tucson)
        assertTrue(done.text.startsWith(RendyAnswerComposer.CANNOT_SEE_CAR))
        listOf("됐어요", "완료됐", "성공").forEach { assertFalse(done.text.contains(it)) }
        assertEquals(1, s.contexts.size) // follow-ups never call the classifier
    }

    @Test
    fun stepsAreReadOnePerTurn() = runBlocking {
        val s = Scripted().apply { next = "window_lock" }
        val p = pipeline(s, DrivingState.PARKED)
        val steps = button(ioniq, "window_lock").operationSteps!!
        assertEquals(2, steps.size)
        p.answerAndCommit("창문 잠금 어디야", ioniq)
        assertEquals(steps[0], p.answerAndCommit("찾았어", ioniq).text)
        assertEquals(steps[1], p.answerAndCommit("그다음은?", ioniq).text)
        assertTrue(p.answerAndCommit("다음은?", ioniq).text.startsWith(RendyAnswerComposer.CANNOT_SEE_CAR))
    }

    @Test
    fun parkedOnlyControlDefersStepsWhileDriving() = runBlocking {
        val s = Scripted().apply { next = "seat_adjustment" }
        val driving = pipeline(s, DrivingState.DRIVING)
        val first = driving.answerAndCommit("시트 앞으로 당기고 싶어", casper)
        val g = kb.guidance(casper, "seat_adjustment")!!
        // Location only + the DB's own stop instruction ("시트 조절은 정차 중에 하세요.").
        assertEquals("${g.locationSentence} ${g.safetyNote()}", first.text)
        assertFalse(g.steps.any { first.text.contains(it) })
        // Steps are held until a PARKED signal (was PARKED_STEPS_TEXT before the dialogue manager).
        assertEquals(RendyDialogueText.HOLD_DRIVING, driving.answerAndCommit("찾았어", casper).text)

        val unknown = pipeline(s, DrivingState.UNKNOWN)
        unknown.answerAndCommit("시트 조절", casper)
        assertEquals(RendyDialogueText.HOLD_UNKNOWN, unknown.answerAndCommit("찾았어", casper).text)

        val parked = pipeline(s, DrivingState.PARKED)
        parked.answerAndCommit("시트 조절", casper)
        assertEquals(g.steps[0], parked.answerAndCommit("찾았어", casper).text)
    }

    @Test
    fun driverSaysTheyAreDrivingWithoutAVehicleSignal() = runBlocking {
        val s = Scripted().apply { next = "side_mirror_adjust" }
        val a = pipeline(s).answer("달리면서 사이드미러 조절해도 돼?", tucson)
        assertTrue(a.text, a.text.endsWith(RendyAnswerComposer.PARK_FIRST_NOTICE))
        assertFalse(a.text.contains(button(tucson, "side_mirror_adjust").operationSteps!![0]))
    }

    @Test
    fun followUpsOnAnUnverifiedControlEscalate() = runBlocking {
        val s = Scripted().apply { next = "lane_follow_assist" }
        val p = pipeline(s)
        p.answerAndCommit("차로 유지", ioniq)
        listOf("못 찾겠어", "찾았어", "됐어?").forEach { assertEquals(RendyAnswerComposer.ESCALATE_TEXT, p.answerAndCommit(it, ioniq).text) }
    }

    @Test
    fun contextResetsOnTimeoutCarChangeCancelAndNewTopic() = runBlocking {
        val s = Scripted().apply { next = "electronic_parking_brake" }
        val p = pipeline(s)
        assertEquals(RendyAnswerComposer.NO_CONTEXT_TEXT, p.answerAndCommit("찾았어", tucson).text)

        p.answerAndCommit("주차 브레이크", tucson)
        now += 61_000
        assertEquals(RendyAnswerComposer.NO_CONTEXT_TEXT, p.answer("찾았어", tucson).text)

        p.answerAndCommit("주차 브레이크", tucson)
        assertEquals(RendyAnswerComposer.NO_CONTEXT_TEXT, p.answer("찾았어", sonata).text)

        assertEquals(RendyAnswerComposer.CANCEL_TEXT, p.answerAndCommit("그만", tucson).text)
        assertNull(p.currentState())

        p.answerAndCommit("주차 브레이크", tucson)
        p.answerAndCommit("찾았어", tucson)
        s.next = "esc_off"
        p.answerAndCommit("ESC 버튼 어디야", tucson)
        assertEquals("esc_off", p.currentState()!!.intentId)
        assertEquals(-1, p.currentState()!!.stepIndex)

        p.resetContext()
        assertNull(p.currentState())
    }

    @Test
    fun uncommittedAnswerDoesNotChangeTheConversation() = runBlocking {
        val s = Scripted().apply { next = "electronic_parking_brake" }
        val p = pipeline(s, DrivingState.PARKED)
        p.answerAndCommit("주차 브레이크", tucson)
        p.answer("찾았어", tucson) // computed but superseded (never delivered) -> not committed
        assertEquals(-1, p.currentState()!!.stepIndex)
        assertEquals(button(tucson, "electronic_parking_brake").operationSteps!![0], p.answerAndCommit("찾았어", tucson).text)
    }

    @Test
    fun repeatSaysTheLastAnswerAgain() = runBlocking {
        val s = Scripted().apply { next = "electronic_parking_brake" }
        val p = pipeline(s)
        val first = p.answerAndCommit("주차 브레이크", tucson)
        assertEquals(first.text, p.answerAndCommit("다시 말해줘", tucson).text)
    }

    @Test
    fun pronounFollowUpPassesPreviousIntentToClassifier() = runBlocking {
        val mock = FixtureMockClassifier(IntentTestFixtures.cases)
        val p = pipeline(mock)
        assertEquals(AnswerSource.CLARIFICATION, p.answerAndCommit("이거 어떻게 꺼?", ioniq).source)
        assertEquals("seat_vent_heat", p.answerAndCommit("시트 따뜻하게 하려면?", ioniq).intentId)
        now += 30_000
        assertEquals("seat_vent_heat", p.answerAndCommit("이거 어떻게 꺼?", ioniq).intentId)
        assertEquals("seat_vent_heat", mock.seenContexts.last().previousIntentId)
    }

    // ---------------------------------------------------------------- clarification & fallback

    @Test
    fun clarificationUsesTheDbQuestionWhenOneApplies() = runBlocking {
        val p = pipeline(Scripted())
        assertEquals("시트 열선, 핸들 열선, 뒷유리 열선 중 어느 걸 말씀하세요?", p.answer("열선 켜줘", tucson).text)
        assertEquals("앞유리인가요, 뒷유리인가요?", p.answer("습기가 차", tucson).text)
        assertEquals(RendyQuestionPipeline.CLARIFICATION_TEXT, p.answer("저거 좀 켜줘", tucson).text)
    }

    @Test
    fun dbClarificationRuleOverridesAConfidentClassifierOnBareAmbiguousPhrases() = runBlocking {
        val s = Scripted()
        val p = pipeline(s)
        s.next = "light_stalk"
        assertEquals("방향지시등(좌·우 깜빡이)인가요, 아니면 비상등인가요?", p.answer("깜빡이 켜는 법 알려줘", tucson).text)
        s.next = "electronic_parking_brake"
        assertEquals("주차 브레이크인가요, 신호 대기용 오토홀드인가요?", p.answer("브레이크 어떻게 해?", tucson).text)
        s.next = "gear_type"
        assertEquals(AnswerSource.CLARIFICATION, p.answer("차가 안 움직여", ioniq).source)
        // A disambiguating word keeps the classifier's answer.
        s.next = "electronic_parking_brake"
        assertEquals(AnswerSource.LLM, p.answer("주차 브레이크 어떻게 해?", tucson).source)
        s.next = "seat_vent_heat"
        assertEquals("seat_vent_heat", p.answer("시트 열선 켜줘", tucson).intentId)
        // Pronoun follow-up with context is not turned into "어떤 기능을 끄고 싶으세요?".
        s.next = "electronic_parking_brake"
        p.answerAndCommit("주차 브레이크", tucson)
        assertEquals("electronic_parking_brake", p.answer("이거 어떻게 꺼?", tucson).intentId)
        p.resetContext()
        assertEquals("어떤 기능을 끄고 싶으세요?", p.answer("어떻게 꺼?", tucson).text)
    }

    @Test
    fun classifierFailureFallsBackToRulesUnderTheSamePolicy() = runBlocking {
        val p = pipeline(failing())
        val hit = p.answer("주차 브레이크 어디 있어?", tucson)
        assertEquals(AnswerSource.RULE_FALLBACK, hit.source)
        assertEquals(button(tucson, "electronic_parking_brake").spokenAnswer, hit.text)
        assertTrue(hit.reason.startsWith("classifier_failed:proxy_timeout"))

        val partial = p.answer("와이퍼 어떻게 켜?", ioniq)
        assertEquals(AnswerSource.RULE_FALLBACK, partial.source)
        assertFalse(partial.text.contains(button(ioniq, "wiper_stalk").spokenAnswer!!))

        val none = p.answer("타이어 공기압 수치를 알려줘", ioniq)
        assertFalse(none.text.contains("센터페시아"))
        assertEquals("방향지시등(좌·우 깜빡이)인가요, 아니면 비상등인가요?", p.answer("깜빡이 어떻게 켜?", sonata).text)
    }

    @Test
    fun classifierExceptionIsTreatedAsFailure() = runBlocking {
        val throwing = object : IntentClassifier {
            override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification =
                throw IllegalStateException("boom")
        }
        assertEquals(AnswerSource.RULE_FALLBACK, pipeline(throwing).answer("비상등", tucson).source)
    }

    @Test
    fun rulesOnlySyncPathNeverReturnsTheLegacyGenericGuess() {
        for (q in listOf("타이어 공기압", "아무 말", "", "블랙박스 카메라 어디 있어")) {
            val a = RendyQuestionPipeline.answerWithRulesOnce(kb, ioniq, q)
            assertFalse(a.text, a.text.contains("센터페시아 조작부 또는 핸들 버튼"))
        }
        assertEquals(button(tucson, "electronic_parking_brake").spokenAnswer,
            RendyQuestionPipeline.answerWithRulesOnce(kb, tucson, "주차 브레이크 어디 있어?").text)
    }

    // ---- Coordinator: stale responses and duplicate TTS ----

    private val executor = Executors.newSingleThreadExecutor()
    private val mainLike = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + mainLike)

    @After
    fun tearDown() {
        scope.cancel()
        executor.shutdownNow()
    }

    private fun answer(id: String) = VoiceAnswer(id, id, AnswerSource.LLM, id, "test")

    @Test
    fun staleAnswerIsDroppedAndNewestDeliveredExactlyOnce() = runBlocking {
        val delivered = mutableListOf<String>()
        val done = CountDownLatch(1)
        val firstGate = CompletableDeferred<Unit>()
        val coordinator = RendyQuestionCoordinator(scope)
        withContext(mainLike) {
            coordinator.submit({ firstGate.await(); answer("old") }) { _, a -> delivered += a.title }
            coordinator.submit({ answer("new") }) { _, a -> delivered += a.title; done.countDown() }
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        firstGate.complete(Unit)
        withContext(mainLike) { } // drain the main-like queue
        Thread.sleep(50)
        withContext(mainLike) { assertEquals(listOf("new"), delivered.toList()) }
    }

    @Test
    fun newSttSessionCancelsPendingAnswerButKeepsConversation() = runBlocking {
        val s = Scripted().apply { next = "electronic_parking_brake" }
        val p = pipeline(s)
        p.answerAndCommit("주차 브레이크", tucson)

        val delivered = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val coordinator = RendyQuestionCoordinator(scope)
        withContext(mainLike) {
            coordinator.submit({ gate.await(); p.answer("찾았어", tucson) }) { id, a -> p.commit(a, id); delivered += a.text }
            assertTrue(coordinator.cancelPending()) // new STT session
        }
        gate.complete(Unit)
        Thread.sleep(50)
        withContext(mainLike) {
            assertTrue(delivered.isEmpty())
            assertTrue(!coordinator.cancelPending())
        }
        // The late answer was dropped and did not advance the steps; the topic is still active.
        assertEquals("electronic_parking_brake", p.currentState()!!.intentId)
        assertEquals(-1, p.currentState()!!.stepIndex)
    }

    @Test
    fun repeatedSubmitsEachDeliverOnceWhenNotSuperseded() = runBlocking {
        val delivered = mutableListOf<Long>()
        val coordinator = RendyQuestionCoordinator(scope)
        repeat(5) {
            val latch = CountDownLatch(1)
            withContext(mainLike) { coordinator.submit({ answer("a") }) { id, _ -> delivered += id; latch.countDown() } }
            assertTrue(latch.await(2, TimeUnit.SECONDS))
        }
        Thread.sleep(50)
        withContext(mainLike) { assertEquals(listOf(1L, 2L, 3L, 4L, 5L), delivered.toList()) }
    }
}
