#!/usr/bin/env bash
# Build and install the AirTAK debug APK on the running Android emulator.
# Requires setup_android_env.sh and a running emulator.
#
# Usage:  ./tools/install_apk_android.sh           # assemble + install
#         ./tools/install_apk_android.sh install   # install existing APK only
#         ./tools/install_apk_android.sh run       # assemble + install + launch

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_ROOT/android/.android-env"

RED='\033[31m'
GREEN='\033[32m'
NC='\033[0m'

if [ ! -f "$ENV_FILE" ]; then
    echo -e "${RED}Android environment not configured. Run ./tools/setup_android_env.sh first.${NC}" >&2
    exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

APK="$PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
GRADLE_ANDROID="$PROJECT_ROOT/tools/gradle_android.sh"

MODE="${1:-build}"

# Check emulator is running
if ! "$ADB" devices 2>/dev/null | grep -q "emulator-5554"; then
    echo -e "${RED}No emulator running. Start it with: ./tools/emulator_android.sh${NC}" >&2
    exit 1
fi

if [ "$MODE" != "install" ]; then
    echo "Building debug APK..."
    "$GRADLE_ANDROID" assembleDebug
fi

if [ ! -f "$APK" ]; then
    echo -e "${RED}APK not found at $APK${NC}" >&2
    exit 1
fi

echo "Installing APK ($(du -h "$APK" | cut -f1))..."
"$ADB" -e install -r "$APK"

if [ "$MODE" = "run" ]; then
    echo "Launching AirTAK..."
    "$ADB" -e shell am start -n com.airtak/.MainActivity
fi

echo -e "${GREEN}Done.${NC}"
