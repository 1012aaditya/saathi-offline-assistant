#!/usr/bin/env bash
# Record a demo scene: the tablet's screen (scrcpy, no sound: this Samsung blocks app-audio
# capture) + the Mac's camera and microphone (you using the tablet; your voice and Saathi's).
# Makes "<scene>.mp4" (camera left, tablet screen right) and "<scene> (screen).mp4".
#   scripts/demo_record.sh start "Scene 2 - Shopping"
#   scripts/demo_record.sh stop  "Scene 2 - Shopping"
# Files go to ~/Desktop/Saathi-demo/. Background jobs ignore Ctrl+C, so stop ends the screen
# stream (scrcpy then finishes the .mp4 itself) and asks ffmpeg to finish the sound.
set -euo pipefail
DIR="$HOME/Desktop/Saathi-demo"
NAME="${2:?scene name}"
mkdir -p "$DIR"
cd "$DIR"
# pgrep / pkill take a regex: escape the scene name ("Scene 1 (take 3)" has brackets).
RE="$(printf '%s' "$NAME" | sed 's/[][\.*^$()+?{}|]/\\&/g')"
SERIAL="${ADB_SERIAL:-$(adb devices | awk -F'\t' 'NR>1 && $2=="device"{print $1; exit}')}"

case "$1" in
start)
  [ -n "$SERIAL" ] || { echo "No tablet connected."; exit 1; }
  rm -f "$NAME.video.mp4" "$NAME.camera.mp4"
  nohup scrcpy -s "$SERIAL" --record="$NAME.video.mp4" --no-playback --no-control --no-audio \
    --max-size=1280 --video-bit-rate=8M >"$NAME.scrcpy.log" 2>&1 &
  # Start the sound once the first video frame is written, so both begin together.
  for _ in $(seq 1 40); do [ -s "$NAME.video.mp4" ] && break; sleep 0.25; done
  nohup ffmpeg -hide_banner -loglevel error -y -f avfoundation -framerate 30 -video_size 1280x720 -i "0:0" \
    -c:v h264_videotoolbox -b:v 6M -c:a aac -b:a 160k "$NAME.camera.mp4" >"$NAME.ffmpeg.log" 2>&1 &
  sleep 1.5
  pgrep -f "record=$RE\.video\.mp4" >/dev/null && pgrep -f "$RE\.camera\.mp4" >/dev/null && echo "RECORDING: $NAME"
  ;;
stop)
  pkill -TERM -f "$RE\.camera\.mp4" || true
  pkill -f "app_process / com.genymobile.scrcpy.Server" || true # stream ends -> scrcpy finalizes
  for _ in $(seq 1 40); do pgrep -f "record=$RE\.video\.mp4" >/dev/null || break; sleep 0.25; done
  sleep 1
  # scrcpy writes frames only when the screen changes: hold each picture (constant 30 fps, last
  # frame kept) for as long as the sound runs.
  DUR="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$NAME.camera.mp4")"
  # The Mac's mic is far from the voices (peaks ~-36 dB): cut the hiss first, then +30 dB with a
  # limiter (owner checked by ear, 14 Oct; plain loudness normalising mostly raised the hiss).
  AUDIO="highpass=f=120,lowpass=f=7500,afftdn=nf=-30:nr=18,volume=30dB,alimiter=limit=0.9:level=disabled"
  ffmpeg -hide_banner -loglevel error -y -i "$NAME.camera.mp4" -i "$NAME.video.mp4" \
    -filter_complex "[1:v]tpad=stop_mode=clone:stop_duration=600,fps=30,scale=-2:720[scr];[0:v]fps=30,scale=1280:720[cam];[cam][scr]hstack[v]" \
    -map "[v]" -map 0:a -c:v h264_videotoolbox -b:v 10M -af "$AUDIO" -ac 2 -ar 48000 -c:a aac -b:a 192k -t "$DUR" "$NAME.mp4"
  ffmpeg -hide_banner -loglevel error -y -i "$NAME.video.mp4" -i "$NAME.camera.mp4" -map 0:v -map 1:a \
    -vf "tpad=stop_mode=clone:stop_duration=600,fps=30" -c:v h264_videotoolbox -b:v 8M \
    -af "$AUDIO" -ac 2 -ar 48000 -c:a aac -b:a 192k -t "$DUR" "$NAME (screen).mp4"
  rm -f "$NAME.scrcpy.log" "$NAME.ffmpeg.log"
  printf "SAVED: %s.mp4 (%.0f s)\n" "$NAME" "$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$NAME.mp4")"
  ;;
*) echo "usage: $0 start|stop <scene name>"; exit 2 ;;
esac
