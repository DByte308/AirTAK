#!/usr/bin/env bash
# Runs the pinned firmware PlatformIO installation with its venv-local tools on PATH.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
FIRMWARE_DIR="$PROJECT_ROOT/firmware"
VENV_BIN="$FIRMWARE_DIR/.venv/bin"
PLATFORMIO="$VENV_BIN/platformio"

if [ ! -f "$PLATFORMIO" ]; then
    echo -e "\033[31mPlatformIO venv is missing. Run ./tools/setup_firmware_env.sh first.\033[0m" >&2
    exit 1
fi

export PATH="$VENV_BIN:$PATH"

COMMAND=""
ENVIRONMENT=""
EXTRA_ARGS=()

while [ $# -gt 0 ]; do
    case "$1" in
        -e|--environment)
            if [ $# -lt 2 ]; then
                echo -e "\033[31mMissing value for $1\033[0m" >&2
                exit 1
            fi
            ENVIRONMENT="$2"
            shift 2
            ;;
        *)
            if [ -z "$COMMAND" ]; then
                COMMAND="$1"
            else
                EXTRA_ARGS+=("$1")
            fi
            shift
            ;;
    esac
done

ARGS=()
[ -n "$COMMAND" ] && ARGS+=("$COMMAND")
if [ -n "$ENVIRONMENT" ]; then
    ARGS+=("-e" "$ENVIRONMENT")
fi
ARGS+=("${EXTRA_ARGS[@]}")

pushd "$FIRMWARE_DIR" > /dev/null

# ESP32-S3 targets need a pre-build TFT_eSPI header fix (TFT_eSPI#3925).
# The extra_scripts hook runs after library compilation, too late for the
# first build.  Download deps and patch before the main build command.
ESP32S3_ENVS="t_deck t3s3 heltec_v3"
if [ -n "$ENVIRONMENT" ] && echo " $ESP32S3_ENVS " | grep -q " $ENVIRONMENT "; then
    echo "[pio_firmware] Pre-patching TFT_eSPI for $ENVIRONMENT..."
    "$PLATFORMIO" pkg install -e "$ENVIRONMENT" --silent 2>&1 || true
    python3 "$FIRMWARE_DIR/scripts/patch_tft_espi_standalone.py" "$ENVIRONMENT" || true
fi

exec "$PLATFORMIO" "${ARGS[@]}"
