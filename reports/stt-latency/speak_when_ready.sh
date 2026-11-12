#!/usr/bin/env bash
# Starts STT (PTT toggle or simulated wake), waits until the app logs confirmed capture, then speaks a Korean
# question through the host speakers (emulator host-mic must be on: adb emu avd hostmicon). Saves logcat.
# Usage: speak_when_ready.sh <out.txt> <ptt|wake> [question]
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
SVC="com.hyundai.rentalhelper/.service.CoachOverlayService"
Q="${3:-라이트 어디 있어}"
"$ADB" logcat -c
if [ "$2" = "wake" ]; then A=com.hyundai.rentalhelper.action.SIMULATE_WAKE_WORD; else A=com.hyundai.rentalhelper.action.TOGGLE_PLEOS_STT; fi
"$ADB" shell am start-foreground-service -n $SVC -a $A >/dev/null
for k in $(seq 1 60); do "$ADB" logcat -d | grep -q "microphone capture confirmed" && break; sleep 0.2; done
powershell.exe -NoProfile -Command "Add-Type -AssemblyName System.Speech; \$s=New-Object System.Speech.Synthesis.SpeechSynthesizer; \$s.SelectVoice('Microsoft Heami Desktop'); \$s.Volume=100; \$s.Speak('$Q')" >/dev/null 2>&1
sleep 7
"$ADB" logcat -d > "$1"
