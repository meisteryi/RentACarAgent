# RENDI(렌디) — 렌터카 차량 적응 음성 도우미

처음 타는 렌터카의 조작법을 **음성으로** 알려 주는 Android Automotive 앱입니다.

출발 전에는 운전자가 평소 타던 차량 유형과 비교해 달라지는 조작을 짧게 브리핑합니다. 주행 중에는 "렌디"라고 부른 뒤 "스마트 크루즈 어떻게 켜?", "앞유리에 김이 서렸어"처럼 자연어로 물으면, 지금 탄 차량의 조작 데이터에서 버튼 위치와 사용법을 찾아 답합니다.

- 앱 이름: RENDY / 패키지: `com.hyundai.rentalhelper` / Gradle 프로젝트: `RentalCarAdaptationCoach`
- 플랫폼: Android Automotive OS (minSdk 28, compileSdk·targetSdk 34), Kotlin
- 시연 기본 차량: **더 뉴 아이오닉 5**

---

## 1. 핵심 기능

| 기능 | 내용 | 주요 코드 |
|---|---|---|
| 호출어 "렌디" | Vosk 한국어 소형 모델(0.22)로 기기 안에서 호출어를 인식합니다. 문법 전용 경량 그래프와, 말하는 도중 섞인 "렌디"를 거르는 고립 검사를 씁니다. | `service/RendyWakeWordController.kt`, `RendyWakeWordLiteGraph.kt`, `RendyWakeIsolationCheck.kt` |
| 음성 인식·합성 | 질문 인식은 Pleos SpeechToText 3.3.1, 답변 음성은 Pleos TextToSpeech 3.3.1을 씁니다. | `service/VoiceAssistantEngine.kt` |
| 의도 분류 | Gemini가 질문을 기능(의도)으로 분류합니다. API 키는 앱에 넣지 않고 별도 프록시 서버(`server/rendy-intent-proxy`)가 보관합니다. 프록시를 끄거나 오류가 나면 키워드 규칙으로 분류합니다. | `intent/GeminiFlashProxyIntentClassifier.kt`, `intent/KeywordIntentMatcher.kt` |
| 차종별 조작 데이터 | 탑승 차량의 조작 DB에서 답을 찾습니다. **공식 매뉴얼로 확인된 항목만** 위치·조작법을 말하고, 확인되지 않은 부분은 "아직 확인되지 않았어요"라고 말합니다. | `intent/RendyKnowledgeBase.kt`, `intent/RendyAnswerComposer.kt`, `assets/` |
| 출발 전 비교 브리핑 | 평소 차량 유형(경차·세단·SUV·승합차·트럭·없음)을 고르면 출발 전에 음성으로 차이를 알려 줍니다. | `data/Ioniq5FaceliftComparisonBriefing.kt`, `data/CarButtonDatabase.kt` |
| 후속 대화 | 직전 답변 그대로 반복("뭐라고?"), 더 자세한 위치("어디 있는지 모르겠어"), 찾았다는 응답 뒤 다음 단계 안내, 취소를 처리합니다. 대화 맥락은 60초 동안 유지됩니다. | `intent/RendyQuestionPipeline.kt`, `intent/DialogueTurn.kt` |
| 주행 상태별 안내 | 기어 P이고 속도 1km/h 이하일 때만 "정차"로 봅니다. 주행 중에는 짧은 답과 안전 문구만 말하고, 자세한 안내 요청은 보류했다가 정차하면 먼저 "아까 찾으시던 …, 지금 알려드릴까요?"라고 다시 제안합니다(보류 내용은 30분 유지). | `service/VehicleMotion.kt`, `service/VehicleSdkManager.kt`, `service/CoachOverlayService.kt` |
| 자막 | 답변 음성이 재생되는 동안 화면 상단에 자막을 띄우고, 질문을 듣는 동안 인식 중인 문장을 아래쪽 상태 표시줄에 보여 줍니다. | `service/TtsSubtitle.kt`, `service/LiveTranscript.kt`, `service/RendyVoiceStatusOverlay.kt` |
| DEBUG 차량 상태 패널 | 디버그 빌드에만 있는 패널로 기어(P/R/N/D)와 속도를 바꿔 주행·정차를 시연합니다. 릴리스 빌드에서는 동작하지 않습니다. | `service/RendyDebugVehiclePanel.kt`, `service/DebugVehicleControl.kt` |

그 밖에 반납지·반납 일시 입력 화면(`ui/ReturnInfoActivity.kt`)이 있습니다. 현재는 입력값을 저장만 하고, 반납 임박 알림은 아직 구현되지 않았습니다. "지금 내 위치가 어디야?" 같은 질문에는 위치를 확인할 수 없다고 답하고 내비게이션 화면을 안내합니다.

## 2. 동작 흐름

```
"렌디" ─ Vosk 호출어 인식 (기기 내)
   │
   ▼
Pleos SpeechToText ─ 질문 인식, 인식 중 문장 자막
   │
   ▼
의도 분류 ─ Gemini (프록시 서버 경유) / 실패 시 키워드 규칙
   │        후속 발화는 "반복·상세·찾음·확인·취소" 등 대화 행위로 분류
   ▼
대화 상태 + 차량 상태 판단 ─ 정차(P, 1km/h 이하)인지, 이어지는 질문인지
   │
   ▼
차량 조작 DB + 검증 오버레이 ─ 확인된 필드(위치·모양·조작·결과)만 사용
   │
   ▼
Pleos TextToSpeech ─ 음성 답변 + 재생 동기 자막
```

앱 실행 순서: 평소 차량 선택(`StartupCarSelectActivity`) → 반납 정보 입력(`ReturnInfoActivity`) → 순정 내비게이션 실행과 함께 음성 도우미 서비스(`CoachOverlayService`) 시작 → 인사와 비교 브리핑 → "렌디" 대기.
순정 내비게이션을 실행할 수 없는 환경에서는 대체 화면 `MainAssistantActivity`가 열립니다.

## 3. 지원 차량과 데이터

### 차량

| 차량 | 데이터 ID | 비고 |
|---|---|---|
| 더 뉴 아이오닉 5 | `IONIQ5_NE1_FL` | 시연 기본 차량. 2025년판 공식 매뉴얼 기준 디자인 |
| 구형 아이오닉 5 | `IONIQ5_NE1_PRE` | 페이스리프트 이전 디자인(2023·2024년판 매뉴얼 기준) |
| 더 뉴 투싼 | `TUCSON` | |
| 쏘나타 디 엣지 | `SONATA` | |
| 아반떼 | `AVANTE` | |
| 캐스퍼 | `CASPER` | |

조작 DB(`hyundai_buttons_db.json`)에는 5개 차종이 있고, 아이오닉 5는 디자인이 다른 두 변형으로 나뉩니다. 두 디자인은 스마트 크루즈 버튼 위치처럼 서로 다른 부분이 있어 데이터를 섞지 않습니다(`data/RendyVehicleVariants.kt`).
탑승 차량은 차량의 모델 정보(AAOS `CarInfoManager`)로 정하고, 정보가 없으면 더 뉴 아이오닉 5로 시작합니다.

### 데이터 현황

| 데이터 | 파일 | 규모 |
|---|---|---|
| 차량 조작 DB (v4.2.0) | `app/src/main/assets/hyundai_buttons_db.json` | 5개 차종, 조작 항목 237개, FAQ 의도 33개 |
| 필드 단위 검증 오버레이 (4.2.2) | `app/src/main/assets/rendy_verification_overlay.json` | 66개 항목. 공식 매뉴얼 문장·그림에서 확인한 위치·모양·조작 문장과 출처 URL |

**모든 항목의 위치와 조작법이 완성된 것은 아닙니다.** 앱은 항목마다 공식 매뉴얼로 확인된 정도에 따라 말할 수 있는 범위를 정합니다.

| 차량 | 위치·조작 모두 확인 | 조작만 확인 | 위치만 확인 | 매뉴얼에 기능만 있음 | 미확인 |
|---|---|---|---|---|---|
| 더 뉴 투싼 | 18 | 4 | 5 | 9 | 14 |
| 쏘나타 디 엣지 | 3 | 6 | 5 | 20 | 16 |
| 캐스퍼 | 2 | 13 | 1 | 22 | 5 |
| 아반떼 | 1 | 0 | 0 | 15 | 28 |
| 아이오닉 5 (디자인 공통) | 1 | 13 | 5 | 19 | 11 |

- 더 뉴 아이오닉 5는 위 공통 데이터에 더해 스마트 크루즈(켜기·속도·차간거리)와 앞유리·뒷유리 서리 제거의 위치·모양·단계별 조작이 2025년판 매뉴얼 본문과 그림으로 확인되어 있습니다.
- 아이오닉 5의 1개 항목은 해당 차량에 없는 기능(주유구)입니다.
- "미확인" 항목은 위치나 조작법을 말하지 않고, 설명서나 렌터카 업체 확인을 안내합니다.
- 수치는 단위 테스트(`FieldLevelVerificationTest`)가 생성하는 `answerability_by_model.tsv` 기준입니다.

### 출발 전 비교 브리핑

- **더 뉴 아이오닉 5**는 평소 차량 유형 6가지에 대해 각각 다른 비교 브리핑을 합니다.
  - 차량 유형만으로는 평소 차의 기어 방식이나 연료를 알 수 없으므로, 그 부분은 단정하지 않고 "평소 기어봉을 쓰셨다면 …"처럼 조건을 붙여 말합니다.
  - 기어 조작(브레이크를 밟은 채 변속 다이얼로 D·R, 주차는 P 버튼)과 회생 제동은 2025년판 매뉴얼 문장에 근거합니다.
  - "없음"을 고르거나 선택을 건너뛰면 비교 없이 기본 조작만 안내합니다.
- 다른 차량은 아직 이전 방식(차체 크기 중심)의 브리핑을 사용합니다. 비교 브리핑 확대는 향후 과제입니다.

## 4. 디렉터리 구조

```
app/src/main/
├── AndroidManifest.xml
├── assets/
│   ├── hyundai_buttons_db.json          # 차종별 조작 DB, FAQ 의도
│   ├── rendy_verification_overlay.json  # 공식 매뉴얼 기반 필드 단위 검증 데이터
│   └── rendy/                           # Vosk 모델 위치(모델 zip은 git 제외), 라이선스
└── java/com/hyundai/rentalhelper/
    ├── data/      # DB 로딩, 차량 변형, 평소 차량 설정, 브리핑 문장
    ├── intent/    # 의도 분류(Gemini·키워드), 대화 상태, 답변 구성
    ├── service/   # 음성 서비스, 호출어, STT/TTS, 자막, 차량 상태, DEBUG 패널
    ├── ui/        # 차량 선택, 반납 정보, 내비 실행, 대체 화면
    └── utils/     # AAOS 주행 제한 헬퍼(대체 화면에서 사용)
app/src/test/      # 단위 테스트, 의도·대화 평가 케이스
server/rendy-intent-proxy/  # Gemini 의도 분류 프록시(Node, 외부 의존성 없음)
rendy_db_lint.py            # DB 정합성 검사
```

## 5. 실행 방법

### 개발 환경

- Android Studio (JDK 17 이상, 내장 JBR 21에서 확인), Android SDK 34
- Gradle 8.7, Android Gradle Plugin 8.3.2, Kotlin 1.9.23 — Gradle wrapper가 자동으로 받습니다.
- Pleos TextToSpeech·SpeechToText SDK는 `settings.gradle.kts`에 등록된 Pleos Maven 저장소에서 받습니다.
- 실행 환경: Pleos SDK 서비스가 포함된 Android Automotive 에뮬레이터. 개발 중에는 API 34, 1408×792 가로 화면에서 확인했습니다.

### 1) 호출어 모델 받기 (필수, git 제외)

Vosk 한국어 모델 zip(82.8 MiB)은 용량 때문에 저장소에 없습니다. 아래 경로에 압축을 풀지 않은 채로 둡니다.

```bash
curl -L -o app/src/main/assets/rendy/vosk-model-small-ko-0.22.zip \
  https://alphacephei.com/vosk/models/vosk-model-small-ko-0.22.zip
# SHA-256: EEA36124087FED26C59996A4761519458E3BD185E8EA9D9865AD8760C4A1D989
```

모델이 없어도 빌드와 단위 테스트는 통과하지만, "렌디" 호출어가 동작하지 않습니다. 출처와 라이선스는 `app/src/main/assets/rendy/README.txt`에 있습니다.

### 2) Gemini 의도 분류 설정 (선택)

기본값은 Gemini를 쓰지 않고 키워드 규칙으로 동작합니다. Gemini를 쓰려면 다음 두 가지를 설정합니다.

1. 프록시 서버를 각자의 API 키로 실행합니다. 키는 `server/rendy-intent-proxy/.env`에만 두며, 이 파일은 git에서 제외됩니다.
   ```bash
   cd server/rendy-intent-proxy
   # .env 파일에 GEMINI_API_KEY=<본인 키> 입력 (필요하면 RENDY_PROXY_TOKEN도)
   npm test     # 오프라인 테스트, 키 불필요
   npm start    # http://127.0.0.1:8787, 상태 확인: /healthz
   ```
   자세한 설정과 API는 [server/rendy-intent-proxy/README.md](server/rendy-intent-proxy/README.md)를 참고하세요.
2. 프로젝트 루트의 `local.properties`(git 제외)에 다음을 추가합니다. 에뮬레이터에서 `10.0.2.2`는 개발 PC를 가리킵니다.
   ```properties
   rendy.llm.enabled=true
   rendy.llm.proxyEndpoint=http://10.0.2.2:8787/v1/rendy/classify
   rendy.llm.proxyToken=
   ```

### 3) 빌드와 설치

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio에서는 `app` 구성을 실행하면 됩니다. 앱은 화면 위에 음성 상태와 자막을 띄우므로 "다른 앱 위에 표시" 권한이 필요합니다. 마이크 권한이 없으면 음성 기능을 쓸 때 Android 권한 요청 화면이 열립니다.
에뮬레이터에서 PC 마이크를 쓰려면 `adb emu avd hostmicon`을 실행합니다.

### 4) DEBUG 패널로 주행·정차 바꾸기 (디버그 빌드)

1. 화면 왼쪽 아래의 **DEBUG** 칩을 누르면 패널이 펼쳐집니다.
2. **P / R / N / D** 버튼으로 기어를, 슬라이더로 속도(0~120km/h)를 바꿉니다. P를 고르면 속도는 0이 됩니다.
3. 빠른 설정: **주차**(P, 0), **신호 대기**(D, 0), **주행**(D, 40), **후진**(R, 5).
4. 패널 머리글에 현재 판단(예: "DEMO D · 70 km/h → 주행/정차: 상세 안내 보류")이 표시됩니다.
5. **DEMO 해제**를 누르면 실제 차량(에뮬레이터) 신호로 돌아갑니다.

DEMO 값은 실제 차량을 제어하지 않으며, 앱의 판단에만 쓰입니다. 같은 기능을 adb로도 쓸 수 있습니다.

```bash
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_GEAR --es gear DRIVE
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_SPEED --ef speed 70
adb shell am broadcast -a com.hyundai.rentalhelper.CLEAR_DEMO_MOTION
# 탑승 차량 바꾸기: TUCSON, SONATA, AVANTE, CASPER, IONIQ5(더 뉴 아이오닉 5), IONIQ5_NE1_PRE(구형)
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_MODEL --es model TUCSON
```

### 5) 테스트

```bash
./gradlew :app:testDebugUnitTest
```

- 단위 테스트 174개(통과 170, 건너뜀 4)이며, 마지막 확인에서 실패는 0개였습니다.
- 건너뛴 4개는 실제 Gemini 프록시로 평가하는 테스트로, 환경변수 `RENDY_INTENT_PROXY_URL`을 지정했을 때만 실행됩니다.
- DB 정합성 검사: `python rendy_db_lint.py app/src/main/assets/hyundai_buttons_db.json`

## 6. 데모 시나리오 (약 3분)

평소 차량 "SUV", 탑승 차량 "더 뉴 아이오닉 5" 기준입니다. 답변은 에뮬레이터에서 실제 Gemini 프록시와 Pleos TTS로 실행해 기록한 문장입니다(질문은 텍스트로 주입).

| 순서 | 차량 상태 (DEBUG) | 운전자 | RENDY |
|---|---|---|---|
| 1 | — | 평소 차량 **SUV** 선택 → 선택 완료 → 반납 정보 건너뛰기 | "더 뉴 아이오닉 5는 평소 타시던 SUV와 변속과 감속 방식이 다를 수 있습니다. 평소 기어봉을 쓰셨다면, 이 차는 브레이크 페달을 밟은 채 변속 다이얼을 돌려 D나 R을 고르고, 주차할 때는 P 버튼을 누릅니다. 또 전기차라서 주행 중에는 회생 제동이 자동으로 작동하니, 처음에는 감속되는 느낌을 확인하며 천천히 출발하세요." |
| 2 | **D / 70km/h** | "렌디" → "스마트 크루즈 어떻게 켜?" | "핸들 왼쪽 버튼 묶음의 맨 윗줄 바깥쪽을 보세요. 자동차와 속도계가 함께 그려진 주행 보조 버튼이 있어요. 그 버튼을 한 번 누르면 스마트 크루즈를 켤 수 있어요. 운전 보조 기능입니다. 손은 운전대에, 시선은 전방에 두세요." |
| 3 | D / 70 | "뭐라고?" | 직전 답변을 그대로 반복 |
| 4 | D / 70 | "어디 있는지 모르겠어." | "지금은 운전에 집중해 주세요. 정차하면 차근차근 알려드릴게요." |
| 5 | **P / 0km/h** (주차) | — | (정차 확인 후 먼저) "아까 찾으시던 스마트 크루즈, 지금 알려드릴까요?" |
| 6 | P / 0 | "응, 알려줘." | 버튼 위치와 모양을 안내한 뒤 "찾으셨나요?" → "찾았어"라고 하면 단계별 조작 안내 |
| 7 | **D / 70km/h** | "렌디" → "앞이 뿌옇게 보여" | "가운데 화면 아래 히터·에어컨 조작부의 아랫줄, 운전석 온도 버튼 바로 오른쪽을 보세요. 앞유리 모양 안에 물결 화살표가 위로 올라가는 서리 제거 버튼이에요. 한 번 누르면 표시등이 켜지고 앞유리 쪽으로 바람이 나와요. 앞이 많이 안 보이면 먼저 안전한 곳에 차를 세운 뒤 조작하세요." |

시연 팁
- 아래쪽 상태 표시가 "마이크 준비 중"에서 "네, 말씀하세요!"(이어지는 질문은 "더 궁금한 점이 있으면 말씀하세요")로 바뀐 뒤 말합니다. 듣는 창이 열려 있는 동안 발표자가 다른 말을 하면 질문으로 인식될 수 있습니다.
- 듣는 창이 응답 없이 닫히면 "렌디"를 다시 부르고 같은 말을 하면 됩니다. 보류된 질문은 30분 동안 유지됩니다.
- 5번의 재안내는 RENDY가 말하고 있지 않을 때 나옵니다. 주차로 바꾸기 전에 직전 응답이 끝나기를 기다립니다.

## 7. 검증 범위와 한계

| 구분 | 상태 |
|---|---|
| 단위 테스트 | 174개 중 170개 통과, 4개는 실제 프록시가 필요해 기본 실행에서 건너뜀 |
| 실제 Gemini 분류 | 에뮬레이터에서 실제 프록시로 확인(스마트 크루즈 대화, 서리 제거 질문, 데모 시나리오) |
| 질문 입력 방식 | 위 결과는 대부분 **인식 결과 문장을 앱에 주입**해 확인했습니다. 실제 마이크 음성으로는 일부 대화만 확인했고, 전체 시나리오를 마이크로 반복 검증하지는 않았습니다. |
| 차량 상태 | 에뮬레이터의 차량 신호와 **DEBUG 패널(DEMO 신호)**로만 확인했습니다. **실제 차량 SDK 연동은 검증하지 않았습니다.** |
| 공식 매뉴얼 검증 | 일부 항목만 완료(3장 표 참고). 아이오닉 5 공조 버튼 배열은 매뉴얼에 "사양에 따라 달라질 수 있다"고 되어 있습니다. |

알려진 문제
- **Pleos 마이크 준비 지연(미해결):** 답변 뒤 이어서 듣는 창에서, Pleos STT가 녹음을 시작하기까지 8초를 넘기는 경우가 있습니다. 이때 앱의 8초 대기 제한에 걸려 응답 없이 창이 닫힙니다. 에뮬레이터 시연에서 대략 5번 중 1번꼴로 관찰되었고, 다시 "렌디"를 부르면 이어서 진행됩니다. 대기 시간 조정 등은 아직 적용하지 않았습니다.
- 더 뉴 아이오닉 5 단계 안내 중 "그다음엔 어떻게 해?"가 "더 자세히"로 분류되어 다음 단계 대신 "확인된 설명이 없다"는 답이 나올 때가 있습니다. "다음 단계 알려줘"는 정상적으로 다음 단계를 안내합니다.
- Gemini 프록시가 꺼져 있으면 키워드 규칙만 쓰므로, "유리창이 안 보여"처럼 앞·뒤 구분이 없는 표현은 다시 묻는 경우가 있습니다.

향후 개선
- 차량별 상세 조작 데이터의 공식 매뉴얼 검증 확대, 다른 렌트 차량의 비교 브리핑 확대
- 실제 차량 SDK(차속·기어·차종) 연동 검증과 실차 시험
- Pleos 마이크 준비 지연 대응
- 클라우드 LLM 대신 차량용 LLM(Gleo) 적용 검토
- 반납 일시 기반 알림

## 8. 참고 문서

| 문서 | 내용 |
|---|---|
| [RENDY_GEMINI_INTENT_PROXY.md](RENDY_GEMINI_INTENT_PROXY.md) | 앱–프록시 계약, 답변 정책, 설정 플래그 |
| [RENDY_final_integration_guide.md](RENDY_final_integration_guide.md), [RENDY_final_changelog.md](RENDY_final_changelog.md), [RENDY_final_verification_report.md](RENDY_final_verification_report.md) | 조작 DB v4.2 연동 가이드, 변경 내역, 공식 매뉴얼 검증 결과 |
| [server/rendy-intent-proxy/README.md](server/rendy-intent-proxy/README.md) | 프록시 설치·실행·환경변수 |
| `reports/` | 개발 중 측정 기록(DB v4.2 평가, STT 지연, STT 병렬화, 호출어 메모리·오인식) |

조작 데이터의 출처는 현대자동차 공식 취급설명서(ownersmanual.hyundai.com)이며, 각 항목의 출처 URL은 `rendy_verification_overlay.json`에 기록되어 있습니다.
