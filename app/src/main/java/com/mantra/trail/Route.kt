package com.mantra.trail

/**
 * A ROUTE AS A LIST OF POINTS, and the naming that goes with it. No Android imports
 * (android-app.md 1), so the part that decides what a point is called and how a route reads back
 * from a saved line of text is attacked by Test 1.
 *
 * Baba, 16.9.2026: more than two. A, B, C, D and on, walked in the order they were placed, with
 * the engine finding the way between each pair.
 */
object Route {

    /** How many points one route may hold. The alphabet, and nobody plans a walk with 27 stops. */
    const val MAX_POINTS = 26

    /** The letter of a point by its position: 0 is A. */
    fun letterFor(index: Int): String = ('A' + (index % 26)).toString()

    /** A point list as one line, for the store: "45.8,15.9;45.9,16.0". */
    fun encode(points: List<Pair<Double, Double>>): String =
        points.joinToString(";") { "${it.first},${it.second}" }

    /**
     * And back again. A line that has been corrupted, half-written or left over from an older
     * version yields the points it can rather than throwing: a bad preference must never be able
     * to stop the app opening.
     */
    fun decode(text: String?): List<Pair<Double, Double>> {
        if (text.isNullOrBlank()) return emptyList()
        return text.split(";").mapNotNull { pair ->
            val bits = pair.split(",")
            if (bits.size != 2) return@mapNotNull null
            val lat = bits[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lon = bits[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return@mapNotNull null
            lat to lon
        }.take(MAX_POINTS)
    }

    /** The straight-line length through every point in order, in metres. */
    fun straightMetres(points: List<Pair<Double, Double>>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += Geo.distance(
                points[i - 1].first,
                points[i - 1].second,
                points[i].first,
                points[i].second,
            )
        }
        return total
    }

    /** What a saved route is called: the day, the time, and the letters it ran through. */
    fun nameFor(startedMs: Long, count: Int): String {
        val stamp = Tracks.defaultName(startedMs).removeSuffix(" Track")
        val letters = if (count <= 1) "A" else "${letterFor(0)}${letterFor(count - 1)}"
        return "$stamp ($letters)"
    }
}
