#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
command -v adb >/dev/null || { echo 'Android platform-tools / adb is required.' >&2; exit 1; }
[[ -s "$APK" ]] || { echo "APK not found: $APK" >&2; exit 1; }
adb get-state | grep -qx device || { echo 'Connect/unlock a device and allow USB debugging.' >&2; exit 1; }
adb install -r "$APK"
adb shell am force-stop com.dana.ai || true
adb shell am start -W -n com.dana.ai/.MainActivity
printf '\nActivity launch command completed. Inspect the phone and Logcat for crashes; this is not an end-to-end voice test.\n'
