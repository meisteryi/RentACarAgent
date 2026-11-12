#!/usr/bin/env bash
# Measures one wake-word model variant on the emulator under identical conditions.
# Usage: run_variant.sh <off|full|lite> [latency_runs=12]
# Steps: set debug property -> force-stop -> start service -> wait 45 s -> 3x meminfo (15 s apart)
#        -> Vosk timing log -> N wake->Pleos STT latency runs (measure_wake.sh) -> meminfo
#        -> on-device wake self-test (full/lite only; last because it allocates extra recognizers).
export MSYS_NO_PATHCONV=1
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
PKG=com.hyundai.rentalhelper
SVC="$PKG/.service.CoachOverlayService"
V="$1"; RUNS="${2:-12}"
HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/$V${3:+_$3}"; mkdir -p "$OUT"
case "$V" in
  off)  "$ADB" shell setprop debug.rendy.wakeword off;  "$ADB" shell setprop debug.rendy.wakeword.graph '""' ;;
  full) "$ADB" shell setprop debug.rendy.wakeword '""'; "$ADB" shell setprop debug.rendy.wakeword.graph full ;;
  lite) "$ADB" shell setprop debug.rendy.wakeword '""'; "$ADB" shell setprop debug.rendy.wakeword.graph lite ;;
esac
"$ADB" shell "getprop debug.rendy.wakeword; getprop debug.rendy.wakeword.graph" > "$OUT/props.txt"
"$ADB" shell am force-stop $PKG
sleep 3
"$ADB" logcat -c
"$ADB" shell am start-foreground-service -n $SVC -a $PKG.action.START_COACHING >/dev/null
sleep 45
for i in 1 2 3; do
  "$ADB" shell dumpsys meminfo $PKG > "$OUT/meminfo_$i.txt"
  sleep 15
done
PID=$("$ADB" shell pidof $PKG | tr -d '\r')
"$ADB" shell "cat /proc/$PID/status" | grep -E "VmRSS|VmSwap|RssAnon|RssFile" > "$OUT/proc_status.txt"
"$ADB" shell "run-as $PKG --user 10 grep -c 'vosk-model' /proc/$PID/maps" > "$OUT/maps_vosk_hits.txt" 2>&1
"$ADB" logcat -d | grep -E "RendyWakeWord|Vosk model" > "$OUT/startup_log.txt"
bash "$HERE/../stt-latency/measure_wake.sh" "../wakeword-memory/$(basename "$OUT")/latency" "$RUNS"
"$ADB" shell dumpsys meminfo $PKG > "$OUT/meminfo_after_latency.txt"
if [ "$V" != "off" ]; then
  "$ADB" logcat -c
  "$ADB" shell am start-foreground-service -n $SVC -a $PKG.action.WAKEWORD_SELFTEST >/dev/null
  for t in $(seq 1 120); do
    sleep 5
    if "$ADB" logcat -d | grep -q "Wake self-test done"; then break; fi
  done
  "$ADB" logcat -d | grep -E "RendyWakeSelfTest|AndroidRuntime" > "$OUT/selftest_log.txt"
  "$ADB" shell "run-as $PKG --user 10 cat files/wakeeval/result_$V.json" > "$OUT/selftest_result.json"
fi
echo "done $V"
