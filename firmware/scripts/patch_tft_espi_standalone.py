#!/usr/bin/env python3
"""
Standalone patcher for TFT_eSPI_ESP32_S3.h — runs before `platformio run`.
Usage: python3 patch_tft_espi_standalone.py <env>

Unlike the extra_scripts version (which hooks `buildprog` = too late, after
library compilation), this runs at the CI level so the header is patched
before any compilation occurs.
"""

import os, sys

if len(sys.argv) < 2:
    print(f"Usage: {sys.argv[0]} <environment>", file=sys.stderr)
    sys.exit(1)

pio_env = sys.argv[1]

# Path: firmware/.pio/libdeps/<env>/TFT_eSPI/Processors/TFT_eSPI_ESP32_S3.h
project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
header = os.path.join(
    project_root,
    ".pio", "libdeps", pio_env,
    "TFT_eSPI", "Processors", "TFT_eSPI_ESP32_S3.h",
)

if not os.path.isfile(header):
    print(f"[patch_tft_espi] SKIP: {header} not found (library not downloaded?)")
    sys.exit(0)

with open(header, "r") as f:
    text = f.read()

bad = "    #define SPI_PORT FSPI"
good = "    #define SPI_PORT 2  // SPI2_HOST; FSPI is undefined on S3 (TFT_eSPI#3925)"

if good in text:
    print(f"[patch_tft_espi] Already patched, skipping")
    sys.exit(0)

if bad in text:
    text = text.replace(bad, good)
    with open(header, "w") as f:
        f.write(text)
    print(f"[patch_tft_espi] Patched SPI_PORT in {header}")
    sys.exit(0)

print(f"[patch_tft_espi] WARNING: expected pattern not found; file may have changed")
