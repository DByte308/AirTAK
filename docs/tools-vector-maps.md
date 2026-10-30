# AirTAK offline vector maps (issues #7 and #8)

AirTAK serves raster tiles from MBTiles archives and, since the app-side MVT work, also serves
**vector tiles** from MBTiles archives whose `metadata.format` is `pbf`. This directory holds the
render pipeline and the schema the app renders against.

## What this produces

A `.mbtiles` with:

- `metadata.format` = `pbf`
- `tile_data` = **gzip-compressed** Mapbox Vector Tile (MVT) protobuf (config sets `"compress": "gzip"`)
- `metadata.json` = `vector_layers` whose ids are `landuse, water, waterway, transportation, building, place`
- `metadata.attribution` = `© OpenStreetMap contributors, ODbL 1.0`

The app inflates the gzip in `TileServer` and serves `application/vnd.mapbox-vector-tile`. The
MapLibre style in `MapScreen.kt` renders the layers above by `source-layer` id, so the schema
here and the style there are a single contract: change one and the other breaks the map.

## Files

| file | purpose |
|------|---------|
| `config.json` | tilemaker config: layer set, min/max zoom, `compress: gzip`, schema + attribution metadata |
| `process.lua` | tilemaker Lua: which OSM tags become which layer/`class`, and the min zoom per feature |
| `make_fixture.py` | writes a synthetic `OSMHeader`/`OSMData` PBF with dense nodes (for CI smoke tests) |
| `render_vector_maps.sh` | runs the pinned tilemaker Podman image on a PBF to produce an `.mbtiles` |

## Render pipeline

The tilemaker image is built **locally, rootless** from the tilemaker 3.2.0 source at tag
`c7d1dbfaf87baa9e0bc9b2e984e0dae90dcf00c5` on a pinned Debian Bookworm base. It is never published,
and no host package is installed.

```bash
tools/render_vector_maps.sh input.osm.pbf out.mbtiles [config.json] [process.lua]
```

That is a thin wrapper over:

```bash
podman run --rm -v "$WORK:/work" localhost/airtak-tilemaker:3.2.0-c7d1dbf \
  --input /work/input.osm.pbf --output /work/out.mbtiles \
  --config /work/config.json --process /work/process.lua
```

`test/monaco.pbf` in the pinned tilemaker checkout is a small genuine Geofabrik extract that
upstream uses for `pbf_reader.test.cpp`; it is the right smoke fixture because it has real dense
nodes and features (the old synthetic `make_fixture.py` renders only the `place` layer).

## Schema contract (airtak-minimal-v1)

`process.lua` emits these layer ids and `class` values. The app style references exactly these:

| layer | geometry | `class` values | min zoom |
|-------|----------|----------------|----------|
| `landuse` | polygon | `residential, commercial, industrial, retail, forest, wood, scrub, park` | 6 |
| `water` | polygon | `water` | 6 |
| `waterway` | line | `waterway` (river/stream/canal) | 8 (10 for non-river) |
| `transportation` | line | `motorway, trunk, primary, secondary, tertiary, ..., rail` | 6 (8/10/12 by class) |
| `building` | polygon | `yes` etc. | 12 |
| `place` | point/symbol | `city, town, village, hamlet, ...` | 6 (9/11 by class) |

## Licensing and attribution

Source data is ODbL (`© OpenStreetMap contributors`). The config embeds attribution and licence in
the archive metadata, and the app renders the same attribution to the user (not only in a README).

## Notes / gotchas

- **Do not assume OpenMapTiles compatibility.** The style and metadata match the layers this
  pipeline emits, driven by `config.json` + `process.lua`, not OpenMapTiles.
- **Compression is a contract.** The tiles are gzip-compressed protobuf. If you change
  `"compress"` in `config.json`, update `TileServer` and its probe test accordingly.
- **Monaco is tiny.** It renders a handful of tiles; the 50 km package (issue #8) needs a real
  extract and is measured separately.
- The app's 15000-tile bulk-download cap exists to bound package size; it is not to be bypassed.
