# [기술 보고서] 차량 전수 기능 온톨로지화 및 자연어 AI 기반 실시간 핀포인트 코칭 비서 아키텍처

**문서 번호:** HMC-RAC-ARCH-2026-V1  
**작성일:** 2026년 11월 5일  
**프로젝트:** RentalCarAdaptationCoach (현대차 렌터카 맞춤형 실시간 Q&A 비서)  
**대상 환경:** Android Automotive OS (AAOS), Hyundai ccNC / ccIC In-Vehicle Infotainment  

---

## 1. 개요 및 기술 비전 (Executive Summary)

### 1.1 배경 및 당면 과제
렌터카 및 카셰어링 이용자는 익숙하지 않은 차량에 탑승했을 때, 주행 중 급박한 상황(예: 갑작스러운 비로 인한 유리창 성에 발생, 스마트 크루즈 차간거리 조절, 주유구 레버 위치 등)에서 원하는 물리 버튼이나 인포테인먼트 메뉴를 찾지 못해 시선이 분산(Eyes-off-Road)되는 치명적인 안전 위험에 노출됩니다.

### 1.2 목표 시스템
고정된 버튼(성에 제거, 크루즈 등)의 프리셋 방식을 넘어, **운전자의 어떠한 거칠고 간접적인 자연어 발화**("앞이 뿌얘서 안 보여", "차선 밟을 때 삐 소리 나는 거 어떻게 꺼?", "주유구 레버 어디 있어?")라도 AI가 즉각 의도를 간파하고, **현재 탑승 중인 차량의 차종·연식·트림에 일치하는 정확한 물리적 위치 및 조작법을 1~2문장으로 즉각 핀포인트 음성(TTS)과 시각 HUD로 안내**하는 양산급 AI 코칭 아키텍처를 제시합니다.

```
[운전자 자연어 발화] 
   │ "앞이 뿌옇게 흐려져서 안 보여"
   ▼
┌────────────────────────────────────────────────────────┐
│ 1. 음성인식 & 의도 추출 (Edge STT + Semantic NLU)       │
│    - 의도: Defog / Climate Control                     │
│    - 대상: FRONT Defroster                             │
└────────────────────────────────────────────────────────┘
   │
   ▼
┌────────────────────────────────────────────────────────┐
│ 2. 차량 컨텍스트 그라운딩 (Car Telemetry Grounding)    │
│    - 탑승 차종: 2024 투싼 (NX4 PE)                     │
│    - 공조기 타입: 터치 전환식 통합 공조 패널           │
│    - 현재 공조 상태: 에어컨 OFF, 외기 순환 상태        │
└────────────────────────────────────────────────────────┘
   │
   ▼
┌────────────────────────────────────────────────────────┐
│ 3. 온톨로지 핀포인트 룩업 (Graph-based In-Car Ontology)│
│    - 위치 앵커: 센터페시아 디스플레이 하단 1열           │
│    - 상대 위치: 가로 1열 맨 왼쪽 첫 번째                │
│    - 표기 형태: 'FRONT' 영문 부채꼴 각인 버튼          │
└────────────────────────────────────────────────────────┘
   │
   ▼
[초정밀 음성(TTS) & 순정 지도 위 HUD 자막 표출] (< 700ms)
"공조기 가로 1열 중 맨 왼쪽 첫 번째 'FRONT' 부채꼴 버튼을 누르시면 앞유리 성에가 즉시 제거됩니다."
```

---

## 2. Pillar 1: 차량별·연식별 모든 기능과 버튼의 구조화 (Ontology & Knowledge Graph)

단순한 플랫 텍스트 매뉴얼은 LLM의 환각(Hallucination)을 유발하므로, 차량 내부의 물리 인터페이스를 **공간 계층형 그래프(Spatial Hierarchical Graph)**로 모델링해야 합니다.

### 2.1 공간 계층형 온톨로지 (Spatial Hierarchy Ontology)
운전자는 3차원 공간 안에서 손을 뻗어 조작하므로, 모든 버튼과 화면 메뉴는 아래와 같은 4단계 공간 계층으로 인덱싱됩니다.

```
[Zone (대구역)] 
  └── [Cluster / Assembly (중위 앵커)] 
        └── [Relative Anchor Group (기준점 그룹)] 
              └── [Target Component (최종 조작체)]
```

1. **Zone (대구역):**
   - `ZONE_STEERING_WHEEL` (스티어링 휠)
   - `ZONE_CENTER_FASCIA` (센터페시아/공조/오디오)
   - `ZONE_FLOOR_CONSOLE` (센터콘솔/기어박스/컵홀더)
   - `ZONE_DOOR_TRIM_DRIVER` (운전석 도어 트림)
   - `ZONE_CRASH_PAD_LOWER` (크래시패드 좌측 하단)
   - `ZONE_OVERHEAD_CONSOLE` (룸미러 상단 루프 패널)
   - `ZONE_DISPLAY_CCNC` (중앙 인포테인먼트 화면 터치 메뉴)

2. **Assembly / Anchor (중위 앵커):**
   - 예: 핸들 우측 스포크, 기어 시프트 주변, 비상등 버튼 하단, 운전석 시트 왼쪽 하단

3. **Relative Position & Visual Landmark (상대 위치 및 시각적 특징):**
   - "가로 2열 중 맨 왼쪽", "토글 스위치 형태", "은색 크롬 마감", "빨간색 삼각형"

### 2.2 차종·연식·트림 변동 매트릭스 (Variant Management)
동일한 모델명이라도 연식과 페이스리프트에 따라 조작계가 완전히 바뀝니다.
- **예시: 쏘나타 (Sonata)**
  - 8세대 (DN8, 2019~2023): 센터콘솔 **전자식 푸시 버튼(SBW) 기어** (P/R/N/D 개별 버튼).
  - 8세대 페이스리프트 (쏘나타 디 엣지, 2023~현재): 스티어링 칼럼 우측 **전자식 로터리 칼럼 레버**.
- **해결 방안:** 차량 식별 시 Car API의 `VehicleInfo.MODEL_YEAR`, `VIN(차대번호)`, `TRIM` 값을 결합하여 고유 `variant_id`(`HMC_SONATA_DN8_PE_2024`)를 동적으로 확인합니다.

### 2.3 표준 데이터 스키마 (JSON-LD / Knowledge Graph Schema)

```json
{
  "$schema": "https://hyundai.com/schemas/incar-ontology-v1.json",
  "variant_id": "HMC_TUCSON_NX4_PE_2024",
  "model_name_ko": "투싼 페이스리프트",
  "model_year": 2024,
  "infotainment_generation": "ccNC",
  "gear_system": {
    "type": "COLUMN_ROTARY",
    "location_description": "스티어링 휠 뒤편 우측 와이퍼 레버 아래쪽에 위치한 회전식 전자 칼럼",
    "operation_guide": "레버 끝단 다이얼을 앞으로 돌리면 D단, 몸쪽으로 돌리면 R단, 끝단 P 버튼을 누르면 주차 체결됩니다."
  },
  "components": [
    {
      "component_id": "BTN_DEFOG_FRONT",
      "type": "PHYSICAL_BUTTON",
      "canonical_name": "앞유리 성에 제거 버튼",
      "synonyms": ["앞유리 습기", "앞창문 김서림", "디프로스터", "FRONT 버튼", "앞유리 바람"],
      "spatial_zone": "ZONE_CENTER_FASCIA",
      "anchor_group": "공조기 조작 터치/물리 통합 패널",
      "spatial_coordinates": {
        "x_order": 1,
        "y_order": 1,
        "total_in_row": 6,
        "reference_anchor": "공조 패널 가로 첫 번째 줄"
      },
      "visual_features": {
        "icon": "부채꼴 모양에 지그재그 화살표 3개",
        "label": "FRONT",
        "tactile_type": "터치 피드백 패널"
      },
      "pinpoint_guide": "센터페시아 공조장치 가로 첫 번째 줄 맨 왼쪽 'FRONT'라고 적힌 부채꼴 버튼입니다.",
      "action_instruction": "버튼을 1회 누르면 에어컨과 외기 유입이 자동으로 켜지며 성에가 급속히 제거됩니다."
    },
    {
      "component_id": "BTN_SMART_CRUISE_GAP",
      "type": "STEERING_SWITCH",
      "canonical_name": "차간거리 설정 버튼",
      "synonyms": ["크루즈 간격", "앞차 거리", "반자율주행 거리", "스마트크루즈 거리"],
      "spatial_zone": "ZONE_STEERING_WHEEL",
      "anchor_group": "핸들 우측 스포크 버튼 블록",
      "spatial_coordinates": {
        "x_order": 2,
        "y_order": 1,
        "total_in_group": 4,
        "reference_anchor": "핸들 오른쪽 버튼 4개 묶음"
      },
      "visual_features": {
        "icon": "차량 2대 사이에 눈금선 아이콘",
        "tactile_type": "물리 푸시 버튼"
      },
      "pinpoint_guide": "핸들 오른쪽 스포크 버튼 4개 중 윗줄 오른쪽 버튼입니다.",
      "action_instruction": "누를 때마다 1단계부터 4단계까지 차간거리가 순환 변경됩니다."
    }
  ]
}
```

### 2.4 데이터 수집 파이프라인 자동화 (Data Ingestion Pipeline)
모든 차종을 수작업으로 입력하는 것은 불가능합니다. 이를 해결하기 위해 3단계 수집 파이프라인을 구축합니다.
1. **취급설명서(PDF/HTML) 다큐먼트 파서:** 현대차 공식 전장 취급설명서 내 '각 부의 명칭', '계기판 안내', '공조 조작' 챕터를 OCR 및 레이아웃 파서(Document Layout Analysis)로 테이블/이미지 좌표 추출.
2. **CAD/전장 설계 부품 트리(BOM) 연계:** 현대 전장 부품 카탈로그의 파트 트리(`Zone -> Group -> PartNumber`)를 온톨로지 ID와 맵핑.
3. **LLM 기반 정밀 위치 텍스트 증강(Augmentation):** 추출된 메타데이터를 기반으로 "운전자 시점에서 바라본 1문장 직관적 설명"을 LLM Few-Shot 프롬프트로 일괄 생성 후 전장 QA 엔지니어 검수.

---

## 3. Pillar 2: 운전자 자연어 발화 의도 파악 및 솔루션 매핑 (NLU & AI Engine)

운전자는 매뉴얼 용어("앞유리 디프로스터") 대신 **자신의 현재 문제 상태나 불편함**을 일상어로 호소합니다.

### 3.1 3단계 하이브리드 NLU 파이프라인

```
[운전자 음성 입력] ("야 차선 넘어갈 때 삑삑 소리 나는 거 시끄러운데?")
       │
       ▼
[Stage 1: Intent & Target Entity Extraction]
  - Category: ADAS (첨단 운전자 보조 시스템)
  - Target: 차로 이탈 경고음 음량 조절 / 이탈방지 보조
  - Emotion/Urgency: 불만 / 일반 주행 중
       │
       ▼
[Stage 2: Hybrid RAG Search (Dense + Sparse)]
  - 현재 차량 온톨로지에서 후보군 검색:
    1) 크래시패드 '차로 유지 버튼' (물리 버튼 OFF)
    2) ccNC 설정 화면 > 차량 > 운전자 보조 > 경고 음량 (디지털 메뉴)
       │
       ▼
[Stage 3: Vehicle Context Filtering]
  - 현재 주행 속도: 80km/h (주행 중)
  - 가이드라인: 주행 중 복잡한 디스플레이 메뉴 진입 금지(CarUxRestrictions)
  - 선택 솔루션: 핸들 위 물리 '차로 유지 버튼' 원터치 끄기 우선 안내
       │
       ▼
[최종 핀포인트 안내 TTS 생성]
"핸들 오른쪽 버튼 중 조향 핸들 모양 버튼을 2초간 길게 누르시면 차선 보조와 경고음이 꺼집니다."
```

### 3.2 비정형·간접적 발화(Implicit Query) 처리
운전자의 발화 패턴은 크게 3가지로 분류되며, 각각 전용 프롬프트와 임베딩 전략을 취합니다.

| 발화 유형 | 운전자 실제 발화 예시 | 의도 분류 및 대상 기능 | 온톨로지 매핑 솔루션 |
| :--- | :--- | :--- | :--- |
| **증상 호소형** | "앞이 너무 뿌얘서 하나도 안 보여" | 유리창 습기 제거 (FRONT Defog) | 공조기 1열 맨 좌측 `FRONT` 부채꼴 버튼 안내 |
| **동작 문의형** | "주유소 왔는데 기름통 뚜껑 어떻게 열어?" | 연료 주입구 커버 열림 | 쏘나타: 도어 언락 후 주유구 직접 누름 / 캐스퍼: 운전석 바닥 레버 당김 |
| **조작 혼선형** | "기어 P단 어디 갔어? 왜 안 눌려?" | 전자식 칼럼 기어 주차 체결 | 와이퍼 아래 칼럼 레버 끝단 은색 `P` 버튼 누름 안내 |
| **기능 해제형** | "신호 대기 때 엔진 꺼지는 거 거슬려" | ISG (오토 스톱/스타트) OFF | 기어봉 주변 `A OFF` 버튼 위치 안내 |

### 3.3 차량 실시간 텔레메트리 연동 (In-Vehicle Context Grounding)
자연어 모델은 차량의 실시간 상태를 인지해야만 완벽한 답변을 낼 수 있습니다.
- **연동 데이터 (Android Car API):**
  - `CarPropertyManager.GEAR_SELECTION`: 현재 기어가 P단인지 D단인지
  - `CarPropertyManager.PERF_VEHICLE_SPEED`: 주행 중(> 0km/h) 여부
  - `CarPropertyManager.HVAC_DEFROSTER`: 현재 성에 제거기가 이미 켜져 있는지
  - `CarPropertyManager.NIGHT_MODE`: 야간 여부 (버튼 조명 점등 안내)
- **프롬프트 인젝션 예시:**
  ```text
  [System Context]
  - Current Vehicle: 2024 Hyundai Tucson (NX4 PE)
  - Vehicle Speed: 65 km/h (Moving - Keep response under 20 words!)
  - HVAC Front Defroster: OFF
  - User Query: "비 와서 앞이 안 보여"

  [Task]
  Return the exact physical button location and action.
  Do NOT explain theoretical details. Give direct physical coordinates.
  ```

### 3.4 제로 환각(Zero Hallucination) 보장 가드레일
차량 내 안내에서 AI의 거짓 답변(없는 버튼 안내 등)은 사고를 유발할 수 있습니다.
- **Rule-based Verification:** 생성된 답변 내에 지칭된 버튼명과 위치가 온톨로지 DB의 `canonical_name` 및 `spatial_zone`과 100% 일치하지 않으면 발화 반려.
- **Confidence Threshold:** 의도 매칭 신뢰도 < 0.85일 경우, 무리하게 추측하지 않고 안전 안내(예: "현재 투싼 차량의 해당 기능은 센터 디스플레이 '차량 설정'에서 지원됩니다")로 폴백.

---

## 4. Pillar 3: 시스템 아키텍처 (Edge SLM + Cloud Hybrid)

차량은 터널, 지하주차장, 산간도로 등 **통신 음영지역**을 반드시 통과하므로 100% 클라우드 의존형 아키텍처는 부적합합니다.

### 4.1 하이브리드 아키텍처 다이어그램

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Vehicle In-Cabin Environment                     │
│                                                                        │
│   [Driver Voice Mic] ──► [Edge Audio Pre-processing (Noise Canceller)]  │
│                                       │                                │
│                                       ▼                                │
│                        [On-Device Fast Router]                         │
│                                       │                                │
│            ┌──────────────────────────┴──────────────────────────┐     │
│            ▼ (Offline or Fast < 300ms)                            ▼     │
│   ┌────────────────────────────────┐    ┌───────────────────────────┐  │
│   │ On-Device SLM & Vector Index   │    │ Cloud Gateway Client      │  │
│   │ (Gemini Nano / Gemma 2B)       │    │ (LTE / 5G Car Modem)      │  │
│   │ - SQLite / VectorLite Embeddings│    └─────────────┬─────────────┘  │
│   │ - 50대 핵심 기능 즉각 응답      │                  │                │
│   └────────────────┬───────────────┘                  ▼                │
└────────────────────┼──────────────────────────────────┼────────────────┘
                     │                                  │
                     │                   ┌──────────────┴──────────────┐
                     │                   │ Hyundai Cloud AI Service    │
                     │                   │ - Gemini 2.0 Flash / Pro    │
                     │                   │ - Full Manual Knowledge Base│
                     │                   │ - Complex Multi-turn Reason │
                     │                   └──────────────┬──────────────┘
                     ▼                                  ▼
┌────────────────────────────────────────────────────────────────────────┐
│               Response Arbiter & Safety UX Controller                  │
│   - AAOS CarUxRestrictions (주행 중 텍스트 2줄 이하 제한)              │
│   - Text-To-Speech (TTS Engine with Car Speaker Priority)             │
│   - Real-time Verbatim HUD Subtitle Banner (순정 내비 오버레이)         │
└────────────────────────────────────────────────────────────────────────┘
```

### 4.2 계층별 역할 분담
1. **Tier 1: On-Device Edge 엔진 (오프라인/초저지연)**
   - **엔진:** Google AICore (Gemini Nano) 또는 경량 ONNX Runtime 기반 Gemma 2B
   - **데이터:** 현재 탑승한 단일 차종의 전체 물리 버튼 온톨로지 (경량화 벡터 인덱스 ~15MB)
   - **역할:** 비상등, 와이퍼, 성에 제거, 기어 조작, 창문 등 긴급 안전 기능에 대해 **네트워크 연결 없이 0.4초 만에 즉답**.
2. **Tier 2: Cloud 거대 AI 엔진 (온라인/복합 질문)**
   - **엔진:** Cloud-hosted Gemini 1.5/2.0 Flash
   - **데이터:** 전 차종 취급설명서 전수, 정비 매뉴얼, 음향 설정, 멀티미디어 연동 가이드
   - **역할:** "아이폰 블루투스 끊기는데 어떻게 다시 잡아?", "회생제동 레벨을 스마트 모드로 바꾸는 이유가 뭐야?" 등 복합 설명성 질문 처리.

---

## 5. Pillar 4: 초기 승차 적응 브리핑의 동적·다채로운 AI 생성 엔진 (Dynamic Onboarding NLG)

기존 시스템의 가장 큰 한계는 "탑승하신 투싼은 평소 타시던 세단보다 전고가 20센티미터 높아..."와 같은 정형화된 단일 스크립트가 반복되어 사용자가 음성을 기계적 소음으로 인식하고 무시(Alert Fatigue)하게 된다는 점입니다. 이를 해결하기 위해 **100% 검증된 차량 팩트(Fact)를 기반으로 하면서도, 매번 새롭고 다채로운 톤과 상황 맞춤형 비유로 브리핑을 실시간 생성하는 Dynamic NLG 파이프라인**을 구축합니다.

### 5.1 다차원 컨텍스트 결합 (Multi-dimensional Context Ingestion)
초기 브리핑은 단순 차종 비교에 그치지 않고, 아래의 4가지 동적 컨텍스트를 실시간 결합합니다.

```
[차종 간 물리 치수 팩트] ──┐
(전고 +20cm, 칼럼식 기어)  │
                          │
[환경/날씨 컨텍스트]    ────┼──► [동적 프롬프트 조립기] ──► [On-Device SLM / LLM] ──► [다채로운 1~2문장 TTS]
(야간, 비 내림, 외기 8℃)  │     (Dynamic Prompt Builder)   (Gemini 2.0 Flash)
                          │
[운전자 탑승 특성]      ──┘
(평소: 아반떼 / 첫 SUV 렌트)
```

1. **물리적 차이 팩트 (Ground Truth):**
   - 전고, 전폭, 휠베이스 차이 수치(cm/mm 단위 정밀도)
   - 기어 변속 시스템의 형태 (칼럼 로터리 vs 버튼식 vs 기어봉)
   - 파워트레인 특성 (내연기관 vs 전기차 회생제동 원페달)
2. **실시간 주행 환경 (Environment & Time):**
   - 시간대: 이른 아침, 나른한 오후, 어두운 야간, 황혼
   - 기상 센서(비, 눈, 안개, 기온): 우천 시 와이퍼/김서림 팁 연계
3. **렌터카 대여 여정 (Journey Context):**
   - 제주/강원 등 관광지 드라이브 vs 도심 비즈니스 출장
   - 첫 전기차(EV) 렌트 여부: "아이오닉 5가 처음이시라면 회생제동으로 인한 울컥거림을 줄이는 패들 쉬프트 조작법..."

### 5.2 페르소나 및 상황별 다채로운 발화 생성 예시 (Persona & Variation Matrix)

*동일한 조건(평소: 준중형 세단 '아반떼' ➔ 현재 렌터카: 준중형 SUV '투싼', 칼럼식 기어)이라도 상황에 따라 완전히 다른 음성으로 브리핑이 송출됩니다.*

| 상황 및 페르소나 | 실시간 동적 TTS 발화 스크립트 예시 |
| :--- | :--- |
| **기본 / 직관적 코치** | "반갑습니다! 평소 타시던 아반떼보다 운전석 시야가 20센티미터 높아져 탁 트여 있습니다. 출발 전 핸들 오른쪽 뒤 칼럼 레버를 앞으로 살짝 돌려 D단으로 변속해 보세요." |
| **비 오는 날 (우천 모드)** | "빗길 안전운전 되세요! 아반떼보다 차체가 높아 시야 확보는 좋지만, 차폭이 6센티미터 넓으니 빗길 골목길에서는 천천히 회전하세요. 성에가 끼면 센터페시아 맨 왼쪽 부채꼴 버튼을 누르시면 됩니다." |
| **야간 드라이브** | "편안한 밤길 운전 되세요. 아반떼보다 차고가 높아 맞은편 헤드라이트 눈부심이 훨씬 덜합니다. 기어는 콘솔이 아닌 핸들 뒤 칼럼 레버 끝단을 돌려주시면 됩니다." |
| **감성적 여행 메이트** | "투싼과 함께하는 기분 좋은 여정의 시작입니다! 세단보다 시야가 훤해서 창밖 풍경을 즐기기 아주 좋습니다. 차고가 높은 만큼 코너링할 때만 가볍게 속도를 줄여주세요." |
| **간결 모드 (Fast Briefing)** | "투싼 탑승을 환영합니다. 아반떼 대비 전폭 +6cm, 전고 +20cm입니다. 기어는 핸들 우측 칼럼 로터리 방식입니다." |

### 5.3 결정론적 팩트 가드레일 (Deterministic Anchor + Generative Surface)
자유로운 생성형 AI가 "투싼은 후륜구동입니다"라거나 기어 위치를 잘못 설명하는 사고를 방지하기 위해 **Anchor-Slot 하이브리드 생성 방식**을 적용합니다.

```
[Prompt Template]
시스템은 다음 [반드시 포함해야 할 팩트]를 100% 포함하되, 어투와 비유를 [상황 컨텍스트]에 맞추어 2문장 이내로 자연스럽게 변주해야 한다.

[반드시 포함해야 할 팩트]
- 비교 수치: 전고 20cm 높음, 전폭 6cm 넓음
- 기어 조작법: 핸들 오른쪽 뒤 칼럼식 레버 (앞으로 회전 시 D)

[상황 컨텍스트]
- 날씨: 맑음, 시간: 주말 오전, 기분: 여행 시작
- 금지사항: 수치 왜곡 금지, 3문장 초과 금지
```

### 5.4 오프라인 엣지 템플릿 풀 다변화 (Edge Fallback Engine)
통신이 되지 않는 음영지역(지하 3층 렌터카 인수 구역 등)에서는 클라우드 LLM 대신 On-Device 룰 엔진이 동작합니다. 이때도 단일 문장이 아닌 **20가지 이상의 조합형 모듈형 슬롯(Slot-Filling Pool)**을 랜덤 시드(Random Seed)로 결합하여 항상 신선한 느낌을 전달합니다.
- `[인사 슬롯 5종]` + `[시야/차고 체감 비유 4종]` + `[기어/조작 앵커 4종]` + `[안전 당부 3종]` = **최소 240가지 고유 발화 조합 생성**.

---

## 6. 단계별 구현 로드맵 (Actionable Implementation Roadmap)

```
[Phase 1: 데이터 온톨로지 구축] ──► [Phase 2: NLU & Context 파이프라인] ──► [Phase 3: AAOS 양산 배포]
(현대 5대 차종 전수 구축)           (RAG + Function Calling 엔진)       (OTA 업데이트 & 다국어)
```

### Phase 1: 5개 차종 전수 온톨로지 및 공간 좌표 DB 구축 (1~2개월)
- [x] MVP 5종 모델(투싼, 쏘나타, 아반떼, 아이오닉5, 캐스퍼) 기본 스키마 작성 완료
- [ ] 5개 차종의 80개 이상 전 물리 버튼 및 주요 ccNC 화면 경로 전수 수집
- [ ] 취급설명서 PDF 파싱 스크립트 작성 및 JSON 구조화 파이프라인 구축
- [ ] 온톨로지 스키마 유효성 검증기(JSON Schema Validator) 배포

### Phase 2: RAG 기반 지능형 의도 매칭 엔진 개발 (2~3개월)
- [ ] 한국어 문장 임베딩 모델(예: KoSimCSE, bge-m3)의 On-device 양자화 (TFLite/ONNX)
- [ ] 사용자 자연어 발화 ➔ 차량 버튼 온톨로지 매칭 벤치마크 데이터셋 구축 (1,000개 일상 발화 쌍)
- [ ] Android Car API(`CarPropertyManager`) 연동을 통한 속도/공조 상태 실시간 프롬프트 주입기 작성
- [ ] 환각 방지 검증 모듈(Hallucination Guardrail) 구현

### Phase 3: Android Automotive OS 네이티브 고도화 및 검증 (2개월)
- [ ] 주행 안전 규제(CarUxRestrictions) 자동 감응 UI: 정차 시 상세 팝업 / 주행 시 1줄 초간결 자막
- [ ] 에뮬레이터 및 실제 차량 헤드유닛(ccNC) 필드 테스트
- [ ] 통신 단절(Airplane 모드) 환경에서의 On-device SLM 자동 폴백 테스트

---

## 7. 결론 (Conclusion)

본 보고서가 제안하는 **[공간 계층형 온톨로지] + [텔레메트리 연동 하이브리드 RAG]** 아키텍처는 고정된 프리셋 버튼 방식의 한계를 완벽히 극복합니다.

운전자가 어떠한 투박한 일상어로 질문하더라도, 시스템은 차량의 차종과 연식, 현재 주행 상황을 완벽히 이해한 후 **"가장 안전하고 직관적인 손가락의 위치"**를 0.7초 이내에 짚어줌으로써, 렌터카 탑승자의 심리적 불안을 해소하고 차량 주행 안전을 혁신적으로 증대시킬 것입니다.

---
*본 기술 보고서는 프로젝트 내 `report/AI_InCar_Pinpoint_Assistant_Architecture_Report.md` 파일로 영구 보관됩니다.*

