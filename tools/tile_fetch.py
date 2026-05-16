#!/usr/bin/env python3
# AirTAK — Airsoft Team Awareness Kit
# tools/tile_fetch.py — Download offline map tiles to MBTiles
# Planning: §11.5 | Roadmap: T.6
#
# Usage:
#   python tools/tile_fetch.py \
#     --bbox "51.5,-0.2,51.6,-0.1" \
#     --zoom 10-16 \
#     --output tiles/london.mbtiles \
#     --url "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
#
# The resulting .mbtiles file can be loaded by TileRepository on Android.
# IMPORTANT: Respect the tile provider's terms of service and rate limits.
# For production, use a self-hosted tile server or a licensed tile provider.

import argparse
import math
import sqlite3
import time
import urllib.request
import os
import sys
from typing import Tuple, Iterator

TILE_SIZE = 256
DEFAULT_DELAY_SECONDS = 0.1  # Polite rate limit (10 req/s)


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
    req = urllib.request.Request(url, headers={"User-Agent": "AirTAK-tile-fetch/1.0 (+https://github.com/your-repo/airtak)"})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.read()


def main() -> None:
    parser = argparse.ArgumentParser(description="Download map tiles to MBTiles for AirTAK offline use")
    parser.add_argument("--bbox",   required=True, help="Bounding box: min_lat,min_lon,max_lat,max_lon")
    parser.add_argument("--zoom",   required=True, help="Zoom range: 10 or 10-16")
    parser.add_argument("--output", required=True, help="Output .mbtiles file path")
    parser.add_argument("--url",    required=True, help="Tile URL template with {z}, {x}, {y} placeholders")
    parser.add_argument("--delay",  type=float, default=DEFAULT_DELAY_SECONDS,
                        help=f"Delay between requests in seconds (default {DEFAULT_DELAY_SECONDS})")
    args = parser.parse_args()

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
                 version="1")

    total = sum(1 for z in range(z_min, z_max + 1) for _ in tile_range(args.bbox, z))
    downloaded = 0
    skipped    = 0

    print(f"[tile_fetch] Downloading ~{total} tiles ({z_min}–{z_max}) to {args.output}")

    for z in range(z_min, z_max + 1):
        for _, x, y in tile_range(args.bbox, z):
            # MBTiles uses TMS y-axis (inverted vs slippy map)
            tms_y = (2 ** z) - 1 - y
            exists = conn.execute(
                "SELECT 1 FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                (z, x, tms_y)
            ).fetchone()
            if exists:
                skipped += 1
                continue

            try:
                data = fetch_tile(args.url, z, x, y)
                conn.execute(
                    "INSERT OR REPLACE INTO tiles(zoom_level, tile_column, tile_row, tile_data) VALUES (?,?,?,?)",
                    (z, x, tms_y, sqlite3.Binary(data))
                )
                conn.commit()
                downloaded += 1
                if downloaded % 100 == 0:
                    print(f"  {downloaded}/{total} tiles downloaded…")
                time.sleep(args.delay)
            except Exception as exc:
                print(f"  [WARN] Failed to fetch {z}/{x}/{y}: {exc}", file=sys.stderr)

    print(f"[tile_fetch] Done. Downloaded: {downloaded}, Skipped (cached): {skipped}")
    conn.close()


if __name__ == "__main__":
    main()
