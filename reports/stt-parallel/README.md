# 호출 응답 TTS와 Pleos STT 준비 병렬화

측정일 2026-11-11. 에뮬레이터 `Automotive_1408p_landscape`, 앱 user 10, 디버그 빌드.
응답 문구 "네, 말씀하세요!", Vosk 호출어, Gemini, DB 흐름은 바꾸지 않았다.

## 1. 결론

| 항목 | 결과 |
|---|---|
| SDK에 "준비만" 하는 API가 있나 | **없다.** 공개 SDK와 서비스 AIDL 모두 해당 API가 없다. `request()` 하나가 엔진 생성과 녹음 시작을 연달아 수행한다 |
| 그래도 겹칠 수 있나 | 있다. `request()`를 TTS 재생 중에 호출하면 엔진 생성이 TTS와 겹친다. 대신 녹음이 TTS 종료 전에 시작될 수 있어 보호 장치가 필요하다 |
| 구현 | "병렬 + 오디오 혼입 방지 가드" 모드. 녹음이 TTS 종료 75 ms 이후에 시작될 세션만 유지하고, 그보다 이른 세션은 결과를 쓰지 않고 폐기한 뒤 즉시 순차 요청으로 대체한다 |
| 효과 (같은 조건, 독립 사이클) | TTS 종료 → 실제 캡처 중앙값 **2538 ms → 1197 ms** (−1.34 s, 단측 순열검정 p = 0.008). ≤1 s 비율 0/14 → 4/15 |
| 안전성 | 프롬프트 중단 0회, 인식된 텍스트 0건(모든 실행). 유지된 세션은 녹음 시작이 항상 TTS 종료 75 ms 이후다. 폐기 세션은 서비스 녹음기가 1~77 ms 열렸다가 닫히며 그 오디오는 폐기된 세션과 함께 버려진다 |
| 기본값 | `parallel`로 전환. `-Prendy.voice.wakeSttMode=sequential`로 기존 방식 복귀 |
| 1 s 목표 | 중앙값은 여전히 1 s를 넘는다(1.2 s). 첫 요청(콜드)은 두 방식 모두 4.6~8.0 s 걸린다 |

## 2. SDK·서비스 코드 확인 (추측 아님)

1. **공개 SDK** (`ai.pleos.playground:SpeechToText:3.3.1`, `SpeechToText` 클래스)
   - 공개 메서드: `initialize`, `release`, `addListener`, `removeListener`, `registerApp`, `request`, `stop`, `sendAudio`, `completeAudioSend`, `setLocale`, `getCallApi`, `getAmbientAi`
   - prepare·warm-up·pre-buffer 메서드는 없다.
2. **SDK 내부**
   - `request()` → `SttServiceManager.requestSpeechStream(boolean, traceContext)` → `OnDeviceStt.a(boolean, String)`.
   - 이 boolean은 사용되지 않고, 항상 `IOnDeviceStt.request(packageName)`을 호출한다.
   - `sendAudio()`는 ON_DEVICE 모드에서만 `IOnDeviceStt.send`로 간다.
3. **서비스 AIDL `IOnDeviceStt`**: `registerCallback`, `unregisterCallback`, `request`, `close`, `send`, `sendCallAudio`, `requestCompleteSendBuffer`, `setLocale`, `showSensorPrivacyDialog`. 준비 전용 메서드는 없다.
4. **서비스 구현** (에뮬레이터의 `ai.pleos.playground.caas` `AmbientAiService.apk`, version 4.2.10을 dexdump로 확인)
   - `OnDeviceSttServiceManagerImpl.j(appId)`: 요청마다 새 job context를 만든 뒤 `OnDeviceSttManagerImpl.f()`와 `p()`를 **한 호출 안에서 연속 실행**한다.
     - `f()`: `ai.umos.airspeech.swig.SpeechToText` 네이티브 엔진 생성(`getSpeechToText`). 로그상 약 1.9 s 공백이 이 구간이다.
     - `p()`: `AudioFactory.getRecorder` → 녹음 시작.
   - 엔진은 job마다 새로 만들어진다. `close` → `q()` → `d()`에서 녹음기를 정지한다.
   - `ReadyToStartRecognition`(SDK의 `onReady`)은 엔진 생성 직후, `AudioRecord` 생성 직전에 온다(로그상 28 ms 차이).
   - `send`/`sendRx`/`sendTx`는 엔진 인스턴스가 있어야 하고(`failed - instance is null`), 인스턴스는 녹음을 시작하는 `request` 경로로만 만들어진다.
     따라서 앱이 직접 녹음해 TTS 이후 오디오만 보내는 방식은 불가능하다. 서비스 녹음과 섞이기 때문이다.
   - 서비스 녹음기는 `ns:false, aec:false`다. TTS가 마이크로 들어오면 제거되지 않는다.
5. **앱 측 제약**: TTS 오디오 포커스 리스너는 `AUDIOFOCUS_LOSS_TRANSIENT`를 받으면 TTS를 멈춘다(`VoiceAssistantEngine.requestAudioFocus`).
   TTS 중에 STT 포커스(`GAIN_TRANSIENT`)를 요청하면 "네, 말씀하세요!"가 끊기므로, 병렬 모드는 STT 포커스를 TTS 종료 시점까지 미룬다.

## 3. 구현 (`SttPromptOverlapGuard`)

1. 호출 감지 → "네, 말씀하세요!" `speak()`를 디스패치한 직후 `startPleosRecognitionDuringPrompt()`를 호출한다.
   STT 오디오 포커스는 요청하지 않는다(TTS가 이미 transient 포커스를 갖고 있다).
2. 서비스 `onReady` 시점에 판정한다. 녹음은 이 직후 시작된다.
   - TTS가 아직 재생 중이거나, TTS 종료 후 75 ms 미만 → **폐기**: `stop()`(서비스 `close`).
     세션 상태를 IDLE/ERROR로 알리지 않고 조용히 정리해 호출어 UX가 "말 없음"으로 오인하지 않게 한 뒤, 즉시 순차 요청을 한다.
   - 그 외 → **유지**: TTS 종료 시점(`onDone`, AudioTrack marker 도달 후)에 STT 포커스를 받는다.
3. "듣고 있어요" 표시와 준비음은 이전과 같이 onReady 이후 첫 RMS로 실제 캡처가 확인된 다음에만 나온다.
4. 재시도 프롬프트("잘 못 들었어요…")와 PTT는 기존 순차 방식을 유지한다.
5. 디버그 빌드에서는 `adb shell setprop debug.rendy.wake.sttMode sequential|parallel|parallel_unguarded`로 바꿀 수 있다. `parallel_unguarded`는 측정 전용이며 빌드 플래그로는 켤 수 없다.

## 4. 측정

`measure_cycle.sh` 사용. 앱 재시작 → 45 s 대기 → 회차마다 SIMULATE_WAKE_WORD, 9 s 뒤 logcat 저장, 호출어 대기 상태로 돌아올 때까지 대기한 뒤 다음 회차를 시작한다(회차 간 겹침 없음).

| 모드 | n | TTS 종료→캡처 중앙값 | 평균 | 최소 | 최대 | ≤1 s | 유지/폐기 |
|---|---|---|---|---|---|---|---|
| sequential (기존) | 14* | 2538 ms | 2381 | 1070 | 3126 | 0 | – |
| parallel | 15 | **1197 ms** | 1631 | 802 | 4610 | 4 | 5 / 10 |
| parallel (기본 빌드, 디버그 설정 없음) | 6 | 1174 ms | 1852 | 1025 | 5303 | 0 | 1 / 5 |

\* sequential 1회차는 앱 재시작 직후 첫 요청이라 서비스 준비에 7.6 s가 걸려, 9 s 로그 창 끝(캡처 8.0 s)에 걸렸다. 넣으면 평균이 더 커질 뿐 중앙값은 같다.
parallel 1회차(4.6 s)와 기본 빌드 1회차(5.3 s)도 콜드 첫 요청이다.

- 병렬 요청의 서비스 준비 시간(요청 → onReady)은 TTS와 겹쳐 진행된다.
  가드 없는 진단 실행 6회에서는 녹음이 **TTS 종료 0.67~1.50 s 전에** 시작됐다. 가드가 없으면 실차에서 프롬프트 뒷부분이 녹음된다는 뜻이다.
- 폐기 후 대체 요청은 중앙값 약 1.2 s로 빨랐다. 순차 방식의 같은 구간은 2.2 s다.
  직전에 만든 엔진 덕에 두 번째 생성이 빨라진 것으로 보이지만, 서비스 내부 캐시 동작은 확인하지 못했다.
- 프롬프트 재생 시간, TTS 언더런: 언더런은 순차 방식에서도 12회 중 10회 발생하는 에뮬레이터 기존 현상이라 병렬의 영향으로 볼 수 없다.
- 1차 측정(`round1/`, 기존 하네스)은 무발화 타임아웃과 재시도가 다음 회차와 겹쳐 무효 회차가 많았다. 그래서 독립 사이클 하네스로 다시 쟀다.
  1차의 병렬 1회차에서는 첫 요청이 8 s 안에 준비되지 않아 캡처 타임아웃 → 재시도 경로로 넘어간 사례가 1건 있었다. 2차에서는 재현되지 않았다.

## 5. 한계와 추가 검증

- 에뮬레이터 마이크는 무음(RMS 약 4.8)이라 실제 TTS 음향 혼입은 관찰할 수 없었다. 가드는 음향이 아니라 시간으로 보호한다.
  실차에서는 스피커 → 마이크 경로가 있으므로 다음을 실차 테스트로 확인해야 한다.
  1. 유지된 세션의 첫 partial에 "말씀"이 섞이지 않는지(`analyze.py`의 `textsWithPromptWords`)
  2. 75 ms 여유가 실차 스피커 지연과 잔향에 충분한지. 부족하면 `MIN_READY_AFTER_PROMPT_MS`를 올린다
- 콜드 첫 요청 지연(4.6~8.0 s)은 두 방식 공통의 서비스 문제다.
- 1 s 목표는 Pleos 측 지원이 필요하다. 엔진 상주 또는 준비 전용 API(`f()`만 노출), AEC가 켜진 녹음 중 하나가 있으면 녹음을 TTS 중에 미리 시작해 지연을 거의 0으로 만들 수 있다.

## 6. 변경 파일 (미커밋)

- `service/SttPromptOverlapGuard.kt` (신규): 모드, 판정 로직
- `service/VoiceAssistantEngine.kt`
  - `startPleosRecognitionDuringPrompt()`
  - onReady 가드, 조용한 폐기(`discardPromptOverlapSession`)
  - TTS 종료 시 STT 포커스(`onAllSpeechEnded`), `onPromptOverlapSttDiscarded` 콜백
- `service/CoachOverlayService.kt`: 병렬 요청, 폐기 시 순차 대체, `wakeSttMode()`
- `app/build.gradle.kts`: `RENDY_WAKE_STT_MODE` (기본 `parallel`)
- 테스트
  - `SttPromptOverlapGuardTest` (신규, 6개)
  - `RendyVoicePromptsTest`: "핸들러는 STT를 직접 열지 않는다" 규칙을 "가드가 있는 병렬 요청만, speak 이후에만"으로 갱신하고, onReady 가드 존재를 검사한다
- 측정: `measure_cycle.sh`, `run_mode.sh`, `analyze.py`, `sequential/`, `parallel/`, `default/`, `round1/`
