#!/usr/bin/env bash
set -uo pipefail

GPS3D_AUTO_SHARE_QA=1 gradle :app:connectedDebugAndroidTest --stacktrace
qa_status=$?
mkdir -p build/native-map-qa
adb pull /sdcard/Download/GPS3DQA build/native-map-qa || true
adb shell screencap -p /sdcard/gps3d-qa-postrun.png || true
adb pull /sdcard/gps3d-qa-postrun.png build/native-map-qa/ || true
adb shell uiautomator dump /sdcard/gps3d-qa-screen.xml || true
adb pull /sdcard/gps3d-qa-screen.xml build/native-map-qa/ || true
exit "$qa_status"
