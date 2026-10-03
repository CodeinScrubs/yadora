#!/usr/bin/env bash
# Yadora device smoke test.
#
# Installs an APK (upgrading in place; if the installed build is signed with a different key it stops,
# unless ALLOW_UNINSTALL=1, which uninstalls first and DELETES the app's data on that device), launches the app, and reports
# crashes/ANRs, the reminders it armed, its notification channels and WorkManager jobs, plus a
# screenshot. Unit tests cannot see any of this; it only exists on a device.
#
# Usage (Git Bash on Windows, from the repo root, after ./gradlew :app:assembleDebug):
#   tools/device/smoke.sh <adb-serial> [apk]
#   ALLOW_UNINSTALL=1 tools/device/smoke.sh <adb-serial> [apk]   # only on a test device whose data may go
# `adb devices` lists serials; an emulator is usually emulator-5554. Output goes to build/device-smoke.
set -u
# Without this, Git Bash rewrites device paths such as /sdcard/x into Windows paths and adb then
# "succeeds" while doing nothing.
export MSYS_NO_PATHCONV=1

SERIAL="${1:?usage: tools/device/smoke.sh <adb-serial> [apk]}"
APK="${2:-app/build/outputs/apk/debug/app-debug.apk}"
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
OUT="${OUT:-build/device-smoke}"
PKG=com.yadora.app
mkdir -p "$OUT"
a() { "$ADB" -s "$SERIAL" "$@"; }
# Fail fast if the device is not attached: some adb subcommands, logcat among them, otherwise wait for
# it forever — a phone unplugged mid-session left this script hanging with nothing installed.
a get-state >/dev/null 2>&1 || { echo "device $SERIAL is not connected (see: adb devices)"; exit 2; }

echo "=== device ==="
echo "$(a shell getprop ro.product.manufacturer | tr -d '\r') $(a shell getprop ro.product.model | tr -d '\r'), Android $(a shell getprop ro.build.version.release | tr -d '\r')"

echo "=== install ==="
RESULT=$(a install -r "$APK" 2>&1 | tr -d '\r')
echo "$RESULT" | tail -2
if echo "$RESULT" | grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match"; then
  # Uninstalling deletes every topic and review on the device, so it is never the default (an outside audit,
  # 2026-10-03: the script would have done it to a pilot participant's phone without asking).
  if [ "${ALLOW_UNINSTALL:-0}" = "1" ]; then
    echo "installed build has a different signing key -> uninstalling because ALLOW_UNINSTALL=1 (app data on $SERIAL is lost)"
    a uninstall "$PKG" | tr -d '\r'
    a install "$APK" 2>&1 | tr -d '\r' | tail -2
  else
    echo "installed build has a different signing key: NOT uninstalling, which would delete the app's data on $SERIAL."
    echo "Install a build signed with the same key, or rerun with ALLOW_UNINSTALL=1 on a test device."
    exit 3
  fi
fi
a shell dumpsys package "$PKG" | tr -d '\r' | grep -E "versionCode|versionName" | head -2

echo "=== launch ==="
a logcat -c
a shell am force-stop "$PKG"
a shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 8
PID=$(a shell pidof "$PKG" | tr -d '\r')
echo "running pid: ${PID:-NOT RUNNING}"

echo "=== crashes / ANRs since launch ==="
a logcat -d -b crash | tr -d '\r' | grep -iE "$PKG|com\.example" | head -20
a logcat -d | tr -d '\r' | grep -E "FATAL EXCEPTION|ANR in $PKG" | head -10
echo "(end)"

echo "=== Yadora warnings ==="
a logcat -d -s Yadora:* | tr -d '\r' | tail -15

# window=0 with exactAllowReason means an EXACT alarm; a window like +1h0m means the inexact fallback
# Android 14+ uses when SCHEDULE_EXACT_ALARM has not been granted (the default on a fresh install).
echo "=== reminders armed ==="
a shell dumpsys alarm | tr -d '\r' | grep -A3 "$PKG" | grep -E "tag=|origWhen|exactAllowReason" | head -12

echo "=== notification channels ==="
a shell dumpsys notification | tr -d '\r' | grep -oE "NotificationChannel\{mId='medreview[^']*', mName=[^,]*, [^,]*, mImportance=[0-9]" | sort -u

echo "=== WorkManager jobs ==="
a shell dumpsys jobscheduler | tr -d '\r' | grep -E "JOB .*$PKG|$PKG.*RUNNABLE|$PKG.*WAITING" | head -4

a exec-out screencap -p > "$OUT/launch.png" && echo "screenshot: $OUT/launch.png"
