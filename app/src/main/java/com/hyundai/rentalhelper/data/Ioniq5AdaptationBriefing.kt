package com.hyundai.rentalhelper.data

/**
 * First boarding briefing for the IONIQ 5 rental, by the segment of the car the driver usually drives.
 *
 * Rules (2026-11-14 review):
 *  - Only differences that hold for the whole segment; no figures that assume one specific car.
 *  - Gear: official NE1 2025 manual "변속 위치 선택하기" — 전자식 변속 다이얼 (turn for D/R) and a separate
 *    P 버튼, every shift with the brake pedal pressed. The dial's position is shown only in a figure, so no
 *    location is spoken. Not every SUV/truck uses a different selector, so no "unlike your car" claim.
 *  - No marketing claims, no smart cruise (explained only when asked).
 */
object Ioniq5AdaptationBriefing {
    /** Shared, manual-based gear instruction (brake condition, dial vs P button). */
    const val GEAR_SENTENCE = "기어는 브레이크 페달을 밟은 채 전자식 변속 다이얼을 돌려 D나 R을 고르고, 주차할 때는 P 버튼을 눌러 주세요."

    fun text(rentalName: String, usualSegment: CarSegment): String = when (usualSegment) {
        CarSegment.COMPACT ->
            "탑승하신 ${rentalName}는 평소 타시던 경차보다 차폭이 넓습니다. 좁은 길을 지나거나 주차할 때 양옆 간격을 넉넉히 확인해 주세요."
        CarSegment.SEDAN ->
            "탑승하신 ${rentalName}는 세단보다 차체가 높은 편이라 앉는 자세와 시야가 다르게 느껴질 수 있습니다. 출발 전에 시트 높이와 사이드미러를 먼저 맞춰 주세요."
        CarSegment.SUV ->
            "탑승하신 ${rentalName}는 전기차로, 평소 타시던 SUV와 변속 방식이 다를 수 있습니다. $GEAR_SENTENCE"
        CarSegment.VAN ->
            "탑승하신 ${rentalName}는 평소 타시던 승합차보다 차체가 작고 낮습니다. 앉는 위치와 시야가 달라지니 출발 전에 시트와 사이드미러를 맞춰 주세요."
        CarSegment.TRUCK ->
            "탑승하신 ${rentalName}는 승용 전기차로, 기어를 전자식 변속 다이얼과 P 버튼으로 조작합니다. 브레이크 페달을 밟은 채 다이얼을 돌리면 D나 R이 선택되고, 주차할 때는 P 버튼을 누르세요."
        // "없음" (no own car / new driver, added on main): seat and mirrors first, then brake -> D -> pull away slowly.
        CarSegment.NONE ->
            "탑승하신 ${rentalName}는 순수 전기차입니다. 출발 전에 시트와 사이드미러를 먼저 맞추고, 브레이크 페달을 밟은 채 전자식 변속 다이얼을 돌려 D를 고른 뒤 천천히 출발하세요."
    }
}
