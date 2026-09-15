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
    /** Which of the four families this belongs to. The one map key turns through families. */
    val family: Family = Family.OFFLINE,
    /** Which of Google's four views this is. */
    val googleView: GoogleView? = null,
) {
    enum class Offline { COMPLETE, CACHED_ONLY, NONE }

    /**
     * SIXTEEN MAPS, FOUR FAMILIES, ONE BUTTON.
     *
     * Thunderforest draw ten styles and Google four views, and a toggle that turned through all
     * sixteen would be sixteen presses to get back where you started. So the key on the map turns
     * through the FAMILIES, and which style of a family it shows is whichever one of that family
     * was last chosen in settings. Four presses to come full circle, and every style still one
     * press away in the list.
     */
    enum class Family { OFFLINE, OSM, THUNDERFOREST, GOOGLE }

    enum class GoogleView(val mapType: String, val overlayRoads: Boolean) {
        NORMAL("roadmap", false),
        SATELLITE("satellite", false),
        TERRAIN("terrain", false),
        HYBRID("satellite", true),
    }

    /**
     * Whether the credit has to be on the map itself rather than in settings. Thunderforest's
     * terms say the Thunderforest and OpenStreetMap attribution may not be removed from an app,
     * and Google says the same about its own. A file on the phone is a different case: its credit
     * is in settings.
     */
    val creditOnMap: Boolean get() = kind != LayerKind.VECTOR_FILE
}

object Layers {

    val OFFLINE = MapLayer(
        family = MapLayer.Family.OFFLINE,
        id = "offline",
        label = "Offline map",
        short = "OFF",
        kind = LayerKind.VECTOR_FILE,
        offline = MapLayer.Offline.COMPLETE,
        attribution = "© OpenStreetMap contributors",
    )

    val OSM = MapLayer(
        family = MapLayer.Family.OSM,
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
     * THUNDERFOREST'S TEN STYLES, all of them, from his own dashboard. One account, one key, and
     * the key goes in the address of every one of them.
     *
     * They are online maps. Tiles he actually looks at are cached and are then there without a
     * signal; fetching a region he has not looked at is what their terms call bulk downloading,
     * and that needs their Small Business plan, so this app has no button for it.
     */
    private fun thunderforest(style: String, label: String, short: String) = MapLayer(
        family = MapLayer.Family.THUNDERFOREST,
        id = "tf-$style",
        label = label,
        short = short,
        kind = LayerKind.RASTER_XYZ,
        offline = MapLayer.Offline.CACHED_ONLY,
        attribution = "Maps © Thunderforest, Data © OpenStreetMap contributors",
        url = "https://api.thunderforest.com/$style/{z}/{x}/{y}.png?apikey={key}",
        maxZoom = 22,
        provider = Keys.Provider.THUNDERFOREST,
    )

    /** The walking one: contour lines, marked trails, the shape of a hill. */
    val THUNDERFOREST = thunderforest("outdoors", "Outdoors", "OUT")
    val TF_CYCLE = thunderforest("cycle", "OpenCycleMap", "CYC")
    val TF_TRANSPORT = thunderforest("transport", "Transport", "TRN")
    val TF_LANDSCAPE = thunderforest("landscape", "Landscape", "LND")
    val TF_TRANSPORT_DARK = thunderforest("transport-dark", "Transport dark", "TDK")
    val TF_SPINAL = thunderforest("spinal-map", "Spinal map", "SPN")
    val TF_PIONEER = thunderforest("pioneer", "Pioneer", "PIO")
    val TF_MOBILE_ATLAS = thunderforest("mobile-atlas", "Mobile atlas", "MATL")
    val TF_NEIGHBOURHOOD = thunderforest("neighbourhood", "Neighbourhood", "NBH")
    val TF_ATLAS = thunderforest("atlas", "Atlas", "ATL")

    val THUNDERFOREST_ALL: List<MapLayer> = listOf(
        THUNDERFOREST, TF_LANDSCAPE, TF_CYCLE, TF_TRANSPORT, TF_TRANSPORT_DARK,
        TF_ATLAS, TF_PIONEER, TF_NEIGHBOURHOOD, TF_MOBILE_ATLAS, TF_SPINAL,
    )

    private fun google(id: String, label: String, short: String, view: MapLayer.GoogleView) = MapLayer(
        family = MapLayer.Family.GOOGLE,
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

    val GOOGLE_ALL: List<MapLayer> = listOf(GOOGLE, GOOGLE_SATELLITE, GOOGLE_TERRAIN, GOOGLE_HYBRID)

    /** Every map, in the order the settings list shows them: offline first, Google last. */
    val ALL: List<MapLayer> = listOf(OFFLINE, OSM) + THUNDERFOREST_ALL + GOOGLE_ALL

    fun byId(id: String): MapLayer = ALL.firstOrNull { it.id == id } ?: OFFLINE

    fun of(family: MapLayer.Family): List<MapLayer> = ALL.filter { it.family == family }

    /** The next family round the circle. Which style of it appears is the caller's memory. */
    fun nextFamily(current: MapLayer): MapLayer.Family {
        val families = MapLayer.Family.entries
        val i = families.indexOf(current.family)
        return families[(i + 1) % families.size]
    }

    /** The first map of a family, when nothing has been chosen from it yet. */
    fun firstOf(family: MapLayer.Family): MapLayer = of(family).firstOrNull() ?: OFFLINE

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
            "Thunderforest needs your key. Settings, API keys, pick the file it is in."

        null -> null
    }

    object OfflineDownload {
        const val NAME = "croatia.map"
        const val URL = "https://download.mapsforge.org/maps/v5/europe/croatia.map"
        const val BYTES = 175_514_764L
        const val LABEL = "Croatia, 176 MB"
    }
}
