# MBTiles tooling — usage

AirTAK ships two offline Python tools for the raster MBTiles archives the app imports.
Both are offline (`sqlite3` stdlib only, no third-party packages) and never touch the
network. One reads an archive; the other writes a new archive while never mutating its input.

## `tools/verify_mbtiles.py` — read-only compatibility check

Validates the invariants the AirTAK importer relies on, without modifying the archive.
It is a **compatibility checker**, not a full MBTiles-spec validator: it checks what the
importer depends on and does not assert an invented "required" metadata set.

```bash
python3 tools/verify_mbtiles.py path/to/archive.mbtiles
```

Invariants checked:
- the `metadata` and `tiles` relations exist (tables **or** views, as the spec permits);
- tile rows expose `zoom_level`, `tile_column`, `tile_row`, `tile_data`, with each index
  within the valid `[0, 2**zoom)` range and non-empty `tile_data`;
- the `tile_data` magic matches the declared raster `format` (`png` / `jpeg` / `webp`).

Truthfulness rules (so an unverifiable archive is never reported as fine):
- a declared `format` is **required** to prove raster compatibility. An archive missing
  `format` **fails**.
- a present-but-unsupported `format` (e.g. a vector `pbf`) is reported as **unsupported**,
  not "corrupt".
- `minzoom` / `maxzoom` / `bounds` are **optional in practice**: their absence is an
  informational *note*, not a failure (AirTAK infers zoom from the rows and derives the area
  from the UI bbox). A **present-but-malformed** `bounds` is a failure (it cannot be
  consumed), because a W,S,E,N quad is checked for finite / in-range / well-ordered values.
  `name` is tolerated as absent by AirTAK — its absence is also a note, not a failure, but
  this is an app-specific tolerance, not an assertion that it is optional per the spec.

Robustness: the archive is opened strictly read-only (`sqlite mode=ro`); a missing file is an
error, never silently created. Non-SQLite input and missing columns produce a clear diagnostic
rather than a traceback. Coordinates are validated as strict SQLite integers (float / text / NULL
are rejected, not silently coerced); zoom is bounded to `0..22` before any `1 << z`, so a hostile
zoom cannot explode.

Exit code: `0` pass, `1` problems, `2` archive not found. Notes are printed to stderr but are
not failures.

## `tools/migrate_mbtiles_bounds.py` — metadata-only bounds re-order (explicit)

Rewrites **only** `metadata.bounds` from the legacy CLI order
(`min_lat,min_lon,max_lat,max_lon`) to the MBTiles standard W,S,E,N order
(`min_lon,min_lat,max_lon,max_lat` = west,south,east,north). The tiles table is left
byte-identical, and every other metadata row is unchanged.

```bash
# Convert a bounds value you KNOW is in legacy order:
python3 tools/migrate_mbtiles_bounds.py in.mbtiles out.mbtiles --from-legacy
```

**The conversion is explicit — there is no automatic reordering.** Pass `--from-legacy` only
when the source `bounds` is in the legacy CLI order. Without the flag:
- a value already in valid W,S,E,N order is **copied unchanged**;
- any other value is an **error** that tells you to pass `--from-legacy` (a quad of four
  valid numbers cannot be reliably told apart in the two orders, so the tool never guesses).

Safety guarantees:
- the source is opened strictly read-only and is **never mutated**;
- the destination is created **exclusively** — an existing file (or a symlink / hardlink to
  the source) is refused, never overwritten. A destination that is a symlink or hardlink to
  the source is refused via `os.path.samefile`;
- the copy uses the SQLite backup API from a read-only source, producing a **single
  consistent snapshot** (even from a live / WAL database);
- the legacy quad is validated (finite, in-range, well-ordered) **after** the destination
  snapshot is created; if validation or a check fails, the newly-created destination is
  removed, so **no partial destination is retained** and the source is left untouched;
- the result is proven: an ordered-tile checksum plus row/zoom/byte totals must match the
  source, and every metadata row other than `bounds` must be unchanged. This proves the
  archive **content** is preserved — it is not evidence that a decoder can render the tiles.

Exit code: `0` success, `2` refused (existing destination, same-file link, invalid bounds,
`--from-legacy` on a non-legacy value), `3` unexpected error.
