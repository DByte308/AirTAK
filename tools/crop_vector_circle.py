#!/usr/bin/env python3
# AirTAK — tools/crop_vector_circle.py
# Keep only the tiles whose EXTENT intersects a given radius around a centre point.
#
# This is the real (non-synthetic) helper for the "50 km radius around Friedberg" package. The
# crop is by RADIUS, not by the radius's bounding box: a tile is kept when its bounding box
# comes within `radius` metres of the centre. Because a tile at zoom z is quite large relative to
# 50 km, the difference between a radius crop and a bbox crop is material (a bbox crop would keep
# the four corner tiles of the box, which extend well outside the circle).
#
# The distance test is equirectangular (valid and accurate for offsets well under a degree):
#     dx_km = (lon - lon0) * cos(lat0) * 111.32
#     dy_km = (lat - lat0) * 111.32
# and the closest point of a tile's lon/lat bbox to the centre is the clamped projection, so a
# tile intersects the circle iff that distance <= radius.
#
# Two subcommands:
#   predict — print the per-zoom tile counts a radius package WOULD contain (no input needed).
#   crop    — read a tilemaker MBTiles, copy only the circle-intersecting rows into a new archive.
#
# Usage:
#   python3 tools/crop_vector_circle.py predict --center 50.3333,8.75 --radius 50 --zoom 6-12
#   python3 tools/crop_vector_circle.py crop  IN.mbtiles OUT.mbtiles \
#       --center 50.3333,8.75 --radius 50 --zoom 6-12 \
#       [--attribution "© OpenStreetMap contributors, ODbL 1.0"] \
#       [--name "AirTAK offline field map"]
#
# The input is opened read-only and never mutated. The output is created brand new (an existing
# file is refused). Metadata is copied from the source and its `bounds` is reset to the circle's
# bounding box (W,S,E,N); `minzoom`/`maxzoom` are set from the actual rows kept.

import argparse
import json
import math
import sqlite3
import sys
from pathlib import Path

# WGS-84 → km approximations. Equirectangular, valid well below a degree of offset.
KM_PER_DEG_LAT = 111.32

DEFAULT_ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0"
DEFAULT_NAME = "AirTAK offline field map"


# --- slippy tile <-> lat/lon ---------------------------------------------------------------

def lat_lon_to_tile(lat: float, lon: float, zoom: int):
    lat = max(-85.051128, min(85.051128, lat))
    n = 2 ** zoom
    x = int((lon + 180.0) / 360.0 * n)
    lat_rad = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_rad) + 1.0 / math.cos(lat_rad)) / math.pi) / 2.0 * n)
    x = max(0, min(n - 1, x))
    y = max(0, min(n - 1, y))
    return x, y


def tile_lon(xtile: int, zoom: int) -> float:
    return xtile / float(2 ** zoom) * 360.0 - 180.0


def tile_lat(ytile: int, zoom: int) -> float:
    n = math.pi - 2.0 * math.pi * ytile / float(2 ** zoom)
    return math.degrees(math.atan(math.sinh(n)))


def tile_bounds(xtile: int, ytile: int, zoom: int):
    """Return (west, south, east, north) degrees for a tile."""
    west = tile_lon(xtile, zoom)
    east = tile_lon(xtile + 1, zoom)
    north = tile_lat(ytile, zoom)
    south = tile_lat(ytile + 1, zoom)
    return west, south, east, north


# --- circle intersection ------------------------------------------------------------

def distance_km_to_bbox(lon0: float, lat0: float, west, south, east, north) -> float:
    """Equirectangular distance (km) from (lon0,lat0) to the closest point of a lon/lat bbox."""
    cos_lat = math.cos(math.radians(lat0))
    closest_lon = min(max(lon0, west), east)
    closest_lat = min(max(lat0, south), north)
    dx = (closest_lon - lon0) * cos_lat * KM_PER_DEG_LAT
    dy = (closest_lat - lat0) * KM_PER_DEG_LAT
    return math.hypot(dx, dy)


def circle_tiles(lat0: float, lon0: float, radius_km: float, zmin: int, zmax: int):
    """Yield (z,x,y) tiles whose extent intersects the circle. Geometry-only, no input needed."""
    for z in range(zmin, zmax + 1):
        # Longitude here is in the circle's own degree span (radius in degrees of longitude
        # at lat0), over-covering for latitude near the pole where the Mercator tile space
        # pinches; the intersection test below prunes the over-coverage.
        dLon = radius_km / (KM_PER_DEG_LAT * max(math.cos(math.radians(lat0)), 1e-6))
        dLat = radius_km / KM_PER_DEG_LAT
        x0, _ = lat_lon_to_tile(lat0, lon0 - dLon, z)
        x1, _ = lat_lon_to_tile(lat0, lon0 + dLon, z)
        _, y0 = lat_lon_to_tile(lat0 - dLat, lon0, z)
        _, y1 = lat_lon_to_tile(lat0 + dLat, lon0, z)
        for x in range(x0, x1 + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                west, south, east, north = tile_bounds(x, y, z)
                if distance_km_to_bbox(lon0, lat0, west, south, east, north) <= radius_km:
                    yield z, x, y


def circles_bbox_deg(lat0: float, lon0: float, radius_km: float):
    """Bounding box (west, south, east, north) of the circle, in degrees."""
    dLon = radius_km / (KM_PER_DEG_LAT * max(math.cos(math.radians(lat0)), 1e-6))
    dLat = radius_km / KM_PER_DEG_LAT
    return (lon0 - dLon, lat0 - dLat, lon0 + dLon, lat0 + dLat)


# --- subcommands ----------------------------------------------------------------

def cmd_predict(args):
    lat0, lon0 = args.center
    total = 0
    print("[crop_vector_circle] circle: center=(%.5f,%.5f) r=%.1f km  zoom %d-%d"
          % (lat0, lon0, args.radius, args.zmin, args.zmax))
    print("%-8s %10s" % ("zoom", "tiles"))
    per = {}
    for z, x, y in circle_tiles(lat0, lon0, args.radius, args.zmin, args.zmax):
        per.setdefault(z, 0)
        per[z] += 1
        total += 1
    for z in sorted(per):
        print("%-8s %10d" % (z, per[z]))
    print("%-8s %10d" % ("total", total))
    return 0


def cmd_crop(args):
    src = Path(args.input)
    if not src.exists():
        print("[crop_vector_circle] ERROR: input not found: %s" % src, file=sys.stderr)
        return 2
    out = Path(args.output)
    if out.exists():
        print("[crop_vector_circle] ERROR: output already exists: %s (refusing to overwrite)"
              % out, file=sys.stderr)
        return 2

    lat0, lon0 = args.center
    # Open source read-only.
    src_uri = src.resolve().as_uri() + "?mode=ro"
    sconn = sqlite3.connect(src_uri, uri=True)

    # Build the geometry-only expected set, then copy matching rows. The expected set is built
    # in XYZ/slippy tile coordinates (what lat_lon_to_tile returns); the MBTiles archive stores
    # tile_row in TMS (bottom-up), so flip the source row's y before comparing.
    keep = set(circle_tiles(lat0, lon0, args.radius, args.zmin, args.zmax))
    print("[crop_vector_circle] geometry-only expected tiles: %d" % len(keep))

    out_conn = sqlite3.connect(str(out))
    out_conn.execute("CREATE TABLE metadata (name TEXT, value TEXT, PRIMARY KEY (name))")
    out_conn.execute("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, "
                     "tile_row INTEGER, tile_data BLOB, PRIMARY KEY (zoom_level, tile_column, tile_row))")

    src_meta = {str(n): str(v) for n, v in sconn.execute("SELECT name, value FROM metadata")}

    copied = 0
    missing = set(keep)
    zoom_keep = {}
    for z, x, y, data in sconn.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles"):
        zi = int(z)
        xyz_y = ((1 << zi) - 1) - y          # MBTiles stores TMS (flipped) y
        if (zi, x, xyz_y) in keep:
            out_conn.execute("INSERT INTO tiles(zoom_level, tile_column, tile_row, tile_data) "
                             "VALUES (?, ?, ?, ?)", (zi, x, y, sqlite3.Binary(data)))
            zoom_keep.setdefault(zi, 0)
            zoom_keep[zi] += 1
            copied += 1
            missing.discard((zi, x, xyz_y))

    if copied != len(keep):
        print("[crop_vector_circle] WARNING: copied %d tiles but geometry predicts %d; "
              "%d expected tile(s) absent from the source." % (copied, len(keep), len(missing)),
              file=sys.stderr)

    # Rewrite metadata for the cropped package.
    meta = dict(src_meta)
    west, south, east, north = circles_bbox_deg(lat0, lon0, args.radius)
    meta["bounds"] = "%.7f,%.7f,%.7f,%.7f" % (west, south, east, north)
    meta["name"] = args.name
    meta["attribution"] = args.attribution
    zmin = min(zoom_keep) if zoom_keep else args.zmin
    zmax = max(zoom_keep) if zoom_keep else args.zmax
    meta["minzoom"] = str(zmin)
    meta["maxzoom"] = str(zmax)
    for k in list(meta):
        if isinstance(k, str) and k.startswith("center"):
            pass
    # Reset geometry/identity metadata for the cropped package.
    meta["center"] = "%.7f,%.7f" % (lon0, lat0)
    meta["radius_m"] = str(int(args.radius * 1000))
    for k, v in meta.items():
        out_conn.execute("INSERT OR REPLACE INTO metadata(name, value) VALUES (?, ?)", (k, v))

    out_conn.commit()
    out_conn.close()
    sconn.close()

    print("[crop_vector_circle] wrote %s  (%d tiles, zooms %d-%d)" % (out, copied, zmin, zmax))
    print("[crop_vector_circle] bounds W,S,E,N = %.7f,%.7f,%.7f,%.7f" % (west, south, east, north))
    print("[crop_vector_circle] per-zoom:")

    total_comp = 0
    total_uncomp = 0
    zc = sqlite3.connect(str(out))
    for z in sorted(zoom_keep):
        # recompute sizes
        comp = zc.execute("SELECT SUM(LENGTH(tile_data)) FROM tiles WHERE zoom_level=?", (z,)).fetchone()[0] or 0
        total_comp += comp
        print("%-8s %10d %12s" % (z, zoom_keep[z], _human(comp)))
    zc.close()
    print("%-8s %10d %12s" % ("total", copied, _human(total_comp)))
    return 0


def _human(n):
    for unit in ("B", "KiB", "MiB", "GiB"):
        if abs(n) < 1024 or unit == "GiB":
            return "%.1f %s" % (n, unit) if unit != "B" else "%d B" % n
        n /= 1024.0


def parse_center(text):
    parts = text.split(",")
    if len(parts) != 2:
        raise argparse.ArgumentTypeError("center must be lat,lon")
    return (float(parts[0]), float(parts[1]))


def parse_zoom(text):
    if "-" in text:
        a, b = text.split("-", 1)
        return int(a), int(b)
    return int(text), int(text)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    def add_common(p):
        p.add_argument("--center", type=parse_center, required=True, help="center lat,lon")
        p.add_argument("--radius", type=float, required=True, help="radius in km")
        p.add_argument("--zoom", type=parse_zoom, default=(6, 12), help="zoom range, e.g. 6-12")

    p_pred = sub.add_parser("predict")
    add_common(p_pred)
    p_pred.set_defaults(func=cmd_predict)

    p_crop = sub.add_parser("crop")
    p_crop.add_argument("input")
    p_crop.add_argument("output")
    add_common(p_crop)
    p_crop.add_argument("--attribution", default=DEFAULT_ATTRIBUTION)
    p_crop.add_argument("--name", default=DEFAULT_NAME)
    p_crop.set_defaults(func=cmd_crop)

    args = parser.parse_args(argv)
    args.zmin, args.zmax = args.zoom
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
