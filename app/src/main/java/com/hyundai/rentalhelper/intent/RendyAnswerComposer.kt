package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.HyundaiCarModel

/**
 * Driving state for the answer policy. UNKNOWN unless a real vehicle signal says otherwise, or the driver
 * says so ("운전 중인데 ..."). The composer never infers that the car is parked.
 */
enum class DrivingState { UNKNOWN, DRIVING, PARKED }

/**
 * Builds spoken text. Location and operation text comes only from FULL-tier buttons of the selected car
 * (verbatim `spoken_answer` / one `operation_steps` entry / `expected_result`). Every other sentence is a
 * fixed notice that names the feature but contains no location and no steps. No LLM is involved.
 */
object RendyAnswerComposer {
    const val ESCALATE_TEXT = "정확한 위치와 조작법은 아직 확인된 정보가 없어요. 차를 세운 뒤 설명서나 렌터카 업체에 확인해 주세요."
    const val NOT_FOUND_AGAIN_TEXT = "더 자세한 위치는 아직 확인된 정보가 없어요. 차를 세운 뒤 설명서나 렌터카 업체에 확인해 주세요."
    const val LOCATION_UNKNOWN_TEXT = "이 차에서 정확한 위치는 아직 확인되지 않았어요. 차를 세운 뒤 설명서나 렌터카 업체에 확인해 주세요."
    const val OPERATION_UNKNOWN_TEXT = "조작 방법은 아직 확인되지 않았어요. 차를 세운 뒤 설명서나 렌터카 업체에 확인해 주세요."
    const val PARKED_STEPS_TEXT = "이 조작은 차를 세운 뒤에 안내할게요."
    const val PARK_FIRST_NOTICE = "조작은 차를 세운 뒤에 해 주세요."
    const val CANNOT_SEE_CAR = "제가 차 상태를 직접 볼 수는 없어요."
    const val GENERIC_DONE_CHECK = "$CANNOT_SEE_CAR 계기판이나 실제 동작으로 직접 확인해 주세요."
    const val NO_CONTEXT_TEXT = "어떤 기능을 찾고 계신지 먼저 말씀해 주세요."
    const val CANCEL_TEXT = "알겠어요. 필요하면 다시 불러 주세요."
    private val STOP_WORDS = listOf("세운", "세우", "정차", "멈춘")
    private val PERMISSION = Regex("(도돼|도되|도괜찮)")

    /** "눌러도 돼?", "꺼도 되나?" style safety questions. */
    fun asksPermission(query: String): Boolean = PERMISSION.containsMatchIn(KeywordIntentMatcher.normalize(query))

    /** First answer to a classified intent on the selected car. */
    fun initial(
        kb: RendyKnowledgeBase,
        car: HyundaiCarModel,
        route: IntentRoute,
        driving: DrivingState,
        source: AnswerSource,
        reason: String,
        restrictToIntents: Set<String> = emptySet(),
        /** "~해도 돼?" questions get the DB safety note appended to a verified answer. */
        asksPermission: Boolean = false
    ): VoiceAnswer {
        val intentId = route.intentId
        val feature = kb.featureName(intentId)
        fun notice(text: String, why: String) =
            VoiceAnswer(feature, text, source, intentId, "$reason|$why", stateUpdate = DialogueStateUpdate.Clear)

        return when (route) {
            is IntentRoute.NoData ->
                notice("${car.modelNameKo}의 $feature 정보는 아직 없어요. 설명서나 렌터카 업체에 확인해 주세요.", "no_data:${route.reason}")
            is IntentRoute.NotApplicableNonEv ->
                notice("${car.modelNameKo}${topic(car.modelNameKo)} 전기차가 아니어서 $feature 기능이 없어요.", "not_applicable_non_ev")
            is IntentRoute.Redirect -> redirect(kb, car, route, source, reason)
            is IntentRoute.Button -> {
                if (route.viaFallback) {
                    // model_routing "fallback" (e.g. regen paddle -> paddle shift on a non-EV): a different control,
                    // so say the requested one has no data and name the related one without describing it.
                    val related = kb.intentForButton(car, route.buttonId)?.let { kb.featureName(it) }
                    val text = "${car.modelNameKo}의 $feature 정보는 아직 없어요." +
                        (related?.let { " 비슷한 $it 기능이 궁금하시면 그 이름으로 물어봐 주세요." } ?: "")
                    return notice(text, "fallback_route:${route.buttonId}")
                }
                val g = kb.guidance(car, route.buttonId)
                    ?: return notice("${car.modelNameKo}의 $feature 정보는 아직 없어요. 설명서나 렌터카 업체에 확인해 주세요.", "button_missing")
                val tier = if (g.tier.speakable && restrictToIntents.isNotEmpty() && intentId !in restrictToIntents) {
                    GuidanceTier.EXISTS_ONLY
                } else g.tier
                buttonAnswer(kb, car, intentId, g, tier, driving, source, "$reason|tier=$tier|${g.reason}", asksPermission)
            }
        }
    }

    private fun buttonAnswer(
        kb: RendyKnowledgeBase,
        car: HyundaiCarModel,
        intentId: String,
        g: ButtonGuidance,
        tier: GuidanceTier,
        driving: DrivingState,
        source: AnswerSource,
        reason: String,
        asksPermission: Boolean
    ): VoiceAnswer {
        val feature = kb.featureName(intentId)
        val spoken = g.spokenAnswer
        if (tier.speakable && spoken != null) {
            val stopNote = g.safetyNote()?.takeIf { n -> STOP_WORDS.any { n.contains(it) } } ?: PARK_FIRST_NOTICE
            val text = when {
                // Location only (never steps) while driving; without a verified location, only the stop instruction.
                g.requiresParked && driving == DrivingState.DRIVING ->
                    listOfNotNull(g.locationSentence, stopNote, if (g.locationSentence == null) PARKED_STEPS_TEXT else null).joinToString(" ")
                g.requiresParked && STOP_WORDS.none { spoken.contains(it) } -> "$spoken $PARK_FIRST_NOTICE"
                driving == DrivingState.DRIVING || asksPermission || g.appendSafetyNote ->
                    listOfNotNull(spoken, g.safetyNote(preferDriving = true)).joinToString(" ")
                g.answerPolicy == "hedged_safety" -> listOfNotNull(spoken, g.safetyNote()).joinToString(" ")
                else -> spoken
            }
            val title = if (g.overlay == null) g.button.name else feature
            return VoiceAnswer(
                title, text, source, intentId, reason, g.buttonId, tier,
                DialogueStateUpdate.Set(car.selectionId, intentId, g.buttonId, tier, text)
            )
        }
        // One sentence each, so the safety note (hedged_safety) still keeps the answer within two sentences.
        val base = when (tier) {
            GuidanceTier.NOT_APPLICABLE -> "${car.modelNameKo}에는 $feature 기능이 없어요."
            GuidanceTier.EXISTS_ONLY ->
                "$feature 기능은 이 차 설명서에 있지만 정확한 위치와 조작법은 아직 확인 중이라, 차를 세운 뒤 설명서나 렌터카 업체에 확인해 주세요."
            else -> "$feature 기능은 아직 확인된 안내가 없어서, 차를 세운 뒤 설명서나 렌터카 업체에 확인해 주세요."
        }
        val text = if (g.answerPolicy == "hedged_safety") listOfNotNull(base, g.safetyNote()).joinToString(" ") else base
        return VoiceAnswer(
            feature, text, source, intentId, reason, g.buttonId, tier,
            DialogueStateUpdate.Set(car.selectionId, intentId, g.buttonId, tier, text)
        )
    }

    private fun redirect(kb: RendyKnowledgeBase, car: HyundaiCarModel, route: IntentRoute.Redirect, source: AnswerSource, reason: String): VoiceAnswer {
        val feature = kb.featureName(route.intentId)
        val from = kb.guidance(car, route.buttonId)
        // A NOT_APPLICABLE entry's own first sentence states the absence (e.g. "아이오닉 5는 전기차라 주유구가 없어요.").
        val absence = from?.takeIf { it.tier == GuidanceTier.NOT_APPLICABLE }?.button?.spokenAnswer
            ?.let { ButtonGuidance.sentences(it).firstOrNull() }?.takeIf { it.contains("없") }
            ?: "${car.modelNameKo}에는 $feature 기능이 없어요."
        val target = kb.guidance(car, route.targetButtonId)
        val targetIntent = kb.intentForButton(car, route.targetButtonId)
        val targetName = targetIntent?.let { kb.featureName(it) } ?: "대신 쓰는 기능"
        return if (target != null && target.tier.speakable && target.spokenAnswer != null && targetIntent != null) {
            val text = "$absence ${target.spokenAnswer}"
            VoiceAnswer(target.button.name, text, source, targetIntent, "$reason|redirect", target.buttonId, target.tier,
                DialogueStateUpdate.Set(car.selectionId, targetIntent, target.buttonId, target.tier, text))
        } else {
            VoiceAnswer(feature, "$absence $targetName 위치는 아직 확인된 안내가 없어요.", source, route.intentId,
                "$reason|redirect|target_tier=${target?.tier}", route.buttonId, from?.tier, DialogueStateUpdate.Clear)
        }
    }

    /** "못 찾겠어", "찾았어/그다음은?", "됐어?" for the control answered last. */
    fun followUp(
        kb: RendyKnowledgeBase,
        car: HyundaiCarModel,
        kind: FollowUpKind,
        state: DialogueState?,
        driving: DrivingState
    ): VoiceAnswer {
        val src = AnswerSource.FOLLOW_UP
        if (state == null) {
            return VoiceAnswer(RendyQuestionPipeline.CLARIFICATION_TITLE, NO_CONTEXT_TEXT, AnswerSource.CLARIFICATION, null,
                "follow_up_without_context:$kind", stateUpdate = DialogueStateUpdate.Clear)
        }
        fun reply(text: String, why: String, update: DialogueStateUpdate) =
            VoiceAnswer(state.title, text, src, state.intentId, "follow_up:$kind|$why", state.buttonId, state.tier, update)

        when (kind) {
            FollowUpKind.CANCEL -> return reply(CANCEL_TEXT, "cancel", DialogueStateUpdate.Clear)
            FollowUpKind.REPEAT -> return reply(state.lastSpoken, "repeat", DialogueStateUpdate.Touch(state.lastSpoken))
            else -> Unit
        }
        val g = state.buttonId?.let { kb.guidance(car, it) }
        if (g == null || !g.tier.speakable || state.tier?.speakable != true) {
            return reply(ESCALATE_TEXT, "not_verified", DialogueStateUpdate.Advance(state.stepIndex, state.notFoundCount, ESCALATE_TEXT))
        }
        return when (kind) {
            FollowUpKind.NOT_FOUND -> {
                // detailed_location / visual_landmarks carry no per-field verification and contain details the
                // DB's own notes mark as unconfirmed, so only the verified location sentence is repeated.
                val text = when {
                    g.locationSentence == null && g.appearanceSentence != null && state.notFoundCount == 0 ->
                        "${g.appearanceSentence} ${ButtonGuidance.NOTICE_LOCATION_UNCONFIRMED}"
                    g.locationSentence == null -> LOCATION_UNKNOWN_TEXT
                    state.notFoundCount == 0 -> "${g.locationSentence} 운전 중이라면 차를 세운 뒤 천천히 찾아보세요."
                    else -> NOT_FOUND_AGAIN_TEXT
                }
                reply(text, "not_found_${state.notFoundCount + 1}", DialogueStateUpdate.Advance(state.stepIndex, state.notFoundCount + 1, text))
            }
            FollowUpKind.FOUND -> {
                if (g.requiresParked && driving == DrivingState.DRIVING) {
                    return reply(PARKED_STEPS_TEXT, "deferred_until_parked", DialogueStateUpdate.Touch(PARKED_STEPS_TEXT))
                }
                val steps = g.steps
                if (steps.isEmpty()) {
                    return reply(OPERATION_UNKNOWN_TEXT, "operation_unverified", DialogueStateUpdate.Advance(state.stepIndex, state.notFoundCount, OPERATION_UNKNOWN_TEXT))
                }
                val next = state.stepIndex + 1
                if (next < steps.size) {
                    val prefix = if (next == 0 && g.requiresParked && driving != DrivingState.PARKED) "차를 세운 상태에서 해 주세요. " else ""
                    val text = prefix + steps[next]
                    reply(text, "step_${next + 1}_of_${steps.size}", DialogueStateUpdate.Advance(next, state.notFoundCount, text))
                } else {
                    val text = doneCheck(g)
                    reply(text, "steps_complete", DialogueStateUpdate.Advance(steps.size, state.notFoundCount, text))
                }
            }
            FollowUpKind.DONE_CHECK -> {
                val text = doneCheck(g)
                reply(text, "done_check", DialogueStateUpdate.Advance(state.stepIndex, state.notFoundCount, text))
            }
            else -> reply(ESCALATE_TEXT, "unsupported", DialogueStateUpdate.Clear)
        }
    }

    /** Never claims success: the app cannot read the car's state. */
    private fun doneCheck(g: ButtonGuidance): String =
        g.expectedResult?.let { "$CANNOT_SEE_CAR $it" } ?: GENERIC_DONE_CHECK

    /** Korean topic particle (은/는) for a model name, reading a trailing digit aloud. */
    fun topic(word: String): String {
        val c = word.trim().lastOrNull() ?: return "는"
        val hasBatchim = when (c) {
            in '가'..'힣' -> (c - '가') % 28 != 0
            in '0'..'9' -> c in "013678" // 영 일 삼 육 칠 팔 end in a consonant; 이 사 오 구 do not
            else -> false
        }
        return if (hasBatchim) "은" else "는"
    }
}
