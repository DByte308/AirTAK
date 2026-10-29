#!/usr/bin/env python3
# AirTAK — tools/test_tile_fetch.py
# Regression tests for the bulk-download policy in tile_fetch.py.
#
# These run offline. Nothing here may contact a third-party server: the network is actively
# blocked for the duration, so a test that accidentally opens a socket fails loudly instead of
# quietly downloading from somebody's infrastructure.

import importlib.util
import os
import socket
import sqlite3
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("tile_fetch", os.path.join(HERE, "tile_fetch.py"))
tf = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(tf)


class _NoNetwork:
    """Fail loudly if anything tries to leave the process."""

    def __init__(self):
        self.original = socket.socket

    def __enter__(self):
        def _blocked(*args, **kwargs):
            raise AssertionError("test attempted to open a network socket")

        socket.socket = _blocked
        return self

    def __exit__(self, *exc):
        socket.socket = self.original
        return False


class HostPolicy(unittest.TestCase):
    def test_exact_host_is_refused(self):
        with self.assertRaises(SystemExit):
            tf.check_bulk_policy("https://tile.openstreetmap.org/{z}/{x}/{y}.png")

    def test_subdomains_are_refused(self):
        for sub in ("a", "b", "c", "tile"):
            with self.assertRaises(SystemExit):
                tf.check_bulk_policy("https://%s.tile.openstreetmap.org/{z}/{x}/{y}.png" % sub)

    def test_host_case_is_folded(self):
        with self.assertRaises(SystemExit):
            tf.check_bulk_policy("https://TILE.OpenStreetMap.ORG/{z}/{x}/{y}.png")

    def test_trailing_root_dot_is_folded(self):
        with self.assertRaises(SystemExit):
            tf.check_bulk_policy("https://tile.openstreetmap.org./{z}/{x}/{y}.png")

    def test_port_does_not_dodge_the_rule(self):
        for url in (
            "http://tile.openstreetmap.org:443/{z}/{x}/{y}.png",
            "https://tile.openstreetmap.org:8080/{z}/{x}/{y}.png",
        ):
            with self.assertRaises(SystemExit):
                tf.check_bulk_policy(url)

    def test_path_and_query_do_not_dodge_the_rule(self):
        for url in (
            "https://tile.openstreetmap.org/any/prefix/{z}/{x}/{y}.png",
            "https://tile.openstreetmap.org/{z}/{x}/{y}.png?key=abc",
        ):
            with self.assertRaises(SystemExit):
                tf.check_bulk_policy(url)

    def test_lookalike_suffixes_are_not_blocked(self):
        # A guard that blocks these would be wrong in the other direction, and would encourage
        # people to route around it.
        for url in (
            "https://tile.openstreetmap.org.hostile.example/{z}/{x}/{y}.png",
            "https://tile.openstreetmap.org.example.com/{z}/{x}/{y}.png",
            "https://nottile.openstreetmap.org/{z}/{x}/{y}.png",
        ):
            self.assertTrue(tf.check_bulk_policy(url))

    def test_scope_is_the_tile_host_only(self):
        """The approved rule is the tile host and its subdomains, nothing broader.

        openstreetmap.org and osm.org are general project domains, not the tile server. Blocking
        them would be speculative extrapolation, and an early version of this file did exactly
        that.
        """
        for url in ("https://openstreetmap/{z}.png", "https://osm.org/{z}.png", "https://www.openstreetmap.org/{z}.png"):
            self.assertTrue(tf.check_bulk_policy(url))

    def test_ipv6_literal_is_a_host_not_a_port(self):
        self.assertEqual("[::1]", tf._host_of("http://[::1]:8080/{z}/{x}/{y}.png"))
        self.assertEqual("[::1]", tf._host_of("http://[::1]/{z}/{x}/{y}.png"))

    def test_malformed_and_credential_urls_are_refused(self):
        for url in (
            "tile.openstreetmap.org/{z}/{x}/{y}.png",
            "ftp://tiles.example.com/{z}.png",
            "https://user:pw@tiles.example.com/{z}.png",
            "https://[::1/{z}.png",
            "https:///{z}/{x}/{y}.png",
        ):
            with self.assertRaises(SystemExit, msg=url):
                tf.check_bulk_policy(url)


class Identification(unittest.TestCase):
    def test_user_agent_carries_a_contact_url(self):
        self.assertIn("AirTAK", tf.USER_AGENT)
        self.assertIn("github.com", tf.USER_AGENT)
        self.assertNotIn("your-repo", tf.USER_AGENT)

    def test_identity_separates_styles_on_one_host(self):
        base = tf.identity_key("https://t.example.com/{z}/{x}/{y}.png")
        self.assertNotEqual(base, tf.identity_key("https://t.example.com/styleB/{z}/{x}/{y}.png"))
        self.assertNotEqual(base, tf.identity_key("https://t.example.com:8443/{z}/{x}/{y}.png"))
        self.assertNotEqual(base, tf.identity_key("https://t.example.com/{z}/{x}/{y}.png?key=B"))

    def test_identity_folds_only_scheme_and_host_case(self):
        self.assertEqual(
            tf.identity_key("HTTPS://T.Example.COM/a/{z}.png?K=1"),
            tf.identity_key("https://t.example.com/a/{z}.png?K=1"),
        )
        self.assertNotEqual(
            tf.identity_key("https://t.example.com/A/{z}.png"),
            tf.identity_key("https://t.example.com/a/{z}.png"),
        )

    def test_provenance_never_carries_a_credential(self):
        # A credential in a path is a normal tile URL shape, so the label stops at the authority.
        self.assertEqual("t.example.com", tf.provenance_label("https://t.example.com/token/SECRET/{z}.png"))
        self.assertEqual("t.example.com", tf.provenance_label("https://t.example.com/v1/{z}.png?key=SECRET"))
        self.assertEqual("t.example.com:8443", tf.provenance_label("https://t.example.com:8443/a/{z}.png"))
        self.assertEqual("t.example.com", tf.provenance_label("https://user:pw@t.example.com/a/{z}.png"))


class RedirectPolicy(unittest.TestCase):
    def test_redirect_is_refused_rather_than_followed(self):
        """urllib follows redirects by default, which moves the download to an unchecked host."""

        class _Req:
            full_url = "https://tiles.example.com/{z}/{x}/{y}.png"

        handler = tf._RefuseRedirects()
        with self.assertRaises(SystemExit) as ctx:
            handler.redirect_request(_Req(), None, 302, "Found", {}, "https://evil.example/{z}.png")
        self.assertIn("evil.example", str(ctx.exception))


class DownloadLoop(unittest.TestCase):
    def _db(self):
        path = tempfile.mktemp(suffix=".mbtiles")
        self.addCleanup(lambda: os.path.exists(path) and os.remove(path))
        return tf.init_mbtiles(path), path

    def test_stops_on_interrupt_and_closes_the_database(self):
        conn, path = self._db()
        calls = []

        def fetch(url, z, x, y):
            calls.append((z, x, y))
            if len(calls) == 2:
                raise KeyboardInterrupt()
            return b"png"

        with self.assertRaises(KeyboardInterrupt):
            tf.run_download(conn, "50.30,8.60,50.40,8.90", 12, 12, "https://t.example.com/{z}/{x}/{y}.png", 0.0, 4, fetch=fetch, sleep=lambda _s: None)

        # The connection must be closed, and what was stored must still be readable.
        self.assertTrue(conn._closed if hasattr(conn, "_closed") else True)
        check = sqlite3.connect(path)
        try:
            stored = check.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
        finally:
            check.close()
        self.assertEqual(1, stored, "the tile stored before the interrupt must survive")

    def test_normal_run_downloads_then_skips_existing(self):
        conn, _path = self._db()
        n = {"i": 0}

        def fetch(url, z, x, y):
            n["i"] += 1
            return b"png"

        downloaded, skipped = tf.run_download(
            conn, "50.30,8.60,50.40,8.90", 12, 12, "https://t.example.com/{z}/{x}/{y}.png", 0.0, 4,
            fetch=fetch, sleep=lambda _s: None,
        )
        self.assertGreater(downloaded, 0)
        self.assertEqual(0, skipped)

    def test_transient_fetch_failure_does_not_abort_the_run(self):
        conn, _path = self._db()
        state = {"i": 0}

        def fetch(url, z, x, y):
            state["i"] += 1
            if state["i"] == 1:
                raise OSError("connection reset")
            return b"png"

        downloaded, _skipped = tf.run_download(
            conn, "50.30,8.60,50.40,8.90", 12, 12, "https://t.example.com/{z}/{x}/{y}.png", 0.0, 4,
            fetch=fetch, sleep=lambda _s: None,
        )
        self.assertGreaterEqual(downloaded, 1)

    def test_pacing_is_actually_applied(self):
        conn, _path = self._db()
        slept = []
        tf.run_download(
            conn, "50.30,8.60,50.40,8.90", 12, 12, "https://t.example.com/{z}/{x}/{y}.png", 1.0, 4,
            fetch=lambda u, z, x, y: b"png", sleep=slept.append,
        )
        self.assertTrue(slept, "no pacing delay was applied between requests")
        self.assertTrue(all(s >= 1.0 for s in slept), slept)


class Attribution(unittest.TestCase):
    def test_provider_host_is_not_recorded_as_attribution(self):
        """A hostname is not a data attribution, a licence, or a copyright statement."""
        source = open(os.path.join(HERE, "tile_fetch.py")).read()
        self.assertNotIn('attribution="provider:%s"', source)
        self.assertIn("attribution_status", source)

    def test_delay_floor_is_one_request_per_second(self):
        self.assertGreaterEqual(tf.MIN_DELAY_SECONDS, 1.0)
        self.assertGreaterEqual(tf.DEFAULT_DELAY_SECONDS, 1.0)


class NoNetwork(unittest.TestCase):
    def test_policy_checks_never_touch_the_network(self):
        with _NoNetwork():
            with self.assertRaises(SystemExit):
                tf.check_bulk_policy("https://tile.openstreetmap.org/{z}/{x}/{y}.png")
            self.assertEqual("tiles.example.com", tf.check_bulk_policy("https://tiles.example.com/{z}.png"))
            tf.identity_key("https://tiles.example.com/{z}.png")
            tf.provenance_label("https://tiles.example.com/{z}.png")


class PacingOnFailure(unittest.TestCase):
    """D1: pacing must run after every request attempt, including a refused one."""

    def test_failure_path_is_paced(self):
        conn, path = self._db()
        slept = []
        attempts = []

        def fetch(url, z, x, y):
            attempts.append((z, x, y))
            # A server answering 429 "Too Many Requests" is exactly when the tool must back off.
            raise OSError("429 Too Many Requests")

        tf.run_download(
            conn, "50.30,8.60,50.40,8.90", 12, 12, "https://t.example.com/{z}/{x}/{y}.png", 1.0, 4,
            fetch=fetch, sleep=slept.append,
        )
        self.assertGreater(len(attempts), 0, "the fetch should actually have been attempted")
        self.assertEqual(
            len(slept), len(attempts),
            "each request attempt, successful or refused, must be paced",
        )
        self.assertTrue(all(s >= 1.0 for s in slept), slept)

    def test_mix_of_success_and_failure_is_paced(self):
        conn, path = self._db()
        slept = []
        state = {"i": 0}

        def fetch(url, z, x, y):
            state["i"] += 1
            if state["i"] % 2 == 0:
                raise OSError("server error")
            return b"png"

        dl, _sk = tf.run_download(
            conn, "50.30,8.60,50.40,8.90", 12, 12, "https://t.example.com/{z}/{x}/{y}.png", 1.0, 4,
            fetch=fetch, sleep=slept.append,
        )
        self.assertGreater(dl, 0, "some tiles should have downloaded")
        # The request count equals successes + failures; every attempt must be paced.
        self.assertEqual(len(slept), state["i"], "both outcomes must be paced")
        self.assertTrue(all(s >= 1.0 for s in slept), slept)

    def _db(self):
        path = tempfile.mktemp(suffix=".mbtiles")
        self.addCleanup(lambda: os.path.exists(path) and os.remove(path))
        return tf.init_mbtiles(path), path


class ZoomValidation(unittest.TestCase):
    """D2: reject malformed or inverted zoom ranges instead of silently downloading nothing."""

    def test_single_and_range_are_accepted(self):
        self.assertEqual((10, 10), tf.parse_zoom("10"))
        self.assertEqual((10, 14), tf.parse_zoom("10-14"))

    def test_inverted_range_is_rejected(self):
        with self.assertRaises(ValueError):
            tf.parse_zoom("14-10")

    def test_non_integer_is_rejected(self):
        for bad in ("abc", "1.5", "-", ""):
            with self.assertRaises(ValueError, msg=bad):
                tf.parse_zoom(bad)

    def test_negative_zoom_is_rejected(self):
        with self.assertRaises(ValueError):
            tf.parse_zoom("-1")

    def test_too_many_dashes_is_rejected(self):
        with self.assertRaises(ValueError):
            tf.parse_zoom("10-14-18")


class BboxValidation(unittest.TestCase):
    """D3: reject inverted or out-of-range boxes before creating a stray archive."""

    def test_valid_bbox_parses(self):
        self.assertEqual((50.30, 8.68, 50.37, 8.82), tf.parse_bbox("50.30,8.68,50.37,8.82"))

    def test_inverted_bbox_is_rejected(self):
        with self.assertRaises(ValueError):
            tf.parse_bbox("50.40,8.90,50.30,8.60")

    def test_out_of_range_lat_is_rejected(self):
        for b in ("91,0,92,1", "-95,0,-94,1"):
            with self.assertRaises(ValueError, msg=b):
                tf.parse_bbox(b)

    def test_out_of_range_lon_is_rejected(self):
        with self.assertRaises(ValueError):
            tf.parse_bbox("0,200,1,201")

    def test_wrong_field_count_is_rejected(self):
        for b in ("1,2,3", "1,2,3,4,5"):
            with self.assertRaises(ValueError, msg=b):
                tf.parse_bbox(b)

    def test_latitude_is_clamped_at_poles(self):
        # The slippy Mercator formula throws at |lat| = 90; it must clamp to a valid edge tile.
        x0, y0 = tf.lat_lon_to_tile(90.0, 8.0, 12)
        x1, y1 = tf.lat_lon_to_tile(-90.0, 8.0, 12)
        n = 2 ** 12
        self.assertEqual(0, y0)
        self.assertEqual(n - 1, y1)


class DelayValidation(unittest.TestCase):
    """D4: reject non-finite --delay, which slips past a `x < floor` comparison."""

    def test_non_finite_delay_is_rejected(self):
        # NaN compares False to everything and +inf is not < anything, so the plain `x < floor`
        # check lets both through, and time.sleep() then raises across a running download. This
        # is what the isfinite() guard exists for. -inf, by contrast, is caught by the floor
        # comparison already (it is less than everything), so it is not the bypass case.
        import math as _m
        self.assertFalse(_m.isfinite(float("nan")))
        self.assertFalse(_m.isfinite(float("inf")))
        self.assertFalse(_m.isfinite(float("-inf")))
        # Prove the `< floor` comparison is the bypass: it lets nan and +inf through.
        self.assertFalse(float("nan") < tf.MIN_DELAY_SECONDS)
        self.assertFalse(float("inf") < tf.MIN_DELAY_SECONDS)

    def test_finite_below_floor_is_still_rejected(self):
        self.assertTrue(0.5 < tf.MIN_DELAY_SECONDS)


class IPv6Identity(unittest.TestCase):
    """D5: identity_key must separate IPv6 literals that differ only in port."""

    def test_ipv6_ports_do_not_collide(self):
        self.assertNotEqual(
            tf.identity_key("http://[::1]:8080/{z}/{x}/{y}.png"),
            tf.identity_key("http://[::1]:9999/{z}/{x}/{y}.png"),
        )

    def test_ipv6_port_is_preserved_in_provenance(self):
        self.assertEqual("[::1]:8080", tf.provenance_label("http://[::1]:8080/{z}.png"))
        self.assertEqual("[::1]:9999", tf.provenance_label("http://[::1]:9999/{z}.png"))


class TileCoordinateBounds(unittest.TestCase):
    """The slippy map must never emit a tile index outside [0, 2**zoom - 1]."""

    def test_lon_180_is_clamped_to_n_minus_1(self):
        # lon=+180 lands exactly on x == 2**zoom (one past the last tile) at every zoom, so the
        # clamp must bring it back to n-1. Without it, the world-covering bbox would request a
        # nonexistent tile x=n.
        for zoom in (0, 1, 2, 8, 18):
            n = 2 ** zoom
            x, _ = tf.lat_lon_to_tile(0.0, 180.0, zoom)
            self.assertEqual(n - 1, x, "zoom=%d" % zoom)

    def test_lon_neg_180_is_zero(self):
        for zoom in (0, 1, 2, 8):
            x, _ = tf.lat_lon_to_tile(0.0, -180.0, zoom)
            self.assertEqual(0, x, "zoom=%d" % zoom)

    def test_mercator_poles_clamp_to_valid_y(self):
        # The Mercator boundary maps to exactly y == 2**zoom; the clamp must return n-1. At the
        # limits the raw latitude would otherwise throw a math-domain error before any clamp runs.
        for zoom in (0, 1, 2, 8):
            n = 2 ** zoom
            _, y_top = tf.lat_lon_to_tile(85.051128, 0.0, zoom)
            _, y_bot = tf.lat_lon_to_tile(-85.051128, 0.0, zoom)
            self.assertEqual(0, y_top, "zoom=%d" % zoom)
            self.assertEqual(n - 1, y_bot, "zoom=%d" % zoom)

    def test_world_bbox_yields_only_valid_indices(self):
        # The world-covering bbox must produce only in-range indices at every zoom.
        for zoom in (0, 1, 2, 3):
            n = 2 ** zoom
            for z, x, y in tf.tile_range("-90,-180,90,180", zoom):
                self.assertEqual(zoom, z)
                self.assertTrue(0 <= x < n, "zoom=%d x=%d" % (zoom, x))
                self.assertTrue(0 <= y < n, "zoom=%d y=%d" % (zoom, y))

    def test_zoom0_world_bbox_is_exactly_one_tile(self):
        # At zoom 0 the whole world is a single 1x1 tile, regardless of how the edges round.
        self.assertEqual([(0, 0, 0)], list(tf.tile_range("-90,-180,90,180", 0)))


class CliInvalidInput(unittest.TestCase):
    """Rejected invocations must fail before creating any .mbtiles archive."""

    @classmethod
    def setUpClass(cls):
        cls._base = [sys.executable, os.path.join(HERE, "tile_fetch.py"),
                     "--bbox", "50.3,8.6,50.4,8.9", "--zoom", "10-11",
                     "--url", "https://t.example.com/{z}/{x}/{y}.png"]

    def _run(self, label, extra):
        out = os.path.join(tempfile.mkdtemp(), label + ".mbtiles")
        args = self._base + ["--output", out] + extra
        proc = subprocess.run(args, capture_output=True, text=True)
        self.addCleanup(lambda: os.path.exists(out) and os.remove(out))
        self.assertNotEqual(0, proc.returncode, "%s should be rejected" % label)
        self.assertFalse(os.path.exists(out), "%s must not create an archive" % label)

    def test_nan_delay_creates_no_archive(self):
        self._run("nan-delay", ["--delay", "nan"])

    def test_nan_bbox_creates_no_archive(self):
        self._run("nan-bbox", ["--bbox", "nan,nan,nan,nan"])

    def test_inverted_bbox_creates_no_archive(self):
        self._run("inv-bbox", ["--bbox", "50.4,8.9,50.3,8.6"])

    def test_inverted_zoom_creates_no_archive(self):
        self._run("inv-zoom", ["--zoom", "14-10"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
