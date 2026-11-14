package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.RendyVehicleVariants
import com.hyundai.rentalhelper.service.MotionSource
import com.hyundai.rentalhelper.service.VehicleMotionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NEED_DETAIL safety scenario on 더 뉴 아이오닉 5 (2026-11-14 user report: on the emulator the vehicle signal was
 * PARKED, so detailed guidance was correct there; these tests drive the DRIVING -> PARKED path). The classifier is
 * scripted (labels only); the spoken text comes from the real DB/overlay and the dialogue policy.
 */
class NeedDetailSafetyScenarioTest {
    private val kb = IntentTestFixtures.knowledge
    private val fl = RendyVehicleVariants.resolve(IntentTestFixtures.database.models, "IONIQ5_NE1_FL")!!

    private class Scripted(var turn: TurnClassification.Act? = null) : IntentClassifier, DialogueTurnClassifier {
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext) =
            IntentClassification.Classified("smart_cruise", 0.95)
        override suspend fun classifyTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext) =
            turn ?: TurnClassification.Failure("not_scripted")
    }

    private var driving = DrivingState.DRIVING
    private val s = Scripted()
    private val p = RendyQuestionPipeline(s, RuleBasedIntentClassifier(kb.intents), kb, drivingState = { driving }, turnClassifier = s)

    private fun say(q: String, act: DialogueAct, intent: String? = null) = runBlocking {
        s.turn = TurnClassification.Act(act, intent, 0.95, intentResolved = intent != null)
        p.answerAndCommit(q, fl)
    }

    @Test
    fun drivingHoldsDetailThenParkedResumesWithLeftSideGuidance() = runBlocking {
        // A. driving
        val first = p.answerAndCommit("스마트 크루즈 어떻게 켜?", fl)
        assertTrue(first.text, first.text.startsWith("핸들 왼쪽 버튼 묶음의 맨 윗줄 바깥쪽을 보세요. 자동차와 속도계가 함께 그려진 주행 보조 버튼이 있어요."))
        assertTrue("driving: DB safety note appended", first.text.contains("운전 보조 기능입니다"))

        assertEquals(first.text, say("뭐라고?", DialogueAct.REPEAT).text)

        val held = say("어딨는지 모르겠어", DialogueAct.NEED_DETAIL)
        assertEquals("지금은 운전에 집중해 주세요. 정차하면 차근차근 알려드릴게요.", held.text)
        assertEquals(DialogueStage.HELD_FOR_PARKING, p.currentState()!!.stage)
        assertEquals("smart_cruise", p.currentState()!!.intentId) // pending detail kept

        // "그래도 어디야?" -> either label must not read the location while driving.
        val again1 = say("그래도 어디야?", DialogueAct.NEED_DETAIL)
        assertEquals(RendyDialogueText.HOLD_DRIVING, again1.text)
        val again2 = say("그래도 어디야?", DialogueAct.NEW_QUESTION, "smart_cruise")
        assertEquals(RendyDialogueText.HOLD_DRIVING, again2.text)
        assertTrue(again2.reason, again2.reason.contains("NEW_QUESTION_same_feature"))
        assertEquals(DialogueStage.HELD_FOR_PARKING, p.currentState()!!.stage)
        assertNull("no offer while still driving", p.resumeOffer(fl))

        val foundWhileHeld = say("찾았어", DialogueAct.FOUND)
        assertEquals(RendyDialogueText.FOUND_WHILE_HELD, foundWhileHeld.text)

        // B. parked (signal PARKED = speed <= 1 km/h AND gear P)
        driving = DrivingState.PARKED
        val offer = p.resumeOffer(fl)
        assertNotNull(offer)
        assertEquals("아까 찾으시던 스마트 크루즈, 지금 알려드릴까요?", offer!!.text)
        p.commit(offer)
        assertNull("offered only once", p.resumeOffer(fl))

        val guide = say("응", DialogueAct.CONFIRM)
        assertEquals("핸들 왼쪽 버튼 묶음의 맨 윗줄 바깥쪽을 보세요. 자동차와 속도계가 함께 그려진 주행 보조 버튼이 있어요. 찾으셨나요?", guide.text)

        val step = say("찾았어", DialogueAct.FOUND)
        assertTrue(step.text, step.text.startsWith("기어를 D에 두고"))
        assertTrue(step.text, step.text.contains("주행 보조 버튼"))

        val next = say("그다음은?", DialogueAct.FOUND)
        assertTrue(next.text, next.text.startsWith("앞차가 있어 멈춰 있을 때는"))

        for (t in listOf(first, guide, step, next)) assertFalse("old right-side text: ${t.text}", t.text.contains("오른쪽"))
    }

    @Test
    fun unknownStateAlsoHoldsAndOffersNothing() = runBlocking {
        driving = DrivingState.UNKNOWN
        p.answerAndCommit("스마트 크루즈 어떻게 켜?", fl)
        val held = say("못 찾겠어", DialogueAct.NEED_DETAIL)
        assertEquals(RendyDialogueText.HOLD_UNKNOWN, held.text)
        assertEquals(RendyDialogueText.HOLD_UNKNOWN, say("스마트 크루즈 어디 있어?", DialogueAct.NEW_QUESTION, "smart_cruise").text)
        assertNull("UNKNOWN is never treated as parked", p.resumeOffer(fl))
    }

    @Test
    fun aDifferentFeatureIsStillAnsweredWhileHeld() = runBlocking {
        p.answerAndCommit("스마트 크루즈 어떻게 켜?", fl)
        say("어딨는지 모르겠어", DialogueAct.NEED_DETAIL)
        val other = say("비상등은 어딨어?", DialogueAct.NEW_QUESTION, "hazard_light")
        assertEquals("hazard_light", other.intentId)
        assertFalse(other.text == RendyDialogueText.HOLD_DRIVING)
    }

    @Test
    fun parkedOnlyWithZeroSpeedAndGearP() {
        assertEquals(DrivingState.PARKED, VehicleMotionPolicy.evaluate(0f, "PARK", MotionSource.REAL).state)
        assertFalse("speed 0 in D is not parked", VehicleMotionPolicy.evaluate(0f, "DRIVE", MotionSource.REAL).state == DrivingState.PARKED)
        assertEquals(DrivingState.UNKNOWN, VehicleMotionPolicy.evaluate(null, null, MotionSource.NONE).state)
        assertEquals(DrivingState.DRIVING, VehicleMotionPolicy.evaluate(40f, "DRIVE", MotionSource.REAL).state)
    }
}
