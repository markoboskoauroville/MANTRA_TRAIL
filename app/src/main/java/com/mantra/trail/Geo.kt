package com.mantra.trail

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * THE ARITHMETIC OF THE MAP, AND IT IMPORTS NOTHING FROM ANDROID.
 *
 * android-app.md 1: the readers, the parsers and the arithmetic live in files that import nothing
 * from Android, so Test 1 runs on a desk in under a second instead of in an emulator, and
 * verify.py fails the build the day an `import android.` appears here.
 *
 * Distances are haversine on a sphere of the WGS84 mean radius. For a walk in Velebit the error
 * against the true ellipsoid is under a metre in ten kilometres, which is far below the error of
 * the fixes themselves, and a sphere has no iteration that can fail to converge.
 */
object Geo {

    /** WGS84 mean radius, metres (IUGG). */
    const val EARTH_R = 6371008.8

    /** Web Mercator is undefined at the poles; this is the latitude the square is cut at. */
    const val MERC_LAT_LIMIT = 85.05112878

    fun rad(deg: Double): Double = deg * Math.PI / 180.0
    fun deg(rad: Double): Double = rad * 180.0 / Math.PI

    /** Metres between two fixes. */
    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from the first point to the second, 0 at north, clockwise, 0 until 360. */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = rad(lon2 - lon1)
        val y = sin(dLon) * cos(rad(lat2))
        val x = cos(rad(lat1)) * sin(rad(lat2)) - sin(rad(lat1)) * cos(rad(lat2)) * cos(dLon)
        return normaliseDeg(deg(atan2(y, x)))
    }

    /** Any angle brought into 0 until 360. */
    fun normaliseDeg(d: Double): Double {
        var x = d % 360.0
        if (x < 0) x += 360.0
        return x
    }

    /**
     * The shortest way round from one heading to another, negative to the left. Used to move the
     * compass needle without it spinning the long way at the 360/0 crossing.
     */
    fun deltaDeg(from: Double, to: Double): Double {
        var d = (to - from) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        return d
    }

    private val POINTS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /** The sixteen-point name of a heading. */
    fun cardinal(deg: Double): String =
        POINTS[(((normaliseDeg(deg) + 11.25) % 360.0) / 22.5).toInt()]

    // --- Web Mercator, EPSG:3857 -------------------------------------------------------------
    //
    // Both raster layers are tiled in 3857: OpenTopoMap serves z/x/y directly, and the Croatian
    // TK25 is a WMS that takes a bounding box, which is the same arithmetic one step further on.
    // Measured against the real service on 14.9.2026: 3857 is served and returns a 256 px PNG.

    fun clampLat(lat: Double): Double = lat.coerceIn(-MERC_LAT_LIMIT, MERC_LAT_LIMIT)

    fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val x = floor((lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * n).toInt()
        return x.coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val l = rad(clampLat(lat))
        val y = floor((1.0 - ln(tan(l) + 1.0 / cos(l)) / Math.PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }

    // --- How things are written on the screen ---------------------------------------------------

    /**
     * Degrees and decimal minutes, which is what a Croatian mountain map and a rescue call both
     * speak. Rounded to three decimals of a minute, about two metres.
     */
    fun formatLat(lat: Double): String = dm(lat, if (lat >= 0) 'N' else 'S')

    fun formatLon(lon: Double): String = dm(lon, if (lon >= 0) 'E' else 'W')

    private fun dm(value: Double, hemisphere: Char): String {
        val a = abs(value)
        var d = floor(a).toInt()
        var m = (a - d) * 60.0
        // 59.9996 minutes rounds to 60.000 and must become the next whole degree, or the screen
        // shows 44 60.000 and somebody reads it into a radio.
        if (roundTo3(m) >= 60.0) {
            m = 0.0
            d += 1
        }
        return "$hemisphere $d ${padMinutes(roundTo3(m))}"
    }

    private fun roundTo3(v: Double): Double = (v * 1000.0).roundToInt() / 1000.0

    private fun padMinutes(m: Double): String {
        val whole = floor(m).toInt()
        val thousandths = ((m - whole) * 1000.0).roundToInt()
        return "${if (whole < 10) "0" else ""}$whole.${thousandths.toString().padStart(3, '0')}"
    }

    /** Metres under a kilometre, kilometres to two decimals above it. */
    fun formatDistance(metres: Double): String = when {
        metres.isNaN() -> "-"
        metres < 1000.0 -> "${metres.roundToInt()} m"
        else -> "${((metres / 10.0).roundToInt() / 100.0)} km"
    }

    /** A duration as h:mm:ss, or mm:ss under an hour. */
    fun formatDuration(millis: Long): String {
        if (millis < 0) return "-"
        val s = millis / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val mm = m.toString().padStart(2, '0')
        val ss = sec.toString().padStart(2, '0')
        return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
    }
}
