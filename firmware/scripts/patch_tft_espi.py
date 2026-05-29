"""
Pre-build extra_script: patches TFT_eSPI_ESP32_S3.h for ESP32-S3 compatibility.
The FSPI macro does not exist on ESP32-S3; leaving SPI_PORT = FSPI evaluates to 0,
routing all SPI register accesses to invalid addresses and causing StoreProhibited.
TFT_eSPI is abandoned (final release 2.5.43, 2024) so this patch must persist.
"""

import os

Import("env")  # noqa: F821  (PlatformIO injects this)

BAD_LINE = "    #define SPI_PORT FSPI"
GOOD_LINE = "    #define SPI_PORT 2  // SPI2_HOST; FSPI is undefined on S3 (TFT_eSPI#3925)"


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
        return

    with open(header, "r", encoding="utf-8") as f:
        text = f.read()

    if GOOD_LINE in text:
        return

    if BAD_LINE in text:
        patched = text.replace(BAD_LINE, GOOD_LINE)
        with open(header, "w", encoding="utf-8") as f:
            f.write(patched)
        print("[patch_tft_espi] Applied SPI_PORT FSPI→2 fix for ESP32-S3")


env.AddPreAction("buildprog", patch_tft_espi)
