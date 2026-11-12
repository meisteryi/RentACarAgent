package com.hyundai.rentalhelper.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Flag-OFF / fallback keyword engine. The pre-v4.2 algorithm is kept as [KeywordIntentMatcher.legacyBestMatch];
 * the new matcher must fix the measured mis-matches without changing ordinary keyword hits.
 */
class KeywordIntentMatcherTest {
    private val kb = IntentTestFixtures.knowledge
    private val intents = kb.intents

    private fun matched(q: String) = (KeywordIntentMatcher.analyze(intents, q) as? KeywordIntentMatcher.Result.Matched)?.intent?.intent
    private fun result(q: String) = KeywordIntentMatcher.analyze(intents, q)

    @Test
    fun previouslyMeasuredMisclassificationsAreFixed() {
        // legacy: wiper ("비" inside "내비") -> now no answer
        assertEquals(null, matched("차량 내장 내비 목적지 설정해줘"))
        // legacy: rear_defog ("사이드미러") -> now the mirror controls
        assertEquals("side_mirror_fold", matched("사이드미러 접는 버튼 어디야?"))
        assertEquals("side_mirror_fold", matched("사이드 미러 접는거 어딨어요"))
        assertEquals("side_mirror_fold", matched("사이드미러 접어줘"))
        assertEquals("side_mirror_adjust", matched("사이드미러 각도 조절법"))
        // legacy: front_defog ("성에") -> rear glass synonym
        assertEquals("rear_defog", matched("뒤창에 성에가 끼었네"))
        // legacy: hazard_light ("위험") -> safety advice is not a control question
        assertTrue(result("주행 중 위험하면 안전하게 차를 세우는 방법") is KeywordIntentMatcher.Result.Ambiguous)
        // legacy: wiper ("비" inside "비춰")
        assertEquals(null, matched("미러가 너무 바닥만 비춰"))
    }

    @Test
    fun keywordRisksFromV42AreAmbiguousOnTheirOwn() {
        listOf("깜빡이 어떻게 켜?", "열선 켜줘", "충전 어떻게 해", "차선 변경 어떻게 해", "카메라 어디 있어", "뒷문 열어줘")
            .forEach { q -> assertTrue("'$q' must not be answered by a weak keyword: ${result(q)}", result(q) is KeywordIntentMatcher.Result.Ambiguous) }
        // The same words inside a specific phrase still answer.
        assertEquals("rear_defog", matched("뒷유리 열선 어떻게 켜?"))
        assertEquals("heated_steering_wheel", matched("핸들 열선 켜줘"))
        assertEquals("charge_door", matched("충전구 어디 있어"))
        assertEquals("wireless_charger", matched("휴대폰 충전 어디서 해"))
        assertEquals("lane_follow_assist", matched("차선 유지 기능 켜줘"))
        assertEquals("parking_view", matched("후방카메라 켜줘"))
        // Two weak words owned by one intent are enough.
        assertEquals("rear_defog", matched("사이드미러 열선"))
    }

    @Test
    fun multiIntentQuestionsAreNotAnswered() {
        listOf("앞유리 김을 없애고 비상등도 켜줘", "시트 열선이랑 핸들 열선 둘 다 켜는 법", "와이퍼랑 라이트 둘 다 알려줘")
            .forEach { q -> assertEquals("'$q'", KeywordIntentMatcher.Result.Ambiguous("multi_intent"), result(q)) }
        // One control named twice is not multi-intent.
        assertEquals("electronic_parking_brake", matched("주차 브레이크랑 파킹 브레이크 같은 거야?"))
    }

    @Test
    fun ordinaryStrongKeywordsStillSelectTheirOwnIntent() {
        val mismatches = mutableListOf<String>()
        for (intent in intents) {
            for (kw in intent.keywords) {
                val clean = kw.replace(" ", "").lowercase()
                if (clean.length < KeywordIntentMatcher.MIN_KEYWORD_LENGTH || clean in KeywordIntentMatcher.WEAK_KEYWORDS) continue
                val q = "$kw 어떻게 해?"
                val legacy = KeywordIntentMatcher.legacyBestMatch(intents, q)?.intent?.intent
                val now = matched(q)
                if (now != legacy) mismatches += "$q legacy=$legacy now=$now"
            }
        }
        assertTrue("Keyword behaviour changed:\n${mismatches.joinToString("\n")}", mismatches.isEmpty())
    }
}
