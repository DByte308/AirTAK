"""
Pre-build extra_script: patches TFT_eSPI_ESP32_S3.h to fix the SPI_PORT crash.

Root cause: On ESP32-S3, TFT_eSPI defaults `SPI_PORT = FSPI = 1`, but
`REG_SPI_BASE(1) = 0` because the macro requires i >= 2.  Arduino-ESP32's
`SPIClass(FSPI=1)` uses `_spi_bus_array[1].dev = DR_REG_SPI3_BASE = 0x60025000`,
so `REG_SPI_BASE(3) = 0x60025000` is the correct value → `SPI_PORT = 3`.

Without this patch `SET_BUS_WRITE_MODE` writes to address 0x10, causing a
StoreProhibited guru meditation on every boot.
"""

import os
import re

Import("env")  # noqa: F821  (PlatformIO injects this)

BAD_LINE  = "    #define SPI_PORT FSPI"
GOOD_LINE = "    #define SPI_PORT 2  // patched: FSPI=0 on ESP32-S3; REG_SPI_BASE(0)=0 crashes; GPSPI2=REG_SPI_BASE(2)=0x60024000I_BASE(2)=0x60024000"


def patch_tft_espi(source, target, env):
    libdeps = env.subst("$PROJECT_LIBDEPS_DIR")
    header = os.path.join(
        libdeps,
        env["PIOENV"],
        "TFT_eSPI",
        "Processors",
        "TFT_eSPI_ESP32_S3.h",
    )
    if not os.path.isfile(header):
        print(f"[patch_tft_espi] WARNING: {header} not found, skipping patch")
        return

    with open(header, "r") as f:
        text = f.read()

    # Already patched (either by this script or by manual edit)
    if "SPI_PORT FSPI" not in text:
        return

    if BAD_LINE not in text:
        print("[patch_tft_espi] WARNING: expected pattern not found; library may have changed")
        return

    patched = text.replace(BAD_LINE, GOOD_LINE)
    with open(header, "w") as f:
        f.write(patched)
    print("[patch_tft_espi] Applied SPI_PORT=2 fix to TFT_eSPI_ESP32_S3.h")


env.AddPreAction("buildprog", patch_tft_espi)
