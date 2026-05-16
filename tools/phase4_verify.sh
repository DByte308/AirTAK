#!/usr/bin/env bash
# AirTAK Phase 4 verification helper.
# Runs automated Android situational-awareness checks and lists the physical
# field workflow gates that require mesh devices and a tile provider.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
GRADLE_WRAPPER="$SCRIPT_DIR/gradle_android.sh"
OVERVIEW_TILES="$PROJECT_ROOT/android/app/src/main/assets/tiles/world-overview.mbtiles"

pushd "$PROJECT_ROOT" > /dev/null

echo -e "\033[36m[phase4] Verifying bundled world overview MBTiles...\033[0m"
if [ ! -s "$OVERVIEW_TILES" ]; then
    echo "Missing bundled overview tiles: $OVERVIEW_TILES" >&2
    exit 1
fi

echo -e "\n\033[36m[phase4] Building debug APK...\033[0m"
"$GRADLE_WRAPPER" assembleDebug

echo -e "\n\033[36m[phase4] Running Android unit tests...\033[0m"
"$GRADLE_WRAPPER" test

echo -e "\n\033[32m[phase4] Automated checks passed.\033[0m"
echo -e "\033[33m[phase4] Physical/app workflow checks remain:\033[0m"
echo "  - Map renders bundled zoom 0-5 overview offline with airplane mode enabled."
echo "  - Tile manager: tap two map corners, choose zoom 6-17, download from configured URL, then relaunch offline."
echo "  - Long-press map creates local Room-backed waypoints and survives app restart."
echo "  - AIRTAK_PLAYER TX/RX updates roster and remote map pins between at least two devices."
echo "  - Admin ping sends AIRTAK_POSITION_REQUEST and target broadcasts POSITION_APP within 2 s."

popd > /dev/null