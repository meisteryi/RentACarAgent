#!/usr/bin/env bash
# One isolated wake interaction per run: simulate wake, dump logcat after the capture window, stop STT if still
# listening, then wait until the wake interaction has fully ended (no overlap with the next run).
# Usage: measure_cycle.sh <mode> <runs> [tag]   (mode: sequential | parallel | parallel_unguarded | default = build default, no debug override)
export MSYS_NO_PATHCONV=1
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
PKG=com.hyundai.rentalhelper; SVC="$PKG/.service.CoachOverlayService"
M="$1"; RUNS="$2"; HERE="$(cd "$(dirname "$0")" && pwd)"; OUT="$HERE/${M}${3:+_$3}"; mkdir -p "$OUT"
if [ "$M" = "default" ]; then "$ADB" shell setprop debug.rendy.wake.sttMode "\"\""; else "$ADB" shell setprop debug.rendy.wake.sttMode "$M"; fi
"$ADB" shell getprop debug.rendy.wake.sttMode > "$OUT/props.txt"
"$ADB" shell am force-stop $PKG; sleep 3
"$ADB" shell am start-foreground-service -n $SVC -a $PKG.action.START_COACHING >/dev/null
sleep 45
for i in $(seq 1 "$RUNS"); do
  "$ADB" logcat -c
  "$ADB" shell am start-foreground-service -n $SVC -a $PKG.action.SIMULATE_WAKE_WORD >/dev/null
  sleep 9
  "$ADB" logcat -d > "$OUT/run_$i.txt"
  if grep -q "state=LISTENING" "$OUT/run_$i.txt" && ! grep -qE "session=[0-9]+ finalized" "$OUT/run_$i.txt"; then
    "$ADB" shell am start-foreground-service -n $SVC -a $PKG.action.TOGGLE_PLEOS_STT >/dev/null
  fi
  # wait (max 40 s) until the interaction is over and the wake word listens again
  for t in $(seq 1 40); do
    if "$ADB" logcat -d | grep -q "Wake-word microphone is capture-ready"; then break; fi
    sleep 1
  done
  "$ADB" logcat -d > "$OUT/run_${i}_tail.txt"
  sleep 2
done
echo "done $M"
