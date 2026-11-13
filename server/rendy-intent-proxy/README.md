# RENDY 의도 분석 프록시 (Gemini)

운전자 발화(STT 결과)를 Gemini로 분류해, 앱이 보낸 허용 목록 중 **의도 ID 하나**만 돌려주는 작은 HTTP 서버입니다.

- Gemini API 키는 이 서버의 환경변수에만 둡니다. 앱(APK)에는 키가 없습니다.
- 서버는 차량 조작 안내 문장을 만들지 않습니다. 응답은 `{intent, confidence, requires_clarification}` 세 필드뿐입니다.
- 실제 답변 문장은 앱이 차종별 DB(`hyundai_buttons_db.json`)에서 고릅니다.

## 1. 요구 사항

| 항목 | 내용 |
|---|---|
| Node.js | **20.12 이상** (`process.loadEnvFile` 사용). v24.21.0에서 테스트함 |
| 의존성 | 없음. Node 내장 모듈만 씁니다. `npm install`은 필요 없습니다 |
| Gemini API 키 | 팀원 각자 [Google AI Studio](https://aistudio.google.com/apikey)에서 발급 |

## 2. 설치와 실행

```bash
cd server/rendy-intent-proxy
cp .env.example .env        # Windows PowerShell: Copy-Item .env.example .env
# .env를 열어 GEMINI_API_KEY 입력. 필요하면 RENDY_PROXY_TOKEN도 설정
npm test                    # 오프라인 테스트 (키·네트워크 불필요)
npm run check-models        # 이 키로 쓸 수 있는 모델 목록과 GEMINI_MODEL 사용 가능 여부 (키는 출력하지 않음)
npm start                   # = node server.mjs → http://127.0.0.1:8787
```

동작 확인:

```bash
curl http://127.0.0.1:8787/healthz
# {"ok":true,"model":"gemini-3.5-flash-lite","keyConfigured":true}
```

`.env`는 `.gitignore`로 제외됩니다. 절대 커밋하지 마세요. `.env` 대신 셸 환경변수로 넣어도 됩니다. 셸에 이미 설정된 값이 `.env`보다 우선합니다(Node 24에서 확인).

## 3. 환경변수

| 이름 | 필수 | 기본값 | 설명 |
|---|---|---|---|
| `GEMINI_API_KEY` | **예** | 없음 | Gemini API 키. 없으면 `/v1/rendy/classify`가 `503 key_not_configured`를 반환 |
| `GEMINI_MODEL` | 아니오 | `gemini-3.5-flash-lite` | 분류 모델. `npm run check-models`로 사용 가능 여부 확인 |
| `RENDY_PROXY_TOKEN` | 아니오 | 없음 | 설정하면 요청에 `Authorization: Bearer <토큰>`이 필요. 앱은 `rendy.llm.proxyToken` 값을 보냄 |
| `HOST` | 아니오 | `127.0.0.1` | 바인딩 주소. 에뮬레이터에서는 `10.0.2.2`로 접근 |
| `PORT` | 아니오 | `8787` | 포트 |
| `UPSTREAM_TIMEOUT_MS` | 아니오 | `5000` | Gemini 호출 제한 시간 |
| `RATE_LIMIT_PER_MINUTE` | 아니오 | `60` | 클라이언트 IP별 분당 요청 수 |
| `DAILY_REQUEST_LIMIT` | 아니오 | `3000` | 서버 전체 일일 요청 수 (메모리 기준, 재시작 시 초기화) |
| `GEMINI_THINKING_LEVEL` | 아니오 | `MINIMAL` | Gemini thinking 수준. `NONE`이면 설정하지 않음 |
| `GEMINI_API_BASE` | 아니오 | `https://generativelanguage.googleapis.com` | 테스트에서 가짜 upstream을 가리킬 때만 사용 |

## 4. API

### `POST /v1/rendy/classify`

요청 (앱의 `GeminiFlashProxyIntentClassifier`가 보내는 형태):

```json
{
  "query": "앞유리가 뿌예서 안 보여",
  "allowed_intents": [
    {"id": "front_defog", "examples": ["김서림", "앞유리 김"], "label": "앞유리 성에 제거 / 앞유리 김서림 버튼"}
  ],
  "previous_intent": "seat_vent_heat",
  "response_schema": {"type": "object", "...": "앱이 보내지만 서버는 무시하고 자체 스키마를 씀"}
}
```

서버 검증 규칙 (위반 시 `400`):

- `query`: 공백이 아닌 문자열, 300자 이하
- `allowed_intents`: 1~64개
  - `id`: `^[a-z0-9_]{1,64}$`, `UNKNOWN` 사용 불가, 중복 불가
  - `examples`: 30개 이하, 각 40자 이하
  - `label`: 80자 이하
- `previous_intent`: 생략 또는 `null` 가능. 값이 있으면 `allowed_intents`에 있어야 함
- 요청 본문: 32KB 이하
- 그 밖의 필드(`model`, `response_schema` 등)는 무시합니다. 모델과 스키마는 항상 서버 설정을 씁니다.

성공 응답 (`200`, 정확히 세 필드):

```json
{"intent": "front_defog", "confidence": 0.91, "requires_clarification": false}
```

- `intent`는 허용 목록의 ID 또는 `"UNKNOWN"`입니다.
- 모호하거나 여러 기능을 묻거나 목록에 없는 질문이면 `"UNKNOWN"`과 `requires_clarification: true`를 반환합니다.

오류 응답은 `{"error": "<코드>"}` 형태입니다. 오류가 나면 앱은 키워드 규칙으로 대체합니다.

| 상태 | 코드 예 |
|---|---|
| 400 | `invalid_json`, `invalid_query`, `invalid_allowed_intents`, `invalid_intent_id`, `invalid_previous_intent` 등 |
| 401 | `unauthorized` (토큰 불일치) |
| 429 | `rate_limited`, `daily_limit`, `upstream_http_429_RESOURCE_EXHAUSTED` |
| 502 | `upstream_http_<status>`, `upstream_invalid_json`, `upstream_schema_violation`, `upstream_empty_*` |
| 503 | `key_not_configured` |
| 504 | `upstream_timeout`, `upstream_network` |

### `POST /v1/rendy/turn` (후속 발화)

렌디가 방금 한 말이 있을 때(앱의 대화 문맥 60초, 정차 대기 중이면 30분) 앱은 `/classify` 대신 이 경로를 **한 번만** 호출합니다. Gemini가 발화의 의미와 대화 상태를 함께 보고 대화 행위(dialogue act)를 고르고, 새 질문이면 의도 ID도 함께 고릅니다. 앱은 이 라벨만 보고 무엇을 말할지, 상태를 어떻게 바꿀지 스스로 정합니다.

요청: `/classify`의 `query`, `allowed_intents`에 `dialogue` 객체를 더합니다(`previous_intent`는 쓰지 않음).

```json
{
  "query": "어? 뭐라고?",
  "allowed_intents": [{"id": "smart_cruise", "examples": ["크루즈"], "label": "스마트 크루즈 / 스마트 크루즈 컨트롤(SCC) 스위치"}],
  "dialogue": {
    "last_rendy_utterance": "투싼은 운전대 왼쪽의 ‘주행 보조’ 버튼으로 스마트 크루즈를 켜요. ...",
    "rendy_asked_question": false,
    "last_driver_question": "크루즈 컨트롤 어떻게 켜?",
    "current_intent": "smart_cruise",
    "stage": "ANSWERED",
    "pending_detail": false,
    "driving_state": "DRIVING",
    "car_model": "투싼"
  }
}
```

- `stage`: `NONE`, `ANSWERED`, `HELD_FOR_PARKING`, `RESUME_OFFERED`, `GUIDE_LOCATE`, `GUIDE_STEP`, `GUIDE_DONE`
- `driving_state`: `DRIVING`, `PARKED`, `UNKNOWN`
- 문자열 300자(`car_model` 40자) 이하, `current_intent`는 `allowed_intents`에 있어야 함. 위반 시 `400`

성공 응답 (`200`, 정확히 세 필드):

```json
{"dialogue_act": "REPEAT", "intent": "UNKNOWN", "confidence": 0.92}
```

- `dialogue_act`: `NEW_QUESTION`, `REPEAT`, `NEED_DETAIL`, `CONFIRM`, `FOUND`, `DENY`, `CANCEL`, `UNCLEAR`
- `intent`는 `NEW_QUESTION`일 때만 의미가 있습니다. 다른 행위에 의도가 붙어 오면 서버가 `UNKNOWN`으로 바꿉니다.
- 프롬프트에는 한국어 예시 문장을 넣지 않았습니다. 특정 표현 목록이 아니라 의미와 문맥으로 고르게 하고, 평가 문장(`app/src/test/resources/dialogue_act_cases.json`)이 프롬프트에 노출되지 않게 하기 위해서입니다.
- 앱 쪽 추가 검증: 확신도 0.55 미만은 `UNCLEAR`, `CONFIRM`/`FOUND`/`DENY`는 0.7 미만이면 `UNCLEAR`(무리하게 진행하지 않음).
- 오류 코드와 앱의 대체 동작은 `/classify`와 같습니다. 실패하면 앱은 DB의 `follow_up_phrases`(정확 일치)와 키워드 규칙으로만 처리하고 Gemini를 다시 부르지 않습니다.

### `GET /healthz`

`{"ok": true, "model": "<GEMINI_MODEL>", "keyConfigured": true|false}`를 반환합니다. 키 값은 반환하지 않습니다.

로그는 상태 코드, 지연 시간, 의도 ID만 남깁니다. 발화 원문, 요청 본문, 키는 기록하지 않습니다.

## 5. Android 앱 연결

앱 설정은 git에서 제외된 프로젝트 루트의 `local.properties` 또는 Gradle `-P` 옵션으로 넣습니다. **API 키는 여기에 넣지 마세요.**

```properties
rendy.llm.enabled=true
# 에뮬레이터 → 개발 PC의 프록시
rendy.llm.proxyEndpoint=http://10.0.2.2:8787/v1/rendy/classify
# 서버에 RENDY_PROXY_TOKEN을 설정했을 때만, 같은 값으로
rendy.llm.proxyToken=
```

- **HTTP는 디버그 빌드에서만** `10.0.2.2`, `localhost`, `127.0.0.1`로 허용됩니다 (`app/src/debug/res/xml/network_security_config.xml`).
- 릴리스 빌드는 HTTPS 주소만 사용합니다. 그 외 주소이거나 `enabled=false`(기본값)이면 네트워크 없이 키워드 규칙으로만 동작합니다.
- 실기기(USB)에서는 `adb reverse tcp:8787 tcp:8787` 후 `http://127.0.0.1:8787/v1/rendy/classify`를 쓸 수 있습니다.
- `rendy.llm.proxyToken`은 BuildConfig로 APK에 들어가므로 추출될 수 있습니다. 로컬 개발용 간이 인증으로만 쓰고, 외부 배포 시에는 별도 인증, HTTPS, 요청 제한을 갖춘 게이트웨이를 두세요.

## 6. 테스트

| 테스트 | 명령 | 키 필요 |
|---|---|---|
| 서버 오프라인 테스트 (가짜 Gemini upstream, 11개: `/classify` 7, `/turn` 4) | `npm test` | 아니오 |
| 앱 단위 테스트 (요청 생성, 응답 검증, 후보 제한 포함) | 루트에서 `./gradlew :app:testDebugUnitTest` | 아니오 |
| 앱 → 프록시 → Gemini 실측 (의도 정확도, QA 133개) | 아래 참고 | 예 |

실측 평가 (프록시 실행 중, 키 설정 상태):

```bash
RENDY_INTENT_PROXY_URL=http://127.0.0.1:8787/v1/rendy/classify \
RENDY_PROXY_TOKEN=<서버와 같은 값, 없으면 생략> \
./gradlew :app:testDebugUnitTest --tests "*IntentEvaluationTest*" --tests "*RendyQaSuiteTest*" --tests "*DialogueActEvaluationTest*" --rerun
```

`DialogueActEvaluationTest`는 후속 발화 84개(`/v1/rendy/turn`)의 대화 행위 정확도와 지연을 `dialogue_act_<RENDY_EVAL_LABEL>.summary.txt`로 남깁니다. 환경변수만 바뀌면 Gradle이 테스트를 건너뛰므로 `--rerun`을 붙이세요.

결과는 `app/build/reports/rendy-intent/`에 생깁니다. `RENDY_INTENT_PROXY_URL`이 없으면 이 두 테스트는 skip됩니다.

## 7. 보안 체크리스트

- [ ] `.env`가 `git status`에 보이지 않는다 (`.gitignore`가 `.env`, `.env.*`를 제외, `.env.example`만 허용)
- [ ] `HOST`는 기본값 `127.0.0.1`을 유지한다. 외부에 열 때는 HTTPS와 인증을 앞단에 둔다
- [ ] 키가 노출되었다면 AI Studio에서 즉시 폐기하고 재발급한다

요청·응답 계약의 배경과 앱 쪽 응답 정책은 루트의 `RENDY_GEMINI_INTENT_PROXY.md`를 참고하세요. 이 README의 API 절이 현재 구현 기준입니다.
