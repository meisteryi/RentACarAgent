package com.hyundai.rentalhelper.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.hyundai.rentalhelper.intent.RendyKnowledgeBase
import com.hyundai.rentalhelper.intent.RendyQuestionPipeline
import java.io.InputStreamReader

data class CarSpecs(
    @SerializedName("length_mm") val lengthMm: Int,
    @SerializedName("width_mm") val widthMm: Int,
    @SerializedName("height_mm") val heightMm: Int,
    @SerializedName("wheelbase_mm") val wheelbaseMm: Int
)

/**
 * v4.2 per-button verification against the official owner's manual. Gson does not honour Kotlin
 * defaults, so every field is nullable and a missing block (v4.0 DB) reads as "not verified".
 */
data class ButtonVerification(
    @SerializedName("status") val status: String? = null,
    @SerializedName("priority") val priority: String? = null,
    @SerializedName("answer_policy") val answerPolicy: String? = null,
    @SerializedName("location_verified") val locationVerified: Boolean? = null,
    @SerializedName("operation_verified") val operationVerified: Boolean? = null,
    @SerializedName("source_url") val sourceUrl: String? = null,
    @SerializedName("source_urls") val sourceUrls: List<String>? = null,
    @SerializedName("source_page") val sourcePage: String? = null,
    @SerializedName("finding") val finding: String? = null,
    @SerializedName("notes") val notes: String? = null,
    @SerializedName("needed_evidence") val neededEvidence: String? = null
)

data class ButtonFollowUp(
    @SerializedName("on_not_found") val onNotFound: String? = null,
    @SerializedName("on_found") val onFound: String? = null,
    @SerializedName("on_done") val onDone: String? = null,
    @SerializedName("require_user_confirmation") val requireUserConfirmation: Boolean? = null,
    @SerializedName("max_steps_per_turn") val maxStepsPerTurn: Int? = null
)

data class ButtonDetail(
    @SerializedName("name") val name: String,
    @SerializedName("zone") val zone: String? = null,
    @SerializedName("location") val location: String,
    @SerializedName("action_guide") val actionGuide: String,
    // ---- v4.2 (optional; absent in v4.0) ----
    @SerializedName("spoken_answer") val spokenAnswer: String? = null,
    @SerializedName("detailed_location") val detailedLocation: String? = null,
    @SerializedName("operation_steps") val operationSteps: List<String>? = null,
    @SerializedName("visual_landmarks") val visualLandmarks: List<String>? = null,
    @SerializedName("expected_result") val expectedResult: String? = null,
    @SerializedName("user_phrases") val userPhrases: List<String>? = null,
    @SerializedName("control_type") val controlType: String? = null,
    @SerializedName("safety_notes") val safetyNotes: List<String>? = null,
    @SerializedName("requires_parked") val requiresParked: Boolean? = null,
    @SerializedName("follow_up") val followUp: ButtonFollowUp? = null,
    @SerializedName("redirect_to") val redirectTo: String? = null,
    @SerializedName("duplicate_of") val duplicateOf: String? = null,
    @SerializedName("verification") val verification: ButtonVerification? = null
)

data class HyundaiCarModel(
    @SerializedName("model_id") val modelId: String,
    @SerializedName("model_name_ko") val modelNameKo: String,
    @SerializedName("model_name_en") val modelNameEn: String,
    @SerializedName("model_year") val modelYear: String? = null,
    @SerializedName("segment") val segment: String,
    @SerializedName("segment_detail") val segmentDetail: String,
    @SerializedName("specs") val specs: CarSpecs,
    @SerializedName("gear_type") val gearType: String,
    @SerializedName("buttons") val buttons: Map<String, ButtonDetail>,
    // ---- Design variant (set only on variants from [RendyVehicleVariants]; null for a plain DB model) ----
    /** Manufacturer model code, e.g. "NE1". */
    @SerializedName("model_code") val modelCode: String? = null,
    /** "facelift_the_new" / "pre_facelift"; null = design not known (only design-neutral data applies). */
    @SerializedName("design_generation") val designGeneration: String? = null,
    /** Selectable id of the variant (e.g. IONIQ5_NE1_FL); null for a plain DB model. */
    @SerializedName("variant_id") val variantId: String? = null
) {
    /**
     * Identity of the selected car for conversation context and car-change detection. [modelId] stays the DB
     * model (routing and design-neutral data are shared by both designs); a design variant must not inherit a
     * conversation held with the other design.
     */
    val selectionId: String get() = variantId ?: modelId
}

data class FaqIntent(
    @SerializedName("intent") val intent: String,
    @SerializedName("keywords") val keywords: List<String>,
    @SerializedName("response_key") val responseKey: String?,
    @SerializedName("is_gear") val isGear: Boolean = false,
    /** v4.2 proposed intents only (e.g. per-model branching hint for `temperature`). */
    @SerializedName("note") val note: String? = null
)

data class ClarificationRule(
    @SerializedName("phrase") val phrase: String? = null,
    /** Button IDs (not intent IDs) the phrase may refer to. */
    @SerializedName("candidates") val candidates: List<String>? = null,
    @SerializedName("question") val question: String? = null
)

data class KeywordRisk(
    @SerializedName("keyword") val keyword: String? = null,
    @SerializedName("intent") val intent: String? = null,
    @SerializedName("issue") val issue: String? = null
)

/** One `model_routing[intent][model]` entry. Actions present in v4.2: button, fallback, redirect, no_data, not_applicable_non_ev. */
data class ModelRoute(
    @SerializedName("action") val action: String? = null,
    @SerializedName("button") val button: String? = null,
    @SerializedName("to") val to: String? = null
)

data class IntentEnhancements(
    @SerializedName("clarification_rules") val clarificationRules: List<ClarificationRule>? = null,
    @SerializedName("keyword_risks") val keywordRisks: List<KeywordRisk>? = null,
    @SerializedName("proposed_new_intents") val proposedNewIntents: List<FaqIntent>? = null,
    @SerializedName("model_routing") val modelRouting: Map<String, Map<String, ModelRoute>>? = null,
    @SerializedName("follow_up_phrases") val followUpPhrases: Map<String, List<String>>? = null,
    @SerializedName("dialogue_rules") val dialogueRules: List<String>? = null
)

/**
 * One officially sourced, speakable field of a button (assets/rendy_verification_overlay.json).
 * [text] restates only [evidence]; [scope] is "exact" (position on the car) or "component" (panel/unit only).
 */
data class OverlayField(
    @SerializedName("text") val text: String? = null,
    @SerializedName("scope") val scope: String? = null,
    @SerializedName("steps") val steps: List<String>? = null,
    @SerializedName("result") val result: String? = null,
    @SerializedName("evidence") val evidence: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("section") val section: String? = null
)

data class OverlayEntry(
    @SerializedName("model") val model: String? = null,
    @SerializedName("button") val button: String? = null,
    @SerializedName("edition") val edition: String? = null,
    @SerializedName("location") val location: OverlayField? = null,
    @SerializedName("operation") val operation: OverlayField? = null,
    @SerializedName("appearance") val appearance: OverlayField? = null,
    /** One more verified clue for "못 찾겠어" during guidance (e.g. which neighbouring button it is NOT). */
    @SerializedName("clue") val clue: OverlayField? = null,
    /** Design this entry was verified for; null = design-neutral (applies when no design-specific entry exists). */
    @SerializedName("design_generation") val designGeneration: String? = null,
    @SerializedName("append_safety_note") val appendSafetyNote: Boolean? = null,
    @SerializedName("notes") val notes: String? = null
)

data class VerificationOverlay(
    @SerializedName("version") val version: String? = null,
    @SerializedName("base_db_version") val baseDbVersion: String? = null,
    @SerializedName("checked_date") val checkedDate: String? = null,
    @SerializedName("entries") val entries: List<OverlayEntry>? = null
)

data class HyundaiDatabase(
    @SerializedName("version") val version: String,
    @SerializedName("models") val models: List<HyundaiCarModel>,
    @SerializedName("faq_intents") val faqIntents: List<FaqIntent>,
    /** v4.2+. Null for a v4.0 DB, in which case only the 33 FAQ intents exist and nothing is verified. */
    @SerializedName("intent_enhancements") val intentEnhancements: IntentEnhancements? = null
)

class CarButtonDatabase private constructor(context: Context) {
    private var database: HyundaiDatabase? = null
    /** Optional field-level verification overlay; absent or invalid -> DB verification alone. Declared before init. */
    private var overlay: VerificationOverlay? = null

    init {
        loadDatabase(context)
    }

    companion object {
        private const val TAG = "CarButtonDatabase"
        private const val DB_FILE_NAME = "hyundai_buttons_db.json"
        private const val OVERLAY_FILE_NAME = "rendy_verification_overlay.json"

        @Volatile
        private var instance: CarButtonDatabase? = null

        fun getInstance(context: Context): CarButtonDatabase {
            return instance ?: synchronized(this) {
                instance ?: CarButtonDatabase(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun loadDatabase(context: Context) {
        try {
            context.assets.open(DB_FILE_NAME).use { inputStream ->
                InputStreamReader(inputStream).use { reader ->
                    database = Gson().fromJson(reader, HyundaiDatabase::class.java)
                    Log.i(TAG, "차량 버튼 DB 로드 완료: ${database?.models?.size}개 차종 등록됨")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "차량 버튼 DB JSON 파싱 실패", e)
        }
        try {
            context.assets.open(OVERLAY_FILE_NAME).use { inputStream ->
                InputStreamReader(inputStream, Charsets.UTF_8).use { reader ->
                    overlay = Gson().fromJson(reader, VerificationOverlay::class.java)
                    Log.i(TAG, "검증 오버레이 로드: ${overlay?.version}, entries=${overlay?.entries?.size}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "검증 오버레이 없음 또는 파싱 실패; DB 검증 정보만 사용", e)
        }
    }

    fun getAllModels(): List<HyundaiCarModel> = database?.models ?: emptyList()

    fun getFaqIntents(): List<FaqIntent> = database?.faqIntents ?: emptyList()

    fun getDatabaseVersion(): String? = database?.version

    /**
     * Routing, verification policy and follow-up data for the loaded DB (33 FAQ + accepted v4.2 intents).
     * Null only when the asset failed to load.
     */
    val knowledge: RendyKnowledgeBase? by lazy { database?.let { RendyKnowledgeBase(it, overlay) } }

    /**
     * 모델 ID로 차종 검색 (예: AVANTE, TUCSON, IONIQ5)
     */
    fun findModelById(modelId: String): HyundaiCarModel? {
        val models = database?.models ?: return null
        // A design variant id (e.g. IONIQ5_NE1_FL) resolves to its base DB model carrying the variant's identity.
        RendyVehicleVariants.resolve(models, modelId)?.let { return it }
        return models.firstOrNull {
            it.modelId.equals(modelId, ignoreCase = true)
        }
    }

    /**
     * 차종 이름 또는 VIN/CarInfo 문자열 매칭 검색
     */
    fun findModelByNameOrFuzzy(query: String): HyundaiCarModel? {
        val clean = query.trim().uppercase()
        return database?.models?.firstOrNull {
            clean.contains(it.modelId) ||
            clean.contains(it.modelNameKo.uppercase()) ||
            clean.contains(it.modelNameEn.uppercase()) ||
            it.modelNameKo.contains(query, ignoreCase = true)
        }
    }

    /**
     * [기능 2] 평소 타던 차종과 현재 렌터카의 체급 비교 1~2문장 음성 브리핑 생성
     */
    fun generateAdaptationBriefing(rentalModel: HyundaiCarModel, usualSegment: CarSegment): String {
        val rentalSegment = rentalModel.segment.uppercase()
        val rentalName = rentalModel.modelNameKo

        // [특화] 아이오닉 5 순수 전기차 전용 체급 및 EV 조작 브리핑
        if (rentalModel.modelId.equals("IONIQ5", ignoreCase = true)) {
            // Smart cruise is not part of the briefing; it is explained when the driver asks (verified overlay answer).
            return Ioniq5AdaptationBriefing.text(rentalName, usualSegment)
        }

        return when (usualSegment) {
            CarSegment.COMPACT -> { // 경차를 몰던 운전자
                when (rentalSegment) {
                    "COMPACT" -> "현재 탑승하신 차량은 ${rentalName}입니다. 평소 타시던 경차와 차폭 및 시야가 비슷하여 편안하게 주행하실 수 있습니다."
                    "SEDAN" -> "탑승하신 ${rentalName}은 평소 몰던 경차보다 차폭이 약 25센티미터 넓고 전장이 깁니다. 골목길 회전 및 주차 시 측면을 주의하세요."
                    "SUV" -> "탑승하신 ${rentalName}은 평소 타시던 경차보다 차고가 높고 차폭이 넓습니다. 높은 시야에 적응하시고, 차폭 감각에 유의하세요."
                    else -> "${rentalName}에 탑승하셨습니다. 평소 타시던 차보다 체급이 크므로 차선 유지와 코너링 회전 반경을 넓게 확보하세요."
                }
            }
            CarSegment.SEDAN -> { // 세단을 몰던 운전자
                when (rentalSegment) {
                    "COMPACT" -> "${rentalName}에 탑승하셨습니다. 평소 세단보다 전장이 짧아 회전이 민첩하니 스티어링을 부드럽게 조작해 주세요."
                    "SEDAN" -> "탑승하신 차량은 ${rentalName}입니다. 평소 타시던 세단과 시트 포지션이 유사하여 익숙하게 운전하실 수 있습니다."
                    "SUV" -> "탑승하신 ${rentalName}은 평소 타시던 세단보다 전고가 20센티미터 높아 시야가 탁 트여 있습니다. 무게중심이 높으므로 고속 코너링 시 감속하세요."
                    else -> "${rentalName}에 탑승하셨습니다. 시트 포지션과 사이드미러 각도를 먼저 맞춘 후 출발하세요."
                }
            }
            CarSegment.SUV -> { // SUV를 몰던 운전자
                when (rentalSegment) {
                    "COMPACT", "SEDAN" -> "탑승하신 ${rentalName}은 평소 타시던 SUV보다 시트 포지션이 낮아 바닥과 가깝게 느껴집니다. 전방 시야 거리를 여유 있게 확보하세요."
                    "SUV" -> "탑승하신 ${rentalName}은 평소 타시던 SUV와 비슷한 차고와 시야를 제공합니다. 편안한 드라이빙 되세요."
                    else -> "${rentalName}에 탑승하셨습니다. 평소 감각대로 안전하게 운전하세요."
                }
            }
            CarSegment.VAN -> { // 승합차를 몰던 운전자
                "${rentalName}에 탑승하셨습니다. 평소 대형 승합차보다 회전 반경이 짧고 차체가 낮아 경쾌하게 조작하실 수 있습니다."
            }
            CarSegment.TRUCK -> { // 트럭을 몰던 운전자
                "${rentalName}에 탑승하셨습니다. 상용차에 비해 브레이크 반응이 즉각적이니 페달을 부드럽게 밟아주세요."
            }
        }
    }

    /**
     * [기능 3] 음성 질문 → 키워드 규칙 → 차종별 라우팅 → 검증 정책을 거친 짧은 답변 (대화 맥락 없음).
     * v4.2부터 검증되지 않은 위치·조작법(action_guide 원문 포함)은 읽지 않으며, 매칭 실패 시
     * 근거 없는 일반 안내("센터페시아 조작부를 확인") 대신 다시 질문해 달라고 안내합니다.
     */
    fun matchVoiceQuery(carModel: HyundaiCarModel, query: String): Pair<String, String> {
        val kb = knowledge ?: return Pair(
            RendyQuestionPipeline.UNAVAILABLE_TITLE, RendyQuestionPipeline.UNAVAILABLE_TEXT
        )
        val answer = RendyQuestionPipeline.answerWithRulesOnce(kb, carModel, query)
        return Pair(answer.title, answer.text)
    }
}

