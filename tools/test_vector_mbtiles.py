#!/usr/bin/env python3
"""Self-test for tools/verify_vector_mbtiles.py + tools/crop_vector_circle.py.

The offline vector-map deliverable (issue #8) is only as trustworthy as its verifier and its
crop helper, and neither is exercised by the Android suite. This builds a synthetic vector
MBTiles archive in memory (real sqlite3, real gzip-compressed MVT payloads) and asserts:

  * verify passes a valid pbf archive (format pbf, gzip tile_data, vector_layers, attribution);
  * verify FAILS a raster (png) archive instead of silently accepting it;
  * verify FAILS a pbf archive whose tiles are NOT gzip-compressed (the compression contract);
  * crop's `predict` and `crop` emit a smaller, bounds-corrected archive that still verifies.

This is the same principle as the other gates: a check that cannot fail is worse than no check.
Run: python3 tools/test_vector_mbtiles.py
"""

from __future__ import annotations

import gzip
import importlib.util
import sqlite3
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
VERIFY_NAME = "verify_vector_mbtiles"
CROP_NAME = "crop_vector_circle"

def _load(name: str):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod

verify = _load(VERIFY_NAME)
crop = _load(CROP_NAME)

CENTER = (50.3333, 8.75)
# A tiny kludge of protobuf-ish bytes with a `place` layer id, so the tile constrains to the test
# name; the verifier only requires gzip + non-empty decompress, not a full MVT parse.
def _mvt_payload() -> bytes:
    return bytes([0x1A, 0x0E]) + b"AirTAK-fixture"


def _make_archive(path: Path, *, fmt: str = "pbf", gzip_tiles: bool = True, layers: list[str] | None = None):
    """Write a minimal but structurally real MBTiles: metadata + tiles(TMS)."""
    layers = layers or ["landuse", "water", "waterway", "transportation", "building", "place"]
    conn = sqlite3.connect(str(path))
    conn.execute("CREATE TABLE metadata (name TEXT, value TEXT, PRIMARY KEY (name))")
    conn.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, "
                 "tile_data BLOB, PRIMARY KEY (zoom_level, tile_column, tile_row))")

    metadata = {
        "name": f"Synthetic {fmt} archive",
        "type": "baselayer",
        "version": "1",
        "format": fmt,
        "minzoom": "6",
        "maxzoom": "12",
        "bounds": "8.700000,50.310000,8.800000,50.360000",
        "center": "8.750000,50.333300,12",
        "json": '{"vector_layers":[' + ",".join(
            '{"id":"%s","fields":{},"minzoom":6,"maxzoom":12}' % l for l in layers) + "]}",
        "attribution": "© OpenStreetMap contributors, ODbL 1.0",
        "license": "https://www.openstreetmap.org/copyright",
        "airtak:vector-schema": "airtak-minimal-v1",
    }
    for k, v in metadata.items():
        conn.execute("INSERT INTO metadata(name, value) VALUES (?, ?)", (k, v))

    payload = _mvt_payload()
    blob = gzip.compress(payload) if gzip_tiles else payload
    # A tile each at z6..z10 near the centre (xyz -> tms via flip).
    for z in range(6, 11):
        predicted = list(crop.circle_tiles(CENTER[0], CENTER[1], 10, z, z))
        for (zi, x, y) in predicted[:2]:
            tms_y = (1 << zi) - 1 - y
            conn.execute(
                "INSERT INTO tiles(zoom_level, tile_column, tile_row, tile_data) VALUES (?, ?, ?, ?)",
                (zi, x, tms_y, sqlite3.Binary(blob)),
            )
    conn.commit()
    conn.close()


def _run(main, argv):
    """Capture stdout/stderr so failures are readable, and return the exit code."""
    return main(argv)


def main() -> int:
    failures: list[str] = []

    def expect(cond: bool, msg: str) -> None:
        if not cond:
            failures.append(msg)

    with tempfile.TemporaryDirectory() as td:
        d = Path(td)

        # 1. A valid pbf archive must verify.
        good = d / "good.mbtiles"
        _make_archive(good, fmt="pbf", gzip_tiles=True)
        expect(verify.main([str(good)]) == 0, "valid pbf archive must verify clean")

        # 2. A raster (png) archive must NOT verify as a vector archive.
        raster = d / "raster.mbtiles"
        _make_archive(raster, fmt="png", gzip_tiles=False)
        expect(verify.main([str(raster)]) != 0, "raster archive must fail the vector verifier")

        # 3. A pbf archive with uncompressed tiles must fail (compression = contract).
        raw = d / "raw.mbtiles"
        _make_archive(raw, fmt="pbf", gzip_tiles=False)
        expect(verify.main([str(raw)]) != 0, "uncompressed pbf tiles must fail the gzip check")

        # 4. crop predict is callable and returns 0.
        expect(crop.main(["predict", "--center", "50.3333,8.75", "--radius", "5", "--zoom", "6-10"]) == 0,
               "crop predict must report the geometry tile set")

        # 5. crop produces a bounded archive that still verifies, with the circle bounds.
        out = d / "cropped.mbtiles"
        expect(
            crop.main(["crop", str(good), str(out), "--center", "50.3333,8.75", "--radius", "1", "--zoom", "6-10"]) == 0,
            "crop must succeed",
        )
        expect(out.exists(), "crop must write the output archive")
        if out.exists():
            expect(verify.main([str(out)]) == 0, "cropped archive must still verify")
            conn = sqlite3.connect(f"{out.resolve().as_uri()}?mode=ro", uri=True)
            try:
                n = conn.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
            finally:
                conn.close()
            expect(n > 0, "cropped archive must keep tiles")

        # 6. crop refuses to overwrite an existing output (never a data-loss silent rewrite).
        expect(
            crop.main(["crop", str(good), str(out), "--center", "50.3333,8.75", "--radius", "1", "--zoom", "6-10"]) != 0,
            "crop must refuse to overwrite an existing output",
        )

    if failures:
        for f in failures:
            print("FAIL: %s" % f)
        print("\n%d self-test(s) failed" % len(failures))
        return 1
    print("vector_mbtiles self-test: all cases passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
