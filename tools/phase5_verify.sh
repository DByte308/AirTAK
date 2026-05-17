#!/usr/bin/env bash
# AirTAK Phase 5 verification helper.
# Runs automated checks for tactical markers, drawing UI, active/ended game
# toggle, marker rebroadcast, and vote-kick moderation. Field gates that
# require multiple mesh devices are listed at the end.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
GRADLE_WRAPPER="$SCRIPT_DIR/gradle_android.sh"

pushd "$PROJECT_ROOT" > /dev/null

echo -e "\033[36m[phase5] Verifying Phase 5 source artefacts...\033[0m"
required_files=(
    "android/app/src/main/kotlin/com/airtak/data/db/TacticalMarkerEntity.kt"
    "android/app/src/main/kotlin/com/airtak/data/db/TacticalMarkerDao.kt"
    "android/app/src/main/kotlin/com/airtak/data/db/ModerationVoteEntity.kt"
    "android/app/src/main/kotlin/com/airtak/data/db/ModerationVoteDao.kt"
    "android/app/src/main/kotlin/com/airtak/data/marker/TacticalMarkerRepository.kt"
    "android/app/src/main/kotlin/com/airtak/data/moderation/ModerationRepository.kt"
    "android/app/src/main/kotlin/com/airtak/ui/markers/MarkersScreen.kt"
    "android/app/src/main/kotlin/com/airtak/ui/markers/MarkersViewModel.kt"
    "android/app/src/test/kotlin/com/airtak/data/db/TacticalMarkerEntityTest.kt"
    "android/app/src/test/kotlin/com/airtak/data/moderation/BanStateTest.kt"
    "proto/airtak/game.proto"
)
for f in "${required_files[@]}"; do
    if [ ! -s "$f" ]; then
        echo "Missing or empty Phase 5 artefact: $f" >&2
        exit 1
    fi
done

echo -e "\n\033[36m[phase5] Checking proto definitions for TacticalMarker + ModerationPacket...\033[0m"
grep -q "message TacticalMarker" proto/airtak/game.proto \
    || { echo "TacticalMarker proto missing" >&2; exit 1; }
grep -q "message ModerationPacket" proto/airtak/game.proto \
    || { echo "ModerationPacket proto missing" >&2; exit 1; }
grep -q "AIRTAK_TACTICAL" android/app/src/main/kotlin/com/airtak/data/mesh/MeshWire.kt \
    || { echo "AIRTAK_TACTICAL portnum constant missing" >&2; exit 1; }
grep -q "AIRTAK_MODERATION" android/app/src/main/kotlin/com/airtak/data/mesh/MeshWire.kt \
    || { echo "AIRTAK_MODERATION portnum constant missing" >&2; exit 1; }

echo -e "\n\033[36m[phase5] Guarding against accidental Game tab regressions...\033[0m"
if grep -RIn "GameScreen()" android/app/src/main/kotlin > /dev/null; then
    echo "AppShell still references the removed GameScreen()." >&2
    exit 1
fi

echo -e "\n\033[36m[phase5] Building debug APK...\033[0m"
"$GRADLE_WRAPPER" assembleDebug

echo -e "\n\033[36m[phase5] Running Android unit tests...\033[0m"
"$GRADLE_WRAPPER" testDebugUnitTest

echo -e "\n\033[32m[phase5] Automated checks passed.\033[0m"
echo -e "\033[33m[phase5] Physical/multi-device workflow checks remain:\033[0m"
echo "  - Map drawing: create POINT, ROUTE, AREA, NOTE, and OBJECTIVE markers; commit and verify they render."
echo "  - Cross-device: marker created on device A appears on device B within ≤2 s on matching channel binding."
echo "  - Scope routing: SCOPE_FIRE_TEAM marker only reaches devices bound to the same fireteam channel."
echo "  - Active/Ended toggle: pause icon on Map → state INACTIVE broadcast; verify peer sees player drop from roster."
echo "  - Marker rebroadcast on reconnect: cycle BLE link; ensure owned markers retransmit (≥1 per marker)."
echo "  - Vote-kick: three devices each vote-kick the same target at SCOPE_FIRE_TEAM; verify target's row disappears locally."
echo "  - 24h TTL: simulate clock advance >24h; banned target reappears (votes pruned)."
echo "  - Marker delete (tombstone): owner deletes via long-press in Markers tab; other devices stop rendering."

popd > /dev/null
