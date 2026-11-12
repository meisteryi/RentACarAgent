package com.hyundai.rentalhelper.intent

import com.hyundai.rentalhelper.data.ButtonDetail
import com.hyundai.rentalhelper.data.ClarificationRule
import com.hyundai.rentalhelper.data.FaqIntent
import com.hyundai.rentalhelper.data.HyundaiCarModel
import com.hyundai.rentalhelper.data.HyundaiDatabase
import com.hyundai.rentalhelper.data.OverlayEntry
import com.hyundai.rentalhelper.data.VerificationOverlay

/** Where an intent leads for one car model (from v4.2 `model_routing`, else the intent's response_key). */
sealed class IntentRoute {
    abstract val intentId: String

    data class Button(override val intentId: String, val buttonId: String, val viaFallback: Boolean = false) : IntentRoute()
    /** The car has no such control; [buttonId] states that and the driver is pointed to [targetButtonId]. */
    data class Redirect(override val intentId: String, val buttonId: String, val targetButtonId: String) : IntentRoute()
    data class NotApplicableNonEv(override val intentId: String) : IntentRoute()
    /** No data for this car. This is NOT a claim that the feature is absent. */
    data class NoData(override val intentId: String, val reason: String) : IntentRoute()
}

/**
 * How much of a button may be spoken, decided per car, button and FIELD.
 *  - FULL: location AND operation verified. Either both v4.2 flags are true (the DB `spoken_answer` is used), or
 *    the verification overlay holds an exact location and an operation from an official source.
 *  - LOCATION_ONLY / OPERATION_ONLY: only that field is verified (overlay). Only that field's text is spoken, plus a
 *    short "the other part is not confirmed" notice. The v4.2 `spoken_answer` mixes both, so it is not used.
 *  - EXISTS_ONLY: the official manual lists the feature, nothing speakable about location/operation.
 *  - UNVERIFIED: no official evidence (also every button of a v4.0 DB without `verification`).
 *  - NOT_APPLICABLE: the DB states the car does not have it.
 */
enum class GuidanceTier {
    FULL, LOCATION_ONLY, OPERATION_ONLY, EXISTS_ONLY, UNVERIFIED, NOT_APPLICABLE;

    val speakable: Boolean get() = this == FULL || this == LOCATION_ONLY || this == OPERATION_ONLY
}

data class ButtonGuidance(
    val buttonId: String,
    val button: ButtonDetail,
    val tier: GuidanceTier,
    val reason: String,
    /** Officially sourced field texts (overlay); null when the DB's own FULL answer is used or nothing is verified. */
    val overlay: OverlayEntry? = null
) {
    private val dbFull get() = tier == GuidanceTier.FULL && overlay == null

    val answerPolicy: String? get() = button.verification?.answerPolicy

    private val overlayLocation: String? get() = overlay?.location?.text?.trim()?.takeIf { it.isNotEmpty() }
    private val overlayOperation: String? get() = overlay?.operation?.text?.trim()?.takeIf { it.isNotEmpty() }
    private val overlayAppearance: String? get() = overlay?.appearance?.text?.trim()?.takeIf { it.isNotEmpty() }
    val locationIsExact: Boolean get() = dbFull || overlay?.location?.scope == "exact"

    /**
     * The first spoken answer: DB `spoken_answer` for a DB-verified button; otherwise location -> appearance ->
     * operation from the overlay, plus a short notice for the field that is not confirmed. Null when nothing is speakable.
     */
    val spokenAnswer: String? get() {
        if (!tier.speakable) return null
        if (dbFull) return button.spokenAnswer?.trim()?.takeIf { it.isNotEmpty() }
        if (overlayLocation == null && overlayOperation == null) return null
        val notice = when {
            overlayOperation == null -> NOTICE_OPERATION_UNCONFIRMED
            !locationIsExact && overlayLocation != null -> NOTICE_EXACT_POSITION_UNCONFIRMED
            !locationIsExact -> NOTICE_LOCATION_UNCONFIRMED
            else -> null
        }
        // At most two sentences: location > first operation sentence > appearance, spoken in that natural order.
        // The rest of the operation is read step by step on "찾았어 / 그다음은?".
        var budget = MAX_FIRST_ANSWER_SENTENCES - (if (notice != null) 1 else 0)
        val loc = overlayLocation?.takeIf { budget > 0 }?.also { budget -= sentences(it).size }
        val opSentences = overlayOperation?.let { sentences(it) }.orEmpty()
        val op = opSentences.take(budget.coerceAtLeast(0)).takeIf { it.isNotEmpty() }?.joinToString(" ")?.also { budget -= sentences(it).size }
        val look = overlayAppearance?.takeIf { budget > 0 }
        return listOfNotNull(loc, look, op, notice).joinToString(" ")
    }

    /** Verified appearance clue (overlay), used when the position itself is not confirmed. */
    val appearanceSentence: String? get() = if (tier.speakable) overlayAppearance else null

    /** Verified location sentence (exact or component-level), or null. */
    val locationSentence: String? get() = when {
        !tier.speakable -> null
        dbFull -> button.spokenAnswer?.trim()?.let { sentences(it).firstOrNull() }
        else -> overlayLocation
    }

    val steps: List<String> get() = when {
        !tier.speakable -> emptyList()
        dbFull -> button.operationSteps.orEmpty()
        else -> overlay?.operation?.steps?.takeIf { it.isNotEmpty() } ?: listOfNotNull(overlayOperation)
    }.map { it.trim() }.filter { it.isNotEmpty() }

    /** Button-specific check the driver can do. The 159 copies of the generic template are not specific. */
    val expectedResult: String? get() = when {
        !tier.speakable -> null
        dbFull -> button.expectedResult?.trim()?.takeIf { it.isNotEmpty() && it != GENERIC_EXPECTED_RESULT }
        else -> overlay?.operation?.result?.trim()?.takeIf { it.isNotEmpty() }
    }

    val appendSafetyNote: Boolean get() = overlay?.appendSafetyNote == true

    val requiresParked: Boolean get() = button.requiresParked == true

    /** Caution text only (never a location or step). The DB's generic "unverified" disclaimer is excluded. */
    fun safetyNote(preferDriving: Boolean = false): String? {
        val notes = button.safetyNotes.orEmpty().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith(UNVERIFIED_DISCLAIMER_PREFIX) }
        return (if (preferDriving) notes.firstOrNull { it.contains("주행") } else null) ?: notes.firstOrNull()
    }

    companion object {
        const val GENERIC_EXPECTED_RESULT = "버튼 불빛이나 계기판 표시가 바뀌는지 확인해 보세요."
        const val MAX_FIRST_ANSWER_SENTENCES = 2
        const val NOTICE_OPERATION_UNCONFIRMED = "조작 방법은 아직 확인되지 않았어요."
        const val NOTICE_LOCATION_UNCONFIRMED = "정확한 위치는 아직 확인되지 않았어요."
        const val NOTICE_EXACT_POSITION_UNCONFIRMED = "조작부 안의 정확한 버튼 위치는 아직 확인되지 않았어요."
        private const val UNVERIFIED_DISCLAIMER_PREFIX = "공식 자료로 확인되지 않은"

        fun sentences(text: String): List<String> =
            text.trim().split(Regex("(?<=[.?!])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

enum class FollowUpKind { NOT_FOUND, FOUND, DONE_CHECK, REPEAT, CANCEL }

/**
 * Read-only view of the v4.2 DB used by routing, answering and follow-ups. It never composes vehicle
 * instructions: everything spoken about a control comes from a FULL-tier button of the selected car.
 */
class RendyKnowledgeBase(val database: HyundaiDatabase, overlay: VerificationOverlay? = null) {
    private val enhancements = database.intentEnhancements
    private val allButtonIds: Set<String> = database.models.flatMapTo(mutableSetOf()) { it.buttons.keys }

    /** Overlay entries that were not accepted, with the reason. */
    val rejectedOverlayEntries: List<String>

    /** Accepted overlay entries keyed by (modelId, buttonId). */
    private val overlayByButton: Map<Pair<String, String>, OverlayEntry>

    init {
        val accepted = mutableMapOf<Pair<String, String>, OverlayEntry>()
        val rejected = mutableListOf<String>()
        for (e in overlay?.entries.orEmpty()) {
            val key = "${e.model}.${e.button}"
            val car = database.models.firstOrNull { it.modelId == e.model }
            val button = car?.buttons?.get(e.button.orEmpty())
            val fields = listOfNotNull(e.location, e.operation, e.appearance)
            when {
                car == null || button == null -> rejected += "$key: unknown model/button"
                button.verification?.status == STATUS_NOT_APPLICABLE -> rejected += "$key: button is NOT_APPLICABLE"
                e.location == null && e.operation == null -> rejected += "$key: no location or operation field"
                fields.any { it.text.isNullOrBlank() || it.evidence.isNullOrBlank() || it.source.isNullOrBlank() } ->
                    rejected += "$key: field without text/evidence/source"
                e.location != null && e.location.scope !in setOf("exact", "component") -> rejected += "$key: invalid location scope"
                (car.modelId to e.button!!) in accepted -> rejected += "$key: duplicate entry"
                else -> accepted[car.modelId to e.button] = e
            }
        }
        overlayByButton = accepted
        rejectedOverlayEntries = rejected
    }

    /** Overlay entry used for a button, if any (for reports). */
    fun overlayFor(car: HyundaiCarModel, buttonId: String): OverlayEntry? = overlayByButton[car.modelId to buttonId]

    /** Proposed v4.2 intents that were not accepted, with the reason. Reported, never silently dropped. */
    val rejectedProposals: List<String>

    /** The 33 FAQ intents followed by the accepted v4.2 proposals, in declaration order. */
    val intents: List<FaqIntent>

    init {
        val accepted = mutableListOf<FaqIntent>()
        val rejected = mutableListOf<String>()
        val ids = database.faqIntents.mapTo(mutableSetOf()) { it.intent }
        for (p in enhancements?.proposedNewIntents.orEmpty()) {
            val id = p.intent
            when {
                !ID_PATTERN.matches(id) -> rejected += "$id: invalid id"
                id in ids -> rejected += "$id: duplicates an existing intent id"
                p.keywords.isNullOrEmpty() -> rejected += "$id: no keywords"
                p.responseKey != null && p.responseKey !in allButtonIds -> rejected += "$id: response_key ${p.responseKey} is not a button"
                p.responseKey == null && id != TEMPERATURE_INTENT -> rejected += "$id: no response_key"
                else -> { accepted += p; ids += id }
            }
        }
        intents = database.faqIntents + accepted
        rejectedProposals = rejected
    }

    private val intentsById = intents.associateBy { it.intent }

    val clarificationRules: List<ClarificationRule> =
        enhancements?.clarificationRules.orEmpty().filter { !it.phrase.isNullOrBlank() }

    private val followUpPhrases: List<Pair<FollowUpKind, Set<String>>> = run {
        val raw = enhancements?.followUpPhrases.orEmpty()
        // Cancel before done-check so "됐어 그만" is not read as "됐어?".
        listOf(
            FollowUpKind.CANCEL to "cancel", FollowUpKind.DONE_CHECK to "done_check", FollowUpKind.REPEAT to "repeat",
            FollowUpKind.NOT_FOUND to "not_found", FollowUpKind.FOUND to "found"
        ).map { (kind, key) -> kind to raw[key].orEmpty().map(::normalizeUtterance).filter { it.isNotEmpty() }.toSet() }
    }

    fun intent(id: String): FaqIntent? = intentsById[id]

    fun route(car: HyundaiCarModel, intentId: String): IntentRoute {
        val intent = intentsById[intentId] ?: return IntentRoute.NoData(intentId, "unknown_intent")
        val entry = enhancements?.modelRouting?.get(intentId)?.get(car.modelId)
        if (entry != null) {
            return when (entry.action) {
                "button", "fallback" -> buttonRoute(car, intentId, entry.button, viaFallback = entry.action == "fallback")
                "redirect" -> {
                    val from = entry.button
                    val to = entry.to
                    if (from != null && to != null && from in car.buttons && to in car.buttons) IntentRoute.Redirect(intentId, from, to)
                    else IntentRoute.NoData(intentId, "redirect_target_missing")
                }
                "not_applicable_non_ev" -> IntentRoute.NotApplicableNonEv(intentId)
                "no_data" -> IntentRoute.NoData(intentId, "model_routing_no_data")
                else -> IntentRoute.NoData(intentId, "unknown_routing_action")
            }
        }
        val key = when {
            intent.isGear -> GEAR_BUTTON
            intent.responseKey != null -> intent.responseKey
            // The DB note on `temperature` lists these three per-model controls; the first one the car has wins.
            intentId == TEMPERATURE_INTENT -> TEMPERATURE_BUTTONS.firstOrNull { it in car.buttons }
            else -> null
        }
        return buttonRoute(car, intentId, key, viaFallback = false)
    }

    private fun buttonRoute(car: HyundaiCarModel, intentId: String, key: String?, viaFallback: Boolean): IntentRoute {
        if (key == null || key !in car.buttons) return IntentRoute.NoData(intentId, "button_missing_for_model")
        val canonical = canonicalButtonId(car, key)
        val button = car.buttons.getValue(canonical)
        val redirect = button.redirectTo
        if (button.verification?.status == STATUS_NOT_APPLICABLE && redirect != null && redirect in car.buttons && redirect != canonical) {
            return IntentRoute.Redirect(intentId, canonical, redirect)
        }
        return IntentRoute.Button(intentId, canonical, viaFallback)
    }

    /** Follows `duplicate_of` (bounded, cycle-safe) so duplicated entries share one verification state. */
    fun canonicalButtonId(car: HyundaiCarModel, buttonId: String): String {
        var current = buttonId
        val seen = mutableSetOf(current)
        while (true) {
            val next = car.buttons[current]?.duplicateOf ?: return current
            if (next !in car.buttons || !seen.add(next) || current == next) return current
            current = next
        }
    }

    fun guidance(car: HyundaiCarModel, buttonId: String): ButtonGuidance? {
        val button = car.buttons[buttonId] ?: return null
        val v = button.verification
        val status = v?.status
        val dbFull = status in EVIDENCED_STATUSES && v?.locationVerified == true && v.operationVerified == true &&
            v.answerPolicy in SPEAKABLE_POLICIES && !button.spokenAnswer.isNullOrBlank()
        val o = if (dbFull || status == STATUS_NOT_APPLICABLE) null else overlayByButton[car.modelId to buttonId]
        val tier = when {
            status == STATUS_NOT_APPLICABLE -> GuidanceTier.NOT_APPLICABLE
            dbFull -> GuidanceTier.FULL
            o != null && o.operation != null && o.location?.scope == "exact" -> GuidanceTier.FULL
            o != null && o.operation != null -> GuidanceTier.OPERATION_ONLY
            o != null && o.location != null -> GuidanceTier.LOCATION_ONLY
            status in EVIDENCED_STATUSES -> GuidanceTier.EXISTS_ONLY
            else -> GuidanceTier.UNVERIFIED
        }
        val reason = "status=${status ?: "none"},loc=${v?.locationVerified},op=${v?.operationVerified},policy=${v?.answerPolicy}" +
            (o?.let { ",overlay=${listOfNotNull(it.location?.let { l -> "loc:${l.scope}" }, it.operation?.let { "op" }).joinToString("+")}" } ?: "")
        return ButtonGuidance(buttonId, button, tier, reason, o)
    }

    /** Every intent is offered: one with no verified answer still gets a truthful "not confirmed / no data" reply. */
    fun candidatesFor(car: HyundaiCarModel): List<IntentCandidate> = intents.map { intent ->
        val route = route(car, intent.intent)
        val buttonName = when (route) {
            is IntentRoute.Button -> car.buttons[route.buttonId]?.name
            is IntentRoute.Redirect -> car.buttons[route.buttonId]?.name
            else -> null
        }
        val label = listOfNotNull(featureName(intent.intent), buttonName).joinToString(" / ").take(MAX_LABEL_CHARS)
        IntentCandidate(
            id = intent.intent,
            examples = intent.keywords.map { it.trim().take(MAX_EXAMPLE_CHARS) }.filter { it.isNotEmpty() }.distinct().take(MAX_EXAMPLES),
            label = label
        )
    }

    /** Intent whose direct (non-fallback) route for this car lands on [buttonId], if any. */
    fun intentForButton(car: HyundaiCarModel, buttonId: String): String? = intents.firstOrNull { intent ->
        when (val r = route(car, intent.intent)) {
            is IntentRoute.Button -> r.buttonId == buttonId && !r.viaFallback
            else -> false
        }
    }?.intent

    /**
     * The DB's own clarification question for an unclear utterance, if one applies to this car:
     * the phrase occurs in the utterance and at least two of its candidate controls exist on the car
     * (or the rule has no candidates, e.g. "어떻게 꺼").
     */
    fun clarificationQuestion(query: String, car: HyundaiCarModel): String? {
        val normalized = normalizeUtterance(query)
        return clarificationRules
            .filter { rule ->
                val phrase = normalizeUtterance(rule.phrase.orEmpty())
                val candidates = rule.candidates.orEmpty()
                !rule.question.isNullOrBlank() && phrase.isNotEmpty() && normalized.contains(phrase) &&
                    (candidates.isEmpty() || candidates.count { it in car.buttons } >= 2)
            }
            .maxByOrNull { normalizeUtterance(it.phrase.orEmpty()).length }
            ?.question
    }

    /**
     * DB clarification question for an utterance that says nothing beyond an ambiguous phrase plus filler
     * ("깜빡이 켜줘", "브레이크 어떻게 해?", "차가 안 움직여"). Applied after any classifier, so an LLM guess cannot
     * override the DB's own clarification rule. "시트 열선 켜줘" is not affected ("시트" narrows it).
     */
    fun mandatoryClarification(query: String, car: HyundaiCarModel): String? {
        val normalized = normalizeUtterance(query)
        val rule = clarificationRules
            .filter { r ->
                val phrase = normalizeUtterance(r.phrase.orEmpty())
                val candidates = r.candidates.orEmpty()
                !r.question.isNullOrBlank() && phrase.isNotEmpty() && normalized.contains(phrase) &&
                    (candidates.isEmpty() || candidates.count { it in car.buttons } >= 2)
            }
            .maxByOrNull { normalizeUtterance(it.phrase.orEmpty()).length } ?: return null
        var residue = normalized.replace(normalizeUtterance(rule.phrase.orEmpty()), "")
        FILLER_TOKENS.forEach { residue = residue.replace(it, "") }
        return rule.question.takeIf { residue.length <= 1 }
    }

    /** Exact-phrase follow-up detection; longer sentences such as "앞이 안 보여" are new questions. */
    fun followUpKind(query: String): FollowUpKind? {
        val q = stripFollowUpFillers(normalizeUtterance(query))
        if (q.isEmpty()) return null
        val variants = setOf(q, q.removeSuffix("요"))
        return followUpPhrases.firstOrNull { (_, phrases) -> variants.any { it in phrases } }?.first
    }

    /** Generic Korean name of the feature, used only in notices that contain no location or steps. */
    fun featureName(intentId: String): String = FEATURE_NAMES[intentId] ?: intentId

    companion object {
        const val GEAR_BUTTON = "gear_shift"
        const val TEMPERATURE_INTENT = "temperature"
        val TEMPERATURE_BUTTONS = listOf("temp_toggle_driver", "temp_dial_driver", "climate_temp_dial")
        const val STATUS_NOT_APPLICABLE = "NOT_APPLICABLE"
        val EVIDENCED_STATUSES = setOf("VERIFIED", "PARTIAL", "CONFLICT")
        val SPEAKABLE_POLICIES = setOf("direct", "direct_with_caution", "corrected_official")
        private val ID_PATTERN = Regex("^[a-z0-9_]{1,64}$")
        // Proxy request limits (server.mjs validateRequest).
        private const val MAX_EXAMPLES = 30
        private const val MAX_EXAMPLE_CHARS = 40
        private const val MAX_LABEL_CHARS = 80

        private val WAKE_PREFIX = Regex("^(렌디야|랜디야|렌디|랜디)")
        private val FILLERS = listOf("아직도", "아직", "여전히", "그래도")
        /** Verbs/particles that carry no feature information (longest first). */
        private val FILLER_TOKENS = listOf(
            "어떻게해야돼", "어떻게하는거야", "어떻게해", "어떻게", "알려줘", "켜는법", "끄는법", "쓰는법", "하는법",
            "어딨어", "어디있어", "어디야", "어디", "있어", "해야돼", "해줘", "켜줘", "꺼줘", "줘", "해", "켜", "꺼", "법",
            "좀", "요", "은", "는", "을", "를", "이", "가", "돼"
        )

        /** Generic references to the control just discussed ("레버가 안 보여" = "안 보여"). Not feature names. */
        private val GENERIC_OBJECTS = listOf("레버가", "버튼이", "스위치가", "다이얼이", "그게", "그거", "이게", "이거", "거기", "레버", "버튼", "스위치")

        /** Lower-cased, whitespace and punctuation removed, wake word removed. */
        fun normalizeUtterance(text: String): String =
            text.lowercase().replace(Regex("[\\s?!.,~…·'\"]"), "").replace(WAKE_PREFIX, "")

        private fun stripFollowUpFillers(q: String): String {
            var s = q
            FILLERS.firstOrNull { s.startsWith(it) && s.length > it.length }?.let { s = s.removePrefix(it) }
            GENERIC_OBJECTS.firstOrNull { s.startsWith(it) && s.length > it.length }?.let { s = s.removePrefix(it) }
            return s
        }

        private val FEATURE_NAMES = mapOf(
            "front_defog" to "앞유리 김서림 제거", "rear_defog" to "뒷유리 열선", "hazard_light" to "비상등",
            "smart_cruise" to "스마트 크루즈", "lane_follow_assist" to "차로 유지 보조", "wiper_stalk" to "와이퍼",
            "light_stalk" to "라이트", "electronic_parking_brake" to "주차 브레이크", "auto_hold" to "오토홀드",
            "gear_type" to "기어 변속", "charge_door" to "충전구", "fuel_door" to "주유구", "tailgate" to "트렁크",
            "regen_paddle" to "회생제동 패들", "drive_mode" to "주행 모드", "seat_vent_heat" to "시트 열선·통풍",
            "heated_steering_wheel" to "핸들 열선", "v2l" to "실내 전원 콘센트", "sliding_console" to "슬라이딩 콘솔",
            "window_lock" to "창문 잠금", "door_lock_unlock" to "문 잠금", "side_mirror_adjust" to "사이드미러 조절",
            "side_mirror_fold" to "사이드미러 접기", "hood_open" to "보닛 열기", "battery_reset" to "12V 배터리 리셋",
            "engine_start" to "시동", "parking_sensor" to "주차 경고음", "parking_view" to "주차 카메라",
            "sunroof_switch" to "선루프", "seat_adjustment" to "시트 조절", "seat_full_folding" to "시트 풀 폴딩",
            "shift_lock_release" to "시프트 락 해제", "downhill_brake" to "경사로 저속 주행",
            "wireless_charger" to "무선 충전", "climate_auto" to "에어컨 자동 모드", "climate_off" to "에어컨 끄기",
            "air_recirculation" to "내기 순환", "esc_off" to "ESC 끄기", "cruise_gap" to "크루즈 차간거리",
            "cruise_speed" to "크루즈 속도", "audio_volume" to "음량 조절", "phone_call" to "전화 버튼",
            "voice_recognition" to "음성 인식 버튼", "headlight_leveling" to "전조등 높이 조절",
            "paddle_shift" to "패들 시프트", "memory_seat" to "시트 메모리", "sos_button" to "SOS 버튼",
            "room_lamp" to "실내등", "cluster_view" to "계기판 화면", "foot_parking_brake" to "풋 파킹 브레이크",
            "temperature" to "실내 온도"
        )
    }
}
