package com.airtak.ui.map

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The self-authored map style must be valid JSON that MapLibre can parse, and its vector source and
 * layers must match the `airtak-minimal-v1` schema the tilemaker pipeline emits (issue #7).
 *
 * The generic vector rendering cannot be exercised on a JVM (it needs MapLibre's native renderer),
 * but the *contract* can: a source named `local-vector` of `type: vector`, referencing the `.pbf`
 * template, and layers whose `source-layer` ids are exactly the ones `tools/vector_maps/process.lua`
 * emits (landuse, water, waterway, transportation, building, place). If the pipeline or this style
 * drift apart, this test fails instead of silently shipping a blank map.
 */
class MapStyleJsonTest {

    private val vector = "http://127.0.0.1:12345/tiles/{z}/{x}/{y}.pbf?v=7"

    @Test
    fun emitsAValidVectorStyleWithThePipelineSchemaLayers() {
        val json = mapStyleJson(
            bgArgb = 0xFF101010.toInt(),
            baseTileTemplate = null,
            localTileTemplate = null,
            localTileMaxZoom = null,
            vectorTileTemplate = vector,
            vectorTileMaxZoom = 12,
            isDark = false,
        )
        val style = JSONObject(json)
        assertEquals(8, style.getInt("version"))
        assertEquals("AirTAK Field", style.getString("name"))

        val source = style.getJSONObject("sources").getJSONObject("local-vector")
        assertEquals("vector", source.getString("type"))
        assertEquals(12, source.getInt("maxzoom"))
        assertEquals(listOf(vector), (source.getJSONArray("tiles") as JSONArray).map { it.toString() })

        val layers = style.getJSONArray("layers")
        val sourceLayers = (0 until layers.length())
            .map { layers.getJSONObject(it) }
            .filter { it.has("source") && it.getString("source") == "local-vector" }
            .map { it.getString("source-layer") }
            .toSet()
        assertEquals(
            setOf("landuse", "water", "waterway", "transportation", "building", "place"),
            sourceLayers,
            "vector layers must match the tilemaker airtak-minimal-v1 schema",
        )
    }

    @Test
    fun vectorAndRasterSourcesCoexistAdditively() {
        // Issue #7 is additive: a raster source and a vector source must both appear when both a
        // local raster archive and a vector archive are present, and raster must not be removed.
        val raster = "http://127.0.0.1:12345/tiles/{z}/{x}/{y}.png?v=3"
        val json = mapStyleJson(
            bgArgb = 0xFF202020.toInt(),
            baseTileTemplate = "http://example.com/{z}/{x}/{y}.png",
            localTileTemplate = raster,
            localTileMaxZoom = 14,
            vectorTileTemplate = vector,
            vectorTileMaxZoom = 12,
            isDark = false,
        )
        val style = JSONObject(json)
        val sources = style.getJSONObject("sources")
        assertTrue(sources.has("local-tiles"), "raster local source must still be present")
        assertEquals("raster", sources.getJSONObject("local-tiles").getString("type"))
        assertEquals("vector", sources.getJSONObject("local-vector").getString("type"))
        assertEquals(14, sources.getJSONObject("local-tiles").getInt("maxzoom"))
    }

    @Test
    fun omitsVectorWhenNoVectorTemplate() {
        // No vector archive: the style must not reference a missing source.
        val json = mapStyleJson(
            bgArgb = 0xFF202020.toInt(),
            baseTileTemplate = null,
            localTileTemplate = null,
            localTileMaxZoom = null,
            vectorTileTemplate = null,
            vectorTileMaxZoom = null,
            isDark = false,
        )
        val style = JSONObject(json)
        assertTrue(!style.getJSONObject("sources").has("local-vector"), "no vector source without a template")
        val layers = style.getJSONArray("layers")
        (0 until layers.length()).forEach { i ->
            val layer = layers.getJSONObject(i)
            if (layer.has("source")) {
                assertTrue(
                    layer.getString("source") != "local-vector",
                    "no layer may reference a source that is absent",
                )
            }
        }
    }

    @Test
    fun vectorLayerJsonIsSelfConsistentJson() {
        // vectorLayerJson is the appended block; it must parse as a standalone array of layers
        // (mapStyleJson wraps it in the layers array). Validate it separately so a bad match-arm
        // cannot hide behind mapStyleJson.
        val block = vectorLayerJson(isDark = false, bgHex = "#202020")
        val trimmed = block.trim().removePrefix(",").trim()
        val arr = JSONArray("[$trimmed]")
        assertEquals(7, arr.length(), "expected 7 vector layers: landuse, water, waterway, road-casing, road, building, place")
        val place = arr.getJSONObject(arr.length() - 1)
        assertEquals("symbol", place.getString("type"))
        assertEquals("place", place.getString("source-layer"))
    }
}
