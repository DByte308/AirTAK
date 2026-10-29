#!/usr/bin/env python3
# AirTAK — Airsoft Team Awareness Kit
# tools/tile_fetch.py — Download offline map tiles to MBTiles
# Planning: §11.5 | Roadmap: T.6
#
# Usage:
#   python tools/tile_fetch.py \
#     --bbox "50.30,8.68,50.37,8.82" \
#     --zoom 10-14 \
#     --output tiles/friedberg.mbtiles \
#     --url "https://tiles.example.com/{z}/{x}/{y}.png"
#
# The resulting .mbtiles file can be loaded by TileRepository on Android.
#
# This tool performs bulk downloads. Bulk requests against the public OpenStreetMap tile
# server are refused outright, with no override flag: pacing does not make bulk use of that
# shared service acceptable, and an option to bypass the check would be accepted by reflex.
# Point --url at a server you operate or are licensed to use.

import argparse
import math
import sqlite3
import time
import urllib.request
import os
import sys
from typing import Callable, Iterator, Optional, Tuple

TILE_SIZE = 256

# One request per second. The previous default of 0.1 s was ten requests per second, which is
# bulk traffic by any reading and was described in the help text as "polite".
DEFAULT_DELAY_SECONDS = 1.0
MIN_DELAY_SECONDS = 1.0

# Identification with a reachable contact. The previous value pointed at a placeholder URL,
# so an operator seeing this traffic had no way to reach whoever sent it.
USER_AGENT = "AirTAK-tile-fetch/1.0 (+https://github.com/DByte308/AirTAK)"

# The one host family refused for bulk download, with no override.
#
# Deliberately narrow, and it mirrors TileProviderPolicy.kt exactly:
#     host == "tile.openstreetmap.org" || host.endsWith(".tile.openstreetmap.org")
# The bare `openstreetmap.org` and `osm.org` domains are NOT blocked. They are general-purpose
# domains for the project's website and services, and refusing them here would be speculative
# extrapolation from a policy that is about the tile server specifically. A guard that blocks more
# than the written rule is as much a defect as one that blocks less, because it hides behind the
# appearance of being cautious.
PROHIBITED_BULK_HOST = "tile.openstreetmap.org"


class BulkPolicyError(SystemExit):
    """Refusal that must stop the run before any socket is opened."""


def _host_of(url: str) -> str:
    scheme_sep = url.find("://")
    if scheme_sep <= 0:
        raise BulkPolicyError(
            "[tile_fetch] Tile URL must use http or https. Got: %r" % url)
    scheme = url[:scheme_sep].lower()
    if scheme not in ("http", "https"):
        # An ftp:// or file:// template would sail past a host-only check and then be fetched by
        # urllib anyway, so the scheme is validated here rather than assumed.
        raise BulkPolicyError("[tile_fetch] Tile URL must use http or https, not %s." % scheme)
    rest = url[scheme_sep + 3:]
    for sep in ("/", "?", "#"):
        rest = rest.split(sep)[0]
    if "@" in rest:
        raise BulkPolicyError("[tile_fetch] Tile URL must not contain credentials.")
    # An IPv6 literal keeps its brackets and contains colons, so split off the port only outside
    # a literal. `[::1]` is a host, `[::1]:8080` is a host and port.
    if rest.startswith("["):
        close = rest.find("]")
        if close == -1:
            raise BulkPolicyError("[tile_fetch] Malformed IPv6 literal in tile URL.")
        host = rest[: close + 1]
    else:
        host = rest.split(":")[0]
    # Lowercase and drop a single trailing dot, which is the root-label form some resolvers and
    # copy-pasted URLs leave behind. Everything else is preserved.
    return host.lower().rstrip(".")


def check_bulk_policy(url_template: str) -> str:
    """Refuse bulk download from shared public infrastructure. Returns the host if allowed."""
    host = _host_of(url_template)
    if not host:
        raise BulkPolicyError("[tile_fetch] Tile URL has no usable hostname.")
    if host == PROHIBITED_BULK_HOST or host.endswith("." + PROHIBITED_BULK_HOST):
        raise BulkPolicyError(
            "[tile_fetch] Refusing bulk download from %s.\n"
            "  The public OpenStreetMap tile server is shared community infrastructure and its\n"
            "  rule here is exactly: the host tile.openstreetmap.org and any subdomain of it.\n"
            "  usage policy does not permit bulk downloading. Slowing the loop down does not\n"
            "  change that, so there is no --force flag here.\n"
            "  Use a tile server you operate or are licensed to use. For a self-hosted stack,\n"
            "  see the OpenMapTiles / tilemaker route described in the offline map notes." % host)
    return host


def normalize_template(url: str) -> str:
    """Fold only scheme and host case; keep port, path and query, any of which can select a style."""
    scheme_sep = url.find("://")
    if scheme_sep <= 0:
        raise BulkPolicyError("[tile_fetch] Tile URL must use http or https.")
    scheme = url[:scheme_sep].lower()
    if scheme not in ("http", "https"):
        raise BulkPolicyError("[tile_fetch] Tile URL must use http or https.")
    rest = url[scheme_sep + 3:]
    end = len(rest)
    for sep in ("/", "?", "#"):
        idx = rest.find(sep)
        if idx != -1:
            end = min(end, idx)
    authority, tail = rest[:end], rest[end:]
    at = authority.rfind("@")
    prefix, host_port = ("", authority) if at == -1 else (authority[: at + 1], authority[at + 1:])
    if host_port.startswith("["):
        close = host_port.find("]")
        host = host_port[: close + 1] if close != -1 else host_port
    else:
        host = host_port.split(":")[0]
    # Rebuild the authority with the host lowercased and any port preserved.
    port = ""
    if not host_port.startswith("[") and ":" in host_port:
        port = ":" + host_port.split(":", 1)[1]
    return "%s://%s%s%s%s" % (scheme, prefix, host.lower().rstrip("."), port, tail)


def identity_key(url: str) -> str:
    """Hash of the normalised template.

    The host alone is not an identity: one server commonly serves several styles, and
    /styleA/{z}/{x}/{y}, /styleB/{z}/{x}/{y}, a different port or a query carrying an account are
    different streams. Writing them into one archive while the per-tile skip check keys on z/x/y
    makes one style's tile look already downloaded to another, so the archive silently alternates
    between two renderings of the same coordinates.

    Hashed rather than stored verbatim because the template can carry an API key in its query and
    the archive outlives the shell history that produced it.
    """
    import hashlib

    return hashlib.sha256(normalize_template(url).encode("utf-8")).hexdigest()[:16]


def provenance_label(url: str) -> str:
    """Host and port only.

    The path is excluded because a credential in a path is a normal tile URL shape, not an exotic
    one: /token/SECRET/{z}/{x}/{y}.png. Storing the path would write that secret into an archive
    that gets copied and backed up.
    """
    normalized = normalize_template(url)
    rest = normalized[normalized.find("://") + 3:]
    end = len(rest)
    for sep in ("/", "?", "#"):
        idx = rest.find(sep)
        if idx != -1:
            end = min(end, idx)
    authority = rest[:end]
    at = authority.rfind("@")
    return authority[at + 1:] if at != -1 else authority


class _RefuseRedirects(urllib.request.HTTPRedirectHandler):
    """urllib follows redirects silently by default.

    A redirect moves the download onto a host the policy above never examined, so it is
    refused rather than followed.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D102
        raise BulkPolicyError(
            "[tile_fetch] Refusing redirect (%s) from %s to %s. The redirect target was not "
            "checked against the bulk policy." % (code, req.full_url, newurl))


_OPENER = urllib.request.build_opener(_RefuseRedirects())


def lat_lon_to_tile(lat: float, lon: float, zoom: int) -> Tuple[int, int]:
    """Convert lat/lon to tile x/y at the given zoom level (slippy map convention)."""
    n = 2 ** zoom
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n)
    # Clamp to valid tile range
    x = max(0, min(n - 1, x))
    y = max(0, min(n - 1, y))
    return x, y


def tile_range(bbox: str, zoom: int) -> Iterator[Tuple[int, int, int]]:
    """Yield (z, x, y) tile coords for the given bbox string 'min_lat,min_lon,max_lat,max_lon'."""
    min_lat, min_lon, max_lat, max_lon = map(float, bbox.split(","))
    x_min, y_max = lat_lon_to_tile(min_lat, min_lon, zoom)
    x_max, y_min = lat_lon_to_tile(max_lat, max_lon, zoom)
    for x in range(x_min, x_max + 1):
        for y in range(y_min, y_max + 1):
            yield zoom, x, y


def init_mbtiles(path: str) -> sqlite3.Connection:
    """Create (or open) an MBTiles SQLite file and initialize the required tables."""
    conn = sqlite3.connect(path)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS metadata (
            name  TEXT,
            value TEXT,
            PRIMARY KEY (name)
        )
    """)
    conn.execute("""
        CREATE TABLE IF NOT EXISTS tiles (
            zoom_level  INTEGER,
            tile_column INTEGER,
            tile_row    INTEGER,
            tile_data   BLOB,
            PRIMARY KEY (zoom_level, tile_column, tile_row)
        )
    """)
    conn.commit()
    return conn


def set_metadata(conn: sqlite3.Connection, name: str, **kwargs: str) -> None:
    for key, val in kwargs.items():
        conn.execute("INSERT OR REPLACE INTO metadata(name, value) VALUES (?, ?)", (key, val))
    conn.commit()


def fetch_tile(url_template: str, z: int, x: int, y: int) -> bytes:
    """Download a single tile. Raises urllib.error.URLError on failure."""
    url = url_template.replace("{z}", str(z)).replace("{x}", str(x)).replace("{y}", str(y))
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with _OPENER.open(req, timeout=30) as resp:
        return resp.read()


def main() -> None:
    parser = argparse.ArgumentParser(description="Download map tiles to MBTiles for AirTAK offline use")
    parser.add_argument("--bbox",   required=True, help="Bounding box: min_lat,min_lon,max_lat,max_lon")
    parser.add_argument("--zoom",   required=True, help="Zoom range: 10 or 10-16")
    parser.add_argument("--output", required=True, help="Output .mbtiles file path")
    parser.add_argument("--url",    required=True, help="Tile URL template with {z}, {x}, {y} placeholders")
    parser.add_argument("--attribution", default=None,
                        help="Data attribution / licence notice required by your tile source. "
                             "This tool will not guess one from the hostname.")
    parser.add_argument("--delay",  type=float, default=DEFAULT_DELAY_SECONDS,
                        help=f"Delay between requests in seconds (default {DEFAULT_DELAY_SECONDS})")
    args = parser.parse_args()

    # Checked before the database is created or any request is made.
    provider_host = check_bulk_policy(args.url)
    if args.delay < MIN_DELAY_SECONDS:
        raise BulkPolicyError(
            "[tile_fetch] --delay %s is below the %s s floor. Bulk fetching faster than one "
            "request per second is not something this tool will do." % (args.delay, MIN_DELAY_SECONDS))

    # Parse zoom range
    if "-" in args.zoom:
        z_min, z_max = map(int, args.zoom.split("-"))
    else:
        z_min = z_max = int(args.zoom)

    os.makedirs(os.path.dirname(os.path.abspath(args.output)) or ".", exist_ok=True)
    conn = init_mbtiles(args.output)
    set_metadata(conn,
                 name=os.path.basename(args.output),
                 format="png",
                 bounds=args.bbox,
                 minzoom=str(z_min),
                 maxzoom=str(z_max),
                 type="overlay",
                 version="1",
                 # Source identity, not a licence statement. See the attribution block below.
                 provider_host=provider_host,
                 provider_identity=identity_key(args.url))

    # Attribution and licence are the user's to supply, never this tool's to invent.
    #
    # Recording `attribution=provider:tiles.example.com` would look like a compliance field while
    # asserting nothing true. A hostname is not a data attribution, not a licence and not a
    # copyright statement. Tile servers commonly proxy data from OpenStreetMap or a commercial
    # provider under terms that require a specific notice, and guessing it from the host would
    # fabricate a claim that then survives into every copy of the archive.
    if args.attribution:
        set_metadata(conn, attribution=args.attribution, attribution_status="user-supplied")
    else:
        set_metadata(
            conn,
            attribution_status="MISSING - provider host is not a data attribution; pass "
                               "--attribution before publishing or sharing this archive",
        )
        print(
            "[tile_fetch] WARNING: no --attribution given. The archive records which host it came\n"
            "  from, which is NOT a data attribution or a licence. Add the notice your tile source\n"
            '  requires, for example: --attribution "\u00a9 Some Provider, CC BY 4.0".',
            file=sys.stderr,
        )

    total = sum(1 for z in range(z_min, z_max + 1) for _ in tile_range(args.bbox, z))
    print(f"[tile_fetch] {total} tiles ({z_min}-{z_max}) from {provider_host} -> {args.output}")
    print(f"[tile_fetch] Pacing at 1 request per {args.delay:g}s; expect ~"
          f"{total * args.delay / 60:.0f} min. Press Ctrl-C to stop; stored tiles stay valid.")

    # run_download closes the connection on every path, including interruption.
    run_download(conn, args.bbox, z_min, z_max, args.url, args.delay, total)


def run_download(
    conn: sqlite3.Connection,
    bbox: str,
    z_min: int,
    z_max: int,
    url_template: str,
    delay_seconds: float,
    total: int,
    fetch=fetch_tile,
    sleep=time.sleep,
) -> Tuple[int, int]:
    """Fetch tiles into an open database.

    Extracted from main() so cancellation and database cleanup are testable. The connection is
    always closed, including on KeyboardInterrupt and on an unexpected error: the previous inline
    loop left the database open with a partial transaction when the user pressed Ctrl-C, which is
    how an archive ends up unreadable after a long download is stopped.
    """
    downloaded = 0
    skipped = 0
    interrupted: Optional[BaseException] = None
    try:
        for z in range(z_min, z_max + 1):
            for _, x, y in tile_range(bbox, z):
                tms_y = (2 ** z) - 1 - y
                exists = conn.execute(
                    "SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                    (z, x, tms_y),
                ).fetchone()
                if exists:
                    skipped += 1
                    continue

                try:
                    data = fetch(url_template, z, x, y)
                except (KeyboardInterrupt, SystemExit) as exc:
                    interrupted = exc
                    break
                except Exception as exc:
                    print(f"  [WARN] Failed to fetch {z}/{x}/{y}: {exc}", file=sys.stderr)
                    continue

                conn.execute(
                    "INSERT OR REPLACE INTO tiles(zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)",
                    (z, x, tms_y, sqlite3.Binary(data)),
                )
                conn.commit()
                downloaded += 1
                if downloaded % 100 == 0:
                    print(f"  {downloaded}/{total} tiles downloaded...")
                sleep(delay_seconds)
            if interrupted is not None:
                break
    finally:
        # Commit what is already stored so a stopped download leaves a usable partial archive,
        # then close. Tiles already written are valid; the archive is simply incomplete.
        try:
            conn.commit()
        except sqlite3.Error:
            pass
        conn.close()

    if interrupted is not None:
        print(
            f"[tile_fetch] Stopped after {downloaded} tiles. Stored tiles are valid; the area is "
            f"partial. Re-run to resume; existing tiles are skipped.",
            file=sys.stderr,
        )
        raise interrupted

    print(f"[tile_fetch] Done. Downloaded: {downloaded}, Skipped (cached): {skipped}")
    return downloaded, skipped


if __name__ == "__main__":
    main()
