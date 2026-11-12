# RENDY 최종 DB 연동 가이드 (Codex 작업 지시용)

이 문서는 Codex 같은 코딩 에이전트가 `meisteryi/RentACarAgent` 저장소에 최종 DB를 연동할 때 그대로 따라 할 수 있게 쓴 작업 지시서입니다. 사람 개발자가 읽어도 됩니다.

- 입력 파일: `hyundai_buttons_db_final.json`(v4.2.0-final), `rendy_qa_test_cases_final.json`, `rendy_db_lint.py`
- 대상 코드: `app/src/main/java/com/hyundai/rentalhelper/data/CarButtonDatabase.kt`, `service/VoiceAssistantEngine.kt`
- 원칙: **main 브랜치에 직접 커밋하지 않습니다.** `feature/rendy-db-v4.2` 브랜치에서 작업하고 PR로 올립니다.

---

## 0. Codex에게 줄 작업 프롬프트 (복사해서 사용)

```text
목표: RENDY 버튼 DB를 v4.2.0-final로 교체하고, 음성 안내를 1차 답변 → 상세 위치 → 단계별 조작 → 결과 확인 흐름으로 바꿔라.

작업 규칙:
1. 브랜치 feature/rendy-db-v4.2를 만들어 작업한다. main에 직접 커밋·푸시하지 않는다.
2. app/src/main/assets/hyundai_buttons_db.json을 hyundai_buttons_db_final.json의 내용으로 교체한다. 파일명은 그대로 둔다.
3. 기존 data class 필드(name, zone, location, action_guide, faq_intents)는 이름과 타입을 바꾸지 않는다. 새 필드는 모두 nullable로 추가한다.
4. faq_intents 배열은 수정하지 않는다. 새 키워드와 되묻기 규칙은 intent_enhancements에서 읽는다.
5. 어떤 차종에 버튼이 없을 때 다른 차종의 문구로 대신 답하지 않는다. intent_enhancements.model_routing을 따른다.
6. 아래 '수용 기준'을 모두 통과하는 JVM 단위 테스트를 추가한다.
7. 작업이 끝나면 python3 tools/rendy_db_lint.py app/src/main/assets/hyundai_buttons_db.json을 실행해, summary에 keyword_in_multiple_intents만 남는지 확인한다.

참고 문서: docs/RENDY_final_integration_guide.md (이 파일)
```

권장 저장소 배치:

```text
app/src/main/assets/hyundai_buttons_db.json        ← final JSON 내용으로 교체
app/src/test/resources/rendy_qa_test_cases_final.json
tools/rendy_db_lint.py
docs/RENDY_final_integration_guide.md
docs/RENDY_final_verification_report.md
docs/RENDY_final_changelog.md
```

---

## 1. 스키마 호환성

| 위치 | v4.0.0 원본 | v4.2.0-final | Gson 호환 |
|---|---|---|---|
| 루트 | version, comment, models, faq_intents | 동일 + schema_notes, intent_enhancements, verification_summary, sources | ✅ 모르는 키는 무시 |
| models[] | model_id … gear_type, buttons | 동일 + `gear_type_original`. `gear_type` 값은 투싼·쏘나타·아이오닉5에서 교정 | ✅ |
| buttons{id} | name, zone, location, action_guide | 4개 필드 유지(일부 값 교정) + 신규 필드 | ✅ |
| faq_intents[] | 33개 | **원본과 바이트 단위로 같은 내용** | ✅ |

- 버튼 ID 237개는 그대로입니다. `name`과 `zone`은 하나도 바뀌지 않았습니다.
- 교정된 `location`·`action_guide`의 원문은 `verification.original_location`·`original_action_guide`에 있습니다.
- **에셋만 바꿔도 기존 앱이 그대로 동작합니다.** 이 경우 교정된 `action_guide`와 `gear_type`이 자동으로 반영됩니다.

---

## 2. Kotlin 데이터 클래스 (전체)

Gson은 Kotlin 생성자 기본값을 보장하지 않으므로 신규 필드는 **모두 nullable**로 두고, 쓰는 쪽에서 `?:`로 처리합니다.

```kotlin
data class Verification(
    @SerializedName("status") val status: String? = null,                 // VERIFIED|PARTIAL|CONFLICT|UNVERIFIED|NOT_APPLICABLE
    @SerializedName("priority") val priority: String? = null,             // P0|P1|P2
    @SerializedName("answer_policy") val answerPolicy: String? = null,    // direct|direct_with_caution|corrected_official|hedged|hedged_safety|redirect
    @SerializedName("location_verified") val locationVerified: Boolean? = null,
    @SerializedName("operation_verified") val operationVerified: Boolean? = null,
    @SerializedName("source_url") val sourceUrl: String? = null,
    @SerializedName("needed_evidence") val neededEvidence: String? = null
)

data class FollowUp(
    @SerializedName("on_not_found") val onNotFound: String? = null,       // "detailed_location"
    @SerializedName("on_found") val onFound: String? = null,              // "operation_steps"
    @SerializedName("on_done") val onDone: String? = null,                // "expected_result"
    @SerializedName("require_user_confirmation") val requireConfirm: Boolean? = null,
    @SerializedName("max_steps_per_turn") val maxStepsPerTurn: Int? = null
)

data class ButtonDetail(
    @SerializedName("name") val name: String,
    @SerializedName("zone") val zone: String? = null,
    @SerializedName("location") val location: String,
    @SerializedName("action_guide") val actionGuide: String,
    // ---- v4.1/4.2 (optional) ----
    @SerializedName("spoken_answer") val spokenAnswer: String? = null,
    @SerializedName("detailed_location") val detailedLocation: String? = null,
    @SerializedName("operation_steps") val operationSteps: List<String>? = null,
    @SerializedName("visual_landmarks") val visualLandmarks: List<String>? = null,
    @SerializedName("expected_result") val expectedResult: String? = null,
    @SerializedName("user_phrases") val userPhrases: List<String>? = null,
    @SerializedName("safety_notes") val safetyNotes: List<String>? = null,
    @SerializedName("control_type") val controlType: String? = null,
    @SerializedName("requires_parked") val requiresParked: Boolean? = null,
    @SerializedName("redirect_to") val redirectTo: String? = null,
    @SerializedName("duplicate_of") val duplicateOf: String? = null,
    @SerializedName("follow_up") val followUp: FollowUp? = null,
    @SerializedName("verification") val verification: Verification? = null
)

data class ClarifyRule(val phrase: String, val candidates: List<String>, val question: String?)
data class RouteTarget(val action: String, val button: String?, val to: String? = null)

data class IntentEnhancements(
    @SerializedName("clarification_rules") val clarificationRules: List<ClarifyRule>? = null,
    @SerializedName("proposed_new_intents") val proposedNewIntents: List<FaqIntent>? = null,
    @SerializedName("model_routing") val modelRouting: Map<String, Map<String, RouteTarget>>? = null,
    @SerializedName("follow_up_phrases") val followUpPhrases: Map<String, List<String>>? = null
)

// HyundaiDatabase 에 한 줄 추가
@SerializedName("intent_enhancements") val intentEnhancements: IntentEnhancements? = null
```

`applicability`, `sources`, `verification.finding` 같은 조사용 메타데이터는 앱에서 파싱하지 않아도 됩니다. APK 용량이 걱정되면 빌드 스크립트로 이 키들을 빼 낸 경량 에셋을 만드세요. 앱이 실제로 쓰는 필드는 §3 표에 있는 것뿐입니다.

---

## 3. 대화 단계별 필드

| 사용자 발화 | 읽을 필드 | 규칙 |
|---|---|---|
| 첫 질문 | `spoken_answer` | 최대 2문장. 그대로 TTS로 읽음 |
| “안 보여 / 못 찾겠어” (1회) | `detailed_location` | 기준점·조작계 유형·아이콘 단서가 들어 있음 |
| “못 찾겠어” (2회) | `visual_landmarks`를 하나씩 → 없으면 에스컬레이션 문구 | “차를 세운 뒤 차량 설명서나 렌터카 업체에 확인해 주세요.” |
| “찾았어 / 그다음은?” | `operation_steps[i]` | **한 턴에 한 단계**. 사용자가 확인하기 전에는 다음 단계로 넘어가지 않음 |
| 마지막 단계 이후 / “됐어?” | `expected_result` | 성공을 단정하지 않고 사용자가 직접 확인하게 함 |
| 안전 | `safety_notes` | `answer_policy=hedged_safety`이면 반드시 1문장 덧붙임 |
| 다른 기능으로 | `redirect_to` / `duplicate_of` | 예: 아이오닉5 주유구 → 충전구 |

### answer_policy 처리

| 값 | 의미 | 앱 동작 |
|---|---|---|
| `direct` | VERIFIED | 그대로 안내 |
| `direct_with_caution` | PARTIAL | 그대로 안내하고 마지막에 `expected_result`로 확인을 유도 |
| `corrected_official` | CONFLICT 교정 | 교정된 값만 사용, `original_*`는 읽지 않음 |
| `hedged` | UNVERIFIED(일반) | `spoken_answer`를 그대로 읽음(문장 안에서 이미 단정을 피함) |
| `hedged_safety` | UNVERIFIED(안전) | `operation_steps[0]`이 “차를 세우세요” 문장임. 주행 중이면 단계 안내를 미룸 |
| `redirect` | 해당 차종에 없음 | “이 차엔 없는 기능”이라고 말하고 `redirect_to`를 제안 |

---

## 4. 의도 매칭 순서 (`matchVoiceQuery` 교체안)

```kotlin
fun CarButtonDatabase.answer(car: HyundaiCarModel, query: String, uxRestricted: Boolean): Pair<String, String> {
    val q = query.replace(" ", "").lowercase()
    val enh = database?.intentEnhancements

    // 1) 되묻기 규칙: 후보가 이 차종에 2개 이상 있을 때만 되묻는다
    enh?.clarificationRules?.firstOrNull { r ->
        r.question != null && q.contains(r.phrase.replace(" ", "")) &&
        (r.candidates.isEmpty() || r.candidates.count { car.buttons.containsKey(it) } >= 2)
    }?.let { return "확인 필요" to it.question!! }

    // 2) 인텐트 매칭: 원본 33개 + 제안 인텐트, 가장 긴 키워드 우선(기존 로직 유지)
    val intents = database?.faqIntents.orEmpty() + enh?.proposedNewIntents.orEmpty()
    val best = intents.flatMap { it -> it.keywords.map { kw -> kw.replace(" ", "").lowercase() to it } }
        .filter { (kw, _) -> kw.isNotEmpty() && q.contains(kw) }
        .maxByOrNull { it.first.length }?.second
        ?: return "안내 불가" to "${car.modelNameKo}에서 그 기능 정보는 아직 없어요. 다른 표현으로 다시 말씀해 주시겠어요?"

    // 3) 차종별 라우팅: 다른 차종 문구로 대신하지 않음
    val route = enh?.modelRouting?.get(best.intent)?.get(car.modelId)
    val key = when (route?.action) {
        "button", "fallback" -> route.button
        "redirect" -> route.button
        "not_applicable_non_ev" -> return "해당 없음" to "${car.modelNameKo}는 전기차가 아니라 그 기능이 없어요."
        "no_data" -> return "정보 없음" to "${car.modelNameKo}에서 그 기능 정보는 아직 없어요."
        else -> if (best.isGear) "gear_shift" else best.responseKey
    } ?: return "정보 없음" to "${car.modelNameKo}에서 그 기능 정보는 아직 없어요."

    val b = car.buttons[key] ?: return "정보 없음" to "${car.modelNameKo}에서 그 기능 정보는 아직 없어요."
    var text = b.spokenAnswer ?: b.actionGuide
    if (uxRestricted && b.requiresParked == true) text += " 조작은 차를 세운 뒤 알려드릴게요."
    return b.name to text
}
```

- 기존 실패 응답 “센터페시아 조작부 또는 핸들 버튼을 확인해 주세요”는 **삭제**합니다. 근거 없는 추측이기 때문입니다.
- `best.isGear`는 기존과 같이 `gear_shift` 버튼으로 연결됩니다. 버튼 단위 대화 흐름을 쓰려면 `gear_type` 대신 `buttons.gear_shift`를 쓰세요.

---

## 5. 후속 대화 상태 머신

```kotlin
enum class Stage { LOCATING, LOCATING_2, STEPPING, CHECK, DONE }
data class Session(val modelId: String, val key: String, var stage: Stage = Stage.LOCATING, var step: Int = -1, var landmark: Int = 0)

fun CarButtonDatabase.next(s: Session, said: String, uxRestricted: Boolean): String {
    val b = findModelById(s.modelId)?.buttons?.get(s.key) ?: return "이 차량 정보가 없어요."
    val q = said.replace(" ", "")
    val ph = database?.intentEnhancements?.followUpPhrases.orEmpty()
    fun hit(k: String) = ph[k].orEmpty().any { q.contains(it.replace(" ", "").replace("?", "")) }
    return when {
        hit("cancel") -> { s.stage = Stage.DONE; "알겠어요. 필요하면 다시 불러 주세요." }
        hit("repeat") -> b.spokenAnswer ?: b.actionGuide
        hit("not_found") && s.stage == Stage.LOCATING -> { s.stage = Stage.LOCATING_2; b.detailedLocation ?: b.location }
        hit("not_found") -> b.visualLandmarks?.getOrNull(s.landmark++)?.let { "$it 을(를) 찾아보세요." }
            ?: "차를 안전한 곳에 세운 뒤 차량 설명서나 렌터카 업체에 확인해 주세요."
        hit("found") || hit("done_check") -> {
            if (uxRestricted && b.requiresParked == true) return "이 조작은 차를 세운 뒤에 안내할게요."
            val steps = b.operationSteps.orEmpty()
            s.step += 1; s.stage = Stage.STEPPING
            if (s.step < steps.size) steps[s.step]
            else { s.stage = Stage.CHECK; b.expectedResult ?: "작동했는지 직접 확인해 주세요." }
        }
        else -> b.spokenAnswer ?: b.actionGuide
    }
}
```

`uxRestricted`는 `CarUxRestrictionsManager`의 `isRequiresDistractionOptimization`에서 가져옵니다. 기존 `CarUxRestrictionsHelper.kt`를 그대로 쓰면 됩니다.

---

## 6. 수용 기준 (JVM 테스트로 자동화)

`app/src/test/.../RendyDbTest.kt`에서 `rendy_qa_test_cases_final.json`(133케이스)을 읽어 다음을 검사합니다.

- [ ] `expected_intent`가 `NOT:x`인 케이스는 x로 매칭되면 실패
- [ ] `CLARIFY` 케이스는 되묻기 문구가 나와야 하고, `clarification_candidates` 중 하나로 바로 답하면 실패
- [ ] `must_include`, `must_include_any`, `must_not_include`는 응답 문자열 포함 여부로 검사
- [ ] `must_say_not_available_for_model`은 다른 차종의 `spoken_answer`가 나오면 실패
- [ ] `follow_up_full`은 턴마다 `source_field`에 해당하는 필드 값이 나와야 함
- [ ] `driving_restriction`은 `uxRestricted=true`로 호출했을 때 조작 단계가 나오면 실패
- [ ] `data_rule`은 `tools/rendy_db_lint.py` 실행 결과로 대체(CI 단계)

CI 예시:

```bash
python3 tools/rendy_db_lint.py app/src/main/assets/hyundai_buttons_db.json --orig tools/hyundai_buttons_db_v4.0.0.json \
  | python3 -c "import json,sys; s=json.load(sys.stdin)['summary']; bad={k:v for k,v in s.items() if k!='keyword_in_multiple_intents'}; sys.exit(1 if bad else 0)"
./gradlew testDebugUnitTest
```

---

## 7. 하지 말아야 할 것

- `faq_intents`를 직접 수정하지 마세요. 키워드 정리(‘깜빡이’, ‘비’, ‘차선’, ‘충전’)는 `intent_enhancements.keyword_risks`를 참고해 **별도 PR**로 진행하세요.
- `verification.status`를 코드에서 바꾸거나, UNVERIFIED 항목을 단정형 문장으로 다시 쓰지 마세요.
- 어떤 차종의 답이 없을 때 다른 차종의 버튼 데이터로 대신하지 마세요.
- `requires_parked=true`인 기능의 조작 단계를 주행 중에 읽지 마세요.
- 차량을 직접 제어하는 Vehicle SDK set API를 이 안내 흐름에 붙이지 마세요. RENDY는 위치를 안내하는 서비스입니다.

---

## 8. 데이터 갱신 절차 (검증 자료가 추가될 때)

1. 새 근거(국문 PDF 페이지, 실차 사진)를 확보하면 해당 버튼의 `verification`에 `source_url`, `source_page`, `finding`을 채웁니다.
2. 위치와 조작이 **모두** 텍스트로 확인된 경우에만 `status=VERIFIED`, `location_verified=true`, `operation_verified=true`로 바꿉니다.
3. `spoken_answer`는 2문장 이하로 쓰고, 조작계 설명은 `control_type`과 일치해야 합니다.
4. `rendy_db_lint.py`를 실행해 새 이슈가 0건인지 확인한 뒤 PR을 올립니다.
