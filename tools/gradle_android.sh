#!/usr/bin/env bash
# Runs the Android Gradle wrapper with the correct JDK and Android SDK environment.
# Must run setup_android_env.sh first.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ANDROID_DIR="$PROJECT_ROOT/android"
ENV_FILE="$ANDROID_DIR/.android-env"

RED='\033[31m'
NC='\033[0m'

if [ ! -f "$ENV_FILE" ]; then
    echo -e "${RED}Android environment not configured. Run ./tools/setup_android_env.sh first.${NC}" >&2
    exit 1
fi

# shellcheck source=/dev/null
source "$ENV_FILE"

export JAVA_HOME
export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/platform-tools:$PATH"

# The AGP uses ANDROID_SDK_ROOT preferentially; set both.
# Use ANDROID_USER_HOME inside the project to keep SDK data local.
export GRADLE_USER_HOME="$ANDROID_DIR/.gradle-home"

if [ ! -f "$ANDROID_DIR/gradlew" ]; then
    echo -e "${RED}Gradle wrapper not found. Run ./tools/setup_android_env.sh first.${NC}" >&2
    exit 1
fi

GRADLEW="$ANDROID_DIR/gradlew"

if [ ! -x "$GRADLEW" ]; then
    chmod +x "$GRADLEW"
fi

pushd "$ANDROID_DIR" > /dev/null
exec "$GRADLEW" "$@"
