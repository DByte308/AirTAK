"""
PlatformIO extra_script for the native test environment on Windows.

mbedtls (used by Crypto.cpp / x25519.cpp) calls BCryptGenRandom on Windows,
which requires linking against bcrypt.lib.  Without -lbcrypt the native test
executable fails with undefined-symbol errors for BCryptOpenAlgorithmProvider,
BCryptGenRandom, BCryptCloseAlgorithmProvider.
"""

import sys

Import("env")  # noqa: F821


def add_bcrypt_on_windows():
    if sys.platform != "win32":
        return
    env.Append(LINKFLAGS=["-lbcrypt"])
    env.Append(_LIBFLAGS=["-lbcrypt"])


add_bcrypt_on_windows()
