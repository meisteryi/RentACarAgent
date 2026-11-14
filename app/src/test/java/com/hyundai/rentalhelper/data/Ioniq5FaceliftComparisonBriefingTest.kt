package com.hyundai.rentalhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 더 뉴 아이오닉 5 rental: one comparison briefing per usual body type, plus "선택 안 함" (none / skipped). */
class Ioniq5FaceliftComparisonBriefingTest {
    private val options: List<CarSegment?> = listOf(
        CarSegment.COMPACT, CarSegment.SEDAN, CarSegment.SUV, CarSegment.VAN, CarSegment.TRUCK, null
    )
    private val all = options.associateWith { Ioniq5FaceliftComparisonBriefing.text(it) }

    @Test
    fun sixDistinctBriefingsAndNoneEqualsSkipped() {
        assertEquals(6, all.values.toSet().size)
        assertEquals(all.getValue(null), Ioniq5FaceliftComparisonBriefing.text(CarSegment.NONE))
    }

    @Test
    fun comparisonOnlyWithAUsualCar() {
        for (seg in options.filterNotNull()) {
            val t = all.getValue(seg)
            assertTrue("$seg: $t", t.startsWith("더 뉴 아이오닉 5는 평소 타시던 ${seg.displayName.replace("트럭/상용차", "트럭")}"))
        }
        val basic = all.getValue(null)
        assertTrue(basic, basic.startsWith("더 뉴 아이오닉 5의 출발 전 기본 조작"))
        assertFalse("no comparison without a usual car: $basic", basic.contains("평소"))
    }

    @Test
    fun everyBriefingStatesTheManualGearOperation() {
        all.forEach { (seg, t) ->
            assertTrue("$seg: $t", t.contains("브레이크 페달을 밟은 채 변속 다이얼을 돌려 D나 R을 고르고, 주차할 때는 P 버튼을 누릅니다."))
        }
    }

    @Test
    fun usualCarControlsAreNeverAssumed() {
        // The body type does not tell the usual gear/powertrain: gear differences are conditional.
        for (seg in listOf(CarSegment.COMPACT, CarSegment.SEDAN, CarSegment.SUV)) {
            assertTrue("$seg", all.getValue(seg).contains("평소 기어봉을 쓰셨다면"))
        }
        assertTrue(all.getValue(CarSegment.TRUCK).contains("수동 기어에 익숙하시다면"))
        assertTrue(all.getValue(CarSegment.VAN).contains("위치가 다를 수 있으니"))
        val definiteClaims = listOf("기어봉이 있었", "기어봉과 달리", "엔진차와 달리", "평소 차는", "수동이었")
        all.forEach { (seg, t) -> definiteClaims.forEach { c -> assertFalse("$seg '$c': $t", t.contains(c)) } }
    }

    @Test
    fun noUnverifiedOrMarketingWording() {
        // Not in the manual text (location only in a figure, READY lamp, paddle sides) or marketing / old wording.
        val banned = listOf("오른쪽", "왼쪽", "칼럼", "READY", "패들", "아이 페달", "스마트 크루즈", "무게중심", "플랫폼",
            "E-GMP", "토크", "편안한", "넓은 실내", "100%", "순수 전기차입니다")
        all.forEach { (seg, t) -> banned.forEach { b -> assertFalse("$seg contains '$b': $t", t.contains(b)) } }
    }

    @Test
    fun about20to30SecondsOfSpeech() {
        // Pleos ko-KR TTS measured ~6.5-7 characters/s on earlier briefings: 130..210 chars ~ 20..30 s.
        all.forEach { (seg, t) -> assertTrue("$seg length ${t.length}: $t", t.length in 130..215) }
    }

    @Test
    fun routedOnlyForTheFaceliftVariant() {
        val base = HyundaiCarModel("IONIQ5", "아이오닉 5", "IONIQ 5", null, "SUV", "", CarSpecs(4655, 1890, 1605, 3000), "", emptyMap())
        val fl = RendyVehicleVariants.apply(base, RendyVehicleVariants.IONIQ5_FACELIFT)
        val pre = RendyVehicleVariants.apply(base, RendyVehicleVariants.IONIQ5_PRE_FACELIFT)
        assertEquals("IONIQ5_NE1_FL", fl.selectionId)
        assertEquals("IONIQ5_NE1_PRE", pre.selectionId)
    }

    @Test
    fun dumpForReport() {
        val out = options.joinToString("\n") { "${it?.code ?: "SKIPPED_OR_NONE"}\t${all.getValue(it).length}\t${all.getValue(it)}" }
        File("build/ioniq5_fl_briefings.tsv").apply { parentFile?.mkdirs() }.writeText(out + "\n", Charsets.UTF_8)
    }
}
