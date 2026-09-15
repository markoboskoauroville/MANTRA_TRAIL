package com.mantra.trail

/**
 * READING A GPX FILE BACK. No Android imports (android-app.md 1).
 *
 * Written to draw a saved walk on the map (15.9.2026), so it only needs the track points — but it
 * must survive every file it will be handed, and those will not all be ours. Garmin writes
 * extensions, Strava writes a different element order, some writers put the whole file on one
 * line, and a track interrupted by a flat battery ends mid-element with no closing tags.
 *
 * SO IT IS NOT AN XML PARSER. It looks for trkpt elements and reads the two attributes and the
 * two children it cares about. A file it cannot understand yields the points it could read rather
 * than an exception: half a walk drawn is better than a dialog saying the file is bad.
 */
object GpxRead {

    private val POINT = Regex(
        """<trkpt\s[^>]*?lat\s*=\s*["']([-\d.]+)["'][^>]*?lon\s*=\s*["']([-\d.]+)["'][^>]*?(/>|>)""",
        RegexOption.IGNORE_CASE,
    )
    private val POINT_REVERSED = Regex(
        """<trkpt\s[^>]*?lon\s*=\s*["']([-\d.]+)["'][^>]*?lat\s*=\s*["']([-\d.]+)["'][^>]*?(/>|>)""",
        RegexOption.IGNORE_CASE,
    )
    private val ELEVATION = Regex("""<ele>\s*([-\d.]+)\s*</ele>""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""<time>([^<]+)</time>""", RegexOption.IGNORE_CASE)

    /** Every track point in the text, in the order they appear. */
    fun points(text: String): List<Fix> {
        val found = ArrayList<Fix>()
        var searchFrom = 0
        while (true) {
            val match = POINT.find(text, searchFrom) ?: POINT_REVERSED.find(text, searchFrom) ?: break
            val reversed = match.value.indexOf("lon", ignoreCase = true) <
                match.value.indexOf("lat", ignoreCase = true)
            val first = match.groupValues[1].toDoubleOrNull()
            val second = match.groupValues[2].toDoubleOrNull()
            val lat = (if (reversed) second else first)
            val lon = (if (reversed) first else second)
            searchFrom = match.range.last + 1
            if (lat == null || lon == null) continue
            // A file can carry a coordinate that is not on Earth. It is not drawn.
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) continue

            // The element's own body, up to whichever comes first: its close, or the next point.
            val bodyEnd = minOf(
                text.indexOf("</trkpt>", searchFrom).let { if (it < 0) text.length else it },
                text.indexOf("<trkpt", searchFrom).let { if (it < 0) text.length else it },
            )
            val body = text.substring(searchFrom.coerceAtMost(bodyEnd), bodyEnd)
            val ele = ELEVATION.find(body)?.groupValues?.get(1)?.toDoubleOrNull()
            val time = TIME.find(body)?.groupValues?.get(1)?.let { parseTime(it) } ?: 0L
            found.add(Fix(lat, lon, ele, time, null))
        }
        return found
    }

    /** ISO 8601 as GPX writes it, or 0 when it is something else. A drawn line needs no clock. */
    fun parseTime(text: String): Long = try {
        java.time.Instant.parse(text.trim()).toEpochMilli()
    } catch (e: Exception) {
        0L
    }

    /** The name in the file's metadata, when it has one. */
    fun name(text: String): String? =
        Regex("<name>([^<]*)</name>", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.trim()
            ?.ifEmpty { null }
}
