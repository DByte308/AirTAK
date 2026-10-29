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
# SAFETY (what this tool guarantees):
#   * It NEVER mutates the source. The source is opened strictly read-only and read via the SQLite
#     backup API, which takes a consistent snapshot even from a live/WAL database.
#   * The source and destination must be distinct files. The same-file guard uses os.path.samefile,
#     so a destination that is a symlink or hardlink to the source is refused, not just a literal
#     equal path.
#   * The destination must not already exist. It is created exclusively (O_CREAT|O_EXCL), so an
#     existing file is never destructively overwritten.
#   * It validates the legacy quad (finite, in-range, well-ordered) BEFORE any write, so a malformed
#     source leaves no partial destination behind.
#   * On any validation/check failure it removes the newly-created destination and leaves the source
#     untouched.
#   * Tiles are proven unchanged by a checksum over the ordered tiles table plus row/zoom/byte
#     totals, and every metadata row other than 'bounds' is proven unchanged.
#
# ORDER SEMANTICS: the CLI --bbox order is (min_lat,min_lon,max_lat,max_lon). This tool converts a
# legacy value in THAT order to the MBTiles W,S,E,N order (west, south, east, north =
# min_lon,min_lat,max_lon,max_lat). The conversion is EXPLICIT ONLY: pass --from-legacy to apply it
# to a value you know is in legacy order. Without the flag, a value that is already a valid W,S,E,N
# quad is copied unchanged, and any other value is an error that instructs you to pass
# --from-legacy. There is NO automatic detection of a legacy value, because a quad of four valid
# numbers cannot be reliably told apart in the two orders.

import argparse
import hashlib
import math
import os
import sqlite3
import sys
from pathlib import Path


def _readonly(path: str) -> sqlite3.Connection:
    """Open a SQLite file strictly read-only. The path is escaped via Path.as_uri() so a '#' or '?'
    in the filename is passed verbatim rather than re-parsed as a URI fragment/query."""
    uri = Path(path).resolve().as_uri() + "?mode=ro"
    return sqlite3.connect(uri, uri=True)


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
    if math.isfinite(f) and f == int(f):
        return str(int(f))
    return repr(f)


def _legacy_to_wsen(bounds: str) -> str:
    """Convert a legacy CLI-order bounds (min_lat,min_lon,max_lat,max_lon) to W,S,E,N.

    Raises ValueError when the quad is not four finite, in-range, well-ordered values.
    """
    parts = bounds.split(",")
    if len(parts) != 4:
        raise ValueError("legacy bounds %r is not exactly 4 comma values" % bounds)
    try:
        min_lat, min_lon, max_lat, max_lon = (float(tok.strip()) for tok in parts)
    except ValueError:
        raise ValueError("legacy bounds %r is not numeric" % bounds)
    for name, v in (("min_lat", min_lat), ("min_lon", min_lon), ("max_lat", max_lat), ("max_lon", max_lon)):
        if not math.isfinite(v):
            raise ValueError("legacy bounds %r is not finite (%s=%r)" % (bounds, name, v))
    if not (-90 <= min_lat <= 90 and -90 <= max_lat <= 90):
        raise ValueError("legacy bounds %r has latitude outside [-90,90]" % bounds)
    if not (-180 <= min_lon <= 180 and -180 <= max_lon <= 180):
        raise ValueError("legacy bounds %r has longitude outside [-180,180]" % bounds)
    if not (min_lat <= max_lat and min_lon <= max_lon):
        raise ValueError("legacy bounds %r is inverted (min>max)" % bounds)
    return ",".join(_fmt(v) for v in (min_lon, min_lat, max_lon, max_lat))


def _is_standard_wsen(bounds: str) -> bool:
    """Whether a bounds string is already a standard W,S,E,N quad (west<=east, south<=north).

    Used when --from-legacy is NOT passed: if the value is a valid W,S,E,N quad we should not
    silently re-run the conversion on it (which would transpose it again)."""
    try:
        parts = [float(tok.strip()) for tok in bounds.split(",")]
    except ValueError:
        return False
    if len(parts) != 4 or not all(math.isfinite(p) for p in parts):
        return False
    w, s, e, n = parts
    return (-180 <= w <= 180 and -180 <= e <= 180 and -90 <= s <= 90 and -90 <= n <= 90
            and w <= e and s <= n)


def _backup_into(source: str, destination: str) -> None:
    """Consistently copy source -> destination via the SQLite backup API.

    Opens the source read-only (so a live/WAL database yields a consistent snapshot) and writes to
    an exclusively-created destination file.
    """
    src_conn = _readonly(source)
    dst_conn = sqlite3.connect(destination)
    try:
        src_conn.backup(dst_conn)
    finally:
        dst_conn.close()
        src_conn.close()


def migrate(source: str, destination: str, from_legacy: bool = False) -> dict:
    """Re-order source's metadata.bounds to W,S,E,N into [destination] when [from_legacy].

    Reordering happens ONLY explicitly, via from_legacy. Without it, a bounds value already in
    W,S,E,N order is copied unchanged; any other value is an error (no silent autodetect of a
    "legacy" value, because from four valid numbers the two orders are not distinguishable).

    Never mutates the source. Creates the destination exclusively (no overwrite); on failure
    removes it and leaves the source untouched. Uses a single source snapshot (the SQLite backup)
    so the before/after tile comparison is not raced by a live writer.
    """
    if not os.path.exists(source):
        raise SystemExit("[migrate_mbtiles_bounds] source not found: %s" % source)
    if os.path.lexists(destination):
        # destination (or a link) already exists; never overwrite. If it is a symlink/hardlink to
        # the source, name it specifically; a dangling symlink raises OSError from samefile, which
        # we treat as "existing destination, refuse" (still a non-zero error).
        try:
            is_same = os.path.samefile(source, destination)
        except OSError:
            is_same = False
        if is_same:
            raise SystemExit(
                "[migrate_mbtiles_bounds] source and destination are the same file "
                "(or a link to it); refuse to mutate the source in place.")
        raise SystemExit(
            "[migrate_mbtiles_bounds] destination already exists; refusing to overwrite: %s"
            % destination)

    # Create the destination exclusively (O_CREAT|O_EXCL), so an existing file is never destroyed.
    Path(destination).parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o644)
    os.close(fd)
    created = True
    try:
        # ONE source snapshot: the SQLite backup reads the source in a single consistent read. We
        # then read the pre-change state from the freshly-created DESTINATION (the backup copy),
        # so the before/after comparison is against one fixed snapshot, not two racy reads.
        _backup_into(source, destination)

        pre_conn = _readonly(destination)
        try:
            old_bounds = _metadata(pre_conn).get("bounds")
            src_hash = _tile_checksum(pre_conn)
            src_totals = _tile_totals(pre_conn)
            src_meta = _metadata(pre_conn)
        finally:
            pre_conn.close()

        if old_bounds is None:
            raise SystemExit("[migrate_mbtiles_bounds] source has no 'bounds' metadata")

        # Reordering is explicit only. No autodetect: a value that is already a valid W,S,E,N quad
        # is copied unchanged; any other value is an error instructing to pass --from-legacy.
        if from_legacy:
            try:
                new_bounds = _legacy_to_wsen(old_bounds)
            except ValueError as exc:
                raise SystemExit("[migrate_mbtiles_bounds] %s" % exc)
        else:
            if _is_standard_wsen(old_bounds):
                new_bounds = old_bounds  # already W,S,E,N; copy unchanged
            else:
                raise SystemExit(
                    "[migrate_mbtiles_bounds] bounds %r is not a valid W,S,E,N quad; "
                    "pass --from-legacy only if it is in legacy min_lat,min_lon,max_lat,max_lon "
                    "order." % old_bounds)

        w = sqlite3.connect(destination)
        try:
            w.execute("UPDATE metadata SET value=? WHERE name='bounds'", (new_bounds,))
            w.commit()
        finally:
            w.close()

        after_conn = _readonly(destination)
        try:
            after_hash = _tile_checksum(after_conn)
            after_totals = _tile_totals(after_conn)
            after_meta = _metadata(after_conn)
        finally:
            after_conn.close()

        tiles_untouched = (src_hash == after_hash) and (src_totals == after_totals)
        other_src = {k: v for k, v in src_meta.items() if k != "bounds"}
        other_after = {k: v for k, v in after_meta.items() if k != "bounds"}
        meta_untouched = other_src == other_after

        if not (tiles_untouched and meta_untouched):
            # Do not leave a wrong destination in place.
            os.remove(destination)
            created = False
            raise SystemExit(
                "[migrate_mbtiles_bounds] check FAILED: tiles or metadata changed; "
                "destination removed; source untouched.")

        return {
            "source": source,
            "destination": destination,
            "from_legacy": from_legacy,
            "old_bounds": old_bounds,
            "new_bounds": new_bounds,
            "tiles_untouched": tiles_untouched,
            "tile_checksum": after_hash,
            "tile_totals": after_totals,
            "meta_untouched": meta_untouched,
        }
    except BaseException:
        # Covers SystemExit (raised for validation/refusal) and any Exception, so the newly-created
        # destination is always removed on failure and the source is left untouched.
        if created and os.path.exists(destination):
            os.remove(destination)
        # Preserve the source regardless; it was never opened writable.
        raise


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Create a migrated copy of an MBTiles archive with metadata.bounds re-ordered "
                    "to the MBTiles W,S,E,N order. Never mutates the source; never overwrites an "
                    "existing destination.")
    parser.add_argument("source", help="Source .mbtiles (read-only, never mutated)")
    parser.add_argument("destination", help="Destination .mbtiles (must not already exist)")
    parser.add_argument("--from-legacy", action="store_true",
                        help="Treat the source bounds as legacy CLI order "
                             "(min_lat,min_lon,max_lat,max_lon) and convert it to W,S,E,N. "
                             "Without this flag nothing is reordered: a value already in W,S,E,N "
                             "order is copied unchanged and any other value is an error.")
    args = parser.parse_args(argv)

    try:
        evidence = migrate(args.source, args.destination, args.from_legacy)
    except SystemExit as exc:
        # migrate() messages may already carry the tool prefix; avoid double-printing it.
        msg = str(exc)
        if msg.startswith("[migrate_mbtiles_bounds] "):
            msg = msg[len("[migrate_mbtiles_bounds] "):]
        print("[migrate_mbtiles_bounds] %s" % msg, file=sys.stderr)
        return 2
    except Exception as exc:  # noqa: BLE001 - report any failure as a diagnostic
        print("[migrate_mbtiles_bounds] ERROR: %s" % exc, file=sys.stderr)
        return 3

    print("[migrate_mbtiles_bounds] %s -> %s" % (evidence["source"], evidence["destination"]))
    print("[migrate_mbtiles_bounds] bounds: %s -> %s" % (evidence["old_bounds"], evidence["new_bounds"]))
    print("[migrate_mbtiles_bounds] tiles untouched: %s" % evidence["tiles_untouched"])
    print("[migrate_mbtiles_bounds] tile checksum: %s" % evidence["tile_checksum"])
    print("[migrate_mbtiles_bounds] tile totals (rows, zooms, bytes): %s" % (evidence["tile_totals"],))
    print("[migrate_mbtiles_bounds] other metadata rows untouched: %s" % evidence["meta_untouched"])
    if not (evidence["tiles_untouched"] and evidence["meta_untouched"]):
        print("[migrate_mbtiles_bounds] FAILED: tiles or metadata were altered.", file=sys.stderr)
        return 1
    print("[migrate_mbtiles_bounds] OK: tile rows preserved; bounds set to W,S,E,N.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
