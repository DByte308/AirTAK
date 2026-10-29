#!/usr/bin/env python3
"""
Standalone patcher for TFT_eSPI_ESP32_S3.h — runs before `platformio run`.
Usage: python3 patch_tft_espi_standalone.py <env>

Unlike the extra_scripts version (which hooks `buildprog` = too late, after
library compilation), this runs at the CI level so the header is patched
before any compilation occurs.

The library deps directory is resolved WITHOUT hardcoding an in-tree path:
  * If PLATFORMIO_LIBDEPS_DIR is set, it is used as the base (this overrides platformio's
    default `[platformio] libdeps_dir`, which is what the env var maps to). A RELATIVE value is
    resolved against this project_root (NOT the invocation cwd), matching how platformio treats a
    relative libdeps_dir.
  * Otherwise it falls back to `<project_root>/.pio/libdeps` so existing direct (non-external-cache)
    invocation still works unchanged.
Both branches are joined with the target env name (like `$PROJECT_LIBDEPS_DIR/$PIOENV`).
NOTE: this is an ENV override only; it does not read a custom `[platformio] libdeps_dir` from a
platformio.ini section. No SCons Import is used for the standalone path.
"""

import os
import sys


def _libdeps_base(project_root):
    """Return the base directory that holds per-env library deps.

    A relative PLATFORMIO_LIBDEPS_DIR is resolved against project_root so the
    result does not depend on the invocation cwd. Returns an absolute path.
    """
    env = os.environ.get("PLATFORMIO_LIBDEPS_DIR")
    if env:
        if not os.path.isabs(env):
            env = os.path.join(project_root, env)
        return os.path.normpath(env)
    return os.path.join(project_root, ".pio", "libdeps")


def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <environment>", file=sys.stderr)
        return 1

    pio_env = sys.argv[1]
    project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    libdeps_base = _libdeps_base(project_root)
    header = os.path.join(
        libdeps_base,
        pio_env,
        "TFT_eSPI", "Processors", "TFT_eSPI_ESP32_S3.h",
    )

    if not os.path.isfile(header):
        print(f"[patch_tft_espi] SKIP: {header} not found (library not downloaded?)")
        return 0

    with open(header, "r", encoding="utf-8") as f:
        text = f.read()

    bad = "    #define SPI_PORT FSPI"
    good = "    #define SPI_PORT 2  // SPI2_HOST; FSPI is undefined on S3 (TFT_eSPI#3925)"

    if good in text:
        print(f"[patch_tft_espi] Already patched, skipping")
        return 0

    if bad in text:
        text = text.replace(bad, good)
        with open(header, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"[patch_tft_espi] Patched SPI_PORT in {header}")
        return 0

    print(f"[patch_tft_espi] WARNING: expected pattern not found; file may have changed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
