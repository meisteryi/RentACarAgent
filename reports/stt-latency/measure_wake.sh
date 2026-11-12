#!/usr/bin/env bash
# Repeats the wake flow N times via the debug-only SIMULATE_WAKE_WORD action and saves logcat per run.
# Usage: measure_wake.sh <label> <runs> [speak]   (speak=1 plays a Korean question through host speakers after the prompt)
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
SVC="com.hyundai.rentalhelper/.service.CoachOverlayService"
OUT="$(dirname "$0")/$1"; mkdir -p "$OUT"
for i in $(seq 1 "$2"); do
  "$ADB" logcat -c
  "$ADB" shell am start-foreground-service -n $SVC -a com.hyundai.rentalhelper.action.SIMULATE_WAKE_WORD >/dev/null
  if [ "$3" = "1" ]; then
    # wait for the prompt to finish (~2.2s) then speak immediately, like a driver who answers right away
    sleep 2.3
    powershell.exe -NoProfile -Command "Add-Type -AssemblyName System.Speech; \$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.SelectVoice('Microsoft Heami Desktop'); \$s.Speak('라이트 어디 있어')" >/dev/null 2>&1
    sleep 6
  else
    sleep 8
  fi
  "$ADB" logcat -d > "$OUT/run_$i.txt"
  # stop the session if still listening (toggle), then let the wake word resume
  if grep -q "state=LISTENING" "$OUT/run_$i.txt" && ! grep -qE "session=[0-9]+ finalized" "$OUT/run_$i.txt"; then
    "$ADB" shell am start-foreground-service -n $SVC -a com.hyundai.rentalhelper.action.TOGGLE_PLEOS_STT >/dev/null
  fi
  sleep 4
done
