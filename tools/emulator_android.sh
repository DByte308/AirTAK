#!/usr/bin/env bash
# Launch the AirTAK Android emulator.
# Requires setup_android_env.sh to have been run first.
#
# Usage:  ./tools/emulator_android.sh          # launch with GUI
#         ./tools/emulator_android.sh headless  # launch without window
#         ./tools/emulator_android.sh kill      # stop running emulator

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ENV_FILE="$PROJECT_ROOT/android/.android-env"
AVD_NAME="AirTAK_API35"

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
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/emulator:$ANDROID_SDK_ROOT/platform-tools:$ANDROID_SDK_ROOT/cmdline-tools/latest/bin:$PATH"

EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
APK="$PROJECT_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"

MODE="${1:-gui}"

case "$MODE" in
    kill|stop)
        echo "Stopping emulator..."
        "$ADB" -e emu kill 2>/dev/null || true
        echo "Emulator stopped."
        exit 0
        ;;
    headless|no-window)
        EMU_ARGS=(-no-window -no-audio)
        echo "Starting emulator in headless mode..."
        ;;
    gui|*)
        EMU_ARGS=()
        echo "Starting emulator with GUI..."
        ;;
esac

if ! "$ADB" devices 2>/dev/null | grep -q "emulator-5554"; then
    "$EMULATOR" \
        -avd "$AVD_NAME" \
        -gpu host \
        -accel on \
        -netdelay none \
        -netspeed full \
        -memory 2048 \
        -cores 4 \
        "${EMU_ARGS[@]}" &
    EMU_PID=$!
    echo "Waiting for emulator to boot (pid $EMU_PID)..."
else
    echo "Emulator already running."
fi

# Wait for boot to complete
echo -n "Waiting for device"
for i in $(seq 1 120); do
    if "$ADB" -e shell getprop sys.boot_completed 2>/dev/null | grep -q "1"; then
        echo ""
        echo -e "${GREEN}Emulator ready.${NC}"

        # Install and launch the app if an APK exists
        if [ -f "$APK" ]; then
            echo "Installing AirTAK..."
            # -g grants all runtime permissions (BLE/location/notifications)
            # so the app launches into the main shell without manual taps.
            "$ADB" -e install -r -g "$APK"
            echo "Launching AirTAK..."
            "$ADB" -e shell am start -n com.airtak/.ui.MainActivity
            echo -e "${GREEN}AirTAK launched.${NC}"
        else
            echo "No APK found — build first with: ./tools/install_apk_android.sh"
        fi

        exit 0
    fi
    echo -n "."
    sleep 2
done

echo ""
echo -e "${RED}Emulator did not boot within 4 minutes.${NC}" >&2
exit 1
