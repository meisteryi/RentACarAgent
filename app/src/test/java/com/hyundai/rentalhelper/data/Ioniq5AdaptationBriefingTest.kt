package com.hyundai.rentalhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** First boarding briefing (rental: 더 뉴 아이오닉 5) for each usual-car segment. */
class Ioniq5AdaptationBriefingTest {
    private val name = "더 뉴 아이오닉 5"
    private val all = CarSegment.entries.associateWith { Ioniq5AdaptationBriefing.text(name, it) }

    @Test
    fun everySegmentGetsItsOwnShortBriefing() {
        assertEquals(5, all.values.toSet().size)
        all.forEach { (seg, t) ->
            assertTrue("$seg: $t", t.startsWith("탑승하신 $name"))
            val sentences = t.split(Regex("(?<=[.?!])\\s+")).filter { it.isNotBlank() }
            assertTrue("$seg: at most two sentences: $t", sentences.size <= 2)
        }
    }

    @Test
    fun noCarSpecificFiguresMarketingOrUnverifiedClaims() {
        val banned = listOf("센티미터", "미터", "30", "3미터", "무게중심", "안정적", "못지않", "토크", "조용한 승차감",
            "실내가 매우 넓", "패들", "회생제동", "칼럼 레버", "오른쪽", "왼쪽", "스마트 크루즈", "100%")
        all.forEach { (seg, t) -> banned.forEach { b -> assertFalse("$seg contains '$b': $t", t.contains(b)) } }
    }

    @Test
    fun gearGuidanceFollowsTheManual() {
        // Official NE1 2025: dial turned for D/R, separate P button, brake pedal pressed for every shift.
        all.filterValues { it.contains("변속 다이얼") }.forEach { (seg, t) ->
            assertTrue("$seg brake: $t", t.contains("브레이크 페달을 밟은 채"))
            assertTrue("$seg P button: $t", t.contains("P 버튼"))
            assertTrue("$seg dial for D/R: $t", t.contains("다이얼을 돌려") || t.contains("다이얼을 돌리면"))
        }
        assertTrue(all.getValue(CarSegment.SUV).contains("변속 다이얼"))
        assertTrue(all.getValue(CarSegment.TRUCK).contains("변속 다이얼"))
        // SUV: not every SUV differs (recent Santa Fe also has a column dial) -> no definite "unlike your car".
        assertTrue(all.getValue(CarSegment.SUV).contains("다를 수 있습니다"))
    }

    @Test
    fun segmentSpecificPoints() {
        assertTrue(all.getValue(CarSegment.COMPACT).contains("경차보다 차폭이 넓습니다"))
        assertTrue(all.getValue(CarSegment.COMPACT).contains("주차"))
        assertTrue(all.getValue(CarSegment.SEDAN).contains("세단보다 차체가 높은 편"))
        assertTrue(all.getValue(CarSegment.VAN).contains("승합차보다 차체가 작고 낮습니다"))
        assertFalse("truck: no manual-transmission assumption", all.getValue(CarSegment.TRUCK).contains("클러치"))
    }
}
