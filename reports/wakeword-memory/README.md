# 렌디 호출어(Vosk) 메모리 분석·최적화와 Pleos STT 준비 지연 비교

측정일 2026-11-11. 에뮬레이터 `Automotive_1408p_landscape`(x86_64, 4 vCPU, RAM 4 GB, swap 사용). 앱은 user 10.
호출어 "렌디", 응답 "네, 말씀하세요!", Gemini, DB, Pleos STT/TTS 흐름은 바꾸지 않았다.
같은 모델과 같은 grammar `["렌디","<UNK>"]`를 쓰며, 수락 규칙(text == 렌디 && conf ≥ 0.70)도 그대로다.

## 1. 결론 요약

| 항목 | 결과 |
|---|---|
| Vosk가 앱 메모리에서 차지하는 비중 | 앱 TOTAL PSS 546.8 MB 중 약 491 MB(90%). 전부 Native Heap이며 Java Heap은 변화 없음(10.5 MB) |
| mmap 여부 | `/proc/<pid>/maps`에 모델 파일 매핑 0건. Kaldi가 파일 전체를 native heap으로 읽어 들인다 |
| 전체 한국어 ASR 모델이 필요한가 | 음향모델(final.mdl)·ivector·HCLr.fst는 필요하다. 범용 언어모델 Gr.fst(48.5 MB)와 50만 단어 어휘는 grammar 방식에서 쓰이지 않는다 |
| 적용한 최적화 | 앱 전용 저장소에서 "lite" 모델 파생: Gr.fst를 1-state FST로 바꾸고 words.txt를 grammar 단어 + 구조 기호 6줄로 축소(원래 id 유지). 원본 모델·zip은 그대로 둠 |
| 메모리 효과 (기기) | TOTAL PSS **546.8 → 411.2 MB (−135.6 MB, −25%)**, Native Heap 496.2 → 360.4 MB |
| 인식 영향 | 호스트(결정적): full / lite / lite+어휘축소의 340개 파일 판정이 **완전히 동일**. 기기: 같은 변형을 다시 돌려도 5개 파일 판정이 바뀌는 비결정성이 있고, 변형 간 차이(4~8개)는 그 범위 안 |
| Pleos STT 캡처 준비 지연 | **Vosk를 아예 끈 상태에서도 TTS 종료 → 마이크 캡처 중앙값 2.26 s.** 메모리 절감으로 중앙값이 full 대비 약 250 ms 줄었지만 통계적으로 유의하지 않음(p = 0.10~0.29). 지연의 주원인은 Pleos 음성 서비스 쪽이다 |
| 별도 발견 | 기존 호출어 방식의 오인식이 매우 높다(합성 음성 부정 샘플 72%). 메모리 최적화와 무관한 기존 문제다 |

## 2. 메모리 측정 (기기, `dumpsys meminfo`, 시작 후 약 75 초 정상 상태)

| 변형 | 실행 | TOTAL PSS | Native Heap PSS | Java Heap | TOTAL RSS |
|---|---|---|---|---|---|
| off (Vosk 미로드, 기준선) | 1 | 55.5 MB | 14.0 MB | 10.3 MB | 143.9 MB |
| full (기존) | 2회 | 546.9 / 546.7 MB | 496.3 / 496.1 MB | 10.5 MB | 636.7 / 636.5 MB |
| lite (Gr.fst 대체, 어휘 전체) | 2회 | 465.5 / 465.7 MB | 414.8 / 415.3 MB | 10.5 MB | 555.7 / 555.5 MB |
| **lite + 어휘 축소 (현재 기본값)** | 1 | **411.2 MB** | **360.4 MB** | 10.5 MB | 501.4 MB |

- Vosk 몫(PSS − off 기준선): full 약 491 MB, lite 약 410 MB, lite+어휘 약 356 MB.
- `RssFile` 약 98 MB는 앱 코드·라이브러리다. 모델 파일 매핑은 0건(`*/maps_vosk_hits.txt`).
- 호스트(Windows, Python vosk, 같은 파일)의 모델 로드 RSS 증가: full 446 MB, lite 362 MB, lite+어휘 302 MB.

### 모델 파일 (vosk-model-small-ko-0.22, lookahead)

| 파일 | 크기 | 용도 | grammar 방식에서 |
|---|---|---|---|
| graph/HCLr.fst | 181.5 MB | 문맥·발음·HMM 그래프 (olabel_lookahead) | 필요 |
| graph/Gr.fst | 48.5 MB | 범용 n-gram LM + 단어 기호표 503,352개 | 로드 시 기호표만 사용. grammar가 G를 대신한다 |
| am/final.mdl | 14.4 MB | 음향모델 | 필요 |
| ivector/final.ie 등 | 19.8 MB | 화자 적응 | 필요 |
| APK 내 zip | 86.9 MB | 최초 실행 시 앱 저장소에 압축 해제(258 MB) | 변경 없음 |

lite 디렉터리(`files/rendy-wakeword/vosk-model-small-ko-0.22-liteg`)는 원본 파일을 심볼릭 링크로 가리킨다.
새로 생기는 파일은 78 B짜리 Gr.fst와 6줄 words.txt뿐이라 추가 저장공간은 184 KB다.
Android 앱 저장소는 하드 링크를 거부하는데, 첫 구현은 이때 복사로 넘어가 196 MB를 복사하느라 19.7 s가 걸렸다. 심볼릭 링크로 바꿔 해결했다.
최초 1회 준비(Gr.fst 기호표 스트리밍)는 4~9 s 걸리고, 이후에는 marker 확인만 하므로 약 5 ms다.

## 3. 전체 한국어 ASR 모델이 필요한가

- `RendyWakeWordController`는 매 세션 grammar `["렌디","<UNK>"]`로 Recognizer를 만든다.
  Vosk는 이 grammar로 작은 G를 만들어 HCLr.fst와 lookahead 합성을 한다. Gr.fst의 n-gram은 쓰이지 않는다.
- words.txt는 grammar 단어를 id로 바꾸는 데만 쓰인다. HCLr.fst의 출력 라벨이 원래 id를 쓰므로,
  `<eps> 0`, `<UNK> 253415`, `렌디 9516`, `#0`, `<s>`, `</s>`만 원래 id로 남기면 된다.
  grammar 단어가 어휘에 없으면 준비 단계에서 예외를 던지고 full 모델로 대체한다.
  marker 파일에 단어 목록이 기록되므로 grammar를 바꾸면 lite 디렉터리가 자동으로 다시 만들어진다.
- 다만 HCLr.fst(181.5 MB)는 Kaldi 그래프 컴파일 결과물이라 앱 안에서 줄일 방법이 없다. 남은 약 356 MB의 대부분이 이것과 음향모델이다.
  이보다 더 줄이려면 모델 자체를 바꿔야 한다(6장 참고).

검토했지만 적용하지 않은 것:
- **Gr.fst 삭제:** words.txt가 없으면 모델 로드가 실패한다. 그래서 1-state FST로 대체했다.
- **grammar 없이 전체 LM 자유 인식:** 호스트에서 렌디를 0/25 검출했다. 어휘에 있는 다른 단어로 인식된다.
- **유사어 decoy grammar(92단어):** 오인식은 46/60 → 11/60으로 줄었지만, 검출이 24/25 → 8/25로 떨어졌다("렌디뒤" 등으로 흡수). 현재 규칙으로는 쓸 수 없다.

## 4. 호출어 인식률·오인식률

데이터는 Windows TTS(Heami) 합성 음성이다.
- 양성: "렌디"를 속도 5 × 피치 5로 25개.
- 음성: 30개 문구 × 속도 2로 60개. 그중 20개 문구는 렌트·레디·캔디·핸디 같은 유사어, 10개는 일반 문장이다.
- 각각 clean과 갈색잡음 SNR 20/10/5 dB로 만들어 총 양성 100개, 음성 240개다(`tools/gen.ps1`, `tools/mix.py`, 시드 고정).
- 같은 WAV를 호스트(`tools/eval_files.py`)와 기기(디버그 전용 `ACTION_WAKEWORD_SELFTEST`)에서 앱과 같은 grammar, 2048 B 청크, 수락 규칙으로 판정했다.

호스트 결과(결정적, full = lite = lite+어휘가 파일 단위로 동일):

| 조건 | 렌디 검출 | 오인식(음성 샘플 수락) |
|---|---|---|
| clean | 24/25 | 46/60 |
| SNR 20 dB | 24/25 | 48/60 |
| SNR 10 dB | 20/25 | 44/60 |
| SNR 5 dB | 12/25 | 35/60 |
| 합계 | 80/100 | 173/240 (유사어 75%, 일반 문장 66%) |

기기 결과:

| 변형 | 렌디 검출 | 오인식 |
|---|---|---|
| full | 79/100, 80/100 | 176/240, 175/240 |
| lite | 78/100, 80/100, 79/100, 78/100 | 173/240, 176/240, 175/240, 174/240 |
| lite+어휘 | 79/100 | 172/240 |

같은 lite 모델을 같은 프로세스에서 두 번 돌려도 5개 파일 판정이 달랐다(`compare_result.txt`).
기기와 호스트의 차이(4~6개)와 full과 lite의 차이(8개)는 모두 이 비결정성 범위 안이다.

**기존 문제:** grammar가 렌디와 `<UNK>` 둘뿐이라 대부분의 발화가 conf 1.0의 "렌디"로 강제 정렬된다.
- 일반 문장도 66%가 호출어로 수락됐다. clean 조건의 "네, 말씀하세요"도 포함된다.
- 다만 앱은 TTS 재생 중 호출어 마이크를 멈추므로(`Wake word listening paused: TTS playback`), 자기 프롬프트에 반응하는 일은 실제 흐름에서 막혀 있다.
- 합성 음성 기준 수치라 실차 오인식률과는 다르다.
- 메모리 최적화는 이 문제를 고치지도, 악화시키지도 않는다.

## 5. Pleos STT 캡처 준비 지연 (같은 조건)

방법은 `../stt-latency/measure_wake.sh`와 같다: 디버그 SIMULATE_WAKE_WORD → "네, 말씀하세요!" TTS → Pleos STT 요청 → onReady → 첫 RMS 콜백(실제 마이크 캡처 확인).
변형마다 앱을 재시작하고 45 s 대기한 뒤 12회 측정했다(`run_variant.sh`, `latency_stats.py`).

| 변형 | n | TTS 종료→캡처 중앙값 | 평균 | 최소 | 최대 | ≤1 s |
|---|---|---|---|---|---|---|
| off (Vosk 없음) | 12 | 2256 ms | 1943 | 940 | 2595 | 1/12 |
| full | 24 | 2538 ms | 2415 | 1009 | 4777 | 0/24 |
| lite | 24 | 2292 ms | 2197 | 862 | 3327 | 2/24 |
| lite+어휘 | 9 | 2267 ms | 2328 | 1237 | 3737 | 0/9 |

- 중앙값 차이에 대한 순열검정 p값: full↔lite 0.10, full↔lite+어휘 0.29, off↔lite 0.76. 줄어드는 경향은 있지만 유의하지 않다.
- lite+어휘의 n=9: 1회차에 Pleos onReady가 5.5 s 걸려 8 s 로그 창 안에 캡처가 들어오지 않았다. 그 세션이 2~3회차까지 이어지면서 무음 타임아웃과 재시도 안내가 나왔다.
  Vosk와 무관한 서비스 측 이상값이며, full 측정에서도 onReady 4.4 s가 있었다.
- 지연 구성: TTS 종료 → STT 요청 약 35 ms(앱). **STT 요청 → onReady 약 2.0 s(Pleos 서비스)**. onReady → 첫 RMS 약 250 ms.
  즉 거의 전부가 Pleos 서비스가 onReady를 보내기 전 구간이다.
- Vosk를 완전히 꺼도 중앙값이 2.26 s이므로, Vosk 메모리 사용은 1 s 목표 미달의 주원인이 아니다.
  다만 full 모델의 긴 꼬리(최대 4.8 s)는 메모리 압박·swap과 관련이 있을 수 있고, lite에서 최대값이 3.3 s로 낮아졌다. 표본이 적어 확정할 수는 없다.

## 6. 원인 분리와 대안

1. **Pleos 음성 서비스 준비 구간(주원인):** SDK에는 warm-up이나 pre-buffer API가 없다(이전 분석 `../stt-latency/README.md`).
   Pleos 측에 다음을 요청해야 한다.
   - 세션을 미리 열어 두는 API
   - onReady 이전 오디오 프리버퍼링
   - TTS 종료 후 마이크 재획득 시간 단축
   그 전까지는 지금처럼 캡처가 확인된 뒤에만 "듣는 중"을 표시하는 정직한 UX를 유지한다.
2. **실차 측정:** 에뮬레이터는 4 vCPU와 swap 환경이고, 같은 조건에서도 0.5~5.5 s로 흔들린다. 실차 하드웨어에서 같은 스크립트로 다시 측정해야 한다.
3. **추가 메모리 절감(검증 필요, 미적용):**
   - **전용 키워드 스포터** (openWakeWord, Porcupine, 자체 소형 KWS 등): 수 MB 수준이고 오인식 문제도 함께 다룰 수 있다.
     호출어 방식이 바뀌므로 실제 음성 데이터로 검출률과 오인식률을 검증한 뒤에만 도입해야 한다.
   - **"렌디" 전용 HCLr 재컴파일** (Kaldi 툴체인, 어휘를 렌디·유사어·필러로 축소): HCLr 181 MB를 크게 줄일 수 있지만 모델 빌드 환경이 필요하다.
4. **오인식(별도 과제):**
   - 실제 화자 녹음으로 데이터셋을 만든다.
   - decoy grammar와 수락 규칙(예: "렌디" 단독 + 앞뒤 무음)을 함께 조정한다.
   - 또는 위의 전용 KWS를 쓴다.

## 7. 코드 변경 (미커밋)

- `app/src/main/java/com/hyundai/rentalhelper/service/RendyWakeWordLiteGraph.kt` (신규): lite 모델 생성.
  - 원본 파일을 링크하고, Gr.fst 기호표를 바이트 스트리밍해 축소된 words.txt를 쓴다.
  - 1-state FST를 쓰고, marker에 단어 목록을 기록한다.
- `RendyWakeWordModelCache.kt`
  - lite 사용 여부: `BuildConfig.RENDY_WAKEWORD_LITE_GRAPH`(기본 true, `-Prendy.wakeword.liteGraph=false`로 끔).
  - 실패하면 full 모델로 대체한다.
  - 디버그 빌드 전용 속성 `debug.rendy.wakeword.graph=full|lite`, `debug.rendy.wakeword=off`를 지원한다(릴리스 빌드에서는 무시).
  - 로그 `Vosk model timing: variant=...`를 추가했다.
- `RendyWakeWordController.kt`
  - `GRAMMAR_WORDS`에서 `KEYWORD_GRAMMAR`를 만든다(문자열은 이전과 동일).
  - 판정을 `decide()` 하나로 모아 self-test와 공유한다. 규칙은 변경 없음.
- `RendyWakeWordSelfTest.kt` (신규, 디버그 전용) 및 `CoachOverlayService`의 `ACTION_WAKEWORD_SELFTEST`(디버그 빌드에서만 동작).
- `app/build.gradle.kts`: `RENDY_WAKEWORD_LITE_GRAPH` buildConfigField.
- `app/src/test/.../service/RendyWakeWordLiteGraphTest.kt` (신규, 9개):
  - FST 헤더와 기호표 파싱
  - words.txt 형식, 어휘 축소, 원래 id 보존
  - 누락 단어 예외, grammar 문자열 일치
  - 최소 FST 구조
- 단위 테스트 전체: 79 tests, 0 failures, 2 skipped.

## 8. 파일

| 경로 | 내용 |
|---|---|
| `run_variant.sh` | 변형별 측정 (메모리 3회, 지연 12회, self-test) |
| `latency_stats.py` → `latency_stats.txt` | 지연 통계와 검정 |
| `compare.py` → `compare_result.txt` | 파일 단위 판정 비교 |
| `fa_breakdown.txt` | 오인식 분해 |
| `off/`, `full/`, `full_run2/`, `lite/`, `lite_run2/`, `lite_trimvocab/` | meminfo, `/proc` status, 시작 로그, 지연 로그, self-test 결과 |
| `default_build_*`, `default_build_smoke/`, `lite_v3_first_prepare_log.txt` | 기본 빌드 확인 |
| `tools/` | 음성 생성·잡음 합성·호스트 평가·FST 헤더 도구 |
| `host_results/` | 호스트 판정 결과 |

`full/`, `lite/`, `lite_run2/` 측정은 어휘 축소 이전 빌드(lite = 어휘 전체)로 했다. 이 디렉터리들의 지연·메모리 수치는 해당 빌드 기준이다.
기기의 `files/wakeeval`에는 self-test용 WAV 340개(약 19 MB, 앱 전용 저장소)를 남겨 두었다. 앱 데이터를 지우면 함께 삭제된다.
