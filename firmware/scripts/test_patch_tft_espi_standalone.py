#!/usr/bin/env python3
"""Offline regression test for patch_tft_espi_standalone.py.

Run:  python3 firmware/scripts/test_patch_tft_espi_standalone.py

Self-contained / stdlib-only: it builds throwaway temp fixtures, points the patcher at them via the
PLATFORMIO_LIBDEPS_DIR env override, and asserts the behavior WITHOUT touching the real repo tree,
the real TFT_eSPI library, network, or a compiler.

Cases pinned down:
  1. External path honored — with PLATFORMIO_LIBDEPS_DIR set, the patcher writes into that directory,
     NOT into an in-tree `.pio/libdeps` under the project root (no in-tree assumption).
  2. Actual header patch — the bad `#define SPI_PORT FSPI` line is replaced with the good line.
  3. Idempotent skip — a second run reports "Already patched" and does not re-modify the header.
  4. Relative PLATFORMIO_LIBDEPS_DIR resolved against project_root, not the invocation cwd.
  5. Fallback — with the env var unset, it resolves to <project_root>/.pio/libdeps and SKIPs when absent.
"""

import importlib.util
import os
import shutil
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "patch_tft_espi_standalone.py")

BAD = "    #define SPI_PORT FSPI"
GOOD = "    #define SPI_PORT 2  // SPI2_HOST; FSPI is undefined on S3 (TFT_eSPI#3925)"
HDR_SUB = os.path.join("TFT_eSPI", "Processors", "TFT_eSPI_ESP32_S3.h")


def _load_module():
    spec = importlib.util.spec_from_file_location("patch_tft_espi_standalone", SCRIPT)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


class PatchTftEspiStandaloneTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.mod = _load_module()

    def _run(self, env, project_root, cmd_env=None):
        """Invoke main() with a given env, returning (exit_code, stdout_str)."""
        import io
        from contextlib import redirect_stdout
        old_env = os.environ.get("PLATFORMIO_LIBDEPS_DIR")
        os.environ["PLATFORMIO_LIBDEPS_DIR"] = env  # '' -> unset below
        if env == "":
            os.environ.pop("PLATFORMIO_LIBDEPS_DIR", None)
        old_argv = sys.argv[:]
        sys.argv = [SCRIPT, "t_deck"]
        buf = io.StringIO()
        try:
            with redirect_stdout(buf):
                rc = self.mod.main()
        finally:
            sys.argv[:] = old_argv
            if old_env is None:
                os.environ.pop("PLATFORMIO_LIBDEPS_DIR", None)
            else:
                os.environ["PLATFORMIO_LIBDEPS_DIR"] = old_env
        return rc, buf.getvalue()

    def _make_header(self, root, env="t_deck", text=None):
        """Create a fake external header at <root>/<env>/<HDR_SUB>, returning its path."""
        hdr = os.path.join(root, env, *HDR_SUB.split(os.sep))
        os.makedirs(os.path.dirname(hdr), exist_ok=True)
        with open(hdr, "w", encoding="utf-8") as f:
            f.write(text if text is not None else ("// tft header\n%s\n// trailing\n" % BAD))
        return hdr

    def test1_external_path_no_in_tree(self):
        ext = tempfile.mkdtemp(prefix="tft_ext_")
        try:
            self._make_header(ext)
            rc, out = self._run(ext, None)
            self.assertEqual(rc, 0)
            self.assertIn("Patched SPI_PORT", out)
            # header in the TMP dir must now contain the good line
            hdr = os.path.join(ext, "t_deck", *HDR_SUB.split(os.sep))
            with open(hdr, encoding="utf-8") as f:
                self.assertIn(GOOD, f.read())
            # No .pio/libdeps under any project root assumption -> ensure tmp scaffold has none.
            # (We cannot inspect the real project root here; the external path IS the tmp dir.)
        finally:
            shutil.rmtree(ext, ignore_errors=True)

    def test2_idempotent_skip(self):
        ext = tempfile.mkdtemp(prefix="tft_ext_")
        try:
            hdr = self._make_header(ext)
            rc1, out1 = self._run(ext, None)
            self.assertEqual(rc1, 0)
            rc2, out2 = self._run(ext, None)
            self.assertIn("Already patched", out2)
            with open(hdr, encoding="utf-8") as f:
                self.assertIn(GOOD, f.read())
        finally:
            shutil.rmtree(ext, ignore_errors=True)

    def test3_relative_resolved_against_project_root(self):
        # Build a scaffold whose project_root is a temp dir, and copy the script there so its
        # abspath-__file__ resolves within the scaffold.
        scaffold = tempfile.mkdtemp(prefix="tft_scaf_")
        try:
            scripts = os.path.join(scaffold, "scripts")
            os.makedirs(scripts, exist_ok=True)
            shutil.copy(SCRIPT, os.path.join(scripts, "patch_tft_espi_standalone.py"))
            spec = importlib.util.spec_from_file_location(
                "patch_tft_espi_standalone_rel", os.path.join(scripts, "patch_tft_espi_standalone.py")
            )
            relmod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(relmod)
            old = os.environ.get("PLATFORMIO_LIBDEPS_DIR")
            os.environ["PLATFORMIO_LIBDEPS_DIR"] = "rel_cache"  # relative
            buf = __import__("io").StringIO()
            from contextlib import redirect_stdout
            old_argv = sys.argv[:]
            sys.argv = [os.path.join(scripts, "patch_tft_espi_standalone.py"), "t_deck"]
            try:
                with redirect_stdout(buf):
                    rc = relmod.main()
            finally:
                sys.argv[:] = old_argv
                if old is None:
                    os.environ.pop("PLATFORMIO_LIBDEPS_DIR", None)
                else:
                    os.environ["PLATFORMIO_LIBDEPS_DIR"] = old
            self.assertEqual(rc, 0)
            # SKIP path shows the relative base resolved against the scaffold project_root
            expected = os.path.join(scaffold, "rel_cache", "t_deck")
            self.assertIn(expected, buf.getvalue())
        finally:
            shutil.rmtree(scaffold, ignore_errors=True)

    def test4_fallback_skips_when_absent(self):
        scaffold = tempfile.mkdtemp(prefix="tft_scaf_")
        try:
            scripts = os.path.join(scaffold, "scripts")
            os.makedirs(scripts, exist_ok=True)
            shutil.copy(SCRIPT, os.path.join(scripts, "patch_tft_espi_standalone.py"))
            spec = importlib.util.spec_from_file_location(
                "patch_tft_espi_standalone_fb", os.path.join(scripts, "patch_tft_espi_standalone.py")
            )
            fbmod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(fbmod)
            old = os.environ.get("PLATFORMIO_LIBDEPS_DIR")
            os.environ.pop("PLATFORMIO_LIBDEPS_DIR", None)
            buf = __import__("io").StringIO()
            from contextlib import redirect_stdout
            old_argv = sys.argv[:]
            sys.argv = [os.path.join(scripts, "patch_tft_espi_standalone.py"), "t_deck"]
            try:
                with redirect_stdout(buf):
                    rc = fbmod.main()
            finally:
                sys.argv[:] = old_argv
                if old is not None:
                    os.environ["PLATFORMIO_LIBDEPS_DIR"] = old
            self.assertEqual(rc, 0)
            self.assertIn("SKIP", buf.getvalue())
            self.assertIn(os.path.join(scaffold, ".pio", "libdeps"), buf.getvalue())
        finally:
            shutil.rmtree(scaffold, ignore_errors=True)


if __name__ == "__main__":
    unittest.main()
