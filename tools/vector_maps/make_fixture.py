#!/usr/bin/env python3
"""Write a tiny, wholly synthetic OSM PBF for testing the offline rendering pipeline.

No download or protobuf package is needed. This is not real map data.
"""
import argparse
import struct
from pathlib import Path


def varint(value):
    if value < 0:
        raise ValueError("unsigned varint cannot be negative")
    out = bytearray()
    while value >= 128:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def sint(value):
    return (value << 1) ^ (value >> 63)


def integer(field, value):
    return varint(field << 3) + varint(value)


def message(field, payload):
    if isinstance(payload, str):
        payload = payload.encode("utf-8")
    return varint((field << 3) | 2) + varint(len(payload)) + payload


def packed(field, values):
    return message(field, b"".join(varint(x) for x in values))


def block(kind, payload):
    blob = message(1, payload) + integer(2, len(payload))
    header = message(1, kind) + integer(3, len(blob))
    return struct.pack(">I", len(header)) + header + blob


def make_fixture(path):
    strings = [""]

    def string_id(text):
        if text not in strings:
            strings.append(text)
        return strings.index(text)

    def tags(values):
        return packed(2, [string_id(k) for k in values]) + packed(3, [string_id(v) for v in values.values()])

    # Latitude/longitude, around Friedberg. Geometry is deliberately invented.
    positions = [
        (50.330, 8.730), (50.336, 8.770),
        (50.333, 8.744), (50.333, 8.750), (50.337, 8.750), (50.337, 8.744),
        (50.325, 8.735), (50.325, 8.746), (50.330, 8.746), (50.330, 8.735),
        (50.3310, 8.7530), (50.3310, 8.7540), (50.3318, 8.7540), (50.3318, 8.7530),
        (50.3333, 8.7500),
    ]
    # Tilemaker deliberately supports DenseNodes, not the optional non-dense encoding.
    ids, latitudes, longitudes, keys_values = [], [], [], []
    previous_id = previous_lat = previous_lon = 0
    for node_id, (lat, lon) in enumerate(positions, 1):
        latitude, longitude = round(lat * 10_000_000), round(lon * 10_000_000)
        ids.append(sint(node_id - previous_id))
        latitudes.append(sint(latitude - previous_lat))
        longitudes.append(sint(longitude - previous_lon))
        previous_id, previous_lat, previous_lon = node_id, latitude, longitude
        if node_id == 15:
            for key, value in {"place": "town", "name": "SYNTHETIC AirTAK fixture"}.items():
                keys_values.extend([string_id(key), string_id(value)])
        keys_values.append(0)
    dense = packed(1, ids) + packed(8, latitudes) + packed(9, longitudes) + packed(10, keys_values)
    group = bytearray(message(2, dense))

    ways = [
        ({"highway": "primary", "name": "Synthetic test road"}, [1, 2]),
        ({"natural": "water", "name": "Synthetic pond"}, [3, 4, 5, 6, 3]),
        ({"landuse": "forest"}, [7, 8, 9, 10, 7]),
        ({"building": "yes"}, [11, 12, 13, 14, 11]),
    ]
    for way_id, (attributes, refs) in enumerate(ways, 1):
        previous = 0
        deltas = []
        for ref in refs:
            deltas.append(sint(ref - previous))
            previous = ref
        group += message(3, integer(1, way_id) + tags(attributes) + packed(8, deltas))

    table = b"".join(message(1, text) for text in strings)
    primitive = message(1, table) + message(2, group)
    bbox = (integer(1, sint(8_700_000_000)) + integer(2, sint(8_800_000_000))
            + integer(3, sint(50_360_000_000)) + integer(4, sint(50_310_000_000)))
    header = (message(1, bbox) + message(4, "OsmSchema-V0.6") + message(4, "DenseNodes")
              + message(16, "AirTAK synthetic fixture"))
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(block("OSMHeader", header) + block("OSMData", primitive))
    return path


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    result = make_fixture(args.output)
    print(f"Synthetic fixture: {result} ({result.stat().st_size} bytes)")
