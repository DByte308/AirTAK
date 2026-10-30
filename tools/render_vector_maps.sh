#!/usr/bin/env bash
# Render vector tiles (.pbf .mbtiles) from an OSM extract with tilemaker, in a local rootless
# Podman image. No sudo, no host installs, no large toolchain. (issue #7 / #8)
#
# The image is built from the tilemaker 3.2.0 source at tag c7d1dbf and is local only. It is
# never published. The command form is fixed: --input PBF, --output MBTILES, --config, --process.
#
# Usage:
#   tools/render_vector_maps.sh <input.osm.pbf> <output.mbtiles> \
#        [config.json] [process.lua]
#
# Defaults to tools/vector_maps/config.json + tools/vector_maps/process.lua when omitted.
#
# The output tiles are gzip-compressed protobuf (config.json sets "compress": "gzip"), format
# `pbf`, and the metadata carries attribution "© OpenStreetMap contributors, ODbL 1.0". The app
# decodes the gzip and serves application/vnd.mapbox-vector-tile (see TileServer.kt).

set -euo pipefail

IMAGE="${AIRTAK_TILEMAKER_IMAGE:-localhost/airtak-tilemaker:3.2.0-c7d1dbf}"

INPUT="${1:?usage: render_vector_maps.sh <input.osm.pbf> <output.mbtiles> [config] [process]}"
OUTPUT="${2:?usage: render_vector_maps.sh <input.osm.pbf> <output.mbtiles> [config] [process]}"
CONFIG="${3:-$(dirname "$0")/vector_maps/config.json}"
PROCESS="${4:-$(dirname "$0")/vector_maps/process.lua}"

INPUT="$(readlink -f "$INPUT")"
OUTPUT="$(readlink -f "$OUTPUT")"
CONFIG="$(readlink -f "$CONFIG")"
PROCESS="$(readlink -f "$PROCESS")"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for path in "$INPUT" "$CONFIG" "$PROCESS"; do
  [ -f "$path" ] || { echo "FAIL: missing $path" >&2; exit 1; }
done

cp "$CONFIG" "$WORK/config.json"
cp "$PROCESS" "$WORK/process.lua"
cp "$INPUT" "$WORK/input.osm.pbf"
mkdir -p "$(dirname "$OUTPUT")"

echo "Rendering $(basename "$INPUT") -> $(basename "$OUTPUT") with $IMAGE ..."
podman run --rm -v "$WORK:/work" "$IMAGE" \
  --input /work/input.osm.pbf \
  --output /work/out.mbtiles \
  --config /work/config.json \
  --process /work/process.lua

cp "$WORK/out.mbtiles" "$OUTPUT"
echo "Wrote $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
