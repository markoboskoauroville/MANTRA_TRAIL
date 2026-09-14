package com.mantra.trail

/**
 * WHAT "CACHE THIS VIEW" MEANS, AS ARITHMETIC. No Android imports (android-app.md 1).
 *
 * Baba, 14.9.2026: *"add to every map just one button, small letters CH, which means cache. So
 * anything what's on the view, I'm going to cache so it works offline."*
 *
 * The button caches WHAT IS ON THE SCREEN, at the zoom it is on and two closer, because the thing
 * somebody does on a ridge is zoom in, and a cache that only holds the zoom they happened to be
 * at when they pressed it is a cache that empties the moment it is needed.
 *
 * ONE ZOOM LEVEL CLOSER IS FOUR TIMES THE TILES. That is why there is a ceiling: at zoom 14 a
 * screenful is a few dozen tiles, and three levels of it is a few hundred; a careless press at
 * zoom 8 would ask a public tile server for a hundred thousand. The plan is computed first and
 * the number is shown before anything is fetched, so nobody starts a download in the dark
 * (download-monitor.md).
 */
object Caching {

    /** Zoom levels fetched: the one on screen, and this many closer. */
    const val EXTRA_ZOOMS = 2

    /**
     * The most tiles one press may ask for. Four thousand 256 px tiles is roughly 60 MB and a few
     * minutes on a phone signal; past that the honest answer is "zoom in and press it again"
     * rather than an hour of somebody else's bandwidth.
     */
    const val CEILING = 4000

    data class TileRef(val zoom: Int, val x: Int, val y: Int)

    data class Plan(
        val tiles: List<TileRef>,
        /** What was asked for before the ceiling cut it, so the screen can say so. */
        val wanted: Int,
        val fromZoom: Int,
        val toZoom: Int,
    ) {
        val truncated: Boolean get() = wanted > tiles.size
    }

    /**
     * Every tile covering the box, at the zoom given and the ones below it, nearest zoom first
     * so a cancelled run still leaves the level that is being looked at complete.
     */
    fun plan(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        zoom: Int,
        layer: MapLayer,
        extraZooms: Int = EXTRA_ZOOMS,
        ceiling: Int = CEILING,
    ): Plan {
        val south = minOf(minLat, maxLat)
        val north = maxOf(minLat, maxLat)
        val west = minOf(minLon, maxLon)
        val east = maxOf(minLon, maxLon)

        val first = zoom.coerceIn(layer.minZoom, layer.maxZoom)
        val last = (zoom + extraZooms).coerceAtMost(layer.maxZoom)

        val tiles = ArrayList<TileRef>()
        var wanted = 0
        for (z in first..last) {
            val x0 = Geo.tileX(west, z)
            val x1 = Geo.tileX(east, z)
            // The y axis runs the other way: the northern edge is the smaller row number.
            val y0 = Geo.tileY(north, z)
            val y1 = Geo.tileY(south, z)
            wanted += (x1 - x0 + 1) * (y1 - y0 + 1)
            for (x in x0..x1) {
                for (y in y0..y1) {
                    if (tiles.size < ceiling) tiles.add(TileRef(z, x, y))
                }
            }
        }
        return Plan(tiles, wanted, first, last)
    }

    /**
     * Whether a layer can be cached at all, and the sentence to show when it cannot. Both answers
     * are honest ones rather than a disabled button with no explanation.
     */
    fun refusal(layer: MapLayer): String? = when (layer.kind) {
        LayerKind.GOOGLE_TILES ->
            "Google's terms forbid storing its tiles, so this one cannot be cached"

        LayerKind.VECTOR_FILE ->
            "The offline map is a file on the phone: it is already complete"

        else -> null
    }

    /** Roughly how much room the plan will take, for the sentence before it starts. */
    fun estimateBytes(tileCount: Int): Long = tileCount * 15_000L

    fun formatBytes(bytes: Long): String = when {
        bytes < 1_000_000 -> "${bytes / 1000} kB"
        bytes < 1_000_000_000 -> "${bytes / 1_000_000} MB"
        else -> "${bytes / 100_000_000 / 10.0} GB"
    }
}
