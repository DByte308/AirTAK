#!/usr/bin/env python3
# AirTAK — tools/test_mbtiles_migrate.py
# Offline tests for the metadata-only bounds migration tool.
#
# These build a tiny synthetic archive, run migrate_mbtiles_bounds.migrate on a copy, and assert the
# tiles table is byte-identical (via the ordered-tile checksum), the row/zoom/byte totals are
# unchanged, every metadata row other than 'bounds' is unchanged, and bounds is re-ordered to W,S,E,N.

import importlib.util
import hashlib
import os
import sqlite3
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("migrate_mbtiles_bounds",
                                               os.path.join(HERE, "migrate_mbtiles_bounds.py"))
mg = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mg)

PNG = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + bytes(16)


def _make(tmpdir: str, bounds: str) -> str:
    path = os.path.join(tmpdir, "src.mbtiles")
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE metadata (name TEXT, value TEXT, PRIMARY KEY(name))")
    conn.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB, PRIMARY KEY(zoom_level, tile_column, tile_row))")
    for k, v in {"format": "png", "bounds": bounds, "minzoom": "0", "maxzoom": "6",
                 "type": "overlay", "version": "1"}.items():
        conn.execute("INSERT OR REPLACE INTO metadata(name, value) VALUES (?, ?)", (k, v))
    for z in range(0, 3):
        for x in range(0, 2 ** z):
            for y in range(0, 2 ** z):
                conn.execute("INSERT INTO tiles VALUES (?,?,?,?)", (z, x, y, sqlite3.Binary(PNG)))
    conn.commit()
    conn.close()
    return path


def _tile_checksum(conn: sqlite3.Connection) -> str:
    h = hashlib.sha256()
    for (z, x, y, d) in conn.execute(
            "SELECT zoom_level, tile_column, tile_row, tile_data "
            "FROM tiles ORDER BY zoom_level, tile_column, tile_row"):
        h.update(b"%d/%d/%d/" % (z, x, y))
        h.update(d if d is not None else b"")
    return h.hexdigest()


class MigrateTest(unittest.TestCase):
    def setUp(self):
        self._dir = tempfile.mkdtemp()

    def tearDown(self):
        import shutil
        shutil.rmtree(self._dir, ignore_errors=True)

    def test_migrate_reorders_bounds_and_preserves_tiles(self):
        src = _make(self._dir, "-85.05,-180,85.05,180")  # legacy CLI order (minLat,minLon,maxLat,maxLon)
        dst = os.path.join(self._dir, "out.mbtiles")

        evidence = mg.migrate(src, dst)
        self.assertEqual("-180,-85.05,180,85.05", evidence["new_bounds"])
        self.assertTrue(evidence["tiles_untouched"])
        self.assertTrue(evidence["meta_untouched"])

        check = sqlite3.connect(dst)
        try:
            self.assertEqual("-180,-85.05,180,85.05",
                             check.execute("SELECT value FROM metadata WHERE name='bounds'").fetchone()[0])
            # tiles table must be byte-identical to the source
            src_conn = sqlite3.connect(src)
            try:
                self.assertEqual(_tile_checksum(src_conn), _tile_checksum(check))
            finally:
                src_conn.close()
        finally:
            check.close()

    def test_migrate_does_not_mutate_source(self):
        src = _make(self._dir, "-85.05,-180,85.05,180")
        src_before = _tile_checksum(sqlite3.connect(src))
        dst = os.path.join(self._dir, "out.mbtiles")
        mg.migrate(src, dst)
        self.assertEqual(src_before, _tile_checksum(sqlite3.connect(src)))
        # source bounds unchanged
        c = sqlite3.connect(src)
        try:
            self.assertEqual("-85.05,-180,85.05,180",
                             c.execute("SELECT value FROM metadata WHERE name='bounds'").fetchone()[0])
        finally:
            c.close()

    def test_migrate_refuses_same_file(self):
        src = _make(self._dir, "-85.05,-180,85.05,180")
        with self.assertRaises(SystemExit):
            mg.migrate(src, src)

    def test_migrate_preserves_other_metadata_rows(self):
        src = _make(self._dir, "-85.05,-180,85.05,180")
        dst = os.path.join(self._dir, "out.mbtiles")
        mg.migrate(src, dst)
        c = sqlite3.connect(dst)
        try:
            meta = {r[0]: r[1] for r in c.execute("SELECT name, value FROM metadata")}
            self.assertEqual("png", meta["format"])
            self.assertEqual("0", meta["minzoom"])
            self.assertEqual("6", meta["maxzoom"])
            self.assertEqual("overlay", meta["type"])
            self.assertEqual("1", meta["version"])
        finally:
            c.close()

    def test_migrate_reports_malformed_bounds(self):
        src = _make(self._dir, "not-a-quad")
        dst = os.path.join(self._dir, "out.mbtiles")
        with self.assertRaises(SystemExit):
            mg.migrate(src, dst)


if __name__ == "__main__":
    unittest.main(verbosity=2)
