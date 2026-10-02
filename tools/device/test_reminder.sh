#!/usr/bin/env bash
# Fires Settings -> "Send a test reminder" through the REAL UI, waits for Android to post the
# notification, and prints exactly what the user would see. This exercises the whole path — alarm ->
# receiver -> notification channel -> posted notification — which no unit test can.
#
# Requires the app to be installed with onboarding finished (the language screen hides Settings).
# Matches English, Persian and German labels.
#
# Usage (Git Bash on Windows, from the repo root): tools/device/test_reminder.sh <adb-serial>
set -u
export MSYS_NO_PATHCONV=1   # keep device paths like /sdcard/... from being rewritten by Git Bash

SERIAL="${1:?usage: tools/device/test_reminder.sh <adb-serial>}"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
OUT="${OUT:-build/device-smoke}"
# `pwd -W` (Git Bash) gives a Windows-form path. Plain `pwd` gives /c/Users/..., which Windows Python
# cannot open — and because MSYS_NO_PATHCONV is set above, Git Bash no longer converts it either. That
# combination made every lookup silently return nothing. Falls back to `pwd` on Linux and macOS.
HERE="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
PKG=com.yadora.app
mkdir -p "$OUT"
a() { "$ADB" -s "$SERIAL" "$@"; }
# Fail fast if the device is not attached: some adb subcommands, logcat among them, otherwise wait for
# it forever.
a get-state >/dev/null 2>&1 || { echo "device $SERIAL is not connected (see: adb devices)"; exit 2; }
dump() { a shell uiautomator dump /sdcard/yadora_ui.xml >/dev/null 2>&1; a pull /sdcard/yadora_ui.xml "$OUT/ui.xml" >/dev/null 2>&1; }
find_node() { python "$HERE/ui_find.py" "$OUT/ui.xml" "$1"; }
tap() { a shell input tap $(echo "$1" | cut -d' ' -f1-2); }
yadora_notification() {
  a shell dumpsys notification --noredact | tr -d '\r' | grep -A60 "pkg=$PKG" | grep -E "android.title=|android.text=" | head -2
}

a shell input keyevent KEYCODE_WAKEUP
# Force-stop clears any notification the app already posted, so whatever appears below is new.
a shell am force-stop "$PKG"
rm -f "$OUT/ui.xml"
a shell am start -n "$PKG/com.example.MainActivity" >/dev/null 2>&1

# A cold start on a busy phone can take several seconds; wait for the control rather than guessing.
SETTINGS=""
for _ in $(seq 1 10); do
  sleep 2
  dump
  [ -f "$OUT/ui.xml" ] && SETTINGS=$(find_node "settings|تنظیمات|einstellungen")
  [ -n "$SETTINGS" ] && break
done
[ -z "$SETTINGS" ] && { echo "Settings control not found — has onboarding been completed on this device?"; exit 1; }
tap "$SETTINGS"
sleep 2

BUTTON=""
for _ in $(seq 1 14); do
  dump
  BUTTON=$(find_node "test reminder|test-erinnerung|یادآوری آزمایشی")
  [ -n "$BUTTON" ] && break
  a shell input swipe 540 1700 540 900 300
  sleep 1
done
[ -z "$BUTTON" ] && { echo "'Send a test reminder' not found in Settings"; exit 1; }

[ -n "$(yadora_notification)" ] && echo "warning: a Yadora notification was already showing before the test"
a logcat -c
tap "$BUTTON"
echo "tapped: $(echo "$BUTTON" | cut -d' ' -f3-)"

echo "waiting for the reminder to post (up to 2 minutes)..."
POSTED=""
for _ in $(seq 1 24); do
  POSTED=$(yadora_notification)
  [ -n "$POSTED" ] && break
  sleep 5
done
echo "${POSTED:-NO NOTIFICATION WAS POSTED}"

echo "=== crashes ==="
a logcat -d -b crash | tr -d '\r' | head -8
echo "(end)"
a exec-out screencap -p > "$OUT/test_reminder.png" && echo "screenshot: $OUT/test_reminder.png"
