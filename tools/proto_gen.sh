#!/usr/bin/env bash
# AirTAK — Airsoft Team Awareness Kit
# tools/proto_gen.sh — Generate protobuf bindings from proto/ sources
# Planning: §18.3 | Roadmap: T.4
#
# Generates:
#   1. nanopb C/C++ bindings → firmware/src/proto/
#      Sources: proto/airtak/*.proto (NOT proto/meshtastic/ — those are vendored, do not regenerate)
#   2. Kotlin/Java lite bindings → android/app/src/main/kotlin/com/airtak/proto/
#      Sources are also copied to android/app/src/main/proto/ for review/reference.
#
# Requirements:
#   - protoc in PATH (https://github.com/protocolbuffers/protobuf/releases)
#   - nanopb generator: pip install nanopb  (or use nanopb from lib/nanopb/)
#
# CRITICAL: proto/meshtastic/ protos are VENDORED at the commit pinned in
# proto/meshtastic/VERSION. Do NOT regenerate them — edit manually if needed.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PROTO_AIRTAK="$PROJECT_ROOT/proto/airtak"
FIRMWARE_PROTO="$PROJECT_ROOT/firmware/src/proto/airtak"
ANDROID_PROTO="$PROJECT_ROOT/android/app/src/main/proto/com/airtak/proto"
ANDROID_KOTLIN="$PROJECT_ROOT/android/app/src/main/kotlin"
PROTO_ROOT="$PROJECT_ROOT/proto"

FIRMWARE_ONLY=false
ANDROID_ONLY=false

while [ $# -gt 0 ]; do
    case "$1" in
        --firmware-only|-f) FIRMWARE_ONLY=true ;;
        --android-only|-a)  ANDROID_ONLY=true ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
    shift
done

# Verify protoc is available
if ! command -v protoc &> /dev/null; then
    echo -e "\033[31mprotoc not found in PATH. Download from https://github.com/protocolbuffers/protobuf/releases\033[0m" >&2
    exit 1
fi

shopt -s nullglob
PROTO_FILES=("$PROTO_AIRTAK"/*.proto)
shopt -u nullglob

if [ ${#PROTO_FILES[@]} -eq 0 ]; then
    echo -e "\033[33mNo .proto files found in $PROTO_AIRTAK\033[0m" >&2
    exit 0
fi

# ── Firmware nanopb generation ────────────────────────────────────────────────
if [ "$ANDROID_ONLY" != true ]; then
    echo -e "\n\033[36m[proto_gen.sh] Generating nanopb C bindings → $FIRMWARE_PROTO\033[0m"

    # Find nanopb generator (prefer local lib, then PlatformIO cache, then PATH).
    NANOPB_CANDIDATES=(
        "$PROJECT_ROOT/firmware/lib/nanopb/generator/nanopb_generator.py"
        "$PROJECT_ROOT/firmware/.pio/libdeps/t_echo_plus/Nanopb/generator/nanopb_generator.py"
        "$PROJECT_ROOT/firmware/.pio/libdeps/native/Nanopb/generator/nanopb_generator.py"
    )
    NANOPB_GEN=""
    for candidate in "${NANOPB_CANDIDATES[@]}"; do
        if [ -f "$candidate" ]; then
            NANOPB_GEN="$candidate"
            break
        fi
    done
    if [ -z "$NANOPB_GEN" ]; then
        if command -v nanopb_generator.py &> /dev/null; then
            NANOPB_GEN="$(command -v nanopb_generator.py)"
        fi
    fi
    if [ -z "$NANOPB_GEN" ]; then
        echo -e "\033[31mnanopb_generator.py not found. Build firmware once or install nanopb with pip.\033[0m" >&2
        exit 1
    fi

    mkdir -p "$FIRMWARE_PROTO"

    for proto in "${PROTO_FILES[@]}"; do
        echo "  Generating nanopb for: $(basename "$proto")"
        python3 "$NANOPB_GEN" \
            --proto-path "$PROTO_AIRTAK" \
            --proto-path "$PROTO_ROOT" \
            --output-dir "$FIRMWARE_PROTO" \
            "$proto"
    done
fi

# ── Android Kotlin/Java protobuf generation ───────────────────────────────────
if [ "$FIRMWARE_ONLY" != true ]; then
    echo -e "\n\033[36m[proto_gen.sh] Generating Android protobuf sources → $ANDROID_KOTLIN\033[0m"

    mkdir -p "$ANDROID_PROTO"
    mkdir -p "$ANDROID_KOTLIN"

    for proto in "${PROTO_FILES[@]}"; do
        dest="$ANDROID_PROTO/$(basename "$proto")"
        cp -f "$proto" "$dest"
        echo "  Copied: $(basename "$proto")"
    done

    protoc \
        "--proto_path=$PROTO_ROOT" \
        --java_out="lite,annotate_code=false:$ANDROID_KOTLIN" \
        --kotlin_out="lite:$ANDROID_KOTLIN" \
        "${PROTO_FILES[@]}"

    # protoc ≥ 32.x emits @com.google.protobuf.Generated (and @javax.annotation.Generated)
    # annotations that do not exist in the protobuf-kotlin-lite 4.26 runtime / Android
    # classpath. Strip them from both Java and Kotlin generated sources.
    for ext in java kt; do
        find "$ANDROID_KOTLIN/com/airtak/proto" -name "*.${ext}" \
            -exec sed -i \
                -e '/^[[:space:]]*@com\.google\.protobuf\.Generated[[:space:]]*$/d' \
                -e '/^@javax\.annotation\.Generated(/d' \
                -e '/^@file:com\.google\.protobuf\.Generated[[:space:]]*$/d' \
                -e '/^@file:javax\.annotation\.Generated(/d' \
            {} \;
    done
    echo "  Stripped @Generated annotations from generated Java/Kotlin sources"
fi

echo -e "\n\033[32m[proto_gen.sh] Done.\033[0m"
