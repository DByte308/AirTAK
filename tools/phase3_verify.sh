#!/usr/bin/env bash
# AirTAK Phase 3 verification helper.
# Runs automated Android foundation checks and optionally drives the physical
# BLE background/reconnect acceptance procedure on a connected Android device.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
GRADLE_WRAPPER="$SCRIPT_DIR/gradle_android.sh"
ADB="${ADB:-adb}"
PACKAGE="com.airtak"
ACTIVITY="com.airtak/.ui.MainActivity"

DEVICE_CHECKS=false

while [ "$#" -gt 0 ]; do
    case "$1" in
        --device-checks) DEVICE_CHECKS=true ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

pushd "$PROJECT_ROOT" > /dev/null

echo -e "\033[36m[phase3] Building debug APK...\033[0m"
"$GRADLE_WRAPPER" assembleDebug

echo -e "\n\033[36m[phase3] Running Android unit tests...\033[0m"
"$GRADLE_WRAPPER" test

if [ "$DEVICE_CHECKS" = true ]; then
    if ! command -v "$ADB" >/dev/null 2>&1; then
        echo "adb not found. Set ADB=/path/to/adb or add it to PATH." >&2
        exit 1
    fi

    APK="$PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
    echo -e "\n\033[36m[phase3] Installing APK on connected Android device...\033[0m"
    "$ADB" install -r "$APK"

    echo -e "\n\033[36m[phase3] Launching app. Pair/bond with a Phase 2 AirTAK device and confirm the header shows Linked.\033[0m"
    "$ADB" shell am start -n "$ACTIVITY" >/dev/null
    read -r -p "Press Enter once BLE is connected and the foreground notification is visible... "

    echo -e "\n\033[36m[phase3] Killing app process without force-stop, waiting 30 s, then relaunching...\033[0m"
    "$ADB" shell am kill "$PACKAGE" || true
    sleep 30
    "$ADB" shell am start -n "$ACTIVITY" >/dev/null

    echo -e "\n\033[36m[phase3] Checking foreground service registration...\033[0m"
    "$ADB" shell dumpsys activity services "$PACKAGE" | grep -q "LinkService" || {
        echo "LinkService not present in dumpsys output after relaunch." >&2
        exit 1
    }

    echo -e "\n\033[33m[phase3] Manual acceptance: confirm the app reacquired BLE automatically and remains connected for 30 minutes in background.\033[0m"
fi

echo -e "\n\033[32m[phase3] Automated checks passed.\033[0m"
if [ "$DEVICE_CHECKS" != true ]; then
    echo -e "\033[33m[phase3] Physical BLE background checks remain: rerun with --device-checks on a phone paired to T-Echo hardware.\033[0m"
fi

popd > /dev/null