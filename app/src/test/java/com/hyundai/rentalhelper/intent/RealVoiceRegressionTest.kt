package com.hyundai.rentalhelper.intent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressions from the 11-13 real-voice emulator test (IONIQ 5, Gemini proxy not running, so every turn used the
 * rule fallback):
 *  1. "스마트 크루즈 어떻게 켜" was routed correctly but answered with the EXISTS_ONLY "확인 중이라 ... 렌터카 업체"
 *     notice, because IONIQ 5 smart_cruise had no verified field (DB loc/op=false, no overlay entry).
 *  2. A question about the driver's own position had no non-vehicle intent to land on, so it could only be
 *     re-asked as "기능 이름을 다시 말씀해 주세요".
 */
class RealVoiceRegressionTest {
    private val kb = IntentTestFixtures.knowledge
    private val ioniq = IntentTestFixtures.car("IONIQ5")

    private object ProxyDown : IntentClassifier, DialogueTurnClassifier {
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext) =
            IntentClassification.Failure("proxy_request_failed")
        override suspend fun classifyTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext) =
            TurnClassification.Failure("proxy_request_failed")
    }

    /** Stands in for Gemini: returns a fixed label and records the candidates it was offered. */
    private class Scripted(var intent: String?, var turn: TurnClassification.Act? = null) : IntentClassifier, DialogueTurnClassifier {
        val offered = mutableListOf<List<String>>()
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext): IntentClassification {
            offered += candidates.map { it.id }
            return intent?.let { IntentClassification.Classified(it, 0.9) } ?: IntentClassification.Clarification(0.9, "model_requires_clarification")
        }
        override suspend fun classifyTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext): TurnClassification {
            offered += candidates.map { it.id }
            return turn ?: TurnClassification.Failure("not_scripted")
        }
    }

    private fun pipeline(c: IntentClassifier, t: DialogueTurnClassifier, driving: DrivingState = DrivingState.PARKED) =
        RendyQuestionPipeline(c, RuleBasedIntentClassifier(kb.intents), kb, drivingState = { driving }, turnClassifier = t)

    // ------------------------------------------------------------ 1. smart cruise (IONIQ 5)

    @Test
    fun ioniq5SmartCruiseSpeaksVerifiedOperationNotRentalEscalation() = runBlocking {
        val g = kb.guidance(ioniq, "smart_cruise")!!
        assertEquals(GuidanceTier.OPERATION_ONLY, g.tier)

        val a = pipeline(ProxyDown, ProxyDown).answerAndCommit("스마트 크루즈 어떻게 켜", ioniq)
        assertEquals("smart_cruise", a.intentId)
        assertEquals(GuidanceTier.OPERATION_ONLY, a.tier)
        assertTrue(a.text, a.text.contains("주행 보조 버튼"))
        assertTrue(a.text, a.text.contains(ButtonGuidance.NOTICE_LOCATION_UNCONFIRMED))
        assertFalse(a.text, a.text.contains("렌터카"))
        assertFalse(a.text, a.text.contains("확인 중이라"))
        // The DB's "왼쪽" position is not in the official text and must not be spoken.
        assertFalse(a.text, a.text.contains("왼쪽"))
    }

    @Test
    fun ioniq5SmartCruiseStepsComeFromOverlayOnly() = runBlocking {
        val g = kb.guidance(ioniq, "smart_cruise")!!
        assertEquals(4, g.steps.size)
        assertTrue(g.steps[0].contains("주행 보조 버튼"))
        assertTrue(g.expectedResult!!.contains("계기판"))
        assertEquals(null, g.locationSentence)
        assertEquals(null, g.appearanceSentence)
        assertTrue((g.steps + g.expectedResult!!).none { it.contains("왼쪽") || it.contains("오른쪽") })
    }

    @Test
    fun ioniq5SmartCruiseFollowUpsStayWithinVerifiedFields() = runBlocking {
        val turn = Scripted("smart_cruise")
        val p = pipeline(turn, turn)
        p.answerAndCommit("스마트 크루즈 어떻게 켜", ioniq)

        turn.turn = TurnClassification.Act(DialogueAct.NEED_DETAIL, null, 0.9)
        val where = p.answerAndCommit("어딨는지 모르겠어", ioniq)
        assertEquals(RendyAnswerComposer.LOCATION_UNKNOWN_TEXT, where.text)

        turn.turn = TurnClassification.Act(DialogueAct.FOUND, null, 0.9)
        val step = p.answerAndCommit("찾았어", ioniq)
        assertTrue(step.text, step.text.contains("주행 보조 버튼을 누르세요"))
    }

    // ------------------------------------------------------------ 2. driver location

    @Test
    fun locationIntentIsOfferedToTheClassifiers() = runBlocking {
        val s = Scripted(RendyAssistantIntents.DRIVER_LOCATION)
        val a = pipeline(s, s).answerAndCommit("내가 어디 있는지 모르겠어", ioniq)
        assertTrue(s.offered.single().contains(RendyAssistantIntents.DRIVER_LOCATION))
        assertEquals(RendyAssistantIntents.LOCATION_UNSUPPORTED_TEXT, a.text)
        assertEquals(AnswerSource.LLM, a.source)
        assertEquals(null, a.buttonId)
    }

    @Test
    fun followUpLocationQuestionKeepsTheFeatureTopic() = runBlocking {
        val s = Scripted("smart_cruise")
        val p = pipeline(s, s)
        p.answerAndCommit("스마트 크루즈 어떻게 켜", ioniq)

        s.turn = TurnClassification.Act(DialogueAct.NEW_QUESTION, RendyAssistantIntents.DRIVER_LOCATION, 0.9, intentResolved = true)
        val a = p.answerAndCommit("내가 어디 있는지 모르겠어", ioniq)
        assertEquals(RendyAssistantIntents.LOCATION_UNSUPPORTED_TEXT, a.text)
        assertTrue(s.offered.last().contains(RendyAssistantIntents.DRIVER_LOCATION))
        assertEquals("smart_cruise", p.currentState()!!.intentId)

        // "뭐라고?" repeats the location notice verbatim.
        s.turn = TurnClassification.Act(DialogueAct.REPEAT, null, 0.9)
        assertEquals(RendyAssistantIntents.LOCATION_UNSUPPORTED_TEXT, p.answerAndCommit("뭐라고", ioniq).text)
    }

    @Test
    fun locationQuestionWithProxyDownGetsUnsupportedNoticeNotFeatureReask() = runBlocking {
        val first = pipeline(ProxyDown, ProxyDown).answerAndCommit("내가 어디 있는지 모르겠어", ioniq)
        assertEquals(RendyAssistantIntents.LOCATION_UNSUPPORTED_TEXT, first.text)
        assertEquals(AnswerSource.RULE_FALLBACK, first.source)

        val p = pipeline(ProxyDown, ProxyDown)
        p.answerAndCommit("스마트 크루즈 어떻게 켜", ioniq)
        for (q in listOf("내가 어디 있는지 모르겠어", "여기가 어디야", "지금 내 위치 알려줘", "길을 잃었어")) {
            val a = p.answerAndCommit(q, ioniq)
            assertEquals(q, RendyAssistantIntents.LOCATION_UNSUPPORTED_TEXT, a.text)
            assertEquals(q, "smart_cruise", p.currentState()!!.intentId)
        }
    }

    @Test
    fun cannotFindTheFeatureIsNotALocationQuestion() = runBlocking {
        // The literal STT text of the 11-13 test. Without "내가", it is "I can't find it" about the current feature.
        val p = pipeline(ProxyDown, ProxyDown)
        p.answerAndCommit("스마트 크루즈 어떻게 켜", ioniq)
        for (q in listOf("어딨는지 모르겠어", "어디 있는지 모르겠는데", "버튼이 어디 있어", "그거 어디 눌러")) {
            assertNotEquals(q, RendyAssistantIntents.LOCATION_UNSUPPORTED_TEXT, p.answer(q, ioniq).text)
        }
        assertEquals(null, RendyAssistantIntents.ruleMatch(KeywordIntentMatcher.normalize("비상등 어디 있어")))
    }

    @Test
    fun featureKeywordsStillWinOverTheLocationRule() = runBlocking {
        val a = pipeline(ProxyDown, ProxyDown).answer("비상등 어디 있어", ioniq)
        assertEquals("hazard_light", a.intentId)
    }

    @Test
    fun assistantCandidateFitsProxyLimits() {
        for (c in RendyAssistantIntents.candidates) {
            assertTrue(Regex("^[a-z0-9_]{1,64}$").matches(c.id))
            assertTrue(c.label!!.length <= 80)
            assertTrue(c.examples.size <= 30 && c.examples.all { it.length <= 40 })
            assertTrue("must not collide with a DB intent", kb.intent(c.id) == null)
        }
        assertTrue((kb.candidatesFor(ioniq) + RendyAssistantIntents.candidates).size <= 64)
    }
}
