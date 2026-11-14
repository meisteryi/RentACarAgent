package com.hyundai.rentalhelper.data

/**
 * Boarding briefing for the 더 뉴 아이오닉 5 (IONIQ5_NE1_FL) rental, comparing it with the body type the driver
 * usually drives (2026-11-14). Other rentals keep their existing briefings for now.
 *
 * Comparison basis: the selection screen gives only the BODY TYPE of the usual car. So
 *  - stated as differences: only what holds for the whole class — a 경차 is at most 1.6 m wide (경형 기준) vs
 *    1,890 mm (DB specs); the listed sedans are lower and the listed vans higher than 1,605 mm; 포터·봉고 are
 *    cab-over trucks without a bonnet in front of the driver;
 *  - the usual car's gear or powertrain is never assumed: the rental's operation is introduced with an explicit
 *    condition ("평소 기어봉을 쓰셨다면", "수동 기어에 익숙하시다면").
 * Rental facts, official NE1 2025 Korean manual "변속 위치 선택하기" (iddf661a37926):
 *  - "브레이크 페달을 밟은 상태에서 변속 다이얼을 D 위치로 돌리십시오." (R likewise),
 *    "브레이크 페달을 밟은 상태에서 P 버튼을 누르십시오."
 *  - "주행 중 도로 상황에 따라 회생 제동 시스템이 자동으로 작동합니다."
 *  - "주행 중 차량을 잠시 멈추는 경우, 브레이크 페달을 세게 밟지 않으면 차가 움직일 수 있으니 주의하십시오."
 * Not spoken (not in the manual text): where the dial is (figure only), READY lamp, which paddle does what.
 * Smart cruise is not part of the briefing; it is explained when the driver asks.
 */
object Ioniq5FaceliftComparisonBriefing {
    const val NAME = "더 뉴 아이오닉 5"

    /** Manual wording of the gear operation (D/R by dial, P by button, brake pedal pressed). */
    private const val GEAR = "브레이크 페달을 밟은 채 변속 다이얼을 돌려 D나 R을 고르고, 주차할 때는 P 버튼을 누릅니다."

    /**
     * [usual] null = the driver skipped the selection; [CarSegment.NONE] = no own car. Both get the rental's basic
     * start-up controls without any comparison.
     */
    fun text(usual: CarSegment?): String = when (usual) {
        CarSegment.COMPACT ->
            "${NAME}는 평소 타시던 경차보다 차폭이 30센티미터 가까이 넓습니다. 좁은 길과 주차할 때 양옆 간격을 넉넉히 두세요. " +
                "평소 기어봉을 쓰셨다면 변속 방법도 다릅니다. 이 차는 $GEAR"
        CarSegment.SEDAN ->
            "${NAME}는 평소 타시던 세단보다 차체가 높아 앉는 위치와 시야가 달라집니다. 출발 전에 시트 높이와 사이드미러를 먼저 맞춰 주세요. " +
                "평소 기어봉을 쓰셨다면 변속 방법도 다릅니다. 이 차는 $GEAR"
        CarSegment.SUV ->
            "${NAME}는 평소 타시던 SUV와 변속과 감속 방식이 다를 수 있습니다. 평소 기어봉을 쓰셨다면, 이 차는 $GEAR " +
                "또 전기차라서 주행 중에는 회생 제동이 자동으로 작동하니, 처음에는 감속되는 느낌을 확인하며 천천히 출발하세요."
        CarSegment.VAN ->
            "${NAME}는 평소 타시던 승합차보다 차체가 낮아 앉는 위치와 시야가 달라집니다. 출발 전에 시트와 사이드미러를 맞춰 주세요. " +
                "변속은 $GEAR 평소 차와 위치가 다를 수 있으니 출발 전에 한 번 확인해 주세요."
        CarSegment.TRUCK ->
            "${NAME}는 평소 타시던 트럭과 달리 운전석 앞에 보닛이 있어 앞부분이 깁니다. 주차할 때 앞 간격을 넉넉히 두세요. " +
                "수동 기어에 익숙하시다면, 이 차는 클러치 페달이 없고, $GEAR"
        CarSegment.NONE, null ->
            "${NAME}의 출발 전 기본 조작을 알려 드릴게요. 기어는 $GEAR " +
                "전기차라서 주행 중에는 회생 제동이 자동으로 작동하고, 잠시 멈출 때 브레이크 페달을 충분히 밟지 않으면 차가 움직일 수 있습니다. " +
                "출발 전에 시트와 사이드미러를 먼저 맞춰 주세요."
    }
}
