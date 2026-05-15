#!/usr/bin/env bash
# AirTAK Phase 1 verification helper.
# Runs the automated Phase 1 checks and optionally uploads to a connected T-Echo Plus.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PIO_WRAPPER="$SCRIPT_DIR/pio_firmware.sh"
FLASH_SCRIPT="$SCRIPT_DIR/flash.sh"

UPLOAD=false
PORT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --upload|-u) UPLOAD=true ;;
        --port|-p) PORT="$2"; shift ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

pushd "$PROJECT_ROOT" > /dev/null

echo -e "\033[36m[phase1] Running native unit tests...\033[0m"
"$PIO_WRAPPER" test -e native

echo -e "\n\033[36m[phase1] Building T-Echo Plus firmware...\033[0m"
"$PIO_WRAPPER" run -e t_echo_plus

if [ "$UPLOAD" = true ]; then
    echo -e "\n\033[36m[phase1] Uploading firmware...\033[0m"
    FLASH_ARGS=()
    [ -n "$PORT" ] && FLASH_ARGS+=("--port" "$PORT")
    "$FLASH_SCRIPT" "${FLASH_ARGS[@]}"
fi

echo -e "\n\033[32m[phase1] Automated checks passed.\033[0m"
if [ "$UPLOAD" != true ]; then
    echo -e "\033[33m[phase1] Hardware smoke checks remain in docs/phase1-acceptance.md.\033[0m"
fi

popd > /dev/null
