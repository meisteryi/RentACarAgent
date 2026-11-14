package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.RendyVehicleVariants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 더 뉴 아이오닉 5 front/rear defog answers from the 2025 manual (overlay design entry facelift_the_new):
 * panel figure 1C_AutoAircon, button figures 2C_AirconFrontDefrostButton / 2C_RearWindowDefrost, texts id042e6950a36,
 * idb92168d37b2, idc0853a3fd8c, ide9dfd643271. The classifier is scripted; what is spoken is the real DB + overlay.
 */
class Ioniq5DefogAnswerTest {
    private val kb = IntentTestFixtures.knowledge
    private val db = IntentTestFixtures.database
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

    private fun act(a: DialogueAct, intent: String? = null) = TurnClassification.Act(a, intent, 0.95, intentResolved = intent != null)

    private val location = "가운데 화면 아래 히터·에어컨 조작부의 아랫줄, 운전석 온도 버튼 바로 오른쪽을 보세요."
    private val look = "앞유리 모양 안에 물결 화살표가 위로 올라가는 서리 제거 버튼이에요."

    @Test
    fun parkedFirstAnswerIsWhereWhatAndPress() = runBlocking {
        val a = pipeline(Scripted("front_defog")).answerAndCommit("앞이 뿌옇게 보여", fl)
        assertEquals(GuidanceTier.FULL, a.tier)
        assertEquals("$location $look 한 번 누르면 표시등이 켜지고 앞유리 쪽으로 바람이 나와요.", a.text)
        assertTrue(a.reason, a.reason.contains("design=facelift_the_new"))
    }

    @Test
    fun drivingAddsTheStopFirstNoteAndNoSteps() = runBlocking {
        val a = pipeline(Scripted("front_defog"), DrivingState.DRIVING).answerAndCommit("앞유리에 김이 서렸어", fl)
        assertTrue(a.text, a.text.endsWith("앞이 많이 안 보이면 먼저 안전한 곳에 차를 세운 뒤 조작하세요."))
        assertFalse("no step-by-step while driving: ${a.text}", a.text.contains("풍량") || a.text.contains("A/C"))
    }

    @Test
    fun notVisibleAndMoreDetailGiveTheVerifiedClueThenSteps() = runBlocking {
        val s = Scripted("front_defog")
        val p = pipeline(s)
        p.answerAndCommit("앞유리 서리 제거 버튼 어디 있어?", fl)

        s.turn = act(DialogueAct.NEED_DETAIL)
        val where = p.answerAndCommit("버튼이 안 보여", fl)
        assertEquals("$location $look 찾으셨나요?", where.text)

        s.turn = act(DialogueAct.NEED_DETAIL)
        val more = p.answerAndCommit("좀 더 자세히 알려줘", fl)
        assertTrue(more.text, more.text.startsWith("온도 버튼과 바람 세기 버튼 사이에 있고, 버튼 위에 작은 표시등이 있어요."))
        assertTrue(more.text, more.text.contains("뒷유리 열선이니 헷갈리지 마세요"))

        s.turn = act(DialogueAct.FOUND)
        val step1 = p.answerAndCommit("찾았어", fl)
        assertTrue(step1.text, step1.text.startsWith("서리 제거 버튼을 한 번 누르세요."))
        s.turn = act(DialogueAct.FOUND)
        val step2 = p.answerAndCommit("그다음은?", fl)
        assertTrue(step2.text, step2.text.contains("온도와 풍량을 최대로"))
    }

    @Test
    fun rearGlassIsItsOwnButton() = runBlocking {
        val a = pipeline(Scripted("rear_defog")).answerAndCommit("뒷유리에 성에 꼈어", fl)
        assertEquals(GuidanceTier.FULL, a.tier)
        assertEquals("가운데 화면 아래 히터·에어컨 조작부의 아랫줄, 동승석 온도 버튼 바로 왼쪽을 보세요. " +
            "네모 안에 물결 화살표가 위로 올라가는 뒷유리 서리 제거 버튼이에요. " +
            "시동을 건 상태에서 누르면 표시등이 켜지고 뒷유리와 사이드미러 열선이 함께 켜져요.", a.text)
    }

    @Test
    fun preFaceliftKeepsTheDesignNeutralComponentAnswer() = runBlocking {
        // The 2025 panel figure is not applied to the pre-facelift design.
        val a = pipeline(Scripted("front_defog")).answerAndCommit("앞유리 김서림", pre)
        assertEquals(GuidanceTier.LOCATION_ONLY, a.tier)
        assertFalse(a.text, a.text.contains("운전석 온도 버튼"))
    }

    @Test
    fun keywordFallbackKeepsFrontAndRearApart() {
        fun rule(q: String) = (KeywordIntentMatcher.analyze(kb.intents, q) as? KeywordIntentMatcher.Result.Matched)?.intent?.intent
        assertEquals("front_defog", rule("앞이 뿌옇게 보여"))
        assertEquals("front_defog", rule("앞유리에 성에 꼈어"))
        assertEquals("front_defog", rule("김서림 어떻게 없애?"))
        assertEquals("front_defog", rule("앞유리 서리 제거 버튼 어디 있어?"))
        assertEquals("rear_defog", rule("뒷유리에 성에 꼈어"))
        assertEquals("rear_defog", rule("뒷유리 서리 제거 버튼 어디야?"))
        assertEquals("rear_defog", rule("뒷유리가 뿌예"))
    }
}
