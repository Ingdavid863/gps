#!/usr/bin/env bash
set -uo pipefail

# Pixel comparisons need an awake display throughout the full instrumented suite.
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true

gradle :app:connectedDebugAndroidTest --stacktrace
qa_status=$?
mkdir -p build/native-map-qa
adb pull /sdcard/Download/GPS3DQA build/native-map-qa || true
adb shell screencap -p /sdcard/gps3d-qa-postrun.png || true
adb pull /sdcard/gps3d-qa-postrun.png build/native-map-qa/ || true
adb shell uiautomator dump /sdcard/gps3d-qa-screen.xml || true
adb pull /sdcard/gps3d-qa-screen.xml build/native-map-qa/ || true
exit "$qa_status"
