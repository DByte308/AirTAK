#!/usr/bin/env bash
# AirTAK firmware development environment setup.
# Creates firmware/.venv and installs the pinned PlatformIO toolchain wrapper.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
FIRMWARE_DIR="$PROJECT_ROOT/firmware"
VENV_DIR="$FIRMWARE_DIR/.venv"
REQUIREMENTS="$FIRMWARE_DIR/requirements-dev.txt"

if [ ! -d "$VENV_DIR" ]; then
    python3 -m venv "$VENV_DIR"
fi

PYTHON="$VENV_DIR/bin/python3"
BIN_DIR="$VENV_DIR/bin"

"$PYTHON" -m pip install --upgrade pip
"$PYTHON" -m pip install -r "$REQUIREMENTS"

cat > "$BIN_DIR/gcc" <<'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/python3" -m ziglang cc "$@"
EOF

cat > "$BIN_DIR/g++" <<'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/python3" -m ziglang c++ "$@"
EOF

cat > "$BIN_DIR/ar" <<'EOF'
#!/usr/bin/env bash
DIR="$(cd "$(dirname "$0")" && pwd)"
exec "$DIR/python3" -m ziglang ar "$@"
EOF

chmod +x "$BIN_DIR/gcc" "$BIN_DIR/g++" "$BIN_DIR/ar"

echo -e "\033[32mFirmware environment ready.\033[0m"
echo "Use: ./tools/pio_firmware.sh run -e t_echo_plus"
echo "Use: ./tools/pio_firmware.sh test -e native"
