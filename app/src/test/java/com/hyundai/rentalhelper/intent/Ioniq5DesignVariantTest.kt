package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.HyundaiCarModel
import com.hyundai.rentalhelper.data.RendyVehicleVariants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Default demo car = 더 뉴 아이오닉 5 (facelift_the_new). Smart cruise answers come only from that design's verified
 * overlay entries (RENDY_IONIQ5_SCC_button_handoff.md); the pre-facelift design keeps its own data (right spoke).
 * The classifier is scripted here (it only labels the utterance); what is spoken is the real DB + overlay.
 */
class Ioniq5DesignVariantTest {
    private val kb = IntentTestFixtures.knowledge
    private val db = IntentTestFixtures.database
    private val base = IntentTestFixtures.car("IONIQ5")
    private val fl = RendyVehicleVariants.resolve(db.models, RendyVehicleVariants.DEFAULT.variantId)!!
    private val pre = RendyVehicleVariants.resolve(db.models, "IONIQ5_NE1_PRE")!!

    private class Scripted(var intent: String? = null, var turn: TurnClassification.Act? = null) : IntentClassifier, DialogueTurnClassifier {
        override suspend fun classify(query: String, candidates: List<IntentCandidate>, context: ClassificationContext) =
            intent?.let { IntentClassification.Classified(it, 0.95) } ?: IntentClassification.Failure("not_scripted")
        override suspend fun classifyTurn(query: String, candidates: List<IntentCandidate>, context: TurnContext) =
            turn ?: TurnClassification.Failure("not_scripted")
    }

    private fun pipeline(s: Scripted, driving: DrivingState = DrivingState.PARKED) =
        RendyQuestionPipeline(s, RuleBasedIntentClassifier(kb.intents), kb, drivingState = { driving }, turnClassifier = s)

    private fun act(a: DialogueAct, intent: String? = null) =
        TurnClassification.Act(a, intent, 0.95, intentResolved = intent != null)

    @Test
    fun defaultCarIsTheNewIoniq5() {
        assertEquals("IONIQ5_NE1_FL", RendyVehicleVariants.DEFAULT.variantId)
        assertEquals("더 뉴 아이오닉 5", fl.modelNameKo)
        assertEquals("NE1", fl.modelCode)
        assertEquals(RendyVehicleVariants.FACELIFT_THE_NEW, fl.designGeneration)
        assertEquals("IONIQ5", fl.modelId) // routing and design-neutral data stay on the DB model
        assertEquals(listOf(2025, 2026), RendyVehicleVariants.DEFAULT.manualYearsVerified)
        // The DB model itself is untouched (old data preserved).
        assertEquals("아이오닉 5", base.modelNameKo)
        assertNull(base.designGeneration)
        assertEquals(RendyVehicleVariants.PRE_FACELIFT, pre.designGeneration)
        assertNotEquals(fl.selectionId, pre.selectionId)
    }

    @Test
    fun firstQuestionUsesTheLeftSpokeAndTheButtonLook() = runBlocking {
        val a = pipeline(Scripted("smart_cruise")).answerAndCommit("스마트 크루즈 어떻게 켜?", fl)
        assertEquals(GuidanceTier.FULL, a.tier)
        assertEquals(
            "핸들 왼쪽 버튼 묶음의 맨 윗줄 바깥쪽을 보세요. 자동차와 속도계가 함께 그려진 주행 보조 버튼이 있어요. " +
                "그 버튼을 한 번 누르면 스마트 크루즈를 켤 수 있어요.", a.text)
        assertTrue(a.reason, a.reason.contains("design=facelift_the_new"))
    }

    @Test
    fun followUpsKeepTheFeatureAndTheLeftSide() = runBlocking {
        val s = Scripted("smart_cruise")
        val p = pipeline(s)
        val first = p.answerAndCommit("스마트 크루즈 어떻게 켜?", fl)

        s.turn = act(DialogueAct.NEED_DETAIL)
        val where = p.answerAndCommit("버튼이 어디 있는데?", fl)
        assertEquals("핸들 왼쪽 버튼 묶음의 맨 윗줄 바깥쪽을 보세요. 자동차와 속도계가 함께 그려진 주행 보조 버튼이 있어요. 찾으셨나요?", where.text)
        assertEquals("smart_cruise", p.currentState()!!.intentId)

        s.turn = act(DialogueAct.NEED_DETAIL)
        val notFound = p.answerAndCommit("못 찾겠어", fl)
        assertTrue(notFound.text, notFound.text.contains("차로 주행 보조 버튼이니 헷갈리지 마세요"))

        s.turn = act(DialogueAct.FOUND)
        val step1 = p.answerAndCommit("아 그거? 찾았어", fl)
        assertTrue(step1.text, step1.text.startsWith("기어를 D에 두고"))

        s.turn = act(DialogueAct.REPEAT)
        assertEquals(step1.text, p.answerAndCommit("다시 말해 줘", fl).text)

        for (t in listOf(first, where, notFound, step1)) assertFalse(t.text, t.text.contains("오른쪽"))
    }

    @Test
    fun speedAndGapQuestionsUseTheirOwnVerifiedControls() = runBlocking {
        val s = Scripted("smart_cruise")
        val p = pipeline(s)
        p.answerAndCommit("스마트 크루즈 어떻게 켜?", fl)

        s.turn = act(DialogueAct.NEW_QUESTION, "cruise_speed")
        val speed = p.answerAndCommit("속도는 어떻게 바꿔?", fl)
        assertEquals("cruise_speed_toggle", speed.buttonId)
        assertEquals(
            "핸들 왼쪽 버튼 묶음 가운데 줄의 바깥쪽 롤러가 속도 조절 스위치예요. 롤러 옆에 플러스, 마이너스 표시가 있어요. " +
                "롤러를 위로 올리면 설정 속도가 올라가고, 아래로 내리면 내려가요.", speed.text)

        s.turn = act(DialogueAct.NEW_QUESTION, "cruise_gap")
        val gap = p.answerAndCommit("앞차랑 거리는 어떻게 조절해?", fl)
        assertEquals("cruise_gap", gap.buttonId)
        assertEquals(
            "핸들 왼쪽 버튼 묶음의 맨 아랫줄 바깥쪽을 보세요. 자동차 아래로 줄이 그어진 차간거리 버튼이 있어요. " +
                "누를 때마다 차간거리가 네 단계로 바뀌어요.", gap.text)
        // DB text of the old design ("은색 토글", "SET / 1 / 2") is never read.
        for (t in listOf(speed.text, gap.text)) assertFalse(t, t.contains("은색") || t.contains("SET") || t.contains("오른쪽"))
    }

    @Test
    fun preFaceliftSelectionUsesTheRightSpoke() = runBlocking {
        val s = Scripted("smart_cruise")
        val a = pipeline(s).answerAndCommit("스마트 크루즈 어떻게 켜?", pre)
        assertTrue(a.text, a.text.startsWith("핸들 오른쪽 버튼 묶음의 맨 윗줄 바깥쪽을 보세요."))
        assertFalse(a.text.contains("왼쪽"))
        for (button in listOf("cruise_speed_toggle", "cruise_gap")) {
            val g = kb.guidance(pre, button)!!
            assertEquals(RendyVehicleVariants.PRE_FACELIFT, g.overlay!!.designGeneration)
            assertTrue(g.spokenAnswer!!.contains("오른쪽"))
        }
    }

    @Test
    fun designsNeverMixAndUnknownDesignNamesNoSide() = runBlocking {
        for (button in listOf("smart_cruise", "cruise_speed_toggle", "cruise_gap")) {
            val gf = kb.guidance(fl, button)!!
            val gp = kb.guidance(pre, button)!!
            assertEquals(RendyVehicleVariants.FACELIFT_THE_NEW, gf.overlay!!.designGeneration)
            assertEquals(RendyVehicleVariants.PRE_FACELIFT, gp.overlay!!.designGeneration)
            val flText = (listOfNotNull(gf.spokenAnswer, gf.locationSentence, gf.appearanceSentence, gf.clueSentence, gf.expectedResult) + gf.steps)
            val preText = (listOfNotNull(gp.spokenAnswer, gp.locationSentence, gp.appearanceSentence, gp.clueSentence, gp.expectedResult) + gp.steps)
            assertTrue(flText.toString(), flText.none { it.contains("오른쪽") })
            assertTrue(preText.toString(), preText.none { it.contains("왼쪽") })
        }
        // Design unknown (plain DB model): only the design-neutral entry; no side is spoken, nothing from either design.
        val g = kb.guidance(base, "smart_cruise")!!
        assertNull(g.overlay!!.designGeneration)
        val a = pipeline(Scripted("smart_cruise")).answer("스마트 크루즈 어떻게 켜?", base)
        assertFalse(a.text, a.text.contains("왼쪽") || a.text.contains("오른쪽"))
        assertTrue(a.text.contains(ButtonGuidance.NOTICE_LOCATION_UNCONFIRMED))
        // Speed/gap have no design-neutral entry: the old unverified DB text stays silent.
        assertEquals(GuidanceTier.EXISTS_ONLY, kb.guidance(base, "cruise_speed_toggle")!!.tier)
    }

    @Test
    fun drivingKeepsTheHoldPolicy() = runBlocking {
        val s = Scripted("smart_cruise")
        val p = pipeline(s, DrivingState.DRIVING)
        val a = p.answerAndCommit("스마트 크루즈 어떻게 켜?", fl)
        assertTrue(a.text, a.text.startsWith("핸들 왼쪽 버튼 묶음의 맨 윗줄 바깥쪽을 보세요."))
        assertTrue("driving appends the DB safety note", a.text.contains("운전 보조 기능입니다"))
        s.turn = act(DialogueAct.NEED_DETAIL)
        assertEquals(RendyDialogueText.HOLD_DRIVING, p.answerAndCommit("버튼 어디 있어?", fl).text)
    }

    @Test
    fun switchingDesignDropsTheOtherDesignsConversation() = runBlocking {
        val s = Scripted("smart_cruise")
        val p = pipeline(s)
        p.answerAndCommit("스마트 크루즈 어떻게 켜?", fl)
        s.turn = act(DialogueAct.REPEAT)
        val r = p.answer("다시 말해 줘", pre)
        assertFalse(r.text, r.text.contains("왼쪽"))
    }

    @Test
    fun variantResolvesOnlyItsOwnBaseModel() {
        val tucson: HyundaiCarModel = IntentTestFixtures.car("TUCSON")
        assertNull(RendyVehicleVariants.resolve(listOf(tucson), "IONIQ5_NE1_FL"))
        assertNull(RendyVehicleVariants.resolve(db.models, "TUCSON"))
    }
}
