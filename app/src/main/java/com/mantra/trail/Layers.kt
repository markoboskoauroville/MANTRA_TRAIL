package com.mantra.trail

/**
 * THREE MAPS, AND ONE BUTTON THAT TURNS FROM ONE TO THE NEXT.
 *
 * Baba, 14.9.2026, on the four that were here: the Croatian TK25 and OpenTopoMap were crossed out
 * of the screenshot as useless, and the row of four chips came out with them. **A map is not
 * something he changes often**, so it is a toggle on the map screen and a list in the settings,
 * not five permanent chips across the bottom of the map.
 *
 * What each one does with no signal, which is the only question that matters in the mountains:
 *
 *   Offline map   everything. A mapsforge file on the phone; there is nothing to fetch.
 *   OpenStreetMap whatever is in the cache. CH fills it for the view you are looking at.
 *   Google        NOTHING, and it may not be otherwise. Google's terms forbid pre-fetching,
 *                 caching or storing tiles and name offline use as a prohibited case.
 */
enum class LayerKind {
    /** A .map file on the phone, downloaded once. */
    VECTOR_FILE,

    /** z/x/y raster tiles, cached to a file. */
    RASTER_XYZ,

    /** Google's own view, drawn by Google's SDK, online only. */
    GOOGLE,
}

data class MapLayer(
    val id: String,
    val label: String,
    /** The word under the toggle: three or four letters, because the button is small. */
    val short: String,
    val kind: LayerKind,
    val offline: Offline,
    val attribution: String,
    val url: String? = null,
    val maxZoom: Int = 18,
    val minZoom: Int = 2,
) {
    enum class Offline { COMPLETE, CACHED_ONLY, NONE }

    /** True only where the licence allows the tiles to be kept on the phone. */
    val cacheable: Boolean get() = kind == LayerKind.RASTER_XYZ
}

object Layers {

    /**
     * The offline map: a mapsforge file. The app can fetch Croatia itself (175 MB, from
     * mapsforge's own server) or take any .map file with the picker.
     */
    val OFFLINE = MapLayer(
        id = "offline",
        label = "Offline map",
        short = "OFF",
        kind = LayerKind.VECTOR_FILE,
        offline = MapLayer.Offline.COMPLETE,
        attribution = "OpenStreetMap contributors",
    )

    val OSM = MapLayer(
        id = "osm",
        label = "OpenStreetMap",
        short = "OSM",
        kind = LayerKind.RASTER_XYZ,
        offline = MapLayer.Offline.CACHED_ONLY,
        attribution = "OpenStreetMap contributors",
        url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        maxZoom = 18,
    )

    val GOOGLE = MapLayer(
        id = "google",
        label = "Google",
        short = "GGL",
        kind = LayerKind.GOOGLE,
        offline = MapLayer.Offline.NONE,
        attribution = "Google",
        maxZoom = 21,
    )

    /** The order the one button turns through. */
    val ALL: List<MapLayer> = listOf(OFFLINE, OSM, GOOGLE)

    fun byId(id: String): MapLayer = ALL.firstOrNull { it.id == id } ?: OFFLINE

    /** The next map, wrapping. One button, pressed as many times as there are maps. */
    fun next(current: MapLayer): MapLayer {
        val i = ALL.indexOfFirst { it.id == current.id }
        return ALL[(if (i < 0) 0 else i + 1) % ALL.size]
    }

    fun tileUrl(layer: MapLayer, zoom: Int, x: Int, y: Int): String? =
        if (layer.kind != LayerKind.RASTER_XYZ) {
            null
        } else {
            layer.url
                ?.replace("{z}", zoom.toString())
                ?.replace("{x}", x.toString())
                ?.replace("{y}", y.toString())
        }

    /**
     * The offline map this app can fetch by itself. mapsforge's own server, one file, no archive
     * to unpack, and the render theme built into the library draws it.
     */
    object OfflineDownload {
        const val NAME = "croatia.map"
        const val URL = "https://download.mapsforge.org/maps/v5/europe/croatia.map"

        /** Measured against the server on 14.9.2026, so the screen can say it before it starts. */
        const val BYTES = 175_514_764L
        const val LABEL = "Croatia, 176 MB"
    }
}
