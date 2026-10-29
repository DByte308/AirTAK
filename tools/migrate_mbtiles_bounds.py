#!/usr/bin/env python3
# AirTAK — tools/migrate_mbtiles_bounds.py
# Metadata-only: rewrite an MBTiles archive's metadata.bounds from the legacy CLI order
# (minLat,minLon,maxLat,maxLon) to the MBTiles W,S,E,N order (west,south,east,north =
# minLon,minLat,maxLon,maxLat), leaving every tile row byte-identical.
#
# PURPOSE: the two consumers disagree on bounds order. tools/tile_fetch.py historically wrote
# metadata.bounds from the --bbox string verbatim (min_lat,min_lon,max_lat,max_lon), and the
# bundled android/app/src/main/assets/tiles/world-overview.mbtiles carries the same legacy order.
# MBTiles stores bounds as west,south,east,north (lon,lat,lon,lat). This tool rewrites ONLY the
# metadata.bounds value and proves the tiles table is untouched.
#
# SAFETY: it copies the source to the destination first, then migrates the copy. The source is
# never mutated. It records a checksum over the full ordered tiles table (ordered by PK) before and
# after and refuses to complete unless the checksum and row/zoom/byte totals are unchanged, and
# unless every metadata row other than 'bounds' is unchanged. It does not add or remove any key.

import argparse
import hashlib
import os
import shutil
import sqlite3
import sys
from pathlib import Path


def _readonly(path: str) -> sqlite3.Connection:
    return sqlite3.connect("file:%s?mode=ro" % os.path.abspath(path), uri=True)


def _tile_checksum(conn: sqlite3.Connection) -> str:
    """SHA-256 over the ordered tiles table (z/x/y then the blob), a stable tile preservation proof."""
    h = hashlib.sha256()
    for (z, x, y, data) in conn.execute(
            "SELECT zoom_level, tile_column, tile_row, tile_data "
            "FROM tiles ORDER BY zoom_level, tile_column, tile_row"):
        h.update(b"%d/%d/%d/" % (z, x, y))
        h.update(data if data is not None else b"")
    return h.hexdigest()


def _tile_totals(conn: sqlite3.Connection):
    return (
        conn.execute("SELECT COUNT(*) FROM tiles").fetchone()[0],
        conn.execute("SELECT COUNT(DISTINCT zoom_level) FROM tiles").fetchone()[0],
        conn.execute("SELECT COALESCE(SUM(length(tile_data)),0) FROM tiles").fetchone()[0],
    )


def _metadata(conn: sqlite3.Connection) -> dict:
    return {r[0]: r[1] for r in conn.execute("SELECT name, value FROM metadata ORDER BY name")}


def _fmt(f: float) -> str:
    """Format a float the way the source file does (-180 not -180.0)."""
    if f == int(f):
        return str(int(f))
    return repr(f)


def migrate(source: str, destination: str) -> dict:
    """Copy source -> destination, then rewrite only metadata.bounds to W,S,E,N. Returns evidence."""
    if not os.path.exists(source):
        raise SystemExit("[migrate_mbtiles_bounds] source not found: %s" % source)
    if os.path.abspath(source) == os.path.abspath(destination):
        raise SystemExit(
            "[migrate_mbtiles_bounds] source and destination are the same file; "
            "refuse to mutate the source in place.")

    # Work on a copy; never mutate the source.
    Path(destination).parent.mkdir(parents=True, exist_ok=True)
    shutil.copy(source, destination)

    before = _readonly(destination)
    before_hash = _tile_checksum(before)
    before_totals = _tile_totals(before)
    before_meta = _metadata(before)
    old_bounds = before_meta.get("bounds")
    before.close()

    # Decode the legacy CLI order (min_lat,min_lon,max_lat,max_lon) into W,S,E,N.
    try:
        parts = [float(tok.strip()) for tok in old_bounds.split(",")] if old_bounds else []
    except ValueError:
        raise SystemExit(
            "[migrate_mbtiles_bounds] source bounds %r is not numeric" % old_bounds)
    if len(parts) != 4:
        raise SystemExit(
            "[migrate_mbtiles_bounds] source bounds %r is not a 4-value legacy quad" % old_bounds)
    min_lat, min_lon, max_lat, max_lon = parts
    new_bounds = ",".join(_fmt(v) for v in (min_lon, min_lat, max_lon, max_lat))

    w = sqlite3.connect(destination)
    w.execute("UPDATE metadata SET value=? WHERE name='bounds'", (new_bounds,))
    w.commit()
    w.close()

    after = _readonly(destination)
    after_hash = _tile_checksum(after)
    after_totals = _tile_totals(after)
    after_meta = _metadata(after)
    after.close()

    tiles_untouched = (before_hash == after_hash) and (before_totals == after_totals)
    meta_untouched = {k: v for k, v in after_meta.items() if k != "bounds"} == \
        {k: v for k, v in before_meta.items() if k != "bounds"}

    return {
        "source": source,
        "destination": destination,
        "old_bounds": old_bounds,
        "new_bounds": new_bounds,
        "tiles_untouched": tiles_untouched,
        "tile_checksum": after_hash,
        "tile_totals": after_totals,
        "meta_untouched": meta_untouched,
    }


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Copy an MBTiles archive and rewrite only metadata.bounds to W,S,E,N order.")
    parser.add_argument("source", help="Source .mbtiles (read-only, never mutated)")
    parser.add_argument("destination", help="Destination .mbtiles to write the migrated copy to")
    args = parser.parse_args(argv)

    try:
        evidence = migrate(args.source, args.destination)
    except Exception as exc:  # noqa: BLE001 - report any failure as a diagnostic
        print("[migrate_mbtiles_bounds] ERROR: %s" % exc, file=sys.stderr)
        return 2

    print("[migrate_mbtiles_bounds] %s -> %s" % (evidence["source"], evidence["destination"]))
    print("[migrate_mbtiles_bounds] bounds: %s -> %s" % (evidence["old_bounds"], evidence["new_bounds"]))
    print("[migrate_mbtiles_bounds] tiles untouched: %s" % evidence["tiles_untouched"])
    print("[migrate_mbtiles_bounds] tile checksum: %s" % evidence["tile_checksum"])
    print("[migrate_mbtiles_bounds] tile totals (rows, zooms, bytes): %s" % (evidence["tile_totals"],))
    print("[migrate_mbtiles_bounds] other metadata rows untouched: %s" % evidence["meta_untouched"])
    if not (evidence["tiles_untouched"] and evidence["meta_untouched"]):
        print("[migrate_mbtiles_bounds] FAILED: tiles or metadata were altered.", file=sys.stderr)
        return 1
    print("[migrate_mbtiles_bounds] OK: tile rows preserved; bounds re-ordered to W,S,E,N.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
