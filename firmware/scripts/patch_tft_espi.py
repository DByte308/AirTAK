"""
Pre-build extra_script: patches TFT_eSPI_ESP32_S3.h to fix the SPI_PORT crash.

Root cause: On ESP32-S3, TFT_eSPI defaults `SPI_PORT = FSPI = 0`, but
`REG_SPI_BASE(0) = 0` (the macro requires i >= 2 for valid SPI peripheral
addresses). This makes SET_BUS_WRITE_MODE write to address 0x10, causing a
StoreProhibited guru meditation on every boot.

Fix: SPIClass(FSPI=0) uses GPSPI2 at DR_REG_SPI2_BASE = 0x60024000.
REG_SPI_BASE(2) = 0x60024000, so SPI_PORT must be 2.
"""

import os

Import("env")  # noqa: F821  (PlatformIO injects this)

BAD_LINE  = "    #define SPI_PORT FSPI"
GOOD_LINE = "    #define SPI_PORT 2  // patched: FSPI=0→REG_SPI_BASE(0)=0 crash; GPSPI2=REG_SPI_BASE(2)=0x60024000"


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
