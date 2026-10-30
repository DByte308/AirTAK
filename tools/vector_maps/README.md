# AirTAK offline vector map generation

This directory holds the tooling that turns an OpenStreetMap `.osm.pbf` extract into an
AirTAK offline **vector** map package (an MBTiles of Mapbox Vector Tiles). It complements the
raster `tools/tile_fetch.py` path described in `docs/tools-mbtiles.md`.

Files:

- `config.json` — tilemaker render config. Fixes `minzoom=6`, `maxzoom=12`, `basezoom=12`,
  layer set (`landuse`, `water`, `waterway`, `transportation`, `building`, `place`) and embeds
  the attribution in `settings.metadata.attribution`. Compression is `gzip`.
- `process.lua` — tilemaker tag-processing Lua that maps OSM tags to the AirTAK minimal vector
  schema v1 (`airtak-minimal-v1`), a deliberately small schema for offline field use. It uses
  only OSM tags already present in the input; no external coastline or landcover downloads.
- `make_fixture.py` — writes a tiny, wholly synthetic OSM PBF (valid `OSMHeader`+`OSMData`
  blobs, DenseNodes) centred on Friedberg. Used to smoke-test the pipeline without any download.

## The render toolchain

The tilemaker binary is **not** built or run directly on the host. It is packaged in a Podman
image that is already built and proven:

```
localhost/airtak-tilemaker:3.2.0-c7d1dbf
```

(image built from `tilemaker-src` at tag `c7d1dbf`, tilemaker v3.2.0; source checkout at
`/home/d/output/airtak-render/tilemaker-src`).

### Verified podman run command

```bash
podman run --rm -v /tmp/<workdir>:/work localhost/airtak-tilemaker:3.2.0-c7d1dbf \
  --input /work/<input>.pbf --output /work/<out>.mbtiles \
  --config /work/config.json --process /work/process.lua
```

The image's entrypoint is `/build/tilemaker`, so everything after the image name is a
tilemaker flag. The `-v /tmp/<workdir>:/work` bind mount is required so the container can read
the input and write the output; keep the input PBF, config and process lua in that dir.

`tools/render_vector_maps.sh` (one level up) wraps exactly this command.

### Rendering facts (important)

- **Output is vector, not raster.** `tiles.tile_data` holds Mapbox Vector Tiles (MVT). The
  archive's `metadata.format` is `pbf`.
- **`tile_data` is gzip-compressed MVT.** `config.json` sets `settings.compress = "gzip"`. In
  tilemaker this compresses each tile's protobuf payload with gzip before it is stored as the
  `tile_data` BLOB, so the MVT payload in `tile_data` is **not** a bare protobuf stream — a
  consumer must decompress (gzip) it first, then parse the MVT.
- **`metadata` carries the vector schema and attribution.** `config.json` sets:
  - `airtak:vector-schema` = `airtak-minimal-v1`
  - `attribution` = `© OpenStreetMap contributors, ODbL 1.0`
  - `license` = `https://www.openstreetmap.org/copyright`

  Verify these survive into the output archive's `metadata` table (see `verify_vector_mbtiles`).

### The `metadata.format = pbf` open question

This image emits an MVT archive whose `metadata.format` is `pbf` and whose `tile_data` is
**gzip-compressed**. Whether the AirTAK client's MVT loader (issue #7, being built in
parallel) expects a bare MVT protobuf or a gzip-compressed MVT — and whether it keys on
`format = "pbf"` / `"mvt"` / `"pbf.gz"` — is an open question to resolve against the loader's
implementation. The archive produced here is the `pbf` + gzip form; if the loader needs a
different form, regenerate with the matching `compress` setting.

## Validate a produced archive

```bash
python3 tools/verify_vector_mbtiles.py /tmp/work/out.mbtiles
```

That script is read-only and checks: `metadata.format == "pbf"`, every `tile_data` starts with a
proprietary gzip magic header (`1f 8b`), that vector layers are declared in `metadata`, that the
attribution survives, and it tallies per-zoom tile counts and uncompressed/compressed sizes.
