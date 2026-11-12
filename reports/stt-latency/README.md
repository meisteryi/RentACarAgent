# 호출어 → STT 준비 지연 측정 (2026-11-11, Automotive_1408p_landscape 에뮬레이터)

## SDK 콜백 의미 (Pleos SpeechToText 3.3.1 바이트코드 + 음성 서비스 로그로 확인)
| 서비스 이벤트 | SDK 콜백 | 의미 |
|---|---|---|
| ReadyToStartRecognition | onReady | 서비스 준비 완료 신호. AudioRecord 생성 **전**에 발생 → 캡처 아님 |
| UpdateRms | onUpdatedRms | 오디오 프레임 도달 = 실제 캡처 |
| UpdateEpdState EPD_ON / EPD_OFF | onStartedRecognition / onEndedRecognition | 발화 시작 / 발화 끝 |
| UpdateSttResult / UpdateSttCompleted | onUpdated(text, false/true) | partial / final |

warm-up·prepare API 없음(공개 SDK, IOnDeviceStt AIDL 모두). 서비스 AudioRecord는 `ns:false, aec:false`.

## 결과 (각 12회, 무음, 단위 ms: 중앙값 / 평균 / 최소–최대)
| 지표 | 수정 전 (13:55) | 수정 후 1차 (14:20) | 수정 후 2차 (15:36) |
|---|---|---|---|
| TTS 종료 → STT 요청 | 48 / 46 / 8–73 | 40 / 42 / 24–79 | 12 / 15 / 3–36 |
| STT 요청 → onReady | 2022 / 1759 / 652–2522 | 2242 / 1975 / 804–2413 | 700 / 703 / 223–1266 |
| STT 요청 → 실제 캡처 | 2358 / 1978 / 190*–2874 | 2551 / 2306 / 1117–2783 | 980 / 958 / 467–1498 |
| TTS 종료 → 실제 캡처 | 2398 / 2023 / 233*–2898 | 2594 / 2349 / 1161–2833 | 994 / 973 / 485–1533 |
| STT 요청 → 첫 partial | 측정 불가 (음성 입력 없음) | 측정 불가 | 측정 불가 |
| 발화 → final | 측정 불가 | 측정 불가 | 측정 불가 |

\* 수정 전 run_1의 190/233 ms는 서비스가 AudioRecord를 만들기 약 1초 전에 온 RMS로, 잘못된 "준비됨" 판정이었음(수정됨).

PTT(직전 TTS 없음) 서비스 준비 중앙값 427 ms(10회), 긴 TTS 후 PTT 729 ms(9회). TTS 종료 후 STT 요청을 0/300/700/1200 ms 늦춰도 서비스 준비 시간은 줄지 않음(748–4848 ms).

## 파일
- before/, after/, after2/: 실행별 logcat · *_summary.txt
- before_ptt/, diag_tts_gap/, diag_delay/: 원인 분석 실험
- s3_double_wake.txt, s4_cancel_during_prep.txt, s5_ptt.txt, s6_timeout.txt: 시나리오 로그
- measure_wake.sh, parse.py, speak_when_ready.sh: 측정 스크립트
