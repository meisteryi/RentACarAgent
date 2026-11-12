# RENDY 버튼 DB 변경 로그 — 1차(v4.1.0-enhanced) → 최종(v4.2.0-final)

## 요약

- 검증 상태가 바뀐 버튼 **70개**, 위치·조작 원문(`location`/`action_guide`)이 교정된 버튼 **17개**, 음성 문장(`spoken_answer`)을 다시 쓴 버튼 **237개 전부**입니다.
- 조작계 설명이 섞인 오류 **30건 → 0건**. 예: 아이오닉5 `gear_shift`에 ‘발로 밟는 페달’이 들어간 문제.
- ‘제가 가진 정보로는’ 반복 문구 **199회 → 0회**. 미검증 안전 항목에만 ‘차를 세운 뒤’ 문장을 붙였습니다.
- 2문장을 넘는 1차 답변 **2건 → 0건**, 단어 반복(‘천장 천장’ 등) **6건 → 0건**
- QA 테스트 케이스: 58개 → **133개**
- 원본 필드(name·zone·location·action_guide), 버튼 ID 237개, FAQ 33개는 모두 보존했습니다. 원문을 고친 곳은 `verification.original_*`에 원문을 남겼습니다.

## 1. 검증 상태 변화

| 1차 → 최종 | 건수 |
|---|---|
| UNVERIFIED → PARTIAL | 69 |
| UNVERIFIED → VERIFIED | 1 |

## 2. 위치·조작 원문 교정 (근거 포함)

| 차종 | 버튼 | 수정 전 | 수정 후 | 근거 |
|---|---|---|---|---|
| 아이오닉 5 | `gear_shift` | 브레이크 페달을 밟은 상태에서 운전대 오른쪽 뒤 레버 끝 다이얼을 위쪽으로 돌리면 D, 아래쪽으로 돌리면 R입니다. P는 레버 끝 P 버튼을 누릅니다. | 브레이크 페달을 밟은 상태에서 운전대 오른쪽 뒤 레버 끝 다이얼을 위쪽으로 돌리면 D, 아래쪽으로 돌리면 R입니다. P는 레버 끝의 P 버튼을 누릅니다. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idc1a9567d60e.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/iddf661a37926.html) [3](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `seat_vent_heat` | 공조 터치패널 'WARMER' 터치 후 시트 화면 또는 센터콘솔 버튼 / 공조 패널의 'WARMER' 버튼을 터치하거나 디스플레이 시트 메뉴에서 열선 3단, 통풍 3단으로 터치 조절합니다. | 공조 화면 또는 가운데 콘솔(연식·사양별 확인 필요) / 시트 열선·통풍은 사양에 따라 공조 화면이나 가운데 콘솔 버튼으로 켭니다. 정확한 위치는 아직 확인되지 않았습니다. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id81db5616e14.html) |
| 아이오닉 5 | `drive_mode` | 핸들 8시 방향에 튀어나온 둥근 원형 버튼을 누르면 ECO, NORMAL, SPORT 모드로 전환됩니다. 길게 누르면 눈길 SNOW 모드가 켜집니다. | 운전대의 드라이브 모드 버튼을 누를 때마다 NORMAL, ECO, SPORT로 바뀌고, 길게 누르면 눈길용 SNOW 모드가 켜집니다. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id2581f1d0ebb.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `electronic_parking_brake` | 브레이크를 밟은 채 스위치를 위로 당기면 주차 브레이크가 걸리고, 시동이 켜진 상태에서 브레이크를 밟고 스위치를 누르면 풀립니다. | 브레이크를 밟은 채 스위치를 위로 당기면 주차 브레이크가 걸리고, 시동이 켜진 상태에서 브레이크를 밟고 누르면 풀립니다. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/iddb9b77f2061.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) [3](https://ownersmanual.hyundai.com/full_webhelp/NE1/2025/ko_KR/id23C2C0C10HS.html) |
| 아이오닉 5 | `auto_hold` | 운전석 무릎 앞 'AUTO HOLD' 버튼을 누르면 계기판에 흰색 표시등이 켜지며, 주행 중 정차 시 브레이크에서 발을 떼도 차가 멈춰있습니다. | 브레이크를 밟은 상태에서 AUTO HOLD 버튼을 누르면 계기판에 흰색 표시가 켜지고, 이후 브레이크로 멈추면 초록색으로 바뀌며 발을 떼도 서 있습니다. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/idcf466bc51d1.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `esc_off` | 눈길이나 진흙탕에 빠져 바퀴가 헛돌 때 짧게 누르면 구동력 제어가 꺼지며 탈출할 수 있습니다. 길게 누르면 차체자세제어가 완전 해제됩니다. | 바퀴가 헛돌 때만 짧게 한 번 누르면 구동력 제어가 꺼집니다. 3초 이상 누르면 전체가 꺼지니 일반 주행에선 누르지 마세요. 다시 짧게 누르면 켜집니다. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id8e27fb569fb.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `hood_open` | 운전석 왼쪽 발 앞쪽 벽 하단에 있는 자동차 보닛 열림 레버를 몸쪽으로 딸깍 당긴 후 전방 프렁크를 열 수 있습니다. | 운전석 왼쪽 아래 보닛 레버를 당기면 보닛이 살짝 뜹니다. 차 앞으로 가서 보닛 틈 가운데 안쪽 보조 레버를 왼쪽으로 밀면서 들어 올리세요. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id7a487dade68.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 아이오닉 5 | `window_lock` | 불이 켜지면 뒷좌석 창문 조작이 차단되고 뒷좌석 문이 안쪽에서 열리지 않는 차일드락이 함께 작동합니다. | 운전석 문 팔걸이의 창문 잠금 버튼을 누르면 뒷좌석 창문 스위치가 작동하지 않습니다. 뒷문이 안에서 열리지 않게 하려면 같은 곳의 전자식 차일드 락 버튼을 누르세요. | [1](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id02a1cd26c9c.html) [2](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/id6021613a223.html) [3](https://ownersmanual.hyundai.com/full_webhelp/NE1/2023/ko_KR/picture_index_interior_overview.html) |
| 쏘나타 | `window_lock` | 버튼을 켜두면 뒷좌석 창문이 잠기고 뒷좌석 문이 안에서 열리지 않습니다. | 운전석 문 팔걸이의 창문 잠금 버튼을 누르면 뒷좌석 창문 스위치가 작동하지 않습니다. 뒷문이 안에서 열리지 않게 하는 전자식 차일드 락도 같은 곳에 있습니다. | [1](https://ownersmanual.hyundai.com/full_webhelp/DN8/2024/ko_KR/picture_index_interior_overview.html) |
| 아반떼 | `tailgate` | 운전석 시트 왼쪽 바닥 레버 또는 크래시패드 버튼 / 운전석 시트 왼쪽 바닥의 트렁크 그림 레버를 위로 당기거나, 크래시패드에 트렁크 버튼이 있는 경우 1초간 누르면 트렁크가 열립니다. | 운전석 의자 왼쪽 바닥의 트렁크 그림 레버 (해외판 근거, 국내 트림 확인 필요) / 운전석 의자 왼쪽 바닥의 트렁크 그림 레버를 위로 당기면 트렁크가 열립니다. 밖에서는 스마트키를 가진 채 트렁크의 열림 스위치를 누르세요. | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/topic_dzq_kjn_kxb.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 아반떼 | `esc_off` | 진흙탕이나 눈길 탈출 시 눌러 구동력 제어를 끕니다. | 바퀴가 헛돌 때만 짧게 한 번 누르면 구동력 제어가 꺼집니다. 3초 이상 누르면 전체가 꺼지니 일반 주행에선 누르지 마세요. | [1](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/idde8129c14df.html) [2](https://ownersmanual.hyundai.com/full_webhelp/cn7/2026/en_us/picture_index_interior_overview.html) |
| 캐스퍼 | `drive_traction_dial` | 다이얼 중앙을 꾹 누르면 DRIVE 모드(SPORT, NORMAL)와 TRACTION 험로 모드(SNOW 눈길, MUD 진흙, SAND 모래길)로 전환되며, 다이얼을 돌려 원하는 주행 모드를 선택합… | 기어봉 오른쪽 둥근 다이얼로 일반 주행 모드와 눈길·진흙·모래 험로 모드를 고릅니다. 누르기와 돌리기 순서는 아직 확인되지 않았어요. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `drive_mode` | 다이얼을 돌려 NORMAL, SPORT 주행 모드를 선택하고, 다이얼을 누르면 눈길 탈출용 SNOW/MUD 험로 모드로 바뀝니다. | 기어봉 오른쪽 둥근 다이얼로 일반 주행 모드와 눈길·진흙·모래 험로 모드를 고릅니다. 누르기와 돌리기 순서는 아직 확인되지 않았어요. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `auto_hold` | 캐스퍼는 기계식 풋 파킹 브레이크 차량으로 오토홀드 기능이 지원되지 않습니다. 신호 대기 시 브레이크 페달을 직접 밟고 계셔야 합니다. | 캐스퍼 공식 설명서의 실내 조작부 목록에는 오토홀드 항목이 없습니다. 신호 대기 중에는 브레이크 페달을 밟고 계세요. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `seat_vent_heat` | 위로 누르면 주황색 2단 열선 시트, 아래로 누르면 파란색 2단 통풍 시트가 켜집니다. | 운전석 열선 시트 버튼과 통풍 시트 버튼을 각각 누릅니다. 누를 때마다 단계가 바뀌는지는 아직 확인되지 않았어요. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `heated_steering_wheel` | 공조 패널 하단 스티어링 휠 열선 아이콘 / 버튼을 누르면 핸들이 따뜻해집니다. | 운전석 쪽 조작부(공식 목차상 운전석 측 그림에 분류) — 정확한 위치 확인 필요 / 핸들 열선 버튼을 누르면 운전대가 따뜻해집니다. | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |
| 캐스퍼 | `seat_adjustment` | 운전석 시트 왼쪽 바깥 펌핑 레버 및 하단 슬라이딩 바 / 시트 왼쪽 바깥 레버를 펌핑하여 방석 높이를 맞추고, 시트 앞 바닥 철제 바를 당겨 앞뒤로 이동합니다. | 운전석 의자 방석 앞 아래(앞뒤 레버)와 의자 바깥쪽 옆면(등받이·높이 레버) / 방석 앞 아래 레버를 당긴 채 의자를 앞뒤로 움직입니다. 의자 바깥 옆면의 등받이 레버로 각도를 맞추고, 높이 레버는 위로 올리면 높아지고 아래로 누르… | [1](https://casper.hyundai.com/wcontents/repn-car/catalog/AX01/AX_CASPER_manual.pdf) [2](https://casper.hyundai.com/wcontents/repn-car/catalog/AX02/AX_CASPER_manual.pdf) |

## 3. 조작계 혼입 오류 수정 (전수 탐지)

1차 생성기는 `action_guide`에 들어 있는 단어(예: ‘브레이크 **페달**을 밟고’)를 보고 조작계 유형을 추정했습니다. 그래서 다이얼 변속기에 ‘발로 밟는 페달’이 붙는 식의 오류가 생겼습니다. 최종본은 버튼마다 `control_type`을 명시하고, 유형 설명은 그 값에서만 가져오도록 바꿨습니다.

| 1차 오류(버튼: 잘못 붙은 설명 ↔ 실제 유형) |
|---|
| IONIQ5.gear_shift: '발로 밟는 페달' ↔ control_type=column_dial |
| IONIQ5.temp_toggle_driver: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=toggle |
| IONIQ5.temp_toggle_passenger: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=toggle |
| IONIQ5.air_recirculation: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=button |
| IONIQ5.seat_vent_heat: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=info |
| IONIQ5.power_windows: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=switch |
| IONIQ5.side_mirror_adjust: '손으로 잡고 움직이는 레버' ↔ control_type=pad |
| IONIQ5.engine_start_stop: '발로 밟는 페달' ↔ control_type=button |
| TUCSON.engine_start_stop: '발로 밟는 페달' ↔ control_type=button |
| TUCSON.heated_steering_wheel: '위아래로 밀거나 당기는 스위치' ↔ control_type=button |
| TUCSON.downhill_brake: '발로 밟는 페달' ↔ control_type=button |
| TUCSON.hood_open: '발로 밟는 페달' ↔ control_type=lever |
| TUCSON.fuel_door: '손으로 잡고 움직이는 레버' ↔ control_type=exterior |
| TUCSON.power_windows: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=switch |
| SONATA.gear_shift: '발로 밟는 페달' ↔ control_type=column_dial |
| SONATA.audio_seek: '누르는 물리 버튼이에요' ↔ control_type=steering_toggle |
| SONATA.engine_start_stop: '발로 밟는 페달' ↔ control_type=button |
| SONATA.temp_dial_driver: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=dial |
| SONATA.temp_dial_passenger: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=dial |
| SONATA.auto_hold: '발로 밟는 페달' ↔ control_type=button |
| SONATA.fuel_door: '손으로 잡고 움직이는 레버' ↔ control_type=exterior |
| SONATA.trunk_exterior_hidden: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=exterior |
| SONATA.power_windows: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=switch |
| AVANTE.gear_shift: '누르는 물리 버튼이에요' ↔ control_type=gear_lever |
| AVANTE.engine_start_stop: '발로 밟는 페달' ↔ control_type=button |
| AVANTE.climate_auto: '돌리는 다이얼이에요' ↔ control_type=button |
| AVANTE.power_windows: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=switch |
| CASPER.engine_start_stop: '발로 밟는 페달' ↔ control_type=button |
| CASPER.climate_auto: '돌리는 다이얼이에요' ↔ control_type=button |
| CASPER.tailgate: '화면을 손가락으로 누르는 터치 버튼' ↔ control_type=exterior |

## 4. 음성 문장 개선 예시 (1차 → 최종)

**아이오닉 5 · `gear_shift`** (PARTIAL → PARTIAL)

- 1차 답변: “운전대 오른쪽 뒤편 와이퍼 레버 아래 5시 방향에 있어요. 브레이크 페달을 밟은 상태에서 운전대 오른쪽 뒤 레버 끝 다이얼을 위쪽으로 돌리면 D, 아래쪽으로 돌리면 R입니다.”
- 최종 답변: “기어는 운전대 오른쪽 뒤에 달린 레버 끝을 돌려서 바꿔요. 브레이크를 밟은 채 위로 돌리면 D, 아래로 돌리면 R이에요.”
- 1차 상세: “운전대 바로 뒤쪽에 달린 레버 쪽입니다. 운전대 오른쪽 뒤편 와이퍼 레버 아래 5시 방향. 발로 밟는 페달이에요. 찾을 단서: ‘P’ 표시, 둥근 다이얼.”
- 최종 상세: “운전대 바로 뒤쪽을 보세요. 운전대 오른쪽 뒤 와이퍼 레버 아래. 운전대 오른쪽 뒤 레버이고, 끝부분을 돌리는 방식이에요. 끝면에 P 버튼이 있어요. 사양에 따라 위치가 조금 다를 수 있어요.”

**투싼 · `smart_cruise`** (CONFLICT → CONFLICT)

- 1차 답변: “투싼은 운전대 왼쪽 버튼 중 ‘주행 보조’ 버튼으로 켜요. 원하는 속도에서 한 번 누르세요.”
- 최종 답변: “투싼은 운전대 왼쪽의 ‘주행 보조’ 버튼으로 스마트 크루즈를 켜요. 원하는 속도에서 한 번 누르세요.”
- 1차 상세: “운전대를 잡은 상태에서 왼손 엄지 근처, 왼쪽 버튼 영역이에요. 같은 쪽에 차간거리 버튼이 있고, 속도를 바꾸는 +/− 스위치는 오른쪽에 있어요.”
- 최종 상세: “운전대 왼쪽 버튼 영역의 ‘주행 보조’ 버튼 (속도 조절 +/− 스위치는 오른쪽). 운전대를 잡은 채 엄지로 누르는 버튼이에요.”

**투싼 · `hazard_light`** (PARTIAL → PARTIAL)

- 1차 답변: “운전대 오른쪽, 가운데 화면 아래 한가운데를 보세요. 빨간 삼각형 버튼이 비상등이에요. 한 번 누르시면 돼요.”
- 최종 답변: “비상등은 가운데 대시보드 한가운데의 빨간 삼각형 버튼이에요. 한 번 누르세요.”
- 1차 상세: “가운데 대시보드 정중앙, 좌우 송풍구 사이 줄에 있는 빨간 삼각형 버튼이에요. 시동이 꺼져 있어도 작동해요.”
- 최종 상세: “운전대 오른쪽, 가운데 화면 아래 조작부 쪽을 보세요. 중앙 송풍구와 공조 화면 사이 한가운데. 손가락으로 누르는 물리 버튼이에요.”

**쏘나타 · `window_lock`** (PARTIAL → PARTIAL)

- 1차 답변: “운전석 도어 창문 스위치 뒤쪽 X표시 아이콘에 있어요. 버튼을 켜두면 뒷좌석 창문이 잠기고 뒷좌석 문이 안에서 열리지 않습니다.”
- 최종 답변: “창문 잠금 버튼은 운전석 문 팔걸이에 있어요. 누르면 뒷좌석 창문 스위치가 잠겨요.”
- 1차 상세: “운전석 문 안쪽 팔걸이 쪽입니다. 운전석 도어 창문 스위치 뒤쪽 X표시 아이콘. 누르는 물리 버튼이에요. 찾을 단서: X 표시.”
- 최종 상세: “운전석 문 안쪽 팔걸이 쪽을 보세요. 운전석 도어 창문 스위치 뒤쪽 X표시 아이콘. 손가락으로 누르는 물리 버튼이에요. 찾을 단서: X 표시. 사양에 따라 위치가 조금 다를 수 있어요.”

**아반떼 · `tailgate`** (UNVERIFIED → PARTIAL)

- 1차 답변: “제가 가진 정보로는 운전석 시트 왼쪽 바닥 레버 또는 대시보드 버튼에 있어요. 차량 사양에 따라 다를 수 있으니 한번 확인해 보세요.”
- 최종 답변: “트렁크는 운전석 의자 왼쪽 바닥의 트렁크 그림 레버로 열어요. 차가 멈춘 상태에서 위로 당기세요.”
- 1차 상세: “운전석 의자 왼쪽 바닥 쪽입니다. 운전석 시트 왼쪽 바닥 레버 또는 대시보드 버튼. 손으로 잡고 움직이는 레버예요. 찾을 단서: 트렁크 모양 아이콘.”
- 최종 상세: “운전석 의자 왼쪽 바닥의 트렁크 그림 레버 (해외판 근거, 국내 트림 확인 필요). 운전석 의자 왼쪽 바닥 쪽에 있는, 위로 당기는 작은 레버예요. 찾을 단서: 트렁크 모양 아이콘. 사양에 따라 위치가 조금 다를 수 있어요.”

**아반떼 · `fuel_door`** (UNVERIFIED → PARTIAL)

- 1차 답변: “제가 가진 정보로는 운전석 시트 왼쪽 바닥 끝 주유기 그림 레버에 있어요. 차량 사양에 따라 다를 수 있으니 한번 확인해 보세요.”
- 최종 답변: “아반떼 주유구는 운전석 의자 왼쪽 바닥의 주유기 그림 레버로 열어요. 시동을 끈 뒤 레버를 위로 당기세요.”
- 1차 상세: “운전석 의자 왼쪽 바닥 쪽입니다. 운전석 시트 왼쪽 바닥 끝 주유기 그림 레버. 손으로 잡고 움직이는 레버예요. 찾을 단서: 주유기 그림, 은색 부품.”
- 최종 상세: “운전석 의자 왼쪽 바닥 쪽을 보세요. 운전석 시트 왼쪽 바닥 끝 주유기 그림 레버. 운전석 의자 왼쪽 바닥 쪽에 있는, 위로 당기는 작은 레버예요. 찾을 단서: 주유기 그림. 사양에 따라 위치가 조금 다를 수 있어요.”

**캐스퍼 · `drive_mode`** (UNVERIFIED → PARTIAL)

- 1차 답변: “제가 가진 정보로는 기어봉 바로 오른쪽 원형 금속 다이얼에 있어요. 차량 사양에 따라 다를 수 있으니 한번 확인해 보세요.”
- 최종 답변: “주행 모드는 기어봉 바로 오른쪽 둥근 다이얼로 골라요. 눈길·진흙·모래 같은 험로 모드도 같은 다이얼이에요.”
- 1차 상세: “운전대 오른쪽, 가운데 화면 아래 조작부 쪽입니다. 기어봉 바로 오른쪽 원형 금속 다이얼. 돌리는 다이얼이에요. 찾을 단서: 둥근 다이얼.”
- 최종 상세: “운전대 오른쪽, 가운데 화면 아래 조작부 쪽을 보세요. 기어봉 바로 오른쪽 원형 금속 다이얼. 손으로 돌리는 둥근 다이얼이에요. 찾을 단서: 둥근 다이얼. 사양에 따라 위치가 조금 다를 수 있어요.”

**캐스퍼 · `auto_hold`** (UNVERIFIED → PARTIAL)

- 1차 답변: “캐스퍼의 오토홀드 탑재 여부는 아직 확인된 자료가 없어요. 신호 대기 중에는 브레이크 페달을 밟고 계세요.”
- 최종 답변: “캐스퍼 공식 설명서의 조작부 목록에는 오토홀드가 없어서, 없는 것으로 보여요. 신호 대기 중에는 브레이크 페달을 계속 밟아 주세요.”
- 1차 상세: “확인된 위치 정보가 없어요. 렌터카 업체나 차량 내 취급설명서로 확인해 주세요. 찾을 단서: ‘AUTO HOLD’ 글씨.”
- 최종 상세: “공식 설명서의 실내 조작부 목록(차량 내부 I·II·III)에 오토홀드 버튼이 없어요. 신호 대기 중에는 브레이크를 밟고 있어야 해요.”

**캐스퍼 · `heated_steering_wheel`** (UNVERIFIED → PARTIAL)

- 1차 답변: “제가 가진 정보로는 에어컨·히터 조작부 하단 운전대 열선 아이콘에 있어요. 차량 사양에 따라 다를 수 있으니 한번 확인해 보세요.”
- 최종 답변: “핸들 열선 버튼은 운전석 쪽 조작부에 있어요. 운전대 왼쪽 아래 버튼 모음과 에어컨 조작부를 함께 확인해 주세요.”
- 1차 상세: “운전대 오른쪽, 가운데 화면 아래 조작부 쪽입니다. 에어컨·히터 조작부 하단 운전대 열선 아이콘. 누르는 물리 버튼이에요. 찾을 단서: 운전대 모양 아이콘.”
- 최종 상세: “운전석 쪽 조작부(공식 목차상 운전석 측 그림에 분류) — 정확한 위치 확인 필요. 손가락으로 누르는 물리 버튼이에요. 사양에 따라 위치가 조금 다를 수 있어요.”

**아이오닉 5 · `regen_paddle_left`** (UNVERIFIED → PARTIAL)

- 1차 답변: “제가 가진 정보로는 운전대 뒤편 왼쪽 마이너스(-) 패들쉬프트에 있어요. 차량 사양에 따라 다를 수 있으니 한번 확인해 보세요.”
- 최종 답변: “회생제동은 운전대 뒤쪽 패들로 조절해요. 왼쪽 패들을 당길 때마다 감속이 세지고, 당기고 있으면 멈출 때까지 감속해요.”
- 1차 상세: “운전대(핸들) 앞면 버튼 쪽입니다. 운전대 뒤편 왼쪽 마이너스(-) 패들쉬프트. 운전대 뒤쪽에 손가락으로 당기는 패들이에요. 찾을 단서: +/− 표시 패들.”
- 최종 상세: “운전대 뒤쪽을 보세요. 운전대 뒤 왼쪽 마이너스(-) 패들쉬프트. 운전대 뒤쪽에 붙은, 손가락으로 당기는 패들이에요. 찾을 단서: +/− 표시 패들. 사양에 따라 위치가 조금 다를 수 있어요.”

**아반떼 · `smart_cruise`** (UNVERIFIED → UNVERIFIED)

- 1차 답변: “제가 가진 정보로는 운전대 오른쪽 버튼 영역 속도계 모양 아이콘에 있어요. 차량 사양에 따라 다를 수 있으니 한번 확인해 보세요.”
- 최종 답변: “스마트 크루즈는 운전대 오른쪽 버튼 쪽에 있는 것으로 안내돼 있어요. 정확한 조작법은 확인 중이니, 차를 세운 뒤 계기판 표시를 보며 조작해 주세요.”
- 1차 상세: “운전대(핸들) 앞면 버튼 쪽입니다. 운전대 오른쪽 버튼 영역 속도계 모양 아이콘. 누르는 물리 버튼이에요. 찾을 단서: 속도계 모양 아이콘.”
- 최종 상세: “운전대 앞면 쪽을 보세요. 운전대 오른쪽 버튼 영역 속도계 모양 아이콘. 운전대를 잡은 채 엄지로 누르는 버튼이에요. 찾을 단서: 속도계 모양 아이콘. 사양에 따라 위치가 조금 다를 수 있어요.”

**쏘나타 · `fuel_door`** (UNVERIFIED → UNVERIFIED)

- 1차 답변: “제가 가진 정보로는 차량 우측 뒤편 주유구 덮개 (실내 레버 없음)에 있어요. 차량 사양에 따라 다를 수 있으니 한번 확인해 보세요.”
- 최종 답변: “쏘나타는 실내에 주유구 버튼이 없고, 차 오른쪽 뒤 주유구 덮개를 눌러서 열어요. 문 잠금이 풀린 상태에서 덮개 뒤쪽을 가볍게 누르세요.”
- 1차 상세: “차 밖 쪽입니다. 차량 우측 뒤편 주유구 덮개 (실내 레버 없음). 손으로 잡고 움직이는 레버예요.”
- 최종 상세: “차 밖 쪽을 보세요. 차량 오른쪽 뒤 주유구 덮개 (실내 레버 없음). 차 밖에서 조작하는 부분이에요. 사양에 따라 위치가 조금 다를 수 있어요.”

## 5. 스키마·대화 설계 추가

| 필드 | 위치 | 용도 |
|---|---|---|
| `control_type` | 버튼 | 조작계 유형(button/touch/stalk/column_dial/gear_lever/pedal/paddle/dial/toggle/…) — 유형 설명 혼입 방지 |
| `requires_parked` | 버튼 | 주행 중엔 위치만 안내하고 조작 단계는 정차 후로 미룰 기능 |
| `duplicate_of` | 버튼 | 같은 조작부를 가리키는 중복 항목 연결 |
| `verification.location_verified` / `operation_verified` | 버튼 | 위치·조작 독립 검증 결과 |
| `verification.needed_evidence` | 버튼 | 미검증 항목에 필요한 자료 |
| `follow_up.on_done`, `max_steps_per_turn` | 버튼 | 결과 확인 단계, 한 턴 1단계 원칙 |
| `intent_enhancements.model_routing` | 루트 | FAQ 33개 × 5개 차종의 button / redirect / fallback / not_applicable / no_data 라우팅 |
| `intent_enhancements.dialogue_rules` | 루트 | 못 찾겠어(2단계)·찾았어·그다음·됐어·취소 처리 규칙 |
| `clarification_rules` | 루트 | 12개 → 20개 (‘차가 안 움직여’, ‘따뜻하게’, ‘불 켜’ 등 추가) |

## 6. 개별 데이터 수정 메모

- 아이오닉5 `window_lock`·쏘나타 `window_lock`: ‘창문 잠금 + 차일드락 통합 버튼’이라고 단정하던 문구를 두 기능으로 나눠 안내하게 바꿨습니다. 공식 목차는 두 기능을 같은 번호로 표기만 할 뿐입니다.
- 아이오닉5 `drive_mode`: 순서를 공식대로 NORMAL → ECO → SPORT로 고치고 길게 누르면 SNOW임을 반영했습니다(2023 국문).
- 아이오닉5 `hood_open`: 공식 페이지에 없는 ‘프렁크’ 문구를 빼고, 보조 레버 단계를 넣었습니다.
- 캐스퍼 `drive_mode`·`drive_traction_dial`: 공식 목록상 같은 조작부(II-14)입니다. 서로 다른 조작 순서로 설명하던 모순을 없애고, 누르기·돌리기 순서는 미확인으로 표기했습니다.
- 캐스퍼 `seat_vent_heat`: 공식 목록상 열선 시트(15)와 통풍 시트(16)가 별도 항목이라 ‘한 버튼 위/아래’ 단정을 지웠습니다.
- 캐스퍼 `heated_steering_wheel`: 공식 그림 분류가 운전석 측(I)이라 ‘공조 패널 하단’ 단정을 지우고 두 곳을 확인하도록 안내합니다.
- 캐스퍼 `seat_adjustment`: 국문 PDF p.3-5 근거로 앞뒤·등받이·높이 레버를 공식 설명대로 고치고 VERIFIED로 올렸습니다.
- 아반떼 `tailgate`: ‘레버 또는 버튼’ 혼재를 해외판 근거의 바닥 레버로 정리하고, 국내 트림은 확인 필요로 표기했습니다.
- 아반떼 `side_mirror_fold`·`seat_adjustment`: 해외판은 손으로 접는 미러, 수동·전동 시트가 모두 있어 트림 분기를 넣었습니다.
- 전 차종 `operation_steps`: 1차에서 ‘누를 때마다’처럼 문장이 잘리던 분할 버그를 고쳤습니다(문장 끝 ‘다’에서 잘리던 정규식).