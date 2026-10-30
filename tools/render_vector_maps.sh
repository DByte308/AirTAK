#!/usr/bin/env bash
# AirTAK — tools/render_vector_maps.sh
# Render an OpenStreetMap extract to an AirTAK offline vector MBTiles with the proven
# podman tilemaker toolchain. See tools/vector_maps/README.md for the full picture.
#
# Usage:
#   tools/render_vector_maps.sh INPUT.pbf OUTPUT.mbtiles [CONFIG.json] [PROCESS.lua]
#
# Defaults: config.json and process.lua are taken from tools/vector_maps/ next to this script.
#
# The command run (image localhost/airtak-tilemaker:3.2.0-c7d1dbf is already built and proven):
#   podman run --rm -v IN_DIR:/in -v OUT_DIR:/out -v CFG_DIR:/cfg -v PRC_DIR:/prc \
#     localhost/airtak-tilemaker:3.2.0-c7d1dbf \
#     --input /in/INPUT.pbf --output /out/OUTPUT.mbtiles \
#     --config /cfg/CONFIG.json --process /prc/PROCESS.lua
#
# The input, output, config and process may live in different directories: each is bind-mounted
# to its own container path, so nothing is copied and nothing in the host is overwritten.
#
# Notes:
#   * The output .mbtiles stores gzip-compressed Mapbox Vector Tiles (metadata.format = "pbf").
#   * tilemaker is fed the input as-is (it honours the extract's own bbox); circular clipping is
#     applied afterward by tools/crop_vector_circle.py when a radius package is wanted.

set -o errexit
set -o pipefail
set -o nounset

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE="localhost/airtak-tilemaker:3.2.0-c7d1dbf"

INPUT="${1:?usage: render_vector_maps.sh INPUT.pbf OUTPUT.mbtiles [CONFIG.json] [PROCESS.lua]}"
OUTPUT="${2:?usage: render_vector_maps.sh INPUT.pbf OUTPUT.mbtiles [CONFIG.json] [PROCESS.lua]}"
CONFIG="${3:-${SCRIPT_DIR}/vector_maps/config.json}"
PROCESS="${4:-${SCRIPT_DIR}/vector_maps/process.lua}"

# Absolute paths so the bind mounts below are unambiguous.
INPUT="$(readlink -f "${INPUT}")"
OUTPUT="$(readlink -f "${OUTPUT}")"
CONFIG="$(readlink -f "${CONFIG}")"
PROCESS="$(readlink -f "${PROCESS}")"

for f in "${INPUT}" "${CONFIG}" "${PROCESS}"; do
  if [[ ! -f "${f}" ]]; then
    echo "[render_vector_maps] ERROR: missing required file: ${f}" >&2
    exit 1
  fi
done
mkdir -p "$(dirname "${OUTPUT}")"

IN_DIR="$(dirname "${INPUT}")"; IN_NAME="$(basename "${INPUT}")"
OUT_DIR="$(dirname "${OUTPUT}")"; OUT_NAME="$(basename "${OUTPUT}")"
CFG_DIR="$(dirname "${CONFIG}")"; CFG_NAME="$(basename "${CONFIG}")"
PRC_DIR="$(dirname "${PROCESS}")"; PRC_NAME="$(basename "${PROCESS}")"

echo "[render_vector_maps] rendering ${INPUT} -> ${OUTPUT}"
echo "[render_vector_maps]   config  : ${CONFIG}"
echo "[render_vector_maps]   process : ${PROCESS}"
echo "[render_vector_maps]   image   : ${IMAGE}"

podman run --rm \
  -v "${IN_DIR}:/in" \
  -v "${OUT_DIR}:/out" \
  -v "${CFG_DIR}:/cfg" \
  -v "${PRC_DIR}:/prc" \
  "${IMAGE}" \
  --input "/in/${IN_NAME}" \
  --output "/out/${OUT_NAME}" \
  --config "/cfg/${CFG_NAME}" \
  --process "/prc/${PRC_NAME}"

echo "[render_vector_maps] done: ${OUTPUT}"
