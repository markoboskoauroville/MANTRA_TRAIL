package com.mantra.trail

/**
 * THE MAPS, AND WHAT EACH ONE NEEDS FROM THE PERSON.
 *
 * Nothing here holds a key and no build carries one (Keys.kt). A layer says which service it
 * belongs to; the key for that service comes from the file he picked, or the layer says so and
 * draws nothing.
 *
 * What each does with no signal, which is the only question that matters in the mountains:
 *
 *   Offline map    everything. A mapsforge file on the phone; nothing to fetch.
 *   OpenStreetMap  whatever CH has put in the cache.
 *   Outdoors       the same, and it is the one drawn for walking: contours and marked trails.
 *   Google ×4      NOTHING, and it may not be otherwise. Google's terms forbid pre-fetching,
 *                  caching or storing tiles, and name offline use as a prohibited case. Its four
 *                  views are four maps from the seat, so they are four entries in the one toggle.
 */
enum class LayerKind {
    /** A .map file on the phone. */
    VECTOR_FILE,

    /** z/x/y raster tiles we fetch and may keep. */
    RASTER_XYZ,

    /** Google Map Tiles: a session is made with his key, then plain tiles. Never kept. */
    GOOGLE_TILES,
}

data class MapLayer(
    val id: String,
    val label: String,
    /** The word on the toggle: four letters at most, because the key is small. */
    val short: String,
    val kind: LayerKind,
    val offline: Offline,
    val attribution: String,
    val url: String? = null,
    val maxZoom: Int = 18,
    val minZoom: Int = 2,
    /** The service whose key this layer needs, or null when it needs none. */
    val provider: Keys.Provider? = null,
    /** Which of Google's four views this is. */
    val googleView: GoogleView? = null,
) {
    enum class Offline { COMPLETE, CACHED_ONLY, NONE }

    enum class GoogleView(val mapType: String, val overlayRoads: Boolean) {
        NORMAL("roadmap", false),
        SATELLITE("satellite", false),
        TERRAIN("terrain", false),
        HYBRID("satellite", true),
    }

    /** True only where the licence allows the tiles to be kept on the phone. */
    val cacheable: Boolean get() = kind == LayerKind.RASTER_XYZ
}

object Layers {

    val OFFLINE = MapLayer(
        id = "offline",
        label = "Offline map",
        short = "OFF",
        kind = LayerKind.VECTOR_FILE,
        offline = MapLayer.Offline.COMPLETE,
        attribution = "© OpenStreetMap contributors",
    )

    val OSM = MapLayer(
        id = "osm",
        label = "OpenStreetMap",
        short = "OSM",
        kind = LayerKind.RASTER_XYZ,
        offline = MapLayer.Offline.CACHED_ONLY,
        attribution = "© OpenStreetMap contributors",
        url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        maxZoom = 18,
    )

    /**
     * The walking map: contour lines, marked paths, the shape of a hill. It takes a key in the
     * URL, which is exactly the kind of key the picker can feed, and its tiles may be cached — so
     * CH on this one is what fills a valley before you walk into it.
     */
    val OUTDOORS = MapLayer(
        id = "outdoors",
        label = "Outdoors",
        short = "OUT",
        kind = LayerKind.RASTER_XYZ,
        offline = MapLayer.Offline.CACHED_ONLY,
        attribution = "Maps © Thunderforest, Data © OpenStreetMap contributors",
        url = "https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey={key}",
        maxZoom = 18,
        provider = Keys.Provider.THUNDERFOREST,
    )

    private fun google(id: String, label: String, short: String, view: MapLayer.GoogleView) = MapLayer(
        id = id,
        label = label,
        short = short,
        kind = LayerKind.GOOGLE_TILES,
        offline = MapLayer.Offline.NONE,
        attribution = "Google",
        url = "https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}?session={session}&key={key}",
        maxZoom = 21,
        provider = Keys.Provider.GOOGLE,
        googleView = view,
    )

    val GOOGLE = google("google", "Google map", "GGL", MapLayer.GoogleView.NORMAL)
    val GOOGLE_SATELLITE = google("google-sat", "Google satellite", "SAT", MapLayer.GoogleView.SATELLITE)
    val GOOGLE_TERRAIN = google("google-ter", "Google terrain", "TER", MapLayer.GoogleView.TERRAIN)
    val GOOGLE_HYBRID = google("google-hyb", "Google hybrid", "HYB", MapLayer.GoogleView.HYBRID)

    /** The order the one button turns through: what works offline first, Google's four last. */
    val ALL: List<MapLayer> = listOf(
        OFFLINE, OSM, OUTDOORS, GOOGLE, GOOGLE_SATELLITE, GOOGLE_TERRAIN, GOOGLE_HYBRID,
    )

    fun byId(id: String): MapLayer = ALL.firstOrNull { it.id == id } ?: OFFLINE

    fun next(current: MapLayer): MapLayer {
        val i = ALL.indexOfFirst { it.id == current.id }
        return ALL[(if (i < 0) 0 else i + 1) % ALL.size]
    }

    /**
     * One tile's URL. `auth` is whatever that service needs in the address: the key itself for
     * Outdoors, the session token for Google. Null when the layer draws nothing of ours, and
     * null when the layer needs an auth it has not been given — a URL with an empty key in it
     * would fetch four hundred refusals and look like a dead server.
     */
    fun tileUrl(layer: MapLayer, zoom: Int, x: Int, y: Int, auth: String? = null, key: String? = null): String? {
        val template = layer.url ?: return null
        if (layer.kind == LayerKind.VECTOR_FILE) return null
        if (layer.provider != null && (key.isNullOrEmpty())) return null
        if (layer.kind == LayerKind.GOOGLE_TILES && auth.isNullOrEmpty()) return null
        return template
            .replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
            .replace("{key}", key ?: "")
            .replace("{session}", auth ?: "")
    }

    /** What to say when a layer needs a key nobody has given it yet. */
    fun missingKey(layer: MapLayer): String? = when (layer.provider) {
        Keys.Provider.GOOGLE ->
            "Google needs your own key. Settings, API keys, pick the file it is in."

        Keys.Provider.THUNDERFOREST ->
            "Outdoors needs a Thunderforest key. Settings, API keys, pick the file it is in."

        null -> null
    }

    object OfflineDownload {
        const val NAME = "croatia.map"
        const val URL = "https://download.mapsforge.org/maps/v5/europe/croatia.map"
        const val BYTES = 175_514_764L
        const val LABEL = "Croatia, 176 MB"
    }
}
