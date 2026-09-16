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
    /** The full name, for the settings list, where there is room to be unambiguous. */
    val label: String,
    /**
     * The name on the map screen, where it shares one line with the coordinates. The family is
     * already on the key beside it, so "Thunderforest Landscape" would say Thunderforest twice.
     */
    val name: String,
    /** The word on the toggle: four letters at most, because the key is small. */
    val short: String,
    val kind: LayerKind,
    val offline: Offline,
    val attribution: String,
    val url: String? = null,
    /** The last zoom this service actually has tiles for. Past it, the map is scaled, not fetched. */
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
    enum class Family { THUNDERFOREST, OFFLINE, OSM, GOOGLE }

    enum class GoogleView(val mapType: String, val overlayRoads: Boolean) {
        NORMAL("roadmap", false),
        SATELLITE("satellite", false),
        TERRAIN("terrain", false),
        HYBRID("satellite", true),
    }

    /**
     * EVERY CREDIT IS IN SETTINGS AND NONE IS ON THE MAP (15.9.2026, his decision). They are all
     * listed together at the bottom of the settings face rather than printed over the ground he
     * is walking on. Worth knowing, once: Thunderforest's terms ask for their attribution and
     * OpenStreetMap's to stay visible in an app, so if this ever leaves his own phone that line
     * has to come back.
     */
    val creditOnMap: Boolean get() = false

    /**
     * How far the VIEW may go, which is not the same as how far the tiles go. Baba, 15.9.2026:
     * *"OpenStreetMap goes to zoom level 18 and it stops. No more zoom levels than that. Why?"*
     * Because the view was being clamped to the last zoom the service publishes. Past that,
     * mapsforge draws the last real tile enlarged — coarse, but it is a map, and on a mountain
     * the question is where the path goes, not how sharp the letters are. Three levels beyond is
     * the limit because mapsforge only scales a parent four levels up.
     */
    val viewMaxZoom: Int
        get() = if (kind == LayerKind.VECTOR_FILE) 22 else minOf(22, maxZoom + 3)
}

object Layers {

    val OFFLINE = MapLayer(
        family = MapLayer.Family.OFFLINE,
        id = "offline",
        label = "Offline map",
        name = "Offline",
        short = "OFF",
        kind = LayerKind.VECTOR_FILE,
        offline = MapLayer.Offline.COMPLETE,
        attribution = "© OpenStreetMap contributors",
    )

    val OSM = MapLayer(
        family = MapLayer.Family.OSM,
        id = "osm",
        label = "OpenStreetMap",
        name = "OpenStreetMap",
        short = "OSM",
        kind = LayerKind.RASTER_XYZ,
        offline = MapLayer.Offline.CACHED_ONLY,
        attribution = "© OpenStreetMap contributors",
        url = "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        // OpenStreetMap publishes tiles to 19. The view goes to 22 and scales the rest.
        maxZoom = 19,
    )

    /**
     * THUNDERFOREST'S TEN STYLES, all of them, from his own dashboard. One account, one key, and
     * the key goes in the address of every one of them.
     *
     * They are online maps. Tiles he actually looks at are cached and are then there without a
     * signal; fetching a region he has not looked at is what their terms call bulk downloading,
     * and that needs their Small Business plan, so this app has no button for it.
     */
    private fun thunderforest(style: String, name: String, short: String = "THU") = MapLayer(
        family = MapLayer.Family.THUNDERFOREST,
        id = "tf-$style",
        label = "Thunderforest $name",
        name = name,
        short = short,
        kind = LayerKind.RASTER_XYZ,
        offline = MapLayer.Offline.CACHED_ONLY,
        attribution = "Maps © Thunderforest, Data © OpenStreetMap contributors",
        url = "https://api.thunderforest.com/$style/{z}/{x}/{y}.png?apikey={key}",
        maxZoom = 22,
        provider = Keys.Provider.THUNDERFOREST,
    )

    /** The walking one: contour lines, marked trails, the shape of a hill. */
    val THUNDERFOREST = thunderforest("outdoors", "Outdoors")
    val TF_CYCLE = thunderforest("cycle", "Cycle")
    val TF_TRANSPORT = thunderforest("transport", "Transport")
    val TF_LANDSCAPE = thunderforest("landscape", "Landscape")
    val TF_TRANSPORT_DARK = thunderforest("transport-dark", "Transport dark")
    val TF_SPINAL = thunderforest("spinal-map", "Spinal")
    val TF_PIONEER = thunderforest("pioneer", "Pioneer")
    val TF_MOBILE_ATLAS = thunderforest("mobile-atlas", "Mobile atlas")
    val TF_NEIGHBOURHOOD = thunderforest("neighbourhood", "Neighbourhood")
    val TF_ATLAS = thunderforest("atlas", "Atlas")

    val THUNDERFOREST_ALL: List<MapLayer> = listOf(
        THUNDERFOREST, TF_LANDSCAPE, TF_CYCLE, TF_TRANSPORT, TF_TRANSPORT_DARK,
        TF_ATLAS, TF_PIONEER, TF_NEIGHBOURHOOD, TF_MOBILE_ATLAS, TF_SPINAL,
    )

    private fun google(id: String, name: String, view: MapLayer.GoogleView) = MapLayer(
        family = MapLayer.Family.GOOGLE,
        id = id,
        label = "Google $name",
        name = name,
        short = "GOO",
        kind = LayerKind.GOOGLE_TILES,
        offline = MapLayer.Offline.NONE,
        attribution = "Google",
        url = "https://tile.googleapis.com/v1/2dtiles/{z}/{x}/{y}?session={session}&key={key}",
        maxZoom = 21,
        provider = Keys.Provider.GOOGLE,
        googleView = view,
    )

    val GOOGLE = google("google", "Default map", MapLayer.GoogleView.NORMAL)
    val GOOGLE_SATELLITE = google("google-sat", "Satellite", MapLayer.GoogleView.SATELLITE)
    val GOOGLE_TERRAIN = google("google-ter", "Terrain", MapLayer.GoogleView.TERRAIN)
    val GOOGLE_HYBRID = google("google-hyb", "Hybrid", MapLayer.GoogleView.HYBRID)

    val GOOGLE_ALL: List<MapLayer> = listOf(GOOGLE, GOOGLE_SATELLITE, GOOGLE_TERRAIN, GOOGLE_HYBRID)

    /**
     * Every map, in the order the settings list shows them. Thunderforest leads because it is the
     * one he zooms furthest and reads best (15.9.2026); the families follow in the same order the
     * map key turns through them.
     */
    val ALL: List<MapLayer> = THUNDERFOREST_ALL + listOf(OFFLINE, OSM) + GOOGLE_ALL

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
    /**
     * THE SAME URL, SPLIT THE WAY THE GPU ENGINE WANTS IT: a base, and a path with {Z} {X} {Y} in
     * it. VTM builds every request from those two, substituting the numbers and appending the
     * rest verbatim — so whatever follows, a key or a session among it, must stay in the path.
     *
     * It did not (16.9.2026). The path was hard-coded as "/{Z}/{X}/{Y}.png", which threw away
     * "?apikey=…" and left Thunderforest answering every tile with a refusal and a blank screen.
     * The arithmetic lives here, in the half Test 1 can attack, rather than in the canvas.
     */
    fun tilePattern(layer: MapLayer, auth: String? = null, key: String? = null): Pair<String, String>? {
        val sample = tileUrl(layer, 0, 0, 0, auth, key) ?: return null
        val pattern = sample.replace("/0/0/0", "/{Z}/{X}/{Y}")
        val cut = pattern.indexOf("/{Z}")
        if (cut < 0) return null
        return pattern.substring(0, cut) to pattern.substring(cut)
    }

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
