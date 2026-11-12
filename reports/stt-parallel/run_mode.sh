#!/usr/bin/env bash
# Wake -> "네, 말씀하세요!" -> Pleos STT latency for one wake STT mode, same conditions for every mode.
# Usage: run_mode.sh <sequential|parallel|parallel_unguarded> [runs=12] [tag]
export MSYS_NO_PATHCONV=1
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
PKG=com.hyundai.rentalhelper
M="$1"; RUNS="${2:-12}"; HERE="$(cd "$(dirname "$0")" && pwd)"
NAME="$M${3:+_$3}"; mkdir -p "$HERE/$NAME"
"$ADB" shell setprop debug.rendy.wake.sttMode "$M"
"$ADB" shell getprop debug.rendy.wake.sttMode > "$HERE/$NAME/props.txt"
"$ADB" shell am force-stop $PKG; sleep 3
"$ADB" shell am start-foreground-service -n $PKG/.service.CoachOverlayService -a $PKG.action.START_COACHING >/dev/null
sleep 45
bash "$HERE/../stt-latency/measure_wake.sh" "../stt-parallel/$NAME" "$RUNS"
echo "done $NAME"
