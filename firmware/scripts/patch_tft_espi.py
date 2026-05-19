"""
Pre-build extra_script: patches TFT_eSPI_ESP32_S3.h to fix the SPI_PORT crash.

Root cause: On older TFT_eSPI (<2.5.43), TFT_eSPI defaults `SPI_PORT = FSPI = 0`,
but `REG_SPI_BASE(0)` produces an invalid address on ESP32-S3, causing a
StoreProhibited guru meditation on every boot.

TFT_eSPI >= 2.5.43 ships its own REG_SPI_BASE fix (line ~31).  When detected,
the patch is skipped — the library already handles ESP32-S3 correctly.
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

    with open(header, "r", encoding="utf-8") as f:
        text = f.read()

    # TFT_eSPI >= 2.5.43 ships its own REG_SPI_BASE fix — skip our patch.
    if "DR_REG_SPI2_BASE" in text or "DR_REG_SPI3_BASE" in text:
        print("[patch_tft_espi] Library already has ESP32-S3 fix, skipping patch")
        return

    # Already patched (either by this script or by manual edit)
    if "SPI_PORT FSPI" not in text:
        return

    if BAD_LINE not in text:
        print("[patch_tft_espi] WARNING: expected pattern not found; library may have changed")
        return

    patched = text.replace(BAD_LINE, GOOD_LINE)
    with open(header, "w", encoding="utf-8") as f:
        f.write(patched)
    print("[patch_tft_espi] Applied SPI_PORT=2 fix to TFT_eSPI_ESP32_S3.h")


env.AddPreAction("buildprog", patch_tft_espi)
