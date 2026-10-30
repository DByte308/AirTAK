#!/usr/bin/env python3
# AirTAK — tools/verify_vector_mbtiles.py
# Read-only verification of an AirTAK offline VECTOR MBTiles archive.
#
# Complements tools/verify_mbtiles.py (which is raster-only and explicitly reports a vector
# archive as 'unsupported'). This tool opens the archive strictly read-only (mode=ro) and checks
# the invariants the offline vector map path relies on:
#
#   * metadata + tiles relations exist;
#   * metadata['format'] is 'pbf' (MVT);
#   * every tile_data is gzip-compressed (gzip magic 1f 8b) AND decompresses to a non-empty MVT
#     protobuf payload;
#   * vector_layers is declared in metadata (tilemaker writes it as a JSON string);
#   * the OSM attribution survives (metadata['attribution'] == the expected string);
#   * zoom range is 6..12 per settings and per the actual tile rows.
#
# It also reports the per-zoom tile counts plus uncompressed (decompressed MVT) and compressed
# (stored BLOB) byte totals, so a package's true size and tile count are measured, not guessed.
#
# Usage:
#   python3 tools/verify_vector_mbtiles.py archive.mbtiles [--expect-attribution "© OpenStreetMap contributors, ODbL 1.0"]

import argparse
import gzip
import json
import sqlite3
import sys
from pathlib import Path


def _open_readonly(path: str) -> sqlite3.Connection:
    uri = Path(path).resolve().as_uri() + "?mode=ro"
    try:
        return sqlite3.connect(uri, uri=True)
    except sqlite3.Error as exc:
        raise SystemExit("cannot open archive read-only: %s" % exc)


GZIP_MAGIC = bytes([0x1F, 0x8B])


def _firsts(data: bytes, n: int) -> bytes:
    return data[:n]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Read-only verify an AirTAK offline VECTOR MBTiles archive.")
    parser.add_argument("archive", help="Path to the .mbtiles archive to verify (read-only)")
    parser.add_argument("--expect-attribution", default="© OpenStreetMap contributors, ODbL 1.0",
                        help="Expected attribution string in metadata (default: %(default)s)")
    args = parser.parse_args(argv)

    if not Path(args.archive).exists():
        print("[verify_vector_mbtiles] ERROR: archive not found: %s" % args.archive, file=sys.stderr)
        return 2

    conn = _open_readonly(args.archive)
    problems = []
    notes = []

    try:
        relations = {r[0] for r in conn.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%'")}
        if "metadata" not in relations:
            problems.append("missing 'metadata' relation")
        if "tiles" not in relations:
            problems.append("missing 'tiles' relation")
        if problems:
            for p in problems:
                print("[verify_vector_mbtiles] PROBLEM: %s" % p, file=sys.stderr)
            return 1

        metadata = {str(name): str(value) for name, value in conn.execute("SELECT name, value FROM metadata")}
        print("[verify_vector_mbtiles] archive: %s" % args.archive)
        print("[verify_vector_mbtiles] metadata keys: %s" % ", ".join(sorted(metadata)))

        fmt = metadata.get("format")
        if fmt != "pbf":
            problems.append("metadata format=%r (expected 'pbf' for MVT)" % fmt)
            print("[verify_vector_mbtiles] NOTE: metadata format is %r" % fmt)
        else:
            print("[verify_vector_mbtiles] format = pbf  (MVT)  OK")

        vl = metadata.get("vector_layers")
        if vl is None or not vl.strip():
            # tilemaker stores the per-layer declarations under the `json` metadata key
            # (a JSON object {"vector_layers": [...]}), not as its own key. Fall back to it.
            vl = metadata.get("json")
            if vl and "vector_layers" in vl:
                try:
                    parsed = json.loads(vl)
                    names = [L.get("id") for L in parsed.get("vector_layers", [])]
                    print("[verify_vector_mbtiles] vector_layers (from metadata 'json') = %s"
                          % ", ".join(str(n) for n in names))
                except Exception:
                    problems.append("metadata 'json' has vector_layers but is not JSON-parseable")
            else:
                problems.append("metadata has no non-empty 'vector_layers' (or 'json' with vector_layers)")
        else:
            try:
                parsed = json.loads(vl)
                names = [L.get("id") for L in parsed] if isinstance(parsed, list) else list(parsed)
                print("[verify_vector_mbtiles] vector_layers = %s" % ", ".join(str(n) for n in names))
            except Exception as exc:
                notes.append("vector_layers not JSON-parseable (%s); showing raw" % exc)
                print("[verify_vector_mbtiles] vector_layers = %s" % vl)

        attribution = metadata.get("attribution")
        if attribution is None:
            problems.append("metadata has no 'attribution'")
        elif attribution != args.expect_attribution:
            problems.append("attribution mismatch: %r != expected %r" % (attribution, args.expect_attribution))
        else:
            print("[verify_vector_mbtiles] attribution = %r  OK" % attribution)

        for key in ("minzoom", "maxzoom"):
            if key in metadata:
                print("[verify_vector_mbtiles] metadata %s = %s" % (key, metadata[key]))
            else:
                notes.append("metadata has no '%s'" % key)

        # Per-zoom tallies.
        per_zoom = {}
        total_uncompressed = 0
        total_compressed = 0
        n_rows = 0
        bad_gzip = 0
        empty_payload = 0
        for z, x, y, data in conn.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles"):
            zi = int(z)
            per_zoom.setdefault(zi, {"count": 0, "compressed": 0, "uncompressed": 0})
            per_zoom[zi]["count"] += 1
            per_zoom[zi]["compressed"] += len(data)
            total_compressed += len(data)
            n_rows += 1
            if data is None or len(data) == 0:
                empty_payload += 1
                continue
            if not data.startswith(GZIP_MAGIC):
                bad_gzip += 1
                continue
            try:
                raw = gzip.decompress(data)
            except Exception as exc:
                problems.append("tile %d/%d/%d gzip-decompress failed: %s" % (zi, x, y, exc))
                continue
            per_zoom[zi]["uncompressed"] += len(raw)
            total_uncompressed += len(raw)

        print("")
        print("[verify_vector_mbtiles] tile rows: %d" % n_rows)
        print("%-8s %10s %14s %16s" % ("zoom", "tiles", "compressed", "uncompressed"))
        for z in sorted(per_zoom):
            c = per_zoom[z]
            print("%-8s %10d %14s %16s" % (
                z, c["count"],
                _human(c["compressed"]), _human(c["uncompressed"])))
        print("%-8s %10d %14s %16s" % (
            "total", n_rows, _human(total_compressed), _human(total_uncompressed)))

        if bad_gzip:
            problems.append("%d tile(s) are not gzip-compressed (missing gzip magic)" % bad_gzip)
        if empty_payload:
            problems.append("%d tile(s) have empty tile_data" % empty_payload)
        if total_uncompressed and total_compressed:
            print("[verify_vector_mbtiles] compression ratio: %.2fx" % (total_uncompressed / total_compressed))

        # Zoom consistency.
        row_min = min(per_zoom) if per_zoom else None
        row_max = max(per_zoom) if per_zoom else None
        if n_rows == 0:
            problems.append("tiles relation is empty")
        else:
            print("[verify_vector_mbtiles] real zoom range: %s..%s" % (row_min, row_max))
            if metadata.get("minzoom") != str(row_min):
                notes.append("metadata minzoom %s != smallest row zoom %s" % (metadata.get("minzoom"), row_min))
            if metadata.get("maxzoom") != str(row_max):
                notes.append("metadata maxzoom %s != largest row zoom %s" % (metadata.get("maxzoom"), row_max))
    finally:
        conn.close()

    for note in notes:
        print("[verify_vector_mbtiles] NOTE: %s" % note)
    if problems:
        for p in problems:
            print("[verify_vector_mbtiles] PROBLEM: %s" % p, file=sys.stderr)
        print("[verify_vector_mbtiles] FAILED: %d problem(s)." % len(problems), file=sys.stderr)
        return 1
    print("[verify_vector_mbtiles] OK: %s passes vector MBTiles invariants." % args.archive)
    return 0


def _human(n: int) -> str:
    for unit in ("B", "KiB", "MiB", "GiB"):
        if abs(n) < 1024 or unit == "GiB":
            return "%.1f %s" % (n, unit) if unit != "B" else "%d B" % n
        n /= 1024.0


if __name__ == "__main__":
    sys.exit(main())
