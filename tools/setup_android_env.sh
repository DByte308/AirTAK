#!/usr/bin/env bash
# AirTAK Android development environment setup.
# Installs JDK 17, Android SDK cmdline-tools + platform/build-tools,
# and generates the Gradle wrapper.
#
# Planning: §11 | Phase 3

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
ANDROID_DIR="$PROJECT_ROOT/android"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
GRADLE_VERSION="8.7"

RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${BOLD}AirTAK Android Development Environment Setup${NC}"
echo ""

# ── 1. JDK 17 ────────────────────────────────────────────────────

JAVA_HOME_CANDIDATE=""
if command -v java &>/dev/null; then
    JAVA_VER=$(java -version 2>&1 | head -1 | grep -oP '(?<=version ")\d+')
    if [ "${JAVA_VER:-0}" -ge 17 ]; then
        echo -e "  JDK: ${GREEN}$(java -version 2>&1 | head -1)${NC}"
        JAVA_HOME_CANDIDATE="$(dirname "$(dirname "$(readlink -f "$(which java)")")")"
    else
        echo -e "  JDK: ${RED}found Java $JAVA_VER (need ≥ 17)${NC}"
    fi
fi

if [ -z "$JAVA_HOME_CANDIDATE" ]; then
    echo "  Installing JDK 17..."
    if command -v apt &>/dev/null; then
        sudo apt-get update -qq
        sudo apt-get install -y -qq openjdk-17-jdk-headless
    else
        echo -e "${RED}Unsupported package manager. Install JDK ≥ 17 manually.${NC}" >&2
        exit 1
    fi
    JAVA_HOME_CANDIDATE="/usr/lib/jvm/java-17-openjdk-amd64"
fi

export JAVA_HOME="$JAVA_HOME_CANDIDATE"
export PATH="$JAVA_HOME/bin:$PATH"
echo -e "  JAVA_HOME: ${GREEN}$JAVA_HOME${NC}"

# ── 2. Android SDK ───────────────────────────────────────────────

mkdir -p "$ANDROID_SDK_ROOT"

if [ -f "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo -e "  Android SDK: ${GREEN}found${NC}"
else
    echo "  Downloading Android command-line tools..."
    TMP_ZIP="/tmp/airtak-cmdline-tools.zip"
    TMP_DIR="/tmp/airtak-cmdline-tools-tmp"

    if ! curl -fSL --progress-bar "$CMDLINE_TOOLS_URL" -o "$TMP_ZIP"; then
        echo -e "${RED}Failed to download cmdline-tools from:${NC}" >&2
        echo "  $CMDLINE_TOOLS_URL" >&2
        echo "" >&2
        echo "Check https://developer.android.com/studio#command-line-tools-only" >&2
        echo "for the latest download URL and update CMDLINE_TOOLS_URL in this script." >&2
        exit 1
    fi

    rm -rf "$TMP_DIR"
    mkdir -p "$TMP_DIR"
    unzip -qo "$TMP_ZIP" -d "$TMP_DIR"
    rm -f "$TMP_ZIP"

    mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
    rm -rf "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    mv "$TMP_DIR/cmdline-tools" "$ANDROID_SDK_ROOT/cmdline-tools/latest"
    rm -rf "$TMP_DIR"

    echo -e "  Android SDK: ${GREEN}installed${NC}"
fi

export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"
SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

echo "  Installing SDK packages (platform-tools, platform 35, build-tools)..."
yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    > /dev/null 2>&1

yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses > /dev/null 2>&1

echo -e "  SDK packages: ${GREEN}installed${NC}"

# ── 3. Gradle wrapper ────────────────────────────────────────────

if [ -f "$ANDROID_DIR/gradlew" ]; then
    echo -e "  Gradle wrapper: ${GREEN}found${NC}"
else
    echo "  Generating Gradle wrapper ($GRADLE_VERSION)..."
    GRADLE_ZIP="/tmp/gradle-${GRADLE_VERSION}-bin.zip"
    GRADLE_DIR="/tmp/gradle-${GRADLE_VERSION}"
    TMP_PROJECT="/tmp/airtak-gradle-wrapper-gen"

    if [ ! -d "$GRADLE_DIR" ]; then
        curl -fSL --progress-bar \
            "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" \
            -o "$GRADLE_ZIP"
        unzip -qo "$GRADLE_ZIP" -d /tmp
        rm -f "$GRADLE_ZIP"
    fi

    mkdir -p "$TMP_PROJECT"
    pushd "$TMP_PROJECT" > /dev/null
    touch settings.gradle.kts build.gradle.kts
    "$GRADLE_DIR/bin/gradle" wrapper --gradle-version "$GRADLE_VERSION" --no-daemon > /dev/null 2>&1
    popd > /dev/null

    cp "$TMP_PROJECT/gradlew" "$ANDROID_DIR/gradlew"
    cp "$TMP_PROJECT/gradlew.bat" "$ANDROID_DIR/gradlew.bat"
    mkdir -p "$ANDROID_DIR/gradle/wrapper"
    cp "$TMP_PROJECT/gradle/wrapper/gradle-wrapper.jar" "$ANDROID_DIR/gradle/wrapper/"
    cp "$TMP_PROJECT/gradle/wrapper/gradle-wrapper.properties" "$ANDROID_DIR/gradle/wrapper/"
    chmod +x "$ANDROID_DIR/gradlew"

    rm -rf "$TMP_PROJECT" "$GRADLE_DIR"
    echo -e "  Gradle wrapper: ${GREEN}generated${NC}"
fi

# ── 4. Emulator (optional) ────────────────────────────────────────

EMULATOR="$ANDROID_SDK_ROOT/emulator/emulator"
AVD_NAME="AirTAK_API35"
SYSTEM_IMAGE="system-images;android-35;google_apis;x86_64"

if [ -f "$EMULATOR" ] && [ -d "$HOME/.android/avd/${AVD_NAME}.avd" ]; then
    echo -e "  Emulator: ${GREEN}found${NC}"
else
    echo ""
    echo -n "  Install Android emulator + API 35 system image? (~2 GB) [y/N] "
    read -r REPLY
    if [ "$REPLY" = "y" ] || [ "$REPLY" = "Y" ]; then
        echo "  Installing emulator..."
        yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "emulator" > /dev/null 2>&1
        echo "  Installing system image ($SYSTEM_IMAGE)..."
        yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" "$SYSTEM_IMAGE" > /dev/null 2>&1
        echo "  Creating AVD '$AVD_NAME'..."
        echo "no" | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/avdmanager" create avd \
            -n "$AVD_NAME" \
            -k "$SYSTEM_IMAGE" \
            -d "pixel_6" \
            -c 512M \
            -f > /dev/null 2>&1
        echo -e "  Emulator: ${GREEN}ready${NC}"
    else
        echo "  Skipped. Install later by re-running this script."
    fi
fi

# ── Done ─────────────────────────────────────────────────────────

ENV_FILE="$ANDROID_DIR/.android-env"
cat > "$ENV_FILE" <<EOF
JAVA_HOME=$JAVA_HOME
ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT
EOF

echo ""
echo -e "${BOLD}Android environment ready.${NC}"
echo ""
echo "  JAVA_HOME:     $JAVA_HOME"
echo "  ANDROID_HOME:  $ANDROID_SDK_ROOT"
echo ""
echo "  Build & test:   ${BOLD}./tools/gradle_android.sh assembleDebug${NC}"
echo "                   ${BOLD}./tools/gradle_android.sh test${NC}"
echo "  Emulator:       ${BOLD}./tools/emulator_android.sh${NC}           (launch with GUI)"
echo "                   ${BOLD}./tools/emulator_android.sh headless${NC}  (no window)"
echo "                   ${BOLD}./tools/emulator_android.sh kill${NC}      (stop)"
echo "  Install APK:    ${BOLD}./tools/install_apk_android.sh${NC}        (build + install)"
echo "                   ${BOLD}./tools/install_apk_android.sh run${NC}   (build + install + launch)"
echo ""
