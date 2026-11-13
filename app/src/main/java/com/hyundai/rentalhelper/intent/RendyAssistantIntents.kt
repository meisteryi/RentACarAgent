package com.hyundai.rentalhelper.intent

/**
 * Non-vehicle topics offered to the classifiers next to the car-feature intents. Without them, a question that is
 * not about a control (the driver asking where they are) can only be forced into a feature or re-asked as
 * "which feature?". They never reach the vehicle DB routing; each has a fixed, truthful reply.
 *
 * The app has no location permission, GPS access or navigation API (it only launches the native navigation app),
 * so the location topic is answered with an "I can't check your position" notice.
 */
object RendyAssistantIntents {
    /** The driver asks where they or the car are right now, or how to get somewhere. */
    const val DRIVER_LOCATION = "driver_location"

    const val LOCATION_TITLE = "현재 위치"
    const val LOCATION_UNSUPPORTED_TEXT = "저는 현재 위치를 확인할 수 없어요. 위치와 길 안내는 내비게이션 화면에서 확인해 주세요."

    /** Sent to the LLM like any other candidate; the label tells it this is not a car control. */
    val candidates: List<IntentCandidate> = listOf(
        IntentCandidate(
            id = DRIVER_LOCATION,
            examples = listOf("지금 내 위치", "여기가 어디야", "길을 잃었어", "목적지 가는 길"),
            label = "운전자나 차량의 현재 위치, 길 찾기 (차량 버튼이 아님)"
        )
    )

    private val ids: Set<String> = candidates.mapTo(mutableSetOf()) { it.id }

    fun isAssistant(intentId: String?): Boolean = intentId != null && intentId in ids

    /**
     * Rule fallback only (LLM proxy down or disabled), checked after the car-feature keywords found nothing.
     * Keys name the driver's own position, so "어딨는지 모르겠어" about the current feature is not taken.
     */
    private val LOCATION_RULE_KEYS = listOf(
        "내위치", "현재위치", "지금위치", "여기가어디", "내가어디있", "내가어딨", "제가어디있", "제가어딨", "길을잃", "길잃었"
    )

    fun ruleMatch(normalizedQuery: String): String? =
        DRIVER_LOCATION.takeIf { LOCATION_RULE_KEYS.any { normalizedQuery.contains(it) } }

    /** Keeps the current topic: asking where you are does not end the feature guidance. */
    fun answer(intentId: String, source: AnswerSource, reason: String): VoiceAnswer = VoiceAnswer(
        LOCATION_TITLE, LOCATION_UNSUPPORTED_TEXT, source, intentId,
        "$reason|assistant:$intentId|location_unsupported", stateUpdate = DialogueStateUpdate.Keep
    )
}
