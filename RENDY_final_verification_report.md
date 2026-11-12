# RENDY 최종 검증 보고서 (v4.2.0-final)

- 대상 DB: `hyundai_buttons_db_final.json` · 5개 차종 · 버튼 237개 · FAQ 33개(원본 그대로)
- 조사일: 2026-11-10 · 기준: 현대자동차 공식 취급설명서(웹 매뉴얼·국문 PDF)에서 **실제로 읽은 내용만** 근거로 씀
- 판정: VERIFIED=위치·조작 모두 공식 텍스트로 확인 / PARTIAL=기능 존재·위치 분류·조작 중 일부 확인 / CONFLICT=기존 DB와 공식 자료가 충돌해 교정 / UNVERIFIED=근거 미확보(원문 보존) / NOT_APPLICABLE=해당 차종에 없음

## 1. 차종별 결과 (1차 → 최종)

| 차종 | VERIFIED | PARTIAL | CONFLICT | UNVERIFIED | N/A | 근거 판정 비율 |
|---|---|---|---|---|---|---|
| 아이오닉 5 | 0 | 14 → **38** | 0 | 35 → **11** | 1 | 28% → **76%** |
| 투싼 | 8 | 25 | 2 | 15 | 0 | 70% → **70%** |
| 쏘나타 | 0 | 34 | 0 | 16 | 0 | 68% → **68%** |
| 아반떼 | 0 | 3 → **15** | 1 | 40 → **28** | 0 | 9% → **36%** |
| 캐스퍼 | 0 → **1** | 0 → **33** | 0 | 43 → **9** | 0 | 0% → **79%** |
| **합계** | 8 → **9** | 76 → **145** | 3 → **3** | 149 → **79** | 1 → **1** | 37% → **66%** |

- UNVERIFIED: 149개 → **79개** (P0 안전 항목 중 미검증 15개 남음)
- 이번에 새로 VERIFIED가 된 건 캐스퍼 `seat_adjustment` 1개뿐입니다. 위치를 공식 **텍스트**로 확인하지 못한 항목은 PARTIAL로 남겼습니다.
- PARTIAL은 ‘공식 자료에 기능이 있고 어느 구역(운전석 측/중앙/운전대)에 있는지는 확인됨’ 또는 ‘조작법만 확인됨’입니다. 항목마다 `verification.location_verified`·`operation_verified`로 구분해 두었습니다.

## 2. 이번에 확보한 공식 근거

| 차종 | 새로 확보한 자료 | 확인 범위 | 한계 |
|---|---|---|---|
| 아이오닉 5 | 국문 웹 매뉴얼 2023·2024 ‘차량 내부’ + 주제 페이지 20개(EPB·오토홀드·비상등·와이퍼·조명·변속·ESC·주행모드·회생제동·후드·테일게이트·차일드락·미러·후방모니터·주차경고·공조) | 2023·2024판 내부 목차 구성이 같음. 조작법은 2023판 기준으로 대부분 확인 | 버튼 위치는 그림 번호로만 나옴. 충전 도어·12V 리셋·V2L·유니버설 아일랜드·SOS 페이지는 링크가 없어 확인 못 함 |
| 캐스퍼 | 국문 취급설명서 PDF 2종(AX01·AX02, casper.hyundai.com) — 목차, 차량 내부 I·II·III(p.1-6~1-8), 차량 외부, 좌석(p.3-2~3-10) | 모든 조작부의 존재 여부와 그림 분류(운전석 측/중앙/운전대) 확인, 좌석 조절 본문 확인 | 4~7장(조작 본문)은 읽기 범위 밖. 연식·판 표기 없음 |
| 아반떼 | 엘란트라(CN7) 2026 en_US 내부 목차 + 조명·와이퍼·ESC·주유구·트렁크·창문·미러·시트 페이지 | 실내 레버(주유구·트렁크·후드), ESC 2단계, 창문 잠금, 시트(수동·전동) | 국문 매뉴얼은 모든 경로에서 접근 실패 → 해외판 근거라 최대 PARTIAL |
| 투싼·쏘나타 | 1차 근거 유지(NX4 2025·DN8 2024 국문) + 쏘나타 창문 잠금/차일드 락 교정 | — | 운전대 좌우 버튼 배치 일부 미확인 |

## 3. 안전 항목(P0) 상태

| 차종 | P0 전체 | VERIFIED | PARTIAL | CONFLICT(교정) | UNVERIFIED |
|---|---|---|---|---|---|
| 아이오닉 5 | 20 | 0 | 17 | 0 | 3 |
| 투싼 | 19 | 3 | 13 | 2 | 1 |
| 쏘나타 | 18 | 0 | 17 | 0 | 1 |
| 아반떼 | 16 | 0 | 7 | 1 | 8 |
| 캐스퍼 | 17 | 0 | 15 | 0 | 2 |

미검증으로 남은 P0 항목은 1차 답변에서 위치만 안내하고, 조작 단계 앞에 ‘차를 세운 뒤’ 문장을 붙이며, `answer_policy=hedged_safety`로 표시했습니다.

## 4. 남은 미검증 항목과 필요한 자료

| 차종 | 버튼 | 우선순위 | 필요한 자료 |
|---|---|---|---|
| 아반떼 | `auto_hold` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `cruise_gap` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `cruise_speed_toggle` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `engine_start_stop` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `front_defog` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `hazard_light` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `lane_follow_assist` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `smart_cruise` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `ac_on_off` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `air_recirculation` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `audio_seek` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `audio_volume` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `climate_auto` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `climate_off` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `cluster_view_mode` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `custom_star` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `headlight_leveling` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `heated_steering_wheel` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `parking_sensor` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `parking_view` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `passenger_grab_bar` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `phone_call` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `rear_defog` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `seat_heat_passenger` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `seat_vent_heat` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `temp_dial_driver` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `temp_dial_passenger` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아반떼 | `voice_recognition` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 캐스퍼 | `cruise_speed_toggle` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 캐스퍼 | `front_defog` | P0 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 캐스퍼 | `custom_star` | P1 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 캐스퍼 | `ac_button` | P2 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 캐스퍼 | `air_recirculation` | P2 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 캐스퍼 | `audio_seek` | P2 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 캐스퍼 | `climate_fan_dial` | P2 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 캐스퍼 | `climate_temp_dial` | P2 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 캐스퍼 | `rear_defog` | P2 | 국문 취급설명서 PDF 본문(해당 기능 페이지) 또는 실차 사진 |
| 아이오닉 5 | `battery_reset_12v` | P0 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `seat_relaxation_comfort` | P0 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `sos_button` | P0 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `charge_door` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `heated_steering_wheel` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `room_lamp_door` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `seat_adjustment` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `seat_vent_heat` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `sliding_console` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `v2l_indoor` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 아이오닉 5 | `vision_roof_shade` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `sos_button` | P0 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `audio_seek` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `climate_auto` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `custom_star` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `heated_steering_wheel` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `interior_light_dimmer` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `room_lamp_door` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `seat_adjustment` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `seat_heat_passenger` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `seat_vent_heat` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `sunroof_switch` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `sync_climate` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `climate_off` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `fuel_door` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `temp_dial_driver` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 쏘나타 | `temp_dial_passenger` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `sos_button` | P0 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `custom_star` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `heated_steering_wheel` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `room_lamp_door` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `seat_adjustment` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `seat_heat_passenger` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `seat_vent_heat` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `sunroof_switch` | P1 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `audio_seek` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `audio_volume` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `cluster_view_mode` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `fuel_door` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `phone_call` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `rear_defog` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |
| 투싼 | `voice_recognition` | P2 | 해당 기능의 국문 웹 매뉴얼 본문(그림 설명 포함) 또는 실차 사진 |

**자료 확보 권장 순서**
1. **아반떼 국문 취급설명서** (CN7/CN7 PE): 차량에 비치된 책자나 현대차 고객지원 PDF가 있으면 미검증 28개를 한꺼번에 대조할 수 있습니다.
2. **투싼·쏘나타 운전대 사진** (렌터카 실차): 좌우 버튼 배치(음성인식·볼륨·전화·별표·차로 유지)를 확인합니다.
3. **캐스퍼 PDF 4~7장**: PC 브라우저로 AX01 PDF 전체를 열어 p.5-19(핸들 열선), 6-17(풋 브레이크), 6-23(주행 모드), 7-58(스마트 크루즈)를 확인합니다.
4. **아이오닉 5 국문 웹 매뉴얼의 충전 도어·12V 리셋·V2L 페이지**: 내부 목차에 링크가 없어 사이트 검색이 필요합니다.

## 5. 자동 정합성 검사 결과 (실행: `rendy_db_lint.py`)

| 검사 항목 | 1차(enhanced) | 최종(final) |
|---|---|---|
| broken_redirect | 0 | 0 |
| control_type_mixup | 30 | 0 |
| duplicate_id | 0 | 0 |
| faq_intents_modified | 0 | 0 |
| hedge_phrase | 199 | 0 |
| id_lost_vs_original | 0 | 0 |
| keyword_in_multiple_intents | 1 | 1 |
| left_right_mismatch | 0 | 0 |
| missing_field | 0 | 0 |
| other_model_mentioned | 0 | 0 |
| repeated_word | 6 | 0 |
| spoken_over_2_sentences | 2 | 0 |
| status_without_source | 0 | 0 |
| unverified_safety_without_note | 4 | 0 |

남은 1건 `keyword_in_multiple_intents`(‘열선’이 rear_defog·seat_vent_heat 두 인텐트에 모두 있음)는 원본 FAQ에서 온 것입니다. FAQ는 보존 조건 때문에 고치지 않고, `clarification_rules`에서 되묻기로 처리했습니다.
- 인텐트 참조: 원본 FAQ 33개 × 5개 차종 중 해당 차종에 버튼이 없는 조합 35건을 찾아 `intent_enhancements.model_routing`에 fallback, not_applicable, no_data 중 하나로 명시했습니다.
- 중복 버튼 3건(아이오닉5 fuel_door, 캐스퍼 electronic_parking_brake, 캐스퍼 drive_traction_dial)은 `duplicate_of`로 연결하고 안내 문구를 맞췄습니다.

## 6. 237개 최종 검증표

| 차종 | 버튼 | 1차 | 최종 | 위치 확인 | 조작 확인 | 근거 요약 | 출처 |
|---|---|---|---|---|---|---|---|
| 아이오닉 5 | `gear_shift` | PARTIAL | PARTIAL | — | ✅ | [2023·2025] 전자식 변속 다이얼 + P 버튼(내부 III). 모든 변속에 브레이크 필수. D에서 살짝 아래로=N, R에서 살짝 위로=N(2025). | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idc1a9567d60e.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/iddf661a37926.html) [3](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `hazard_light` | UNVERIFIED | PARTIAL | — | — | [2023] 비상 경고등 버튼(내부 II, 중앙). 시동과 무관하게 작동, 점멸 중 방향지시등 불가. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idd7334e78f90.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `wiper_stalk` | UNVERIFIED | PARTIAL | — | — | [2023] MIST/OFF/INT·AUTO(사양)/LO/HI, 속도 노브는 위로 돌리면 빠르게. 워셔는 별도 위치(C). | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id66158b12c5c.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `light_stalk` | UNVERIFIED | PARTIAL | — | — | [2023] 조명 스위치 끝을 돌려 OFF/AUTO/미등/전조등. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idb55c2877eff.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `front_defog` | PARTIAL | PARTIAL | — | — | [2023·2025] 히터 및 에어컨 조작부의 ‘앞 유리창 서리제거’ 버튼(목록 09). | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idfc12f1bc613.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/convenience_device_automatic_climate_control_system.html) |
| 아이오닉 5 | `rear_defog` | PARTIAL | PARTIAL | — | — | [2023·2025] ‘뒷유리창(열선) 서리제거’ 버튼(목록 10/12). | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idfc12f1bc613.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/convenience_device_automatic_climate_control_system.html) |
| 아이오닉 5 | `climate_auto` | PARTIAL | PARTIAL | — | — | 전자동 조절(2번) 조작부 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/convenience_device_automatic_climate_control_system.html) |
| 아이오닉 5 | `climate_off` | PARTIAL | PARTIAL | — | — | 작동 정지(8번) 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/convenience_device_automatic_climate_control_system.html) |
| 아이오닉 5 | `temp_toggle_driver` | PARTIAL | PARTIAL | — | — | 운전석 온도 조절(6번) 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/convenience_device_automatic_climate_control_system.html) |
| 아이오닉 5 | `temp_toggle_passenger` | PARTIAL | PARTIAL | — | — | 동승석 온도 조절(7번) 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/convenience_device_automatic_climate_control_system.html) |
| 아이오닉 5 | `driver_only` | PARTIAL | PARTIAL | — | — | ‘운전석 개별 공조’ 조작부 존재(자동 공조 1번). | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/convenience_device_automatic_climate_control_system.html) |
| 아이오닉 5 | `air_recirculation` | PARTIAL | PARTIAL | — | — | 내기/외기 선택(13번) 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/convenience_device_automatic_climate_control_system.html) |
| 아이오닉 5 | `seat_vent_heat` | UNVERIFIED | UNVERIFIED | — | ✅ | [2023] 좌석 페이지는 인포테인먼트 ‘스마트 열선/통풍’ 자동 제어만 기술. 물리/터치 버튼 위치 미확인. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id81db5616e14.html) |
| 아이오닉 5 | `heated_steering_wheel` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `smart_cruise` | PARTIAL | PARTIAL | — | — | 주행 보조 버튼으로 켜고 +/− 1·10km/h, 차간거리 4단계, 30~180km/h. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/id222PF0ZL0HS.html) |
| 아이오닉 5 | `lane_follow_assist` | UNVERIFIED | PARTIAL | — | — | [2023] 차로 주행 보조 버튼이(가) 운전대 그림(내부 III)에 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `cruise_gap` | PARTIAL | PARTIAL | — | — | 차간거리 버튼 4단계(90km/h 기준 약 52.5/40/32.5/25m). | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/id222PF0ZL0HS.html) |
| 아이오닉 5 | `cruise_speed_toggle` | PARTIAL | PARTIAL | — | — | +/− 짧게 1km/h, 길게 10km/h. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/id222PF0ZL0HS.html) |
| 아이오닉 5 | `drive_mode` | UNVERIFIED | PARTIAL | — | ✅ | [2023] DRIVE MODE 버튼(내부 III, 운전대 그림 그룹)을 누를 때마다 NORMAL→ECO→SPORT, 길게 누르면 SNOW. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id2581f1d0ebb.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `regen_paddle_left` | UNVERIFIED | PARTIAL | — | — | [2023] 한쪽 패들을 한 번 당기면 회생 단계↑, 0.5초 이상 당기고 있으면 정지까지 감속(i-PEDAL). 단계: SNOW 0~1, 그 외 0~3. 브레이… | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idb770623945d.html) |
| 아이오닉 5 | `regen_paddle_right` | UNVERIFIED | PARTIAL | — | — | [2023] 반대쪽 패들은 단계↓, 1초 이상 당기면 스마트 회생 시스템 켜기/끄기. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idb770623945d.html) |
| 아이오닉 5 | `voice_recognition` | UNVERIFIED | PARTIAL | — | — | [2023] 음성 인식 버튼이(가) 운전대 그림(내부 III)에 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `audio_volume` | UNVERIFIED | PARTIAL | — | — | [2023] 오디오 리모컨 스위치이(가) 운전대 그림(내부 III)에 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `phone_call` | UNVERIFIED | PARTIAL | — | — | [2023] 블루투스 핸즈프리 버튼이(가) 운전대 그림(내부 III)에 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `cluster_view_mode` | UNVERIFIED | PARTIAL | — | — | [2023] 클러스터 메뉴 설정 버튼이(가) 운전대 그림(내부 III)에 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `electronic_parking_brake` | PARTIAL | PARTIAL | — | ✅ | [2023·2025] 브레이크를 밟고 EPB 스위치를 당기면(2025 국문: ‘위로 당기십시오’) 체결, 시동 ON·브레이크 밟고 누르면 해제. 공식 내부 목차 … | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/iddb9b77f2061.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) [3](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/id23C2C0C10HS.html) |
| 아이오닉 5 | `auto_hold` | UNVERIFIED | PARTIAL | — | ✅ | [2023] 브레이크를 밟고 AUTO HOLD를 누르면 흰색 대기, 정차하면 초록색. P단·EPB 체결 중 작동 안 함. 공식 내부 목차 ‘차량 내부 I’(운전석… | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idcf466bc51d1.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `charge_door` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `fuel_door` | NOT_APPLICABLE | NOT_APPLICABLE | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `tailgate` | UNVERIFIED | PARTIAL | — | — | [2023] 실내 파워 테일게이트 열림/닫힘 버튼(공식 내부 목차 ‘차량 내부 I’(운전석 측 그림)에 분류 — 세부 위치는 그림에만 있음). 외부 버튼은 길게 … | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id47e0561ff91.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `esc_off` | UNVERIFIED | PARTIAL | — | ✅ | [2023] 짧게=1단계(구동력 제어 해제), 3초 이상=2단계(전체 해제), 짧게 다시 누르면 복귀. 공식 내부 목차 ‘차량 내부 I’(운전석 측 그림)에 분류… | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id8e27fb569fb.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `battery_reset_12v` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `hood_open` | UNVERIFIED | PARTIAL | — | ✅ | [2023] 실내 후드 레버를 당기면 살짝 열림 → 차 앞에서 가운데 안쪽 보조 레버를 왼쪽으로 밀며 들어 올림. 공식 내부 목차 ‘차량 내부 I’(운전석 측 그… | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id7a487dade68.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `sliding_console` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `wireless_charger` | UNVERIFIED | PARTIAL | — | — | [2023] 휴대폰 무선 충전 시스템·스마트폰 인증/무선 충전 패드(내부 II). | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `parking_sensor` | UNVERIFIED | PARTIAL | — | — | [2023] 주차 안전 버튼(내부 II) 존재, R단에서 경고 1회 확인음. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/ida383fc356cb.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `parking_view` | UNVERIFIED | PARTIAL | — | — | [2023] 주차/뷰 버튼(내부 II). P에서 누르면 후방 화면, D·N에서도 토글, 10km/h 초과 시 꺼짐. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id12c4b28d9f7.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `v2l_indoor` | UNVERIFIED | UNVERIFIED | — | — | [2023] 다용도 소켓 페이지는 12V 180W 소켓만 기술, 실내 V2L 언급 없음. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `power_windows` | UNVERIFIED | PARTIAL | — | — | [2023] 유리창 버튼 2단 디텐트에서 자동 열림/닫힘. 공식 내부 목차 ‘차량 내부 I’(운전석 측 그림)에 분류 — 세부 위치는 그림에만 있음. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id02a1cd26c9c.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `window_lock` | UNVERIFIED | PARTIAL | ✅ | ✅ | [2023] 운전석 도어에 ‘유리창 잠금 버튼’과 ‘전자식 차일드 락 버튼’(같은 그림 번호 07, 별도 설명 페이지). 잠금 버튼=뒷좌석 창문 스위치 비활성, … | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id02a1cd26c9c.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id6021613a223.html) [3](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `door_lock_unlock` | UNVERIFIED | PARTIAL | — | — | [2023] 중앙 도어 잠금/잠금 해제 버튼(공식 내부 목차 ‘차량 내부 I’(운전석 측 그림)에 분류 — 세부 위치는 그림에만 있음). | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `side_mirror_adjust` | UNVERIFIED | PARTIAL | — | — | [2023] 선택 레버를 L(운전석)/R(동승석)에 두고 조절 스위치로 상하좌우. 후진 시 자동 하향 기능. 공식 내부 목차 ‘차량 내부 I’(운전석 측 그림)에… | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id754c73d5e23.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `side_mirror_fold` | UNVERIFIED | PARTIAL | — | — | [2023] 접이 버튼으로 접고 폄, 15km/h 초과 시 작동 안 함. 공식 내부 목차 ‘차량 내부 I’(운전석 측 그림)에 분류 — 세부 위치는 그림에만 있음… | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id754c73d5e23.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `memory_seat` | UNVERIFIED | PARTIAL | — | — | [2023] 운전 자세 메모리 시스템(공식 내부 목차 ‘차량 내부 I’(운전석 측 그림)에 분류 — 세부 위치는 그림에만 있음). | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `vision_roof_shade` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `room_lamp_door` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `sos_button` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `seat_adjustment` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `seat_relaxation_comfort` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아이오닉 5 | `engine_start_stop` | PARTIAL | PARTIAL | — | — | [2023] 시동 버튼(내부 II). [2024 en_US] 브레이크를 밟고 P에서 누르면 READY. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2024/en_US/id6bdd2068f97.html) |
| 투싼 | `gear_shift` | PARTIAL | PARTIAL | ✅ | ✅ | 핸들 오른쪽 칼럼 레버 끝의 변속 다이얼. 브레이크를 밟고 위쪽으로 돌리면 D, 아래쪽으로 돌리면 R. P는 레버 바깥쪽 P 버튼. P·N에서 벗어날 때 브레이크… | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id229G80JC0EV.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `wiper_stalk` | PARTIAL | PARTIAL | ✅ | — | 핸들 오른쪽 칼럼 레버. 앞: MIST/OFF/AUTO(사양 적용 시)/LO/HI, 뒷와이퍼는 레버 끝 회전(HI/LO/OFF). | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id9f65f5f8048.html) |
| 투싼 | `light_stalk` | PARTIAL | PARTIAL | ✅ | — | 핸들 왼쪽 칼럼 레버 끝을 돌려 OFF/AUTO/미등/전조등. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id23b13698f66.html) |
| 투싼 | `paddle_shift_left` | PARTIAL | PARTIAL | — | — | 운전대 뒤 +/− 패들. 한 번 당기면 한 단씩 수동 변속, + 길게 당기면 자동 복귀. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id37bd7da1142.html) |
| 투싼 | `paddle_shift_right` | PARTIAL | PARTIAL | — | — | 위와 동일. + 패들 길게 당기면 자동 변속 복귀. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id37bd7da1142.html) |
| 투싼 | `smart_cruise` | CONFLICT | CONFLICT | ✅ | ✅ | 공식 문서 그림 설명상 ‘주행 보조 버튼(SCC)’과 ‘차간거리 버튼’은 운전대 왼쪽, +/− 속도 스위치는 운전대 오른쪽. 버튼으로 켠 뒤 +/−로 1km/h(… | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id222PF0ZL0HS.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `lane_follow_assist` | PARTIAL | PARTIAL | — | — | 차로 주행 보조 버튼은 운전대 버튼 그룹(그림 III-4)에 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `cruise_gap` | CONFLICT | CONFLICT | ✅ | — | 차간거리 버튼은 운전대 왼쪽(SCC 버튼과 같은 쪽). 누를 때마다 4→3→2→1단계 순환. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id222PF0ZL0HS.html) |
| 투싼 | `cruise_speed_toggle` | PARTIAL | PARTIAL | ✅ | ✅ | +/− 스위치는 운전대 오른쪽. 짧게 1km/h, 길게 10km/h 단위, 30~180km/h. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id222PF0ZL0HS.html) |
| 투싼 | `cluster_view_mode` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `voice_recognition` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `audio_volume` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `audio_seek` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `phone_call` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `custom_star` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `hazard_light` | PARTIAL | PARTIAL | ✅ | — | 비상 경고등은 센터페시아 중앙(크래시패드 가운데, 삼각형 기호). 시동과 무관하게 작동. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id71c0f13c980.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `engine_start_stop` | PARTIAL | PARTIAL | — | — | 시동 버튼 존재(그림 II-3). | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `climate_auto` | PARTIAL | PARTIAL | — | — | 온도 노브에 AUTO 표시 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id042e6950a36.html) |
| 투싼 | `climate_off` | PARTIAL | PARTIAL | — | — | OFF는 송풍·내외기 외 작동 정지. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id042e6950a36.html) |
| 투싼 | `temp_dial_driver` | PARTIAL | PARTIAL | — | — | 온도 노브(AUTO·SYNC 표시)를 오른쪽으로 돌리면 따뜻, 왼쪽은 차갑게. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id042e6950a36.html) |
| 투싼 | `temp_dial_passenger` | PARTIAL | PARTIAL | — | — | 동승석 온도 노브 존재(조작 방향 동일). | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id042e6950a36.html) |
| 투싼 | `front_defog` | PARTIAL | PARTIAL | — | — | 앞유리 서리 제거 버튼 누르면 표시등 켜짐, 다시 누르면 이전 설정. 김서림 방지 설정 시 외기·에어컨 자동. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/convenience_device_windshield_defrosting_and_defogging.html) |
| 투싼 | `rear_defog` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `air_recirculation` | PARTIAL | PARTIAL | — | — | 내/외기 버튼, 표시등 켜짐=내기 순환. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id042e6950a36.html) |
| 투싼 | `sync_climate` | PARTIAL | PARTIAL | — | — | SYNC 표시등이 켜지면 전 좌석이 운전석 설정을 따름. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id042e6950a36.html) |
| 투싼 | `drive_mode` | PARTIAL | PARTIAL | ✅ | ✅ | 중앙 콘솔 DRIVE MODE 스위치로 ECO>NORMAL>SPORT>MY DRIVE 순환. 험로 모드는 DRIVE/TERRAIN 버튼을 누른 뒤 4초 안에 MO… | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id235NB0DL0NW.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/ide307a3bb94e.html) [3](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `auto_hold` | PARTIAL | PARTIAL | ✅ | ✅ | AUTO HOLD 버튼은 중앙 콘솔(그림 II-10). 문·후드를 닫고 브레이크를 밟은 채 누르면 흰색 대기 표시, 정차 시 초록색으로 유지. P단·EPB 체결 … | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id390901fe6c7.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `seat_vent_heat` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `seat_heat_passenger` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `heated_steering_wheel` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `parking_sensor` | VERIFIED | VERIFIED | ✅ | ✅ | 주차 안전 버튼은 중앙 콘솔 버튼 줄 오른쪽 끝(그림 II-14). 짧게 누르면 주차 거리 경고 켜기/끄기, 길게 누르면 후방 충돌방지 보조. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id939bb43111e.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `parking_view` | PARTIAL | PARTIAL | ✅ | — | 주차/뷰 버튼은 중앙 콘솔(그림 II-13). 10km/h 미만에서 누르면 후방 모니터 표시. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id222SGG090HS.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `downhill_brake` | PARTIAL | PARTIAL | ✅ | — | DBC 버튼은 중앙 콘솔(그림 II-12). 누르면 초록 대기, 다시 누르면 해제. P단 불가, 시동마다 꺼짐. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id235A90Q01Y4.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `wireless_charger` | PARTIAL | PARTIAL | ✅ | — | 무선 충전 패드는 중앙 콘솔(그림 II-16). 시동 ON·문 닫힘 상태에서 패드 중앙에 Qi 폰. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/idfca5e6e5686.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `electronic_parking_brake` | VERIFIED | VERIFIED | ✅ | ✅ | 운전석 왼쪽 아래 대시보드(그림 I 상세도 A-8)에 EPB 스위치. 브레이크를 밟고 정차한 상태에서 당기면 체결, 시동 ON·브레이크를 밟고 누르면 해제. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id76e01aea2d0.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `tailgate` | PARTIAL | PARTIAL | — | — | 파워 테일게이트 열림/닫힘 버튼은 운전석 왼쪽 대시보드(상세도 A-9). | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `esc_off` | VERIFIED | VERIFIED | ✅ | ✅ | 운전석 왼쪽 아래 대시보드(상세도 A-10). 짧게 누르면 1단계(구동력 제어만 해제), 3초 이상 누르면 2단계(ESC 전체 해제). ESC 표시등이 깜빡일 때… | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id0901bae25b0.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `headlight_leveling` | VERIFIED | VERIFIED | ✅ | ✅ | 운전대 왼쪽 다이얼(상세도 A-7). 숫자가 클수록 빛이 아래로. 운전자만 0, 5인 1, 5인+짐 2, 운전자+트렁크 짐 3. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id7f8417d45da.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `hood_open` | VERIFIED | VERIFIED | ✅ | ✅ | 운전석 대시보드 왼쪽 아래 페달 근처 레버를 당긴 뒤, 차 앞에서 보닛을 살짝 들고 안쪽 보조 레버를 밀어 연다. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/idb9f35a6ce00.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `fuel_door` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `power_windows` | VERIFIED | VERIFIED | ✅ | ✅ | 운전석 도어 팔걸이 스위치. 살짝 누르거나 당기면 이동, 끝까지 누르거나 당기면 자동. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/convenience_device_power_windows.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `window_lock` | PARTIAL | PARTIAL | ✅ | ✅ | 운전석 도어 팔걸이 유리창 잠금 버튼이 동승석·뒷좌석 스위치를 비활성화. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/convenience_device_power_windows.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `door_lock_unlock` | PARTIAL | PARTIAL | ✅ | — | 중앙 도어 잠금/해제 버튼은 운전석 도어 팔걸이(상세도 B-4). | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `side_mirror_adjust` | VERIFIED | VERIFIED | ✅ | ✅ | 운전석 도어 팔걸이. L(운전석)/R(동승석) 선택 후 둥근 스위치로 조절. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/idce3017f48fe.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `side_mirror_fold` | VERIFIED | VERIFIED | ✅ | ✅ | 운전석 도어 팔걸이 접힘 버튼(15km/h 초과 시 작동 안 함). | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/idce3017f48fe.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `memory_seat` | PARTIAL | PARTIAL | — | — | 운전석 자세 메모리 시스템 존재(그림 I-17), 세부 위치·저장 절차 텍스트 미확인. | [1](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) |
| 투싼 | `sunroof_switch` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `room_lamp_door` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `sos_button` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 투싼 | `seat_adjustment` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `gear_shift` | PARTIAL | PARTIAL | — | ✅ | 전자식 변속 다이얼 + P 버튼(다이얼 옆). 모든 변속에 브레이크 필수. D에서 살짝 아래로 돌리면 N, R에서 살짝 위로 돌리면 N. 시동 OFF 시 자동 P… | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id229G80JC0EVa.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `wiper_stalk` | PARTIAL | PARTIAL | — | — | 와이퍼/와셔 스위치(그림 III-02). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `light_stalk` | PARTIAL | PARTIAL | — | — | 조명 스위치(그림 III-01). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `paddle_shift_left` | PARTIAL | PARTIAL | — | — | − 패들을 당기면 한 단 내림. 7km/h 이하 등에서 자동 복귀. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id37bd7da1142a.html) |
| 쏘나타 | `paddle_shift_right` | PARTIAL | PARTIAL | — | — | + 패들 한 번=한 단 올림, 길게=자동 복귀. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id37bd7da1142a.html) |
| 쏘나타 | `smart_cruise` | PARTIAL | PARTIAL | — | — | 운전대 버튼 그룹(그림 III-4)의 주행 보조 버튼으로 켜고 +/−로 1·10km/h 조절, 30~200km/h. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id222PF0ZL0HS.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `lane_follow_assist` | PARTIAL | PARTIAL | — | — | 차로 주행 보조 버튼(그림 III-7) 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `cruise_gap` | PARTIAL | PARTIAL | — | — | 차간거리 버튼(그림 III-6) 4단계 순환. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id222PF0ZL0HS.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `cruise_speed_toggle` | PARTIAL | PARTIAL | — | — | +/− 짧게 1km/h, 길게 10km/h. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id222PF0ZL0HS.html) |
| 쏘나타 | `cluster_view_mode` | PARTIAL | PARTIAL | — | — | 클러스터 메뉴 설정 버튼(그림 III-05). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `voice_recognition` | PARTIAL | PARTIAL | — | — | 음성 인식 버튼(그림 III-08). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `audio_volume` | PARTIAL | PARTIAL | — | — | 오디오 리모컨(그림 III-09). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `audio_seek` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `phone_call` | PARTIAL | PARTIAL | — | — | 블루투스 핸즈프리(그림 III-10). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `custom_star` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `hazard_light` | PARTIAL | PARTIAL | — | — | 비상 경고등 존재(그림 II-07, 중앙부). 누르면 좌우 방향지시등 동시 점멸. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `engine_start_stop` | PARTIAL | PARTIAL | — | — | 시동 버튼(그림 II-04). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `climate_auto` | UNVERIFIED | UNVERIFIED | — | — | 2024 국문 내부 목차는 ‘수동 조절식 히터·에어컨’을 표기. 자동 공조 페이지는 접근 실패. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id22CKHB0705Z.html) |
| 쏘나타 | `climate_off` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `temp_dial_driver` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `temp_dial_passenger` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `front_defog` | PARTIAL | PARTIAL | — | — | 앞유리 서리 제거 조작부 존재(수동 공조 그림 4번), 선택 시 에어컨·외기 자동(김서림 방지 설정 시). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id71225b967b5.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id22CKHB0705Z.html) |
| 쏘나타 | `rear_defog` | PARTIAL | PARTIAL | — | — | 뒷유리(열선) 서리 제거 조작부 존재(수동 공조 그림 5번). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id22CKHB0705Z.html) |
| 쏘나타 | `air_recirculation` | PARTIAL | PARTIAL | — | — | 내기/외기 조작부 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id22CKHB0705Z.html) |
| 쏘나타 | `sync_climate` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `drive_mode` | PARTIAL | PARTIAL | — | — | DRIVE MODE 스위치(그림 II-15)로 ECO>NORMAL>SPORT>MY DRIVE. ECO·SPORT는 재시동 시 NORMAL로 복귀. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id2581f1d0ebba.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `auto_hold` | PARTIAL | PARTIAL | — | — | AUTO HOLD 버튼은 중앙부(그림 II-14). 브레이크 밟고 누르면 흰색, 정차 시 초록. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id390901fe6c7.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `seat_vent_heat` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `seat_heat_passenger` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `heated_steering_wheel` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `parking_sensor` | PARTIAL | PARTIAL | — | — | 주차 안전 버튼 PDW/PCA(그림 II-17). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `parking_view` | PARTIAL | PARTIAL | — | — | 주차/뷰 버튼(그림 II-16). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `wireless_charger` | PARTIAL | PARTIAL | — | — | 무선 충전기(그림 II-13). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `electronic_parking_brake` | PARTIAL | PARTIAL | ✅ | ✅ | EPB(다이얼 변속 사양)는 운전석 쪽 조작부(그림 I-12). 정차·브레이크 상태에서 당기면 체결, 시동 ON·브레이크 밟고 누르면 해제. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id76e01aea2d0.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `tailgate` | PARTIAL | PARTIAL | ✅ | — | 실내 트렁크 열림 버튼(그림 I-11, 크래시패드). 실내에서 버튼을 누르면 열림. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id82fc3560510.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `esc_off` | PARTIAL | PARTIAL | ✅ | ✅ | ESC OFF 버튼은 운전석 쪽(그림 I-10). 짧게=1단계, 3초 이상=2단계 전체 해제. 표시등 깜빡일 때 누르지 말 것. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id0901bae25b0.html) [2](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `interior_light_dimmer` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `hood_open` | PARTIAL | PARTIAL | ✅ | — | 후드 열림 레버(그림 I-13, 운전석 쪽). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `fuel_door` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `trunk_exterior_hidden` | PARTIAL | PARTIAL | — | — | 스마트키 소지 시 트렁크 외부 열림 스위치로 연다. 동결·고압 세차 시 오작동 주의. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id82fc3560510.html) |
| 쏘나타 | `power_windows` | PARTIAL | PARTIAL | — | — | 유리창 열림/닫힘 스위치(그림 I-06). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `window_lock` | PARTIAL | PARTIAL | — | ✅ | 유리창 잠금 버튼 및 전자식 차일드 락(그림 I-07, 운전석 문). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `door_lock_unlock` | PARTIAL | PARTIAL | — | — | 중앙 도어 잠금/해제(그림 I-05). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `side_mirror_adjust` | PARTIAL | PARTIAL | — | — | 실외 미러 조절 스위치(그림 I-04). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `side_mirror_fold` | PARTIAL | PARTIAL | — | — | 실외 미러 접힘 버튼(그림 I-03). | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `memory_seat` | PARTIAL | PARTIAL | — | — | 운전석 자세 메모리(그림 I-02) 존재. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `sunroof_switch` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `room_lamp_door` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `sos_button` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 쏘나타 | `seat_adjustment` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `gear_shift` | PARTIAL | PARTIAL | — | — | 해외판(2026 en_US IVT): 바닥 변속 레버, P에서 빼거나 R·N·D 전환 시 브레이크 필요. 수동 모드는 D에서 수동 게이트로 옮기고 +는 앞으로, … | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idd7ca4074254.html) |
| 아반떼 | `shift_lock_release` | CONFLICT | CONFLICT | ✅ | ✅ | 해외판(2026 en_US): 비상용. 시동 OFF·주차 브레이크 체결 후 변속 레버 부트(덮개)를 조심스럽게 벗기고 레버 밑 해제 버튼을 드라이버 등 도구로 누… | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idd7ca4074254.html) |
| 아반떼 | `wiper_stalk` | UNVERIFIED | PARTIAL | — | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: MIST/OFF/INT/LO/HI, 레버를 몸쪽으로 당기면 워셔. | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/id4c7ceb0a7cd.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `light_stalk` | UNVERIFIED | PARTIAL | ✅ | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: 레버 끝 노브 OFF/AUTO/미등/전조등. | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idabac8f7e8a8.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `smart_cruise` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `lane_follow_assist` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `cruise_gap` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `cruise_speed_toggle` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `cluster_view_mode` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `voice_recognition` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `audio_volume` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `audio_seek` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `phone_call` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `custom_star` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `hazard_light` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `engine_start_stop` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `climate_auto` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `climate_off` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `temp_dial_driver` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `temp_dial_passenger` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `front_defog` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `rear_defog` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `air_recirculation` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `ac_on_off` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `electronic_parking_brake` | PARTIAL | PARTIAL | — | ✅ | 해외판(CN7 2025 en_GN, 2026 en_US): 브레이크를 밟고 EPB 스위치를 위로 당기면 체결, 시동 ON·브레이크 밟고 누르면 해제. | [1](https://ownersmanual.hyundai.com/full_webhelp/CN7/2025/en_GN/idcf2d3edca52.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idb47e1bdf24c.html) |
| 아반떼 | `auto_hold` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `drive_mode` | PARTIAL | PARTIAL | — | — | 해외판(2026 en_US): DRIVE MODE 스위치는 변속 레버 콘솔 또는 대시보드 왼쪽(사양에 따라), 모드 NORMAL/SPORT/SMART. | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idd7ca4074254.html) |
| 아반떼 | `seat_vent_heat` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `seat_heat_passenger` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `heated_steering_wheel` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `parking_sensor` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `parking_view` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `passenger_grab_bar` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `fuel_door` | UNVERIFIED | PARTIAL | — | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: 실내 ‘연료 주입구 열림 레버’를 위로 당겨 연다(시동 OFF 후). 결빙 시 … | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/ideefb3973584.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `tailgate` | UNVERIFIED | PARTIAL | — | ✅ | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: 실내 ‘트렁크 열림 레버’ 존재, 외부 트렁크 스위치(스마트키)·리모컨 1초 이… | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/topic_dzq_kjn_kxb.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `esc_off` | UNVERIFIED | PARTIAL | — | ✅ | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: ESC OFF 버튼, 짧게=구동력 제어만 해제, 3초 이상=전체 해제. 눈·진흙… | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idde8129c14df.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `headlight_leveling` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 아반떼 | `hood_open` | UNVERIFIED | PARTIAL | — | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: 실내 ‘후드 열림 레버’(callout 13) 존재. 조작 본문은 로드 실패. | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `power_windows` | UNVERIFIED | PARTIAL | — | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: 1단 디텐트=수동, 2단 디텐트=자동 열림/닫힘. | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idbfa32f654a9.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `window_lock` | UNVERIFIED | PARTIAL | ✅ | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: 운전석 도어의 창문 잠금 버튼, 뒷좌석 창문 스위치 비활성. | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/id89527f8fde7.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `door_lock_unlock` | UNVERIFIED | PARTIAL | — | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: 중앙 도어 잠금 스위치(callout 3). | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `side_mirror_adjust` | UNVERIFIED | PARTIAL | — | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: L/R 레버 선택 후 조절 스위치. | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/id4ada6a5c0ea.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `side_mirror_fold` | UNVERIFIED | PARTIAL | — | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: 해외판은 손으로 미러 하우징을 접는 수동 접이 — 국내 전동 접이 버튼은 트림 … | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/id4ada6a5c0ea.html) |
| 아반떼 | `seat_adjustment` | UNVERIFIED | PARTIAL | — | — | 해외판(엘란트라 2026 en_US) 근거 — 국내 아반떼 사양과 다를 수 있음: 수동(방석 앞 슬라이드 레버, 등받이 레버, 높이 레버 반복) / 전동(방석 바… | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/id82434e76753.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 캐스퍼 | `gear_shift` | UNVERIFIED | PARTIAL | — | — | 변속 레버(p.6-10) — 국문 PDF 차량 내부 II(중앙 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `drive_traction_dial` | UNVERIFIED | PARTIAL | — | ✅ | drive_mode와 같은 조작부(p.6-23/6-24, 내부 II-14). | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `drive_mode` | UNVERIFIED | PARTIAL | — | ✅ | ‘드라이브 모드(p.6-23) / 험로 주행 모드(p.6-24)’가 같은 번호(14)로 표기 — 국문 PDF 차량 내부 II(중앙 그림) 목록에 포함. 하나의 조… | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `foot_parking_brake` | UNVERIFIED | PARTIAL | — | — | 차량 내부 I 목록에 ‘풋 브레이크(p.6-17)’ 표기, 내부 목록 전체에 전자식 파킹 브레이크(EPB) 항목 없음. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `electronic_parking_brake` | UNVERIFIED | PARTIAL | — | — | 내부 목록 전체에 EPB 항목 없음, ‘풋 브레이크(p.6-17)’만 표기. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `auto_hold` | UNVERIFIED | PARTIAL | — | — | 내부 목록(I·II·III) 어디에도 ‘자동 정차(Auto Hold)’ 항목 없음. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `wiper_stalk` | UNVERIFIED | PARTIAL | — | — | 와이퍼/와셔 스위치(p.5-47) — 국문 PDF 차량 내부 III(운전대·레버 그림) 목록에 포함. 외부 뒷유리창 와이퍼 존재. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `light_stalk` | UNVERIFIED | PARTIAL | — | — | 조명 스위치(p.5-38) — 국문 PDF 차량 내부 III(운전대·레버 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `smart_cruise` | UNVERIFIED | PARTIAL | — | — | 주행 보조 버튼(p.7-58) — 국문 PDF 차량 내부 III(운전대·레버 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `lane_follow_assist` | UNVERIFIED | PARTIAL | — | — | 차로 주행 보조 버튼(p.7-76) — 국문 PDF 차량 내부 III(운전대·레버 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `cruise_gap` | UNVERIFIED | PARTIAL | — | — | 차간거리 버튼(p.7-58) — 국문 PDF 차량 내부 III(운전대·레버 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `cruise_speed_toggle` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 캐스퍼 | `cluster_view_mode` | UNVERIFIED | PARTIAL | — | — | 계기판 메뉴 설정 버튼(p.4-17) — 국문 PDF 차량 내부 III(운전대·레버 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `voice_recognition` | UNVERIFIED | PARTIAL | — | — | 음성 인식 버튼(p.5-79) — 국문 PDF 차량 내부 III(운전대·레버 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `audio_volume` | UNVERIFIED | PARTIAL | — | — | 오디오 리모컨 스위치(p.5-76) — 국문 PDF 차량 내부 III(운전대·레버 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `audio_seek` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 캐스퍼 | `phone_call` | UNVERIFIED | PARTIAL | — | — | 블루투스 핸즈프리 버튼(p.5-78) — 국문 PDF 차량 내부 III(운전대·레버 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `custom_star` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 캐스퍼 | `hazard_light` | UNVERIFIED | PARTIAL | — | — | 비상 경고등 버튼(p.8-2) — 국문 PDF 차량 내부 II(중앙 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `engine_start_stop` | UNVERIFIED | PARTIAL | — | — | 시동 스위치(p.6-2)/시동 버튼(p.6-5) — 국문 PDF 차량 내부 II(중앙 그림) 목록에 포함. 사양에 따라 키 시동 스위치. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `climate_auto` | UNVERIFIED | PARTIAL | — | — | 히터 및 에어컨(p.5-49) — 국문 PDF 차량 내부 II(중앙 그림) 목록에 포함. 자동/수동 사양 구분 미확인. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `climate_off` | UNVERIFIED | PARTIAL | — | — | 히터 및 에어컨(p.5-49) — 국문 PDF 차량 내부 II(중앙 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `climate_temp_dial` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 캐스퍼 | `climate_fan_dial` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 캐스퍼 | `front_defog` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 캐스퍼 | `rear_defog` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 캐스퍼 | `ac_button` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 캐스퍼 | `air_recirculation` | UNVERIFIED | UNVERIFIED | — | — | 공식 근거 미확보 | — |
| 캐스퍼 | `seat_vent_heat` | UNVERIFIED | PARTIAL | — | ✅ | ‘열선 시트(p.3-13)’와 ‘통풍 시트(p.3-14, 운전석)’가 서로 다른 번호(15·16)로 표기 — 국문 PDF 차량 내부 II(중앙 그림) 목록에 포함… | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `seat_heat_passenger` | UNVERIFIED | PARTIAL | — | — | 열선 시트(p.3-13) — 국문 PDF 차량 내부 II(중앙 그림) 목록에 포함. 동승석 통풍은 목록에 운전석만 표기(통풍 시트 ‘운전석’ p.3-14). | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `heated_steering_wheel` | UNVERIFIED | PARTIAL | — | ✅ | 스티어링 휠 열선 버튼(p.5-19)이 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함(전조등 각도·ESC OFF·후드 레버와 같은 그룹). | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `fuel_door` | UNVERIFIED | PARTIAL | — | — | 실내 ‘연료 주입구 열림 레버(p.5-37)’ — 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함. 외부 연료 주입구 p.5-37. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `tailgate` | UNVERIFIED | PARTIAL | — | — | 외부 ‘테일게이트 열림 버튼(p.5-35)’ 존재(차량 외부 II). 실내 버튼은 내부 목록에 없음. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `esc_off` | UNVERIFIED | PARTIAL | — | — | 차체 자세 제어(ESC) OFF 버튼(p.6-19) — 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `headlight_leveling` | UNVERIFIED | PARTIAL | — | — | 전조등 각도 조절 스위치(p.5-41) — 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `hood_open` | UNVERIFIED | PARTIAL | — | — | 엔진 후드 열림 레버(p.5-34) — 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `power_windows` | UNVERIFIED | PARTIAL | — | — | 유리창 열림/닫힘 버튼(p.5-28) — 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `window_lock` | UNVERIFIED | PARTIAL | — | — | 유리창 열림/닫힘 잠금 버튼(p.5-29) — 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `door_lock_unlock` | UNVERIFIED | PARTIAL | — | — | 중앙 도어 잠금/잠금 해제 버튼(p.5-13) — 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `side_mirror_adjust` | UNVERIFIED | PARTIAL | — | — | 실외 미러 조절 스위치(p.5-21) — 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `side_mirror_fold` | UNVERIFIED | PARTIAL | — | — | 실외 미러 접이 버튼(p.5-22) — 국문 PDF 차량 내부 I(운전석 측 그림) 목록에 포함. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `seat_full_folding` | UNVERIFIED | PARTIAL | — | — | 앞좌석 그림(p.3-2)에 ‘등받이 각도 조절/좌석 접이 레버’ 표기, 접는 방법 p.3-10~3-11. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `seat_adjustment` | UNVERIFIED | VERIFIED | ✅ | ✅ | 국문 PDF p.3-5: 앞뒤 조절 레버는 방석 앞 아래, 등받이 각도 레버는 바깥쪽 옆면, 높이 레버(운전석)는 올리면 높아지고 누르면 낮아짐. 주행 중 조절 … | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |

## 7. 공식 출처 (2026-11-10 열람)

- [더 뉴 투싼(NX4) 2025 국문 취급설명서 – 차량 내부 목차](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/picture_index_interior_overview.html) — 차량 내부 그림 I·II·III
- [NX4 2025 국문 – 전자식 변속 다이얼 작동 및 N단 주차 방법](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id229G80JC0EV.html) — 변속 다이얼
- [NX4 2025 국문 – 전자식 파킹 브레이크(EPB)](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id76e01aea2d0.html) — EPB 작동/해제
- [NX4 2025 국문 – 자동 정차 기능(Auto Hold)](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id390901fe6c7.html) — 설정/해제
- [NX4 2025 국문 – 유리창 습기/성에 제거 방법](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/convenience_device_windshield_defrosting_and_defogging.html) — 앞유리 서리 제거
- [NX4 2025 국문 – 공조 장치 수동 조절](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id042e6950a36.html) — 온도·풍량·SYNC·OFF
- [NX4 2025 국문 – 차체 자세 제어(ESC)](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id0901bae25b0.html) — ESC OFF 1·2단계
- [NX4 2025 국문 – 스마트 크루즈 컨트롤 작동](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id222PF0ZL0HS.html) — 버튼 위치·속도/차간 설정
- [NX4 2025 국문 – 비상 경고등](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id71c0f13c980.html) — 비상 경고등
- [NX4 2025 국문 – 와이퍼/와셔](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id9f65f5f8048.html) — 앞·뒤 와이퍼
- [NX4 2025 국문 – 조명 스위치](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id23b13698f66.html) — 조명 스위치
- [NX4 2025 국문 – 전조등 각도 조절 장치](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id7f8417d45da.html) — 0~3 단계
- [NX4 2025 국문 – 후드](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/idb9f35a6ce00.html) — 후드 열림 레버
- [NX4 2025 국문 – 드라이브 모드](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id235NB0DL0NW.html) — DRIVE MODE 스위치
- [NX4 2025 국문 – 험로 주행 모드](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/ide307a3bb94e.html) — DRIVE/TERRAIN·MODE
- [NX4 2025 국문 – 패들 쉬프트](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id37bd7da1142.html) — 패들
- [NX4 2025 국문 – 경사로 저속 주행(DBC)](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id235A90Q01Y4.html) — DBC 버튼
- [NX4 2025 국문 – 주차/뷰 버튼(후방 모니터)](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id222SGG090HS.html) — 주차/뷰 버튼
- [NX4 2025 국문 – 주차 안전 버튼(주차 거리 경고)](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/id939bb43111e.html) — 짧게/길게 누르기
- [NX4 2025 국문 – 휴대폰 무선 충전](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/idfca5e6e5686.html) — 무선 충전 패드
- [NX4 2025 국문 – 파워 윈도우](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/convenience_device_power_windows.html) — 유리창 스위치·잠금
- [NX4 2025 국문 – 실외 미러](https://ownersmanual.hyundai.com/full_webhelp/NX4/2025/ko_KR/idce3017f48fe.html) — 조절·접힘
- [쏘나타 디 엣지(DN8) 2024 국문 취급설명서 – 차량 내부 목차](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) — 그림 I·II·III 번호
- [DN8 2024 국문 – 자동 변속기 작동(변속 다이얼)](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id229G80JC0EVa.html) — P 버튼·다이얼
- [DN8 2024 국문 – 전자식 파킹 브레이크(EPB)](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id76e01aea2d0.html) — EPB
- [DN8 2024 국문 – 자동 정차 기능(Auto Hold)](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id390901fe6c7.html) — Auto Hold
- [DN8 2024 국문 – 유리창 습기/성에 제거](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id71225b967b5.html) — 서리 제거
- [DN8 2024 국문 – 히터 및 에어컨(수동 조절식)](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id22CKHB0705Z.html) — 조작부 1~8
- [DN8 2024 국문 – 차체 자세 제어(ESC)](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id0901bae25b0.html) — ESC OFF
- [DN8 2024 국문 – 스마트 크루즈 컨트롤](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id222PF0ZL0HS.html) — SCC
- [DN8 2024 국문 – 트렁크](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id82fc3560510.html) — 실내 버튼·외부 스위치
- [DN8 2024 국문 – 패들 쉬프트](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id37bd7da1142a.html) — +/− 패들
- [DN8 2024 국문 – 드라이브 모드](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/id2581f1d0ebba.html) — DRIVE MODE
- [아이오닉 5(NE1) 2025 국문 – 변속 위치 선택하기](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/iddf661a37926.html) — 변속 다이얼·P 버튼
- [NE1 2025 국문 – 스마트 크루즈 컨트롤 작동](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/id222PF0ZL0HS.html) — SCC
- [NE1 2025 국문 – 히터와 에어컨(자동 조절식)](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/convenience_device_automatic_climate_control_system.html) — 조작부 1~13
- [NE1 2025 국문 – 시동이 걸리지 않는 경우(주차 절차 포함)](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/id23C2C0C10HS.html) — EPB 스위치를 위로 당김
- [IONIQ 5 (NE1a) 2025 en_US – Electronic Parking Brake](https://ownersmanual.hyundai.com/full_webhelp/NE1a/2025/en_US/iddde46120a36.html) — Applying/Releasing EPB
- [IONIQ 5 (NE1) 2024 en_US – How to Start the Vehicle](https://ownersmanual.hyundai.com/full_webhelp/NE1/2024/en_US/id6bdd2068f97.html) — Start/Stop, READY
- [ELANTRA (CN7) 2025 en_GN – Electronic Parking Brake](https://ownersmanual.hyundai.com/full_webhelp/CN7/2025/en_GN/idcf2d3edca52.html) — EPB (해외판)
- [ELANTRA (CN7) 2026 en_US – Electronic Parking Brake](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idb47e1bdf24c.html) — EPB (해외판)
- [ELANTRA (CN7) 2026 en_US – Intelligent Variable Transmission operation](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idd7ca4074254.html) — 변속 레버·Shift lock release·Drive mode (해외판)
- [아이오닉 5(NE1) 2023 국문 – 차량 내부(I·II·III)](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) — 차량 내부 I(운전석 측)·II(중앙)·III(운전대·레버)
- [아이오닉 5(NE1) 2024 국문 – 차량 내부](https://ownersmanual.hyundai.com/full_webhelp/NE1/2024/ko_KR/picture_index_interior_overview.html) — 2023과 동일 구성 확인
- [NE1 2023 국문 – 전자식 파킹 브레이크(EPB)](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/iddb9b77f2061.html) — 체결/해제
- [NE1 2023 국문 – 자동 정차 기능(Auto Hold)](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idcf466bc51d1.html) — 설정/해제
- [NE1 2023 국문 – 비상 경고등](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idd7334e78f90.html) — 비상 경고등
- [NE1 2023 국문 – 와이퍼 작동](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id66158b12c5c.html) — MIST/OFF/INT·AUTO/LO/HI
- [NE1 2023 국문 – 실외 조명 스위치](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idb55c2877eff.html) — OFF/AUTO/미등/전조등
- [NE1 2023 국문 – 감속기(전자식 변속 다이얼)](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idc1a9567d60e.html) — 변속 다이얼·P 버튼
- [NE1 2023 국문 – 차체 자세 제어(ESC)](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id8e27fb569fb.html) — 1·2단계
- [NE1 2023 국문 – 드라이브 모드](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id2581f1d0ebb.html) — NORMAL/ECO/SPORT, 길게 SNOW
- [NE1 2023 국문 – 회생 제동(패들 쉬프트)](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idb770623945d.html) — 단계 증감·i-PEDAL·스마트 회생
- [NE1 2023 국문 – 후드 여는 방법](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id7a487dade68.html) — 레버·보조 레버
- [NE1 2023 국문 – 파워 테일게이트](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id47e0561ff91.html) — 열림/닫힘 버튼
- [NE1 2023 국문 – 전자식 차일드 락](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id6021613a223.html) — 운전석 도어 버튼
- [NE1 2023 국문 – 유리창(열림/닫힘·잠금)](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id02a1cd26c9c.html) — 운전석 도어 잠금 버튼
- [NE1 2023 국문 – 실외 미러](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id754c73d5e23.html) — L/R 선택·접이 버튼
- [NE1 2023 국문 – 후방 모니터 작동](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id12c4b28d9f7.html) — 주차/뷰 버튼
- [NE1 2023 국문 – 후방 주차 거리 경고](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/ida383fc356cb.html) — 주차 안전 버튼
- [NE1 2023 국문 – 히터 및 에어컨](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idfc12f1bc613.html) — 앞·뒷유리 서리제거 09·10
- [NE1 2023 국문 – 좌석](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id81db5616e14.html) — 스마트 열선/통풍(인포테인먼트)
- [캐스퍼 국문 취급설명서 PDF (AX01)](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) — 차량 내부 I·II·III p.1-6~1-8, 차량 외부 p.1-2~1-5, 좌석 p.3-5~3-8
- [캐스퍼 국문 취급설명서 PDF (AX02)](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) — AX01과 내부 목차 동일
- [ELANTRA(CN7) 2026 en_US – Interior overview (해외판)](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) — callout 1~16
- [CN7 2026 en_US – Lighting control](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idabac8f7e8a8.html) — OFF/AUTO/Parking/Headlight
- [CN7 2026 en_US – Wipers and washers](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/id4c7ceb0a7cd.html) — MIST/OFF/INT/LO/HI, 당겨서 워셔
- [CN7 2026 en_US – ESC](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idde8129c14df.html) — 짧게/3초 이상
- [CN7 2026 en_US – Opening the fuel filler door](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/ideefb3973584.html) — release lever 위로 당김
- [CN7 2026 en_US – Trunk](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/topic_dzq_kjn_kxb.html) — trunk release lever·외부 스위치
- [CN7 2026 en_US – Power window lock button](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/id89527f8fde7.html) — 운전석 도어
- [CN7 2026 en_US – Windows](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idbfa32f654a9.html) — 1·2단 디텐트
- [CN7 2026 en_US – Side view mirrors](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/id4ada6a5c0ea.html) — L/R 레버, 수동 접이(해외판)
- [CN7 2026 en_US – Front seats](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/id82434e76753.html) — 수동/전동 시트 각각 기술

접근 실패: 아반떼(CN7)·캐스퍼(AX1) 국문 웹 매뉴얼 전 경로(client_error), `docview/download/pdf/.../OM_FULL`, 아이오닉5 2025 국문 EPB·ESC·비상등·와이퍼·조명 페이지. hyundai.com 본 사이트는 작업 환경에서 접속이 차단됐습니다.