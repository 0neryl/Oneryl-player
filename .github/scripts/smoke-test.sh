#!/usr/bin/env bash
# Installs the APK on the running emulator, searches SoundCloud, plays the
# first result and checks that the media session reports it as playing, and
# that its uploader opens as an artist. Then tries the same with YouTube
# Music, which may refuse a data-centre IP, so a failure there is only a
# warning. Finally the SoundCloud track, liked along the way, must play from
# the Library with the network off, and the YouTube sign-in page and the
# Yandex Music import must open, and a track must play through the app's own
# Xray core. Leaves screenshots and logcat.txt behind.
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

# Waits for the liked SoundCloud track to download, cuts the network and plays
# it from the Library.
play_offline() {
  local ready=""
  for _ in $(seq 1 30); do
    ready=$(adb logcat -d -s OfflineTracks:I | grep "Ready offline: soundcloud/")
    [ -n "$ready" ] && break
    sleep 3
  done
  if [ -z "$ready" ]; then
    echo "::error::The liked SoundCloud track was not downloaded within 90 seconds"
    return 1
  fi
  echo "$ready"
  adb shell cmd connectivity airplane-mode enable
  adb shell svc wifi disable
  adb shell svc data disable
  sleep 5
  if adb shell ping -c 1 -W 2 8.8.8.8 > /dev/null 2>&1; then
    echo "::warning::The network is still up, so the offline check proves less"
  fi
  tap "Library" || return 1
  sleep 2
  tap "track_row" || return 1
  if wait_for_playback; then
    sleep 10
    adb exec-out screencap -p > screenshot-offline.png
    if is_playing; then
      echo "The liked track plays without a network"
      return 0
    fi
  fi
  echo "::error::The downloaded track did not play without a network"
  return 1
}

# Sets up an Xray config whose only outbound goes straight out as the user's
# own server, waits until the app reports it connected, and plays a
# SoundCloud track through it. The config is typed in: the repository is
# private, so a subscription link to a file in it would need a token.
play_through_own_server() {
  local config='[{"remarks":"CI","outbounds":[{"tag":"proxy","protocol":"freedom"}]}]'
  tap "Library" || return 1
  tap "server_set_up" || return 1
  sleep 2
  if [ -z "$(find_node "xray_version")" ]; then
    echo "::error::The Xray core version is not shown; the core did not load"
    return 1
  fi
  tap "server_link" || return 1
  adb shell input text "'$config'"
  tap "Connect" || return 1
  local connected=""
  for _ in $(seq 1 20); do
    connected=$(find_node "server_connected")
    [ -n "$connected" ] && break
    sleep 3
  done
  adb exec-out screencap -p > screenshot-server.png
  if [ -z "$connected" ]; then
    echo "::error::The app did not connect through its own Xray core"
    return 1
  fi
  if play_first_result "SoundCloud" "lofi"; then
    echo "A track plays through the app's own Xray core"
    return 0
  fi
  echo "::error::No track played through the app's own Xray core"
  return 1
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
  # Liking the track downloads it; the offline check at the end plays it back.
  tap "Like"

  # Tapping the artist of a result asks for the service and opens their page;
  # on SoundCloud that is the uploader of the track, without a search.
  if tap "track_artist" && tap "artist_on_soundcloud"; then
    sleep 8
    adb exec-out screencap -p > screenshot-artist.png
    if [ -n "$(find_node "artist_screen")" ] && [ -n "$(find_node "track_row")" ]; then
      echo "The artist page opened with their tracks"
    else
      echo "::error::The artist page did not open with tracks; see screenshot-artist.png"
      STATUS=1
    fi
    adb shell input keyevent KEYCODE_BACK
    sleep 2
  else
    adb exec-out screencap -p > screenshot-artist.png
    echo "::error::Tapping the artist did not offer the services"
    STATUS=1
  fi

  # Pause first, so the check below cannot see the SoundCloud track still playing.
  adb shell input keyevent KEYCODE_MEDIA_PAUSE
  sleep 2
  if play_first_result "YouTube Music" "believer"; then
    sleep 15
    if is_playing; then
      echo "YouTube Music track is playing"
    else
      echo "::warning::The YouTube Music track started but stopped within 15 seconds"
    fi
  else
    echo "::warning::YouTube Music did not play here; data-centre IPs are often refused, check on a phone"
  fi
  adb exec-out screencap -p > screenshot-youtube.png
  adb logcat -d | grep -E "InnerTubeX|PoToken|StreamUnavailable|Playback error" | tail -n 40

  adb shell input keyevent KEYCODE_MEDIA_PAUSE
  if ! play_offline; then
    adb exec-out screencap -p > screenshot-offline.png
    adb logcat -d | grep -E "OfflineTracks|DownloadManager" | tail -n 20
    STATUS=1
  fi
  adb shell cmd connectivity airplane-mode disable
  adb shell svc wifi enable
  adb shell svc data enable

  # Nobody signs in here, but Google's sign-in page must at least open.
  sleep 10
  if tap "Sign in"; then
    sleep 20
    adb exec-out screencap -p > screenshot-sign-in.png
    adb shell input keyevent KEYCODE_BACK
    sleep 2
  else
    echo "::warning::The YouTube sign-in button was not found"
  fi

  # Yandex Music serves only Russia and nearby countries, so from here the
  # import must end in the hint to turn the VPN off.
  if tap "Playlists" && tap "import_yandex" && tap "import_link"; then
    adb shell input text "https://music.yandex.ru/users/music-blog/playlists/2490"
    tap "Import"
    sleep 10
    adb exec-out screencap -p > screenshot-import.png
    if [ -n "$(find_node "import_error")" ]; then
      echo "The Yandex Music import answered with its error hint"
    else
      echo "::warning::The Yandex Music import showed no error; check screenshot-import.png"
    fi
    adb shell input keyevent KEYCODE_BACK
  else
    echo "::warning::The Yandex Music import dialog did not open"
  fi

  # The app's own Xray core with a subscription whose only outbound goes
  # straight out: the track below then reaches the app through the core.
  adb shell input keyevent KEYCODE_MEDIA_PAUSE
  sleep 2
  if ! play_through_own_server; then
    adb exec-out screencap -p > screenshot-server.png
    STATUS=1
  fi
  adb logcat -d | grep -E "ServerTunnel|GoLog" | tail -n 20
else
  adb exec-out screencap -p > screenshot-failed.png
  echo "::error::The SoundCloud track did not start playing"
  STATUS=1
fi

adb shell dumpsys media_session | grep -A 12 "$PACKAGE" | head -n 30
adb logcat -d -v threadtime > logcat.txt
PID=$(adb shell pidof "$PACKAGE" | tr -d '\r')
grep -E "AndroidRuntime|FATAL|ExoPlayer|MediaSession|PlaybackService|InnerTubeX|PoToken${PID:+| $PID }" logcat.txt | tail -n 200
if grep -q "FATAL EXCEPTION" logcat.txt; then
  echo "::error::The app crashed"
  STATUS=1
fi
exit $STATUS
