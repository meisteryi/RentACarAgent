package com.hyundai.rentalhelper.intent

import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dialogue manager = follow-up acts + conversation state + parked-only detailed guidance.
 * The turn classifier is SCRIPTED here (the test sets the act), so these tests cover the app-side policy and state,
 * not language understanding; that is measured against the real model in [DialogueActEvaluationTest].
 */
class RendyDialogueManagerTest {
    private val kb = IntentTestFixtures.knowledge
    private val rules = RuleBasedIntentClassifier(kb.intents)
    private val tucson = IntentTestFixtures.car("TUCSON")
    private val sonata = IntentTestFixtures.car("SONATA")

    /** Question classifier; counts calls so "one LLM call per turn" can be checked. */
    private class Questions(var next: String?) : IntentClassifier {
        var calls = 0
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification {
            calls++
            return next?.let { IntentClassification.Classified(it, 0.95) } ?: IntentClassification.Clarification(0.3, "unknown")
        }
    }

    /** Turn classifier returning the scripted act; records the context it was given. */
    private class Turns : DialogueTurnClassifier {
        var next: TurnClassification = TurnClassification.Act(DialogueAct.UNCLEAR, null, 0.9, intentResolved = true)
        val contexts = mutableListOf<TurnContext>()
        val queries = mutableListOf<String>()
        fun act(a: DialogueAct, intent: String? = null) {
            next = TurnClassification.Act(a, intent, 0.9, intentResolved = true)
        }
        override suspend fun classifyTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext): TurnClassification {
            contexts += context
            queries += query
            return next
        }
    }

    private var now = 0L
    private var motion = DrivingState.DRIVING
    private val questions = Questions("smart_cruise")
    private val turns = Turns()
    private fun pipeline(turnClassifier: DialogueTurnClassifier = turns) = RendyQuestionPipeline(
        questions, rules, kb, contextWindowMs = 60_000, clock = { now }, drivingState = { motion },
        turnClassifier = turnClassifier, holdWindowMs = 30 * 60_000L
    )

    private val cruise = kb.guidance(tucson, "smart_cruise")!!

    private suspend fun RendyQuestionPipeline.say(text: String, act: DialogueAct, intent: String? = null): VoiceAnswer {
        turns.act(act, intent)
        return answerAndCommit(text, tucson)
    }

    // ---------------------------------------------------------------- scenes A-D (README demo script)

    @Test
    fun scenesAtoDCruiseControlWhileDrivingThenParked() = runBlocking {
        assertEquals("demo feature must be verified for TUCSON", GuidanceTier.FULL, cruise.tier)
        assertEquals(2, cruise.steps.size)
        val p = pipeline()

        // A. First question: DB answer (no follow-up classification without context).
        val first = p.answerAndCommit("렌디, 크루즈 컨트롤 어떻게 켜?", tucson)
        assertEquals("smart_cruise", first.intentId)
        assertTrue(first.text, first.text.startsWith(cruise.spokenAnswer!!))
        assertEquals(0, turns.contexts.size)
        assertEquals(1, questions.calls)

        // B. "어? 뭐라고?" -> REPEAT: exactly the same characters, no regeneration, no question classifier call.
        val again = p.say("어? 뭐라고?", DialogueAct.REPEAT)
        assertEquals(first.text, again.text)
        assertFalse(again.repeatable)
        assertEquals(1, questions.calls)
        val ctxB = turns.contexts.last()
        assertEquals(first.text, ctxB.lastRendyUtterance)
        assertFalse(ctxB.rendyAskedQuestion)
        assertEquals("smart_cruise", ctxB.currentIntentId)
        assertEquals(DialogueStage.ANSWERED, ctxB.stage)
        assertEquals("렌디, 크루즈 컨트롤 어떻게 켜?", ctxB.lastDriverQuestion)
        assertEquals(DrivingState.DRIVING, ctxB.drivingState)
        assertEquals(tucson.modelNameKo, ctxB.carModelName)
        // A second, differently worded repeat still says the original answer.
        assertEquals(first.text, p.say("응? 다시", DialogueAct.REPEAT).text)

        // C. "어디 있는지 모르겠는데?" while DRIVING -> hold, feature and stage remembered.
        val held = p.say("어디 있는지 모르겠는데?", DialogueAct.NEED_DETAIL)
        assertEquals(RendyDialogueText.HOLD_DRIVING, held.text)
        cruise.steps.forEach { assertFalse(held.text.contains(it)) }
        assertEquals(DialogueStage.HELD_FOR_PARKING, p.currentState()!!.stage)
        assertEquals("smart_cruise", p.currentState()!!.intentId)
        assertNull("no offer while driving", p.resumeOffer(tucson))

        // D. Parked signal -> exactly one offer.
        now += 5 * 60_000 // a few minutes later; the hold outlives the 60 s question context
        motion = DrivingState.PARKED
        val offer = p.resumeOffer(tucson)!!
        assertEquals("아까 찾으시던 스마트 크루즈, 지금 알려드릴까요?", offer.text)
        assertTrue(offer.asksQuestion)
        p.commit(offer)
        assertNull("offered once", p.resumeOffer(tucson))
        motion = DrivingState.DRIVING; motion = DrivingState.PARKED
        assertNull("parked/driving flicker does not re-offer", p.resumeOffer(tucson))

        val locate = p.say("어, 알려줘", DialogueAct.CONFIRM)
        val ctxD = turns.contexts.last()
        assertEquals(DialogueStage.RESUME_OFFERED, ctxD.stage)
        assertTrue(ctxD.rendyAskedQuestion)
        assertTrue(ctxD.pendingDetail)
        assertEquals(offer.text, ctxD.lastRendyUtterance)
        assertEquals("${cruise.locationSentence} ${RendyDialogueText.FOUND_QUESTION}", locate.text)
        assertTrue(locate.asksQuestion)
        assertEquals(DialogueStage.GUIDE_LOCATE, p.currentState()!!.stage)

        assertEquals(cruise.steps[0], p.say("아 여기 있네", DialogueAct.FOUND).text)
        assertEquals(DialogueStage.GUIDE_STEP, p.currentState()!!.stage)
        assertEquals(cruise.steps[1], p.say("했어", DialogueAct.FOUND).text)
        val result = p.say("다음은?", DialogueAct.FOUND)
        assertEquals("${RendyAnswerComposer.CANNOT_SEE_CAR} ${cruise.expectedResult}", result.text)
        assertEquals(DialogueStage.GUIDE_DONE, p.currentState()!!.stage)

        val bye = p.say("아 됐어. 고마워", DialogueAct.CANCEL)
        assertEquals(RendyAnswerComposer.CANCEL_TEXT, bye.text)
        assertTrue(bye.endsConversation)
        assertNull(p.currentState())
        assertEquals(1, questions.calls) // every follow-up was answered without a question-classifier call
    }

    @Test
    fun guidanceWaitsForTheDriverAtEveryStep() = runBlocking {
        motion = DrivingState.PARKED
        val p = pipeline()
        p.answerAndCommit("크루즈 어떻게 켜", tucson)
        val locate = p.say("어디 있는지 모르겠어", DialogueAct.NEED_DETAIL)
        assertTrue(locate.text.endsWith(RendyDialogueText.FOUND_QUESTION))
        // "아니 안 보여": the DB has no further verified clue for this button -> say so, never the same sentence again.
        val notFound = p.say("아니, 안 보여", DialogueAct.DENY)
        assertEquals(RendyAnswerComposer.NOT_FOUND_AGAIN_TEXT, notFound.text)
        assertFalse(notFound.text.contains(cruise.locationSentence!!))
        // "찾았어" after that still starts at step 1.
        assertEquals(cruise.steps[0], p.say("이제 찾았다", DialogueAct.FOUND).text)
        // UNCLEAR during a step keeps the step; REPEAT then says the step, not the re-ask.
        assertEquals(RendyDialogueText.UNCLEAR, p.say("그 그", DialogueAct.UNCLEAR).text)
        assertEquals(0, p.currentState()!!.stepIndex)
        assertEquals(cruise.steps[0], p.say("뭐라고?", DialogueAct.REPEAT).text)
        assertEquals(cruise.steps[1], p.say("눌렀어", DialogueAct.CONFIRM).text)
    }

    @Test
    fun detailIsHeldWhenTheMotionStateIsUnknown() = runBlocking {
        motion = DrivingState.UNKNOWN
        val p = pipeline()
        p.answerAndCommit("크루즈 어떻게 켜", tucson)
        assertEquals(RendyDialogueText.HOLD_UNKNOWN, p.say("자세히 알려줘", DialogueAct.NEED_DETAIL).text)
        val foundWhileHeld = p.say("찾았어", DialogueAct.FOUND)
        assertEquals(RendyDialogueText.FOUND_WHILE_HELD, foundWhileHeld.text)
        cruise.steps.forEach { assertFalse(foundWhileHeld.text.contains(it)) }
        assertNull("UNKNOWN is not parked: no offer", p.resumeOffer(tucson))
        // The driver's own words never count as a parked signal.
        p.say("나 지금 차 세웠어", DialogueAct.UNCLEAR)
        assertNull(p.resumeOffer(tucson))
    }

    @Test
    fun declinedOfferIsNotRepeatedAndAHoldExpires() = runBlocking {
        val p = pipeline()
        p.answerAndCommit("크루즈 어떻게 켜", tucson)
        p.say("어딨어?", DialogueAct.NEED_DETAIL)
        motion = DrivingState.PARKED
        p.commit(p.resumeOffer(tucson)!!)
        val no = p.say("아니 나중에", DialogueAct.DENY)
        assertEquals(RendyDialogueText.DECLINED, no.text)
        assertEquals(DialogueStage.ANSWERED, p.currentState()!!.stage)
        assertNull(p.resumeOffer(tucson))

        // A new hold that is never resolved expires after the hold window.
        motion = DrivingState.DRIVING
        p.say("다시 자세히", DialogueAct.NEED_DETAIL)
        now += 31 * 60_000
        motion = DrivingState.PARKED
        assertNull(p.resumeOffer(tucson))
    }

    @Test
    fun confirmWhileDrivingAgainHoldsInsteadOfGuiding() = runBlocking {
        val p = pipeline()
        p.answerAndCommit("크루즈 어떻게 켜", tucson)
        p.say("어딨어?", DialogueAct.NEED_DETAIL)
        motion = DrivingState.PARKED
        p.commit(p.resumeOffer(tucson)!!)
        motion = DrivingState.DRIVING // drove off before answering
        assertEquals(RendyDialogueText.HOLD_DRIVING, p.say("응 알려줘", DialogueAct.CONFIRM).text)
        motion = DrivingState.PARKED
        assertNotNull("a new hold allows one new offer", p.resumeOffer(tucson))
    }

    // ---------------------------------------------------------------- switching topics, failures, safety

    @Test
    fun newQuestionDuringGuidanceEndsItAndKeepsTheCar() = runBlocking {
        motion = DrivingState.PARKED
        val p = pipeline()
        p.answerAndCommit("크루즈 어떻게 켜", tucson)
        p.say("어딨어", DialogueAct.NEED_DETAIL)
        assertEquals(DialogueStage.GUIDE_LOCATE, p.currentState()!!.stage)
        val hazard = p.say("아 그리고 비상등은 어디야?", DialogueAct.NEW_QUESTION, "hazard_light")
        assertEquals("hazard_light", hazard.intentId)
        assertEquals(AnswerSource.LLM, hazard.source)
        assertEquals(1, questions.calls) // intent came with the turn: no second LLM call
        val s = p.currentState()!!
        assertEquals("hazard_light", s.intentId)
        assertEquals(tucson.modelId, s.modelId)
        assertEquals(DialogueStage.ANSWERED, s.stage)
        assertEquals(-1, s.stepIndex)
        assertEquals("아 그리고 비상등은 어디야?", s.lastDriverQuestion)
        // A turn intent that is not offered for this car is never answered.
        assertEquals(AnswerSource.CLARIFICATION, p.say("이상한 거", DialogueAct.NEW_QUESTION, "not_an_intent").source)
        // NEW_QUESTION with no fitting intent -> clarification, not a guess.
        turns.next = TurnClassification.Act(DialogueAct.NEW_QUESTION, null, 0.9, intentResolved = true)
        assertEquals(AnswerSource.CLARIFICATION, p.answerAndCommit("그거 뭐더라", tucson).source)
    }

    @Test
    fun unclearKeepsTheConversationAndRepeatSkipsMetaReplies() = runBlocking {
        val p = pipeline()
        val first = p.answerAndCommit("크루즈 어떻게 켜", tucson)
        val before = p.currentState()!!
        val unclear = p.say("음 그 저기", DialogueAct.UNCLEAR)
        assertEquals(RendyDialogueText.UNCLEAR, unclear.text)
        assertEquals(before.intentId, p.currentState()!!.intentId)
        assertEquals(before.stage, p.currentState()!!.stage)
        assertEquals(first.text, p.lastSpokenTurn()!!.text)
        assertEquals(first.text, p.say("뭐라고", DialogueAct.REPEAT).text)
        // CONFIRM after a plain explanation is an acknowledgement; the topic stays for later follow-ups.
        assertEquals(RendyDialogueText.ACK, p.say("아 알겠어", DialogueAct.CONFIRM).text)
        assertEquals("smart_cruise", p.currentState()!!.intentId)
        assertEquals(first.text, p.say("다시", DialogueAct.REPEAT).text)
    }

    @Test
    fun proxyFailureFallsBackToDbPhrasesWithoutASecondLlmCall() = runBlocking {
        val down = object : DialogueTurnClassifier {
            override suspend fun classifyTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext) =
                TurnClassification.Failure("proxy_timeout")
        }
        val p = pipeline(down)
        val first = p.answerAndCommit("크루즈 어떻게 켜", tucson)
        assertEquals(1, questions.calls)
        // DB follow_up_phrases still work offline.
        val again = p.answerAndCommit("다시 말해줘", tucson)
        assertEquals(first.text, again.text)
        assertTrue(again.reason, again.reason.startsWith("turn_failed:proxy_timeout"))
        // Anything else is answered by keyword rules only: the (also failing) LLM question classifier is skipped.
        val other = p.answerAndCommit("비상등 어디야", tucson)
        assertEquals(1, questions.calls)
        assertEquals(AnswerSource.RULE_FALLBACK, other.source)
        assertEquals("hazard_light", other.intentId)
        // A phrase the offline rules do not know never moves the guidance forward and never drops the topic
        // (emulator regression: "어? 뭐라고?" with the proxy down used to clear the context).
        val p2 = pipeline(down)
        val cruiseAnswer = p2.answerAndCommit("크루즈 어떻게 켜", tucson)
        val stateBefore = p2.currentState()!!
        val reask = p2.answerAndCommit("어? 뭐라고?", tucson)
        assertEquals(AnswerSource.CLARIFICATION, reask.source)
        assertEquals("smart_cruise", p2.currentState()!!.intentId)
        assertEquals(stateBefore.stepIndex, p2.currentState()!!.stepIndex)
        assertEquals(stateBefore.stage, p2.currentState()!!.stage)
        // The topic is still there for the next DB follow-up phrase.
        assertEquals(RendyDialogueText.HOLD_DRIVING, p2.answerAndCommit("못 찾겠어", tucson).text)
        assertEquals(RendyDialogueText.HOLD_DRIVING, p2.answerAndCommit("다시 말해줘", tucson).text)
        assertFalse(cruiseAnswer.text == reask.text)
    }

    @Test
    fun throwingTurnClassifierIsAFailureNotACrash() = runBlocking {
        val broken = object : DialogueTurnClassifier {
            override suspend fun classifyTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext): TurnClassification =
                throw IllegalStateException("boom")
        }
        val p = pipeline(broken)
        p.answerAndCommit("크루즈 어떻게 켜", tucson)
        assertTrue(p.answerAndCommit("그만", tucson).reason.startsWith("turn_failed:turn_classifier_exception"))
    }

    @Test
    fun repeatWithoutAnythingSaidAsksForTheFeature() = runBlocking {
        val p = pipeline()
        // No context at all: no turn call; DB phrase -> "which feature?".
        assertEquals(RendyAnswerComposer.NO_CONTEXT_TEXT, p.answerAndCommit("다시 말해줘", tucson).text)
        assertEquals(0, turns.contexts.size)
        // That question is now context: the next utterance is classified as a turn that knows RENDY asked it.
        p.say("크루즈", DialogueAct.NEW_QUESTION, "smart_cruise")
        assertEquals(RendyAnswerComposer.NO_CONTEXT_TEXT, turns.contexts.last().lastRendyUtterance)
        assertEquals(DialogueStage.NONE, turns.contexts.last().stage)
        // Context belongs to the car it was about.
        val calls = turns.contexts.size
        p.answerAndCommit("뭐라고", sonata)
        assertEquals(calls, turns.contexts.size)
    }

    @Test
    fun followUpsOnUnverifiedControlsNeverHoldOrGuide() = runBlocking {
        val ioniq = IntentTestFixtures.car("IONIQ5")
        questions.next = "lane_follow_assist"
        motion = DrivingState.PARKED
        val p = pipeline()
        p.answerAndCommit("차로 유지 어떻게 켜", ioniq)
        turns.act(DialogueAct.NEED_DETAIL)
        assertEquals(RendyAnswerComposer.ESCALATE_TEXT, p.answerAndCommit("어딨어", ioniq).text)
        turns.act(DialogueAct.FOUND)
        assertEquals(RendyAnswerComposer.ESCALATE_TEXT, p.answerAndCommit("찾았어", ioniq).text)
    }

    @Test
    fun everyDialogueSentenceIsFreeOfVehicleInstructions() {
        val fixed = listOf(
            RendyDialogueText.HOLD_DRIVING, RendyDialogueText.HOLD_UNKNOWN, RendyDialogueText.FOUND_QUESTION,
            RendyDialogueText.FOUND_WHILE_HELD, RendyDialogueText.NO_MORE_STEP_DETAIL, RendyDialogueText.ACK,
            RendyDialogueText.DECLINED, RendyDialogueText.UNCLEAR, RendyDialogueText.CLOSING, RendyDialogueText.resumeOffer("X")
        )
        val positional = listOf("왼쪽", "오른쪽", "아래", "위쪽", "누르", "돌리", "당기", "버튼")
        fixed.forEach { t -> positional.forEach { w -> assertFalse("'$t' contains '$w'", t.contains(w)) } }
    }

    // ---------------------------------------------------------------- proxy client (/v1/rendy/turn)

    private val candidates = listOf(
        IntentCandidate("smart_cruise", listOf("크루즈"), "스마트 크루즈"),
        IntentCandidate("hazard_light", listOf("비상등"))
    )
    private val ids = candidates.map { it.id }.toSet()
    private val ctx = TurnContext("핸들 오른쪽에 있어요.", false, "크루즈 어떻게 켜?", "smart_cruise",
        DialogueStage.ANSWERED, false, DrivingState.DRIVING, "투싼")

    @Test
    fun turnPayloadValidationIsStrictAndCautious() {
        fun v(json: String) = GeminiFlashProxyIntentClassifier.validateTurnPayload(json, ids)
        assertEquals(TurnClassification.Act(DialogueAct.REPEAT, null, 0.9, intentResolved = true),
            v("""{"dialogue_act":"REPEAT","intent":"UNKNOWN","confidence":0.9}"""))
        assertEquals(TurnClassification.Act(DialogueAct.NEW_QUESTION, "hazard_light", 0.8, intentResolved = true),
            v("""{"dialogue_act":"NEW_QUESTION","intent":"hazard_light","confidence":0.8}"""))
        // An intent on a non-question act is ignored.
        assertNull((v("""{"dialogue_act":"FOUND","intent":"hazard_light","confidence":0.9}""") as TurnClassification.Act).intentId)
        // Unsure progress acts become UNCLEAR instead of moving the guidance on.
        listOf("CONFIRM", "FOUND", "DENY").forEach {
            assertEquals(DialogueAct.UNCLEAR, (v("""{"dialogue_act":"$it","intent":"UNKNOWN","confidence":0.65}""") as TurnClassification.Act).act)
        }
        assertEquals(DialogueAct.REPEAT, (v("""{"dialogue_act":"REPEAT","intent":"UNKNOWN","confidence":0.6}""") as TurnClassification.Act).act)
        assertEquals(DialogueAct.UNCLEAR, (v("""{"dialogue_act":"REPEAT","intent":"UNKNOWN","confidence":0.3}""") as TurnClassification.Act).act)
        listOf(
            """{"dialogue_act":"TELEPORT","intent":"UNKNOWN","confidence":0.9}""",
            """{"dialogue_act":"NEW_QUESTION","intent":"sunroof_switch","confidence":0.9}""",
            """{"dialogue_act":"REPEAT","intent":"UNKNOWN","confidence":1.5}""",
            """{"dialogue_act":"REPEAT","intent":"UNKNOWN","confidence":0.9,"text":"다시"}""",
            """{"dialogue_act":"REPEAT","confidence":0.9}""",
            """not json""", """[1]"""
        ).forEach { assertTrue(it, v(it) is TurnClassification.Failure) }
    }

    @Test
    fun turnRequestCarriesTheConversationStateAndGoesToTheTurnEndpoint() = runBlocking {
        var url = ""
        var body = ""
        val c = GeminiFlashProxyIntentClassifier("https://proxy.example.invalid/v1/rendy/classify", transport = ProxyTransport { u, b, _ ->
            url = u; body = b
            ProxyHttpResponse(200, """{"dialogue_act":"REPEAT","intent":"UNKNOWN","confidence":0.92}""")
        })
        val r = c.classifyTurn("어? 뭐라고?", candidates, ctx.copy(lastRendyUtterance = "가".repeat(400)))
        assertEquals(DialogueAct.REPEAT, (r as TurnClassification.Act).act)
        assertEquals("https://proxy.example.invalid/v1/rendy/turn", url)
        val json = JsonParser.parseString(body).asJsonObject
        assertEquals(setOf("query", "allowed_intents", "dialogue"), json.keySet())
        val d = json.getAsJsonObject("dialogue")
        assertEquals(300, d.get("last_rendy_utterance").asString.length) // proxy limit
        assertEquals("ANSWERED", d.get("stage").asString)
        assertEquals("smart_cruise", d.get("current_intent").asString)
        assertEquals("DRIVING", d.get("driving_state").asString)
        assertFalse(d.get("rendy_asked_question").asBoolean)
        assertEquals("투싼", d.get("car_model").asString)
        // A current intent that is not offered is not sent.
        c.classifyTurn("뭐", candidates, ctx.copy(currentIntentId = "sunroof_switch"))
        assertTrue(JsonParser.parseString(body).asJsonObject.getAsJsonObject("dialogue").get("current_intent").isJsonNull)
    }

    @Test
    fun turnTransportProblemsAreFailures() = runBlocking {
        fun c(t: ProxyTransport, timeout: Long = 2_000) = GeminiFlashProxyIntentClassifier("https://p.example.invalid/v1/rendy/classify", totalTimeoutMs = timeout, transport = t)
        assertEquals(TurnClassification.Failure("proxy_timeout"),
            c(ProxyTransport { _, _, _ -> delay(1_000); ProxyHttpResponse(200, "{}") }, 100).classifyTurn("어?", candidates, ctx))
        assertEquals(TurnClassification.Failure("proxy_http_503"),
            c(ProxyTransport { _, _, _ -> ProxyHttpResponse(503, "") }).classifyTurn("어?", candidates, ctx))
        assertEquals(TurnClassification.Failure("proxy_request_failed"),
            c(ProxyTransport { _, _, _ -> throw java.io.IOException("reset") }).classifyTurn("어?", candidates, ctx))
        assertEquals(TurnClassification.Failure("proxy_endpoint_must_be_https"),
            GeminiFlashProxyIntentClassifier("http://evil.example/v1/rendy/classify").classifyTurn("어?", candidates, ctx))
        assertEquals("http://10.0.2.2:8787/v1/rendy/turn", GeminiFlashProxyIntentClassifier.turnEndpointFor("http://10.0.2.2:8787/v1/rendy/classify"))
    }
}
