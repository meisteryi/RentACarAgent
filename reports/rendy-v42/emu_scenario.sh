#!/usr/bin/env bash
# Text-injection scenario on the emulator (not microphone audio). Each step goes through
# CoachOverlayService ACTION_VOICE_QUERY -> VoiceAssistantEngine.processQuestion (same entry as an STT final result).
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
SVC="com.hyundai.rentalhelper/.service.CoachOverlayService"
q() { echo ">>> Q: $1"; "$ADB" shell "am start-foreground-service -n $SVC -a com.hyundai.rentalhelper.action.VOICE_QUERY --es query '$1'" > /dev/null; sleep "${2:-9}"; }
car() { echo ">>> CAR: $1"; "$ADB" shell "am broadcast -a com.hyundai.rentalhelper.SET_CAR_MODEL --es model $1" > /dev/null; sleep 2; }

"$ADB" logcat -c
car IONIQ5
q "앞유리가 뿌예"
q "주유구 어디 있어?"
q "창문 잠금 버튼 어디야"
q "찾았어"
car TUCSON
q "찾았어"                       # context must not cross cars -> ask for the feature first
q "주차 브레이크 어디 있어?"
q "못 찾겠어"
q "아직도 안 보여"
q "찾았어"
q "그다음은?"
q "됐어?"
q "깜빡이 켜줘"
q "달리면서 사이드미러 조절해도 돼?"
# Rapid second question: the first in-flight answer must be dropped, only the second spoken.
q "오토홀드 어디서 켜?" 0.3
q "ESC 버튼 어디야" 9
q "그만"
