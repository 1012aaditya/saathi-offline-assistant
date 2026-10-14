#!/usr/bin/env bash
# Record a demo scene: the tablet's screen (scrcpy, no sound: this Samsung blocks app-audio
# capture) + the room's sound from the Mac's microphone (your voice and Saathi's), then merge.
#   scripts/demo_record.sh start "Scene 2 - Shopping"
#   scripts/demo_record.sh stop  "Scene 2 - Shopping"
# Files go to ~/Desktop/Saathi-demo/. Background jobs ignore Ctrl+C, so stop ends the screen
# stream (scrcpy then finishes the .mp4 itself) and asks ffmpeg to finish the sound.
set -euo pipefail
DIR="$HOME/Desktop/Saathi-demo"
NAME="${2:?scene name}"
mkdir -p "$DIR"
cd "$DIR"
SERIAL="${ADB_SERIAL:-$(adb devices | awk -F'\t' 'NR>1 && $2=="device"{print $1; exit}')}"

case "$1" in
start)
  [ -n "$SERIAL" ] || { echo "No tablet connected."; exit 1; }
  rm -f "$NAME.video.mp4" "$NAME.audio.m4a"
  nohup scrcpy -s "$SERIAL" --record="$NAME.video.mp4" --no-playback --no-control --no-audio \
    --max-size=1280 --video-bit-rate=8M >"$NAME.scrcpy.log" 2>&1 &
  # Start the sound once the first video frame is written, so both begin together.
  for _ in $(seq 1 40); do [ -s "$NAME.video.mp4" ] && break; sleep 0.25; done
  nohup ffmpeg -hide_banner -loglevel error -y -f avfoundation -i ":0" -c:a aac -b:a 160k "$NAME.audio.m4a" \
    >"$NAME.ffmpeg.log" 2>&1 &
  sleep 1
  pgrep -f "record=$NAME.video.mp4" >/dev/null && pgrep -f "$NAME.audio.m4a" >/dev/null && echo "RECORDING: $NAME"
  ;;
stop)
  pkill -TERM -f "$NAME.audio.m4a" || true
  pkill -f "app_process / com.genymobile.scrcpy.Server" || true # stream ends -> scrcpy finalizes
  for _ in $(seq 1 40); do pgrep -f "record=$NAME.video.mp4" >/dev/null || break; sleep 0.25; done
  sleep 1
  # scrcpy writes frames only when the screen changes: hold each picture (constant 30 fps, last
  # frame kept) for as long as the sound runs.
  DUR="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$NAME.audio.m4a")"
  ffmpeg -hide_banner -loglevel error -y -i "$NAME.video.mp4" -i "$NAME.audio.m4a" -map 0:v -map 1:a \
    -vf "tpad=stop_mode=clone:stop_duration=600,fps=30" -c:v h264_videotoolbox -b:v 8M \
    -c:a aac -b:a 160k -t "$DUR" "$NAME.mp4"
  rm -f "$NAME.scrcpy.log" "$NAME.ffmpeg.log"
  printf "SAVED: %s.mp4 (%.0f s)\n" "$NAME" "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$NAME.mp4")"
  ;;
*) echo "usage: $0 start|stop <scene name>"; exit 2 ;;
esac
