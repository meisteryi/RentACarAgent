package com.hyundai.rentalhelper.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.InputStreamReader

data class CarSpecs(
    @SerializedName("length_mm") val lengthMm: Int,
    @SerializedName("width_mm") val widthMm: Int,
    @SerializedName("height_mm") val heightMm: Int,
    @SerializedName("wheelbase_mm") val wheelbaseMm: Int
)

data class ButtonDetail(
    @SerializedName("name") val name: String,
    @SerializedName("zone") val zone: String? = null,
    @SerializedName("location") val location: String,
    @SerializedName("action_guide") val actionGuide: String
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
    @SerializedName("buttons") val buttons: Map<String, ButtonDetail>
)

data class FaqIntent(
    @SerializedName("intent") val intent: String,
    @SerializedName("keywords") val keywords: List<String>,
    @SerializedName("response_key") val responseKey: String?,
    @SerializedName("is_gear") val isGear: Boolean = false
)

data class HyundaiDatabase(
    @SerializedName("version") val version: String,
    @SerializedName("models") val models: List<HyundaiCarModel>,
    @SerializedName("faq_intents") val faqIntents: List<FaqIntent>
)

class CarButtonDatabase private constructor(context: Context) {
    private var database: HyundaiDatabase? = null

    init {
        loadDatabase(context)
    }

    companion object {
        private const val TAG = "CarButtonDatabase"
        private const val DB_FILE_NAME = "hyundai_buttons_db.json"

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
    }

    fun getAllModels(): List<HyundaiCarModel> = database?.models ?: emptyList()

    /**
     * 모델 ID로 차종 검색 (예: AVANTE, TUCSON, IONIQ5)
     */
    fun findModelById(modelId: String): HyundaiCarModel? {
        return database?.models?.firstOrNull {
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
            return when (usualSegment) {
                CarSegment.COMPACT -> "탑승하신 아이오닉 5는 100% 전기차로 평소 경차보다 차폭이 30센티미터 넓고 휠베이스가 3미터로 매우 깁니다. 특히 스마트 크루즈는 일반 차와 달리 핸들 '왼쪽'에 있고, 기어는 핸들 오른쪽 뒤 칼럼 레버를 돌려 조작합니다."
                CarSegment.SEDAN -> "탑승하신 아이오닉 5는 순수 전기차로 평소 세단보다 휠베이스가 3미터에 달해 실내가 매우 넓고 시야가 높습니다. 스마트 크루즈는 핸들 '왼쪽'에 있으며, 기어는 핸들 오른쪽 뒤 칼럼 레버 끝단을 돌려 조작합니다."
                CarSegment.SUV -> "탑승하신 아이오닉 5는 전기차 전용 플랫폼으로 바닥에 배터리가 있어 일반 내연기관 SUV보다 무게중심이 낮고 주행이 안정적입니다. 기어는 핸들 오른쪽 뒤 칼럼 레버, 크루즈는 핸들 '왼쪽'에 위치합니다."
                CarSegment.VAN -> "탑승하신 아이오닉 5는 전기차로 대형 승합차 못지않은 3미터 휠베이스의 넓은 공간을 제공합니다. 핸들 뒤 패들쉬프트로 회생제동 세기를 맞추어 편안하게 주행하세요."
                CarSegment.TRUCK -> "탑승하신 아이오닉 5는 전기 모터의 즉각적인 토크와 조용한 승차감이 특징입니다. 기어는 핸들 오른쪽 뒤 칼럼 레버를 돌려 D단으로 변속하세요."
            }
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
     * [기능 3] 음성 자연어 질문을 버튼/기능 DB와 매칭하여 극단적으로 짧은 1~2문장 즉답 생성
     * (가장 구체적인 긴 키워드 우선 매칭을 통해 '밀기 어떻게' -> '기어' 오작동 방지)
     */
    fun matchVoiceQuery(carModel: HyundaiCarModel, query: String): Pair<String, String> {
        val intents = database?.faqIntents ?: emptyList()
        val normalizedQuery = query.replace(" ", "").lowercase()

        // 1. 모든 인텐트의 키워드를 검사하여 일치하는 (키워드 길이, 인텐트) 후보군 수집
        data class MatchCandidate(val keywordLen: Int, val intent: FaqIntent, val matchedKeyword: String)
        val candidates = mutableListOf<MatchCandidate>()

        for (intent in intents) {
            for (kw in intent.keywords) {
                val cleanKw = kw.replace(" ", "").lowercase()
                if (cleanKw.isNotEmpty() && normalizedQuery.contains(cleanKw)) {
                    candidates.add(MatchCandidate(cleanKw.length, intent, cleanKw))
                }
            }
        }

        // 키워드 길이가 긴 순서(가장 구체적인 의미)로 정렬
        val bestMatch = candidates.maxByOrNull { it.keywordLen }
        if (bestMatch != null) {
            val intent = bestMatch.intent
            if (intent.isGear) {
                return Pair("변속기(기어) 조작법", carModel.gearType)
            }

            val buttonKey = intent.responseKey
            if (buttonKey != null) {
                val button = carModel.buttons[buttonKey]
                if (button != null) {
                    return Pair(button.name, button.actionGuide)
                }
            }
        }

        // 2. 특정 차종 특화 추가 키워드 매칭 (예: 충전구)
        if (carModel.buttons.containsKey("charge_door") &&
            (normalizedQuery.contains("충전") || normalizedQuery.contains("충전구"))) {
            val button = carModel.buttons["charge_door"]!!
            return Pair(button.name, button.actionGuide)
        }

        // 기본 매칭 실패 시 fallback (극단적으로 짧은 1문장)
        return Pair(
            "안내 불가",
            "질문하신 기능은 센터페시아 조작부 또는 핸들 버튼을 확인해 주세요."
        )
    }
}

