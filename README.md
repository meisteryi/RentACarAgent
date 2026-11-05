# RentalCarAdaptationCoach (현대차 렌터카 적응 코칭 비서)

Android Automotive OS (AAOS) 및 Pleos Connect SDK 기반의 현대자동차 렌터카 맞춤형 실시간 Q&A 및 체급 적응 코칭 비서 애플리케이션입니다.

---

## 1. 프로젝트 주요 특징 및 준수 사항

- **타깃 플랫폼:** Android Automotive OS (AAOS), Minimum SDK 28 (Android 9.0+)
- **안전 및 규제 준수:**
  - 운전자 주의 분산 방지(DO: Distraction Optimized) 적용
  - `CarUxRestrictions` 연동: 주행(Moving) 상태 시 터치 차단, 화면 간소화, 긴 텍스트 표시 제한
  - **극단적으로 짧은 1~2문장의 명확한 TTS 음성 안내** 제공
- **현대자동차 MVP 5대 차종 지원:**
  - **아반떼** (준중형 세단)
  - **쏘나타 디 엣지** (중형 세단 - 칼럼식 기어)
  - **투싼** (준중형 SUV)
  - **아이오닉 5** (전기 CUV - 핸들 좌측 크루즈/칼럼 기어)
  - **캐스퍼** (경형 SUV - 풋 파킹 브레이크/대시보드 기어)

---

## 2. 3대 핵심 시나리오 구현

### [기능 1: 승차 직전/시동 시 '평소 내 차종' 선택 프리셋]
- **화면:** `StartupCarSelectActivity`
- 시동 후 메인 화면 진입 전, 드라이버 피로도를 최소화하기 위해 **5가지 체급(경차, 세단, SUV, 승합차, 트럭)** 중 1개를 원터치로 선택.
- `UserPreferences`에 영구 보관되며, 설정 후에는 메인 비서 화면으로 바로 진입합니다.

### [기능 2: 차종 비교 체급 브리핑 (TTS)]
- **엔진:** `VehicleSdkManager` + `CarButtonDatabase` + `VoiceAssistantEngine`
- `VehicleSdkManager`가 현재 렌터카 모델(예: 투싼)을 인식.
- 평소 몰던 차종(예: 세단)과의 전폭, 전고, 시야, 브레이크 감각 차이를 분석하여 **출발 직전 1~2문장의 TTS 음성 브리핑** 자동 송출.
  - *예시:* `"탑승하신 투싼은 평소 타시던 세단보다 전고가 20센티미터 높아 시야가 탁 트여 있습니다. 무게중심이 높으므로 고속 코너링 시 감속하세요."*

### [기능 3: 주행 중 온디맨드 음성 Q&A 챗봇]
- **엔진:** `VoiceAssistantEngine`
- 핸들 음성 버튼 또는 마이크 입력(STT)으로 질문 수신.
- 탑승 차량의 물리 버튼 및 조작계 DB(`hyundai_buttons_db.json`)와 대조하여 **정확한 위치와 조작법을 1~2문장으로 즉답.**
  - *질문 예 1:* "창문에 성에가 꼈어 어떡하지?"
    - *답변 (투싼):* `"공조기 좌측 부채꼴 FRONT 버튼을 누르시면 전면 성에가 빠르게 제거됩니다."`
  - *질문 예 2:* "스마트크루즈 어떻게 켜?"
    - *답변 (아이오닉5):* `"아이오닉5는 핸들 좌측 스포크에 크루즈 컨트롤 버튼이 있습니다. 좌측 버튼을 누르세요."`

---

## 3. 디렉터리 및 패키지 구조

```
RentalCarAdaptationCoach/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml                  # AAOS 선언, 퍼미션, DO 메타데이터
│   │   ├── assets/
│   │   │   └── hyundai_buttons_db.json          # 5개 차종 버튼 위치 & 스펙 DB
│   │   ├── java/com/hyundai/rentalhelper/
│   │   │   ├── RentalCoachApplication.kt
│   │   │   ├── data/
│   │   │   │   ├── CarButtonDatabase.kt         # JSON 파서 & 브리핑/의도 매칭
│   │   │   │   └── UserPreferences.kt           # 평소 차종 SharedPreferences 관리
│   │   │   ├── service/
│   │   │   │   ├── VehicleSdkManager.kt         # Pleos/AAOS Car API + ADB Mock
│   │   │   │   └── VoiceAssistantEngine.kt      # STT 음성 인식 & TTS 음성 제어
│   │   │   ├── ui/
│   │   │   │   ├── StartupCarSelectActivity.kt  # 시동 시 평소 차종 선택 UI
│   │   │   │   └── MainAssistantActivity.kt     # 대화형 비서 & 주행 안전 UI
│   │   │   └── utils/
│   │   │       └── CarUxRestrictionsHelper.kt   # AAOS 주행 안전 규제(DO) 헬퍼
│   │   └── res/
│   │       ├── layout/                          # 터치 타깃 80dp+ AAOS 최적화 레이아웃
│   │       └── values/                          # 다크 테마 & 현대 블루 컬러
│   └── build.gradle.kts                         # AAOS minSdk 28, android.car 라이브러리
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 4. 에뮬레이터(Pleos Connect Emulator) 및 ADB 테스트 방법

Pleos Connect 에뮬레이터나 Android Automotive OS 에뮬레이터에서 adb 브로드캐스트 명령으로 차량 상태를 즉시 시뮬레이션할 수 있습니다.

### 1) 렌터카 모델 변경 테스트
```bash
# 투싼으로 변경
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_MODEL --es model TUCSON

# 아이오닉 5로 변경
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_MODEL --es model IONIQ5

# 쏘나타 디 엣지로 변경
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_MODEL --es model SONATA

# 캐스퍼로 변경
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_MODEL --es model CASPER

# 아반떼로 변경
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_MODEL --es model AVANTE
```

### 2) 주행 및 정차 시뮬레이션 (CarUxRestrictions 검증)
```bash
# 시속 60km/h 주행 상태로 전환 (주행 규제 배너 활성화, 터치 제한)
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_SPEED --ef speed 60.0

# 정차 상태로 복귀 (P단, 0km/h)
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_SPEED --ef speed 0.0
adb shell am broadcast -a com.hyundai.rentalhelper.SET_CAR_GEAR --es gear PARK
```

