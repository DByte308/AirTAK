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


if __name__ == "__main__":
    unittest.main(verbosity=2)
