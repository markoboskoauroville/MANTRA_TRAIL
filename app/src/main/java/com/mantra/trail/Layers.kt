package com.mantra.trail

import java.util.Locale

/**
 * THE MAPS, AND WHICH OF THEM SURVIVE LOSING THE SIGNAL. No Android imports (android-app.md 1).
 *
 * The whole point of this app is the map that is still there on the ridge, so every layer carries
 * the honest answer to one question: what does it show when the phone has no network?
 *
 *   OpenAndroMaps   everything. It is a file on the phone; there is nothing to fetch.
 *   TK25            everything that was fetched before, and nothing else. Croatia's official
 *                   1:25000, served as WMS by the state survey under an open licence.
 *   OpenTopoMap     the same: whatever is in the cache.
 *   Google          NOTHING, and it may not be otherwise. Google's Map Tiles and Maps SDK terms
 *                   forbid pre-fetching, caching or storing tiles, and name offline use as a
 *                   prohibited case. Building a cache behind it would be exactly the failure
 *                   silent-failure.md is about: right at home, empty on the mountain.
 */
enum class LayerKind {
    /** A .map file the person chose once and keeps. */
    VECTOR_FILE,

    /** z/x/y raster tiles, cached to a file. */
    RASTER_XYZ,

    /** A WMS, asked for one tile-shaped bounding box at a time, cached to a file. */
    WMS,

    /** Google's own view, drawn by Google's SDK, online only. */
    GOOGLE,
}

data class MapLayer(
    val id: String,
    val label: String,
    val kind: LayerKind,
    /** What it can still draw with no network at all. */
    val offline: Offline,
    val attribution: String,
    /** Null for the layers that are not fetched by us. */
    val url: String? = null,
    val maxZoom: Int = 17,
    val minZoom: Int = 2,
) {
    enum class Offline { COMPLETE, CACHED_ONLY, NONE }

    /** True only for the layers whose licence allows their tiles to be kept on the phone. */
    val cacheable: Boolean get() = kind == LayerKind.RASTER_XYZ || kind == LayerKind.WMS
}

object Layers {

    /**
     * The offline hiking map: OpenStreetMap rendered for the mountains, contour lines included,
     * one file per country. Chosen with the file picker (design-language.md 17) and kept in a
     * folder the person picks, so it survives an uninstall.
     */
    val OAM = MapLayer(
        id = "oam",
        label = "OpenAndroMaps",
        kind = LayerKind.VECTOR_FILE,
        offline = MapLayer.Offline.COMPLETE,
        attribution = "OpenStreetMap contributors, OpenAndroMaps",
    )

    /**
     * The Croatian state survey's 1:25000. Anonymous WMS, open licence. EPSG:3857 and a 256 px
     * PNG were both proved against the live service on 14.9.2026 before this line was written.
     */
    val TK25 = MapLayer(
        id = "tk25",
        label = "TK25 Hrvatska",
        kind = LayerKind.WMS,
        offline = MapLayer.Offline.CACHED_ONLY,
        attribution = "Državna geodetska uprava",
        url = "https://geoportal.dgu.hr/services/tk/ows",
        maxZoom = 16,
        minZoom = 8,
    )

    val OPENTOPO = MapLayer(
        id = "opentopo",
        label = "OpenTopoMap",
        kind = LayerKind.RASTER_XYZ,
        offline = MapLayer.Offline.CACHED_ONLY,
        attribution = "OpenStreetMap contributors, SRTM, OpenTopoMap (CC-BY-SA)",
        url = "https://tile.opentopomap.org/{z}/{x}/{y}.png",
        maxZoom = 17,
    )

    val GOOGLE = MapLayer(
        id = "google",
        label = "Google",
        kind = LayerKind.GOOGLE,
        offline = MapLayer.Offline.NONE,
        attribution = "Google",
        maxZoom = 21,
    )

    /** The order they appear in the switcher: the one that works everywhere comes first. */
    val ALL: List<MapLayer> = listOf(OAM, TK25, OPENTOPO, GOOGLE)

    fun byId(id: String): MapLayer = ALL.firstOrNull { it.id == id } ?: OAM

    /**
     * One tile's URL. For the WMS this is a GetMap over the tile's own bounding box in EPSG:3857,
     * which is why Geo carries the bounding box arithmetic.
     */
    fun tileUrl(layer: MapLayer, zoom: Int, x: Int, y: Int): String? = when (layer.kind) {
        LayerKind.RASTER_XYZ -> layer.url
            ?.replace("{z}", zoom.toString())
            ?.replace("{x}", x.toString())
            ?.replace("{y}", y.toString())

        LayerKind.WMS -> {
            val b = Geo.tileBbox3857(zoom, x, y)
            layer.url + "?SERVICE=WMS&VERSION=1.1.1&REQUEST=GetMap&LAYERS=tk:TK25&STYLES=" +
                "&FORMAT=image/png&TRANSPARENT=FALSE&SRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX=" +
                String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", b[0], b[1], b[2], b[3])
        }

        else -> null
    }
}
