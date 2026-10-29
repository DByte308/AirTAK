#!/usr/bin/env python3
# AirTAK — tools/verify_mbtiles.py
# Read-only AirTAK compatibility verification of a raster MBTiles archive.
#
# This is an integrity checker, not a converter and not a full MBTiles spec validator. It opens the
# archive strictly read-only (URI mode=ro, so a missing file is an error, never created) and checks
# the invariants the AirTAK importer actually relies on. It never writes, never mutates the journal,
# and never alters the schema.
#
# IMPORTANT scope note: this tool checks COMPATIBILITY WITH AIRTAK, not conformance to the MBTiles
# spec. Several metadata keys the spec treats as optional (minzoom, maxzoom, bounds) are only
# validated here when present and NOT required to exist: their absence is recorded as an
# informational note because AirTAK can infer zoom from the tile rows and derive bounds from the UI.
# `name` is commonly present (and required by MBTiles 1.3 for a fully-specified set) but AirTAK does
# not depend on it, so its absence is likewise a note, not a failure. DO NOT assert any invented
# "required" metadata set here.
#
# The actual checks are:
#   * metadata + tiles relations exist (tables or views — the spec permits either)
#   * tile rows expose z/x/y and a non-empty tile_data; the index is within the supported range
#   * tile_data magic matches a supported raster format (png, jpeg, webp)
#   * IF present and parseable, minzoom/maxzoom and bounds are well-formed and consistent
#   * a vector archive is reported as 'unsupported', not 'corrupt'

import argparse
import os
import sqlite3
import sys
from pathlib import Path
from typing import Dict, Generator, Iterable, List, Optional, Tuple

# Supported raster tile formats. Each maps to a validating magic-prefix predicate.
PNG_MAGIC = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])  # 8-byte PNG signature
JPEG_MAGIC = bytes([0xFF, 0xD8, 0xFF])                                # FFD8FF
WEBP_MAGIC = b"RIFF"                                                  # RIFF....WEBP

SUPPORTED_FORMATS = ("png", "jpeg", "jpg", "webp")

# Zoom levels the verifier accepts. The Android importer clamps the user-facing zoom to [0, 22]
# (TileRepository.download: zMin = minZoom.coerceIn(0, 22)); defining 0..22 here as "supported"
# matches the widest range the importer can hold and prevents a hostile archive from driving a
# 1 << z with an enormous exponent that would allocate an unmanageable integer/loop.
MIN_ZOOM = 0
MAX_ZOOM = 22

MBTILES_W = 0
MBTILES_S = 1
MBTILES_E = 2
MBTILES_N = 3


class _FormatError(ValueError):
    """Raised internally when a format is not one this tool can validate."""


def _canonical_format(tile_format: str) -> str:
    """Map a metadata 'format' value to our canonical key, or raise for unsupported vector formats."""
    f = (tile_format or "").strip().lower()
    if f in ("png",):
        return "png"
    if f in ("jpg", "jpeg"):
        return "jpeg"
    if f in ("webp",):
        return "webp"
    # A vector format (pbf, pmtiles, mvt, pbf.gz) is not corrupt; it is simply not raster and
    # this tool cannot validate it. Raise a distinct error so the caller can label it 'unsupported'.
    raise _FormatError("unsupported tile format: %r (raster verifier only)" % tile_format)


def _magic_matches(data: bytes, tile_format: str) -> bool:
    """Whether the leading bytes of a tile match its declared format."""
    if tile_format == "png":
        # Full 8-byte PNG signature: 89 50 4E 47 0D 0A 1A 0A.
        return data.startswith(PNG_MAGIC) and len(data) >= len(PNG_MAGIC)
    if tile_format == "jpeg":
        return data.startswith(JPEG_MAGIC)
    if tile_format == "webp":
        # WebP is a RIFF container: 'RIFF' + 4-byte size + 'WEBP'.
        return len(data) >= 12 and data[:4] == WEBP_MAGIC and data[8:12] == b"WEBP"
    raise _FormatError("unknown tile format %r" % tile_format)


def _open_readonly(path: str) -> sqlite3.Connection:
    """Open an archive strictly read-only via URI mode=ro.

    A missing file is an error, never silently created. The URI form also disables any journal
    and schema mutation that a plain ad-hoc connect might otherwise permit.

    The path is escaped via Path.resolve().as_uri() so a filename containing '#' or '?' (both of
    which SQLite's URI parser treats specially as fragment/query delimiters) is passed verbatim
    rather than being re-parsed as a URI option or fragment.
    """
    uri = Path(path).resolve().as_uri() + "?mode=ro"
    try:
        return sqlite3.connect(uri, uri=True)
    except sqlite3.Error as exc:
        raise _FormatError("cannot open archive read-only: %s" % exc)


def _relation_names(conn: sqlite3.Connection) -> set:
    """Return the set of relation names present (tables and views alike)."""
    rows = conn.execute(
        "SELECT name FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%'"
    )
    return {r[0] for r in rows}


def _relation_columns(conn: sqlite3.Connection, name: str) -> List[str]:
    """Return the column names of a relation (table or view)."""
    return [r[1] for r in conn.execute("PRAGMA table_info(%s)" % name)]


def _coerce_int(value) -> Optional[int]:
    """Return a genuine SQLite integer, or None.

    SQLite is dynamically typed: a column declared INTEGER can still hold a float, a text, or NULL.
    Python's int() silently coerces '0.5'->0 and '12'->12 and None raises, so we check the stored
    type explicitly rather than trusting the conversion. Returns None when the value is not a
    non-NULL integral value.
    """
    if value is None:
        return None
    if isinstance(value, int) and not isinstance(value, bool):
        return value
    return None


def _parse_bounds(raw: Optional[str]) -> Optional[Tuple[float, float, float, float]]:
    """Parse a W,S,E,N bounds string, or None when malformed.

    Returns (west, south, east, north) or None. No auto-transpose: if the caller supplies a quad
    in some other order we cannot reliably detect it, so a malformed or out-of-range quad is
    reported as-is and never silently reordered.
    """
    if not raw:
        return None
    try:
        parts = [float(tok.strip()) for tok in raw.split(",")]
    except (TypeError, ValueError):
        return None
    if len(parts) != 4:
        return None
    w, s, e, n = parts
    # finite and within WGS84 lon/lat ranges, and not inverted (west<=east, south<=north).
    if not all(map(_finite, (w, s, e, n))):
        return None
    if not (-180 <= w <= 180 and -180 <= e <= 180):
        return None
    if not (-90 <= s <= 90 and -90 <= n <= 90):
        return None
    if w > e or s > n:
        return None
    return (w, s, e, n)


def _finite(x: float) -> bool:
    import math
    return math.isfinite(x)


class MBTilesVerifier:
    """Validates the invariants of a raster MBTiles archive. Read-only, no mutation."""

    def __init__(self, path: str):
        self.path = path
        self._conn: Optional[sqlite3.Connection] = None
        self.problems: List[str] = []
        self.notes: List[str] = []
        self._tile_relation: Optional[str] = None
        self._tile_format: Optional[str] = None
        self._metadata: Dict[str, str] = {}

    def _close(self) -> None:
        if self._conn is not None:
            # mode=ro; rollback is a no-op and close must not commit anything.
            try:
                self._conn.close()
            finally:
                self._conn = None

    def verify(self) -> bool:
        """Run all checks. Returns True when the archive is a valid raster MBTiles set."""
        try:
            self._conn = _open_readonly(self.path)
        except _FormatError as exc:
            self.problems.append(str(exc))
            return False

        try:
            relations = _relation_names(self._conn)
            if "metadata" not in relations:
                self.problems.append("missing 'metadata' relation (table or view)")
            if "tiles" not in relations:
                self.problems.append("missing 'tiles' relation (table or view)")

            self._validate_metadata_relation(relations)
            if "tiles" in relations:
                self._validate_tiles("tiles")
        except sqlite3.Error as exc:
            # A structural problem (e.g. an archive that is not a real SQLite database, or a
            # schema that cannot be interrogated) must surface as a diagnostic, not a traceback.
            self.problems.append("cannot interrogate archive: %s" % exc)
        finally:
            self._close()
        return not self.problems

    def _validate_metadata_relation(self, relations: set) -> None:
        if "metadata" not in relations:
            return
        cols = _relation_columns(self._conn, "metadata")
        if not {"name", "value"}.issubset(cols):
            self.problems.append(
                "metadata is missing required column(s): %s"
                % ", ".join(sorted({"name", "value"} - set(cols))))
            return
        try:
            for name, value in self._conn.execute("SELECT name, value FROM metadata"):
                self._metadata[str(name)] = str(value)
        except sqlite3.Error as exc:
            self.problems.append("could not read metadata: %s" % exc)

    def _validate_tiles(self, relation: str) -> None:
        self._tile_relation = relation
        col_problems = _validate_relation_columns(self._conn, relation)
        self.problems.extend(col_problems)
        # If the required tile columns are absent, a row scan would only produce a confusing
        # 'no such column' diagnostic on top of the schema problem already recorded. Skip it.
        tile_cols_missing = bool(col_problems)

        tile_format_raw = self._metadata.get("format")
        if tile_format_raw is None:
            # Raster compatibility cannot be proven without a declared format (or full-row sniffing,
            # which AirTAK does not specify). A format-less archive is therefore a hard failure, not
            # a note: we cannot assert the tiles are raster. The bounds/minzoom/name exceptions are
            # metadata that AirTAK can derive; `format` is the raster claim and so is not waived.
            self.problems.append("metadata is missing 'format'; cannot verify raster tiles")
            self._tile_format = None
        else:
            try:
                self._tile_format = _canonical_format(tile_format_raw)
            except _FormatError as exc:
                self.problems.append(str(exc))
                self._tile_format = None

        # bounds and zoom metadata validation (independent of whether the tile rows exist).
        self._validate_bounds()
        self._validate_name()

        if not tile_cols_missing:
            # Validate rows even when format is missing: index range and non-empty data are always
            # checked; magic matching only applies when a supported format is declared.
            self._validate_tile_rows(relation, self._tile_format)

    def _validate_bounds(self) -> None:
        raw = self._metadata.get("bounds")
        parsed = _parse_bounds(raw)
        if raw is None:
            # bounds is optional generally; AirTAK can derive the area from the UI bbox. Its absence
            # is a note, not a failure. (The MBTiles 1.3 spec does list bounds among recommended
            # keys, but AirTAK does not depend on it here, so we do not fail the archive.)
            self.notes.append("metadata has no 'bounds' (AirTAK derives area from the UI bbox)")
        elif parsed is None:
            # A PRESENT but malformed bounds is a real problem: it cannot be consumed.
            self.problems.append("metadata bounds is not a valid W,S,E,N quad: %r" % raw)

    def _validate_name(self) -> None:
        raw = self._metadata.get("name")
        if raw is None:
            # name is commonly required by MBTiles 1.3 (a fully-specified archive carries it) but
            # AirTAK does not depend on it; its absence is a note, not a failure.
            self.notes.append("metadata has no 'name' (not required by AirTAK)")
            return
        if not raw.strip():
            self.problems.append("metadata 'name' is empty")

    def _validate_tile_rows(self, relation: str, tile_format: Optional[str]) -> None:
        # Loop over every row and check index range, non-empty data, and magic. This is O(n) but a
        # verifier should look at the data, not just trust the schema. When tile_format is None
        # (missing/unsupported metadata) we still validate the index range and non-empty data, but
        # skip the magic check because we have no declared format to test against.
        n_rows = 0
        min_z, max_z = (None, None)
        try:
            rows = self._conn.execute(
                "SELECT zoom_level, tile_column, tile_row, tile_data FROM %s" % relation)
            for z, x, y, data in rows:
                n_rows += 1
                zi = _coerce_int(z)
                xi = _coerce_int(x)
                yi = _coerce_int(y)
                if zi is None or xi is None or yi is None:
                    bad_fields = [
                        f for f, val in (("zoom_level", z), ("tile_column", x), ("tile_row", y))
                        if _coerce_int(val) is None
                    ]
                    self.problems.append(
                        "tile has a non-INTEGER %s coordinate(s): %r"
                        % ("/".join(bad_fields), (z, x, y)))
                    continue
                if zi < MIN_ZOOM or zi > MAX_ZOOM:
                    self.problems.append(
                        "zoom_level %d outside supported range %d..%d" % (zi, MIN_ZOOM, MAX_ZOOM))
                    continue
                n = 1 << zi
                if xi < 0 or xi >= n:
                    self.problems.append(
                        "tile_column %d out of range for zoom %d (valid 0..%d)" % (xi, zi, n - 1))
                if yi < 0 or yi >= n:
                    self.problems.append(
                        "tile_row %d out of range for zoom %d (valid 0..%d)" % (yi, zi, n - 1))
                if data is None or len(data) == 0:
                    self.problems.append("tile %d/%d/%d has empty tile_data" % (zi, xi, yi))
                elif tile_format is not None and not _magic_matches(data, tile_format):
                    self.problems.append(
                        "tile %d/%d/%d does not match expected %s magic" % (zi, xi, yi, tile_format))
                min_z = zi if min_z is None else min(min_z, zi)
                max_z = zi if max_z is None else max(max_z, zi)
        except sqlite3.Error as exc:
            self.problems.append("could not read tile rows: %s" % exc)
            return

        if n_rows == 0:
            self.problems.append("tile relation %r is empty" % relation)
            return
        if min_z is None:
            # Every row carried an invalid coordinate type or out-of-range zoom and was flagged
            # above; there is no valid zoom range to reconcile against metadata.
            return
        self._validate_zoom_metadata(min_z, max_z)

    def _validate_zoom_metadata(self, row_min: int, row_max: int) -> None:
        # minzoom/maxzoom are OPTIONAL metadata in the MBTiles spec. If present, validate against
        # the rows; if absent, infer (already derived from rows).
        raw_min = self._metadata.get("minzoom")
        raw_max = self._metadata.get("maxzoom")
        if raw_min is None and raw_max is None:
            return
        # minzoom/maxzoom metadata arrive as strings; parse strict integers.
        declared_min = _try_int(raw_min)
        declared_max = _try_int(raw_max)
        if declared_min is None:
            self.problems.append("metadata minzoom is not an integer: %r" % raw_min)
        if declared_max is None:
            self.problems.append("metadata maxzoom is not an integer: %r" % raw_max)
        if declared_min is not None and declared_max is not None:
            if declared_min > row_min:
                self.problems.append(
                    "metadata minzoom %d > smallest row zoom %d" % (declared_min, row_min))
            if declared_max < row_max:
                self.problems.append(
                    "metadata maxzoom %d < largest row zoom %d" % (declared_max, row_max))
            if declared_min < MIN_ZOOM or declared_max > MAX_ZOOM:
                self.problems.append(
                    "metadata zoom range outside supported %d..%d" % (MIN_ZOOM, MAX_ZOOM))


def _validate_relation_columns(conn: sqlite3.Connection, name: str) -> List[str]:
    """Return a list of problems for a relation that must expose the tile columns."""
    problems: List[str] = []
    try:
        cols = set(_relation_columns(conn, name))
    except sqlite3.Error as exc:
        problems.append("cannot inspect %s: %s" % (name, exc))
        return problems
    required = {"zoom_level", "tile_column", "tile_row", "tile_data"}
    if not required.issubset(cols):
        problems.append(
            "%s is missing required column(s): %s" % (name, ", ".join(sorted(required - cols))))
    return problems


def _try_int(value):
    """Return int(value) on success, else None. Handles None / non-numeric strings."""
    if value is None:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def verify_archive(path: str) -> Tuple[bool, List[str]]:
    """Verify one archive. Returns (ok, problems). Never mutates the archive."""
    verifier = MBTilesVerifier(path)
    ok = verifier.verify()
    return ok, verifier.problems + verifier.notes


def verify_archive_with_notes(path: str) -> Tuple[bool, List[str], List[str]]:
    """Verify one archive. Returns (ok, problems, notes). Never mutates the archive."""
    verifier = MBTilesVerifier(path)
    ok = verifier.verify()
    return ok, verifier.problems, verifier.notes


def main(argv: Optional[Iterable[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Read-only verify an MBTiles raster archive against AirTAK import invariants.")
    parser.add_argument("archive", help="Path to the .mbtiles archive to verify (read-only)")
    args = parser.parse_args(argv)

    if not os.path.exists(args.archive):
        print("[verify_mbtiles] ERROR: archive not found: %s" % args.archive, file=sys.stderr)
        return 2

    ok, problems, notes = verify_archive_with_notes(args.archive)
    for note in notes:
        print("[verify_mbtiles] NOTE: %s" % note, file=sys.stderr)
    if ok:
        print("[verify_mbtiles] OK: %s passes raster MBTiles invariants." % args.archive)
        return 0

    for problem in problems:
        print("[verify_mbtiles] PROBLEM: %s" % problem, file=sys.stderr)
    print("[verify_mbtiles] FAILED: %s has %d problem(s)." % (args.archive, len(problems)),
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
