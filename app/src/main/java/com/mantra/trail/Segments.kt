package com.mantra.trail

/**
 * WHICH BROUTER SEGMENT FILE COVERS A PLACE, and what it is called on the server.
 *
 * No Android imports (android-app.md 1), because this is arithmetic and arithmetic is what Test 1
 * is for. BRouter cuts the world into five-degree squares named after their south-west corner:
 * Zagreb at 45.8 N, 16.0 E lives in E15_N45. Get this wrong and the app downloads 130 MB of the
 * wrong country over a phone connection, which is the kind of mistake somebody pays for.
 */
object Segments {

    const val BASE = "https://brouter.de/brouter/segments4"

    /** The file covering this point, e.g. E15_N45.rd5. */
    fun nameFor(lat: Double, lon: Double): String {
        val latFloor = Math.floorDiv(Math.floor(lat).toInt(), 5) * 5
        val lonFloor = Math.floorDiv(Math.floor(lon).toInt(), 5) * 5
        val latPart = if (latFloor < 0) "S${-latFloor}" else "N$latFloor"
        val lonPart = if (lonFloor < 0) "W${-lonFloor}" else "E$lonFloor"
        return "${lonPart}_$latPart.rd5"
    }

    fun urlFor(lat: Double, lon: Double): String = "$BASE/${nameFor(lat, lon)}"

    /**
     * Every file a route between two points may need. A walk that crosses a boundary needs both
     * squares, and a walk near a corner can need four — asking for one and finding the far end
     * missing is a route that fails halfway with no explanation.
     */
    fun namesFor(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): List<String> {
        val names = LinkedHashSet<String>()
        val lats = listOf(fromLat, toLat)
        val lons = listOf(fromLon, toLon)
        for (lat in lats) for (lon in lons) names.add(nameFor(lat, lon))
        return names.toList()
    }

    /** A rough size for what a download will cost, from the ones measured on 16.9.2026. */
    fun sizeHint(name: String): String = when (name) {
        "E15_N45.rd5" -> "130 MB"
        "E10_N45.rd5" -> "199 MB"
        else -> "100 to 300 MB"
    }
}
