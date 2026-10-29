#!/usr/bin/env python3
# AirTAK — tools/test_mbtiles_verify.py
# Offline fixture-based tests for the read-only MBTiles verifier.
#
# These build tiny synthetic archives in a temp dir with sqlite3. No network, no downloads, and no
# dependency on tile_fetch.py or tilemaker. The real bundled world-overview.mbtiles (28 MB) is
# deliberately not a test fixture: synthetic archives are small, deterministic, and can be driven
# to exactly the state each check is meant to catch.

import importlib.util
import os
import sqlite3
import sys
import tempfile
import unittest
from typing import List, Optional

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("verify_mbtiles", os.path.join(HERE, "verify_mbtiles.py"))
vm = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(vm)

# Full 8-byte PNG signature.
PNG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + bytes(16)
JPEG = bytes([0xFF, 0xD8, 0xFF, 0xE0]) + bytes(8)
WEBP = bytes([0x52, 0x49, 0x46, 0x46]) + bytes([0x24, 0x00, 0x00, 0x00]) + b"WEBP" + bytes(8)

DEFAULT_BOUNDS = "-180,-85.05,180,85.05"  # W,S,E,N (lon,lat,lon,lat)


def _build(tmpdir: str, *, format: str = "png", table: bool = True,
           rows: Optional[List[tuple]] = None, metadata: Optional[dict] = None,
           name: str = "x.mbtiles") -> str:
    """Create a synthetic MBTiles archive and return its path."""
    path = os.path.join(tmpdir, name)
    conn = sqlite3.connect(path)
    try:
        if table:
            conn.execute("CREATE TABLE metadata (name TEXT, value TEXT, PRIMARY KEY(name))")
            conn.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
            for k, v in (metadata or {}).items():
                conn.execute("INSERT OR REPLACE INTO metadata(name, value) VALUES (?, ?)", (k, v))
            if rows is not None:
                for z, x, y, data in rows:
                    # tile_data may legitimately be NULL; store bytes or None directly.
                    blob = sqlite3.Binary(data) if data is not None else None
                    conn.execute("INSERT INTO tiles VALUES (?,?,?,?)", (z, x, y, blob))
        else:
            # Views cannot be parameterised in SQLite, so interpolate literals. These are test-
            # controlled constants (format/bounds name and a PNG magic blob), never user input.
            conn.execute("CREATE VIEW metadata AS "
                         "SELECT 'format' AS name, '" + str(format) + "' AS value "
                         "UNION ALL SELECT 'bounds', '" + DEFAULT_BOUNDS + "'")
            conn.execute("CREATE VIEW tiles AS SELECT 0 AS zoom_level, 0 AS tile_column, 0 AS tile_row, X'89504E470D0A1A0A' AS tile_data")
        conn.commit()
    finally:
        conn.close()
    return path


class FixtureTestCase(unittest.TestCase):
    def setUp(self):
        self._dir = tempfile.mkdtemp()
        self.addCleanup(lambda: _rmtree(self._dir))

    def assert_fails(self, *, problems_substr=None, metadata=None, rows=None, **kw):
        ok, problems, _ = vm.verify_archive_with_notes(
            _build(self._dir, metadata=metadata or {}, rows=rows, **kw))
        self.assertFalse(ok, "expected a failure but archive passed")
        if problems_substr:
            self.assertTrue(any(problems_substr in p for p in problems),
                            "expected a problem containing %r; got %s" % (problems_substr, problems))


def _rmtree(path: str) -> None:
    import shutil
    shutil.rmtree(path, ignore_errors=True)


class GoodFixture(FixtureTestCase):
    def make_good(self, **kw) -> str:
        meta = {"format": "png", "minzoom": "0", "maxzoom": "0", "bounds": DEFAULT_BOUNDS,
                "name": "fixture.mbtiles"}
        meta.update(kw.pop("metadata", {}) or {})
        rows = kw.pop("rows", [(0, 0, 0, PNG)])
        return _build(self._dir, metadata=meta, rows=rows, **kw)

    def test_good_png_passes(self):
        ok, problems, _ = vm.verify_archive_with_notes(self.make_good())
        self.assertTrue(ok, problems)
        self.assertEqual([], problems)

    def test_good_jpeg_passes(self):
        ok, problems, _ = vm.verify_archive_with_notes(
            self.make_good(format="jpeg", metadata={"format": "jpeg"}, rows=[(0, 0, 0, JPEG)]))
        self.assertTrue(ok, problems)

    def test_good_webp_passes(self):
        ok, problems, _ = vm.verify_archive_with_notes(
            self.make_good(format="webp", metadata={"format": "webp"}, rows=[(0, 0, 0, WEBP)]))
        self.assertTrue(ok, problems)

    def test_missing_minmax_zoom_is_inferred(self):
        path = _build(self._dir, metadata={"format": "png", "bounds": DEFAULT_BOUNDS, "name": "n"},
                      rows=[(3, 1, 2, PNG)])
        ok, problems, _ = vm.verify_archive_with_notes(path)
        self.assertTrue(ok, problems)

    def test_missing_optional_name_is_a_note_not_a_failure(self):
        path = _build(self._dir, metadata={"format": "png", "bounds": DEFAULT_BOUNDS},
                      rows=[(0, 0, 0, PNG)])
        ok, problems, notes = vm.verify_archive_with_notes(path)
        self.assertTrue(ok, problems)
        self.assertTrue(any("name" in n for n in notes), notes)

    def test_missing_bounds_is_a_note_not_a_failure(self):
        # bounds is optional generally (AirTAK derives area from the UI bbox); absence is a note.
        path = _build(self._dir, metadata={"format": "png", "name": "n"},
                      rows=[(0, 0, 0, PNG)])
        ok, problems, notes = vm.verify_archive_with_notes(path)
        self.assertTrue(ok, problems)
        self.assertTrue(any("bounds" in n for n in notes), notes)

    def test_valid_wsen_bounds_passes(self):
        ok, problems, _ = vm.verify_archive_with_notes(
            self.make_good(metadata={"bounds": "8.68,50.30,8.82,50.37"}))
        self.assertTrue(ok, problems)


class BadSchema(FixtureTestCase):
    def test_missing_metadata_relation(self):
        path = os.path.join(self._dir, "no_metadata.mbtiles")
        conn = sqlite3.connect(path)
        conn.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        conn.commit(); conn.close()
        ok, problems, _ = vm.verify_archive_with_notes(path)
        self.assertFalse(ok)
        self.assertTrue(any("metadata" in p for p in problems))

    def test_missing_tiles_relation(self):
        path = os.path.join(self._dir, "no_tiles.mbtiles")
        conn = sqlite3.connect(path)
        conn.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
        conn.commit(); conn.close()
        ok, problems, _ = vm.verify_archive_with_notes(path)
        self.assertFalse(ok)
        self.assertTrue(any("tiles" in p for p in problems))

    def test_tile_relation_missing_required_column(self):
        path = os.path.join(self._dir, "bad_cols.mbtiles")
        conn = sqlite3.connect(path)
        conn.execute("CREATE TABLE metadata (name TEXT, value TEXT)")
        conn.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_data BLOB)")
        conn.commit(); conn.close()
        ok, problems, _ = vm.verify_archive_with_notes(path)
        self.assertFalse(ok)
        self.assertTrue(any("tile_row" in p for p in problems))

    def test_metadata_missing_name_value_columns(self):
        path = os.path.join(self._dir, "bad_meta_cols.mbtiles")
        conn = sqlite3.connect(path)
        conn.execute("CREATE TABLE metadata (name TEXT)")  # no value column
        conn.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
        conn.commit(); conn.close()
        ok, problems, _ = vm.verify_archive_with_notes(path)
        self.assertFalse(ok)
        self.assertTrue(any("metadata" in p and "value" in p for p in problems))


class BadContent(FixtureTestCase):
    def test_missing_format_metadata_fails(self):
        # Raster compatibility cannot be proven without a declared format; a format-less archive is
        # a hard failure, not a note.
        self.assert_fails(problems_substr="missing 'format'",
                          metadata={"minzoom": "0", "maxzoom": "0", "bounds": DEFAULT_BOUNDS},
                          rows=[(0, 0, 0, PNG)])

    def test_missing_format_with_garbage_blobs_fails(self):
        # Even with format missing, garbage blobs are still flagged (empty/mis-typed data checked
        # independently of the declared-format failure).
        ok, problems, _ = vm.verify_archive_with_notes(_build(
            self._dir, metadata={"bounds": DEFAULT_BOUNDS}, rows=[(0, 0, 0, b"\x00\x11\x22\x33\x44")]))
        self.assertFalse(ok)
        self.assertTrue(any("missing 'format'" in p for p in problems), problems)

    def test_present_unsupported_format_fails(self):
        # A PRESENT but unsupported format (e.g. vector) is a real problem and must fail.
        self.assert_fails(problems_substr="unsupported",
                          metadata={"format": "pbf", "bounds": DEFAULT_BOUNDS},
                          rows=[(0, 0, 0, b"\x00pbf")])

    def test_present_malformed_bounds_fails(self):
        # A PRESENT but malformed bounds cannot be consumed: it is a hard failure. (Missing bounds
        # is a note, covered by GoodFixture.test_missing_bounds_is_a_note_not_a_failure.)
        self.assert_fails(problems_substr="bounds",
                          metadata={"format": "png", "bounds": "not a valid bounds"},
                          rows=[(0, 0, 0, PNG)])

    def test_negative_zoom_is_rejected(self):
        self.assert_fails(problems_substr="outside supported range",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS}, rows=[(-1, 0, 0, PNG)])

    def test_out_of_range_x_is_rejected(self):
        self.assert_fails(problems_substr="out of range",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS}, rows=[(1, 2, 0, PNG)])

    def test_out_of_range_y_is_rejected(self):
        self.assert_fails(problems_substr="out of range",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS}, rows=[(1, 0, 2, PNG)])

    def test_empty_tile_data_is_rejected(self):
        self.assert_fails(problems_substr="empty", metadata={"format": "png", "bounds": DEFAULT_BOUNDS},
                          rows=[(0, 0, 0, b"")])

    def test_wrong_magic_is_rejected(self):
        self.assert_fails(problems_substr="does not match expected png magic",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS}, rows=[(0, 0, 0, JPEG)])

    def test_garbage_blob_is_rejected(self):
        self.assert_fails(problems_substr="does not match expected png magic",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS},
                          rows=[(0, 0, 0, b"\x00\x11\x22\x33\x44")])

    def test_truncated_png_signature_is_rejected(self):
        self.assert_fails(problems_substr="does not match expected png magic",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS},
                          rows=[(0, 0, 0, bytes([0x89, 0x50, 0x4E, 0x47]) + b"\x00")])

    def test_declared_max_below_row_max_is_rejected(self):
        self.assert_fails(problems_substr="maxzoom",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS, "minzoom": "0", "maxzoom": "1"},
                          rows=[(0, 0, 0, PNG), (2, 0, 0, PNG)])

    def test_declared_min_above_row_min_is_rejected(self):
        self.assert_fails(problems_substr="minzoom",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS, "minzoom": "3", "maxzoom": "4"},
                          rows=[(0, 0, 0, PNG)])


class BadCoordinateTypes(FixtureTestCase):
    def test_float_zoom_is_rejected_not_coerced(self):
        self.assert_fails(problems_substr="non-INTEGER",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS}, rows=[(0.5, 0, 0, PNG)])

    def test_string_zoom_is_rejected_not_coerced(self):
        # A genuinely non-numeric text value in an INTEGER column is stored as TEXT (SQLite does
        # not coerce 'abc'); the verifier must reject it rather than int() silently producing 0.
        self.assert_fails(problems_substr="non-INTEGER",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS},
                          rows=[("abc", 0, 0, PNG)])

    def test_null_zoom_is_rejected_not_crash(self):
        self.assert_fails(problems_substr="non-INTEGER",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS}, rows=[(None, 0, 0, PNG)])

    def test_null_tile_data_is_rejected(self):
        self.assert_fails(problems_substr="empty", metadata={"format": "png", "bounds": DEFAULT_BOUNDS},
                          rows=[(0, 0, 0, None)])


class HighZoom(FixtureTestCase):
    def test_absurd_zoom_is_capped_not_blown_up(self):
        self.assert_fails(problems_substr="outside supported range",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS},
                          rows=[(1_000_000_000, 0, 0, PNG)])

    def test_zoom_above_importer_cap_is_rejected(self):
        self.assert_fails(problems_substr="outside supported range",
                          metadata={"format": "png", "bounds": DEFAULT_BOUNDS}, rows=[(23, 0, 0, PNG)])


class BoundsValidation(FixtureTestCase):
    def test_invalid_bounds_text_is_rejected(self):
        self.assert_fails(problems_substr="bounds", metadata={"format": "png", "bounds": "not a valid bounds"},
                          rows=[(0, 0, 0, PNG)])

    def test_inverted_bounds_is_rejected(self):
        self.assert_fails(problems_substr="bounds", metadata={"format": "png", "bounds": "10,10,-10,-10"},
                          rows=[(0, 0, 0, PNG)])

    def test_out_of_range_lon_is_rejected(self):
        self.assert_fails(problems_substr="bounds", metadata={"format": "png", "bounds": "-190,-85,190,85"},
                          rows=[(0, 0, 0, PNG)])

    def test_too_many_bounds_fields_is_rejected(self):
        self.assert_fails(problems_substr="bounds", metadata={"format": "png", "bounds": "1,2,3,4,5"},
                          rows=[(0, 0, 0, PNG)])

    def test_nan_bounds_is_rejected(self):
        self.assert_fails(problems_substr="bounds", metadata={"format": "png", "bounds": "nan,-85,180,85"},
                          rows=[(0, 0, 0, PNG)])


class FormatBehaviour(FixtureTestCase):
    def test_vector_format_is_unsupported_not_corrupt(self):
        ok, problems, _ = vm.verify_archive_with_notes(
            _build(self._dir, metadata={"format": "pbf", "bounds": DEFAULT_BOUNDS}, rows=[(0, 0, 0, b"\x00pbf")]))
        self.assertFalse(ok)
        self.assertTrue(any("unsupported" in p for p in problems))
        self.assertFalse(any("corrupt" in p.lower() for p in problems))

    def test_jpeg_alias_is_accepted(self):
        ok, problems, _ = vm.verify_archive_with_notes(
            _build(self._dir, metadata={"format": "jpg", "bounds": DEFAULT_BOUNDS}, rows=[(0, 0, 0, JPEG)]))
        self.assertTrue(ok, problems)


class ViewsAccepted(FixtureTestCase):
    def test_views_are_accepted_for_metadata_and_tiles(self):
        ok, problems, _ = vm.verify_archive_with_notes(
            _build(self._dir, table=False, metadata={"format": "png"}, rows=[(0, 0, 0, PNG)]))
        self.assertTrue(ok, problems)


class ReadOnly(FixtureTestCase):
    def test_missing_file_is_not_created(self):
        path = os.path.join(self._dir, "does-not-exist.mbtiles")
        ok, problems, _ = vm.verify_archive_with_notes(path)
        self.assertFalse(ok)
        self.assertFalse(os.path.exists(path), "mode=ro must not create a missing file")

    def test_non_sqlite_file_is_diagnosed_not_traceback(self):
        path = os.path.join(self._dir, "not-a-db.mbtiles")
        with open(path, "wb") as f:
            f.write(b"this is not a sqlite database, just text padding padding padding padding")
        ok, problems, _ = vm.verify_archive_with_notes(path)
        self.assertFalse(ok)
        self.assertTrue(any("cannot interrogate" in p for p in problems), problems)

    def test_quoted_path_with_hash_and_query_is_handled(self):
        path = os.path.join(self._dir, "weird #name ?[x].mbtiles")
        conn = sqlite3.connect(path)
        conn.execute("CREATE TABLE metadata (name TEXT, value TEXT, PRIMARY KEY(name))")
        conn.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
        for k, v in {"format": "png", "bounds": DEFAULT_BOUNDS, "name": "n"}.items():
            conn.execute("INSERT OR REPLACE INTO metadata(name, value) VALUES (?, ?)", (k, v))
        conn.execute("INSERT INTO tiles VALUES (0,0,0,?)", (sqlite3.Binary(PNG),))
        conn.commit(); conn.close()
        ok, problems, _ = vm.verify_archive_with_notes(path)
        self.assertTrue(ok, problems)


if __name__ == "__main__":
    unittest.main(verbosity=2)
