#!/usr/bin/env bash
# Installs the APK on the running emulator, searches SoundCloud, plays the
# first result and checks that the media session reports it as playing. Then
# tries the same with YouTube Music, which may refuse a data-centre IP, so a
# failure there is only a warning. Leaves screenshots and logcat.txt behind.
set -u

PACKAGE=com.oneryl.music
APK=$(ls apk/*.apk | head -n 1)

# Prints the centre of the first node whose text, description or test tag is $1.
find_node() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb exec-out cat /sdcard/ui.xml | python3 -c '
import re, sys
import xml.etree.ElementTree as ET
wanted = sys.argv[1]
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    sys.exit()
for node in root.iter("node"):
    labels = {(node.get(k) or "").strip() for k in ("text", "content-desc", "resource-id")}
    if wanted in labels:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        if x2 > x1 and y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
' "$1"
}

tap() {
  for _ in $(seq 1 20); do
    local point
    point=$(find_node "$1")
    if [ -n "$point" ]; then
      echo "Tapping \"$1\" at $point"
      adb shell input tap $point
      return 0
    fi
    sleep 3
  done
  echo "Could not find \"$1\" on screen"
  return 1
}

is_playing() {
  adb shell dumpsys media_session | grep -A 20 "$PACKAGE" | grep -qE "state=(PLAYING|3)[(,]"
}

wait_for_playback() {
  for _ in $(seq 1 30); do
    if is_playing; then
      return 0
    fi
    sleep 2
  done
  return 1
}

play_first_result() {
  local source="$1" query="$2"
  tap "Search" || return 1
  if [ -n "$(find_node "Clear")" ]; then
    tap "Clear"
  fi
  tap "search_field" || return 1
  adb shell input text "$query"
  adb shell input keyevent KEYCODE_ENTER
  tap "$source" || return 1
  sleep 3
  tap "track_row" || return 1
  wait_for_playback
}

adb install -r "$APK"
adb shell pm grant "$PACKAGE" android.permission.POST_NOTIFICATIONS 2> /dev/null
adb logcat -c
adb shell am start -W -n "$PACKAGE/.MainActivity"
sleep 20
adb exec-out screencap -p > screenshot-home.png

STATUS=0
if [ -z "$(adb shell pidof "$PACKAGE" | tr -d '\r')" ]; then
  echo "::error::$PACKAGE is not running 20 seconds after launch"
  STATUS=1
elif play_first_result "SoundCloud" "lofi"; then
  echo "SoundCloud track is playing"
  sleep 5
  adb exec-out screencap -p > screenshot-soundcloud.png
  adb shell cmd statusbar expand-notifications
  sleep 3
  adb exec-out screencap -p > screenshot-notification.png
  adb shell cmd statusbar collapse
  tap "mini_player" && sleep 3 && adb exec-out screencap -p > screenshot-player.png
  adb shell input keyevent KEYCODE_BACK
  sleep 2

  if play_first_result "YouTube Music" "believer"; then
    echo "YouTube Music track is playing"
  else
    echo "::warning::YouTube Music did not play here; data-centre IPs are often refused, check on a phone"
  fi
  adb exec-out screencap -p > screenshot-youtube.png
else
  adb exec-out screencap -p > screenshot-failed.png
  echo "::error::The SoundCloud track did not start playing"
  STATUS=1
fi

adb shell dumpsys media_session | grep -A 12 "$PACKAGE" | head -n 30
adb logcat -d -v threadtime > logcat.txt
PID=$(adb shell pidof "$PACKAGE" | tr -d '\r')
grep -E "AndroidRuntime|FATAL|ExoPlayer|MediaSession|PlaybackService${PID:+| $PID }" logcat.txt | tail -n 150
if grep -q "FATAL EXCEPTION" logcat.txt; then
  echo "::error::The app crashed"
  STATUS=1
fi
exit $STATUS
