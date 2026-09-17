package com.mantra.trail

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/**
 * SATELLITE IMAGERY HE CAN KEEP. No Android imports (android-app.md 1).
 *
 * Baba, 17.9.2026, asking for Google's "select an area and store it": Google forbid it. Their Map
 * Tiles policy lists offline use among the things their content may not be used for, and their
 * terms allow only temporary caching for performance. Their own app does it because it is theirs.
 *
 * So the imagery comes from somewhere that permits it: SENTINEL-2 CLOUDLESS, made by EOX from
 * European Space Agency data, CC BY 4.0 — free to download, to store and to use offline, with
 * attribution. Ten metres to the pixel rather than Google's thirty centimetres: enough to tell
 * forest from clearing and a ridge from a valley, not enough to count cars.
 *
 * This half is the arithmetic: which tiles cover a piece of ground, how many there are, and what
 * they will cost him in megabytes before he presses anything.
 */
object Imagery {

    /** Their tiles are addressed z/ROW/COLUMN — y before x, which is not the usual order. */
    const val BASE = "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2024_3857/default/g"

    const val ATTRIBUTION = "Sentinel-2 cloudless 2024 by EOX IT Services (CC BY 4.0), " +
        "from modified Copernicus Sentinel data"

    /** Measured against their server on 17.9.2026: tiles over land run 5 to 20 KB, say 12. */
    const val BYTES_PER_TILE = 12_000L

    data class Tile(val z: Int, val x: Int, val y: Int) {
        val url: String get() = "$BASE/$z/$y/$x.jpg"

        /** Where it lives on the phone, in the ordinary z/x/y that everything else uses. */
        val path: String get() = "$z/$x/$y.jpg"
    }

    fun xOf(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl zoom)).toInt().coerceIn(0, (1 shl zoom) - 1)

    fun yOf(lat: Double, zoom: Int): Int {
        val radians = lat * PI / 180.0
        val value = (1.0 - asinh(tan(radians)) / PI) / 2.0 * (1 shl zoom)
        return floor(value).toInt().coerceIn(0, (1 shl zoom) - 1)
    }

    fun lonOf(x: Int, zoom: Int): Double = x.toDouble() / (1 shl zoom) * 360.0 - 180.0

    fun latOf(y: Int, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / (1 shl zoom)
        return 180.0 / PI * atan(sinh(n))
    }

    /**
     * Every tile covering a box, from one zoom to another. The corners are given in any order,
     * because a rectangle dragged up-left is the same rectangle.
     */
    fun tilesFor(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        fromZoom: Int,
        toZoom: Int,
    ): List<Tile> {
        val north = maxOf(lat1, lat2)
        val south = minOf(lat1, lat2)
        val west = minOf(lon1, lon2)
        val east = maxOf(lon1, lon2)
        val tiles = ArrayList<Tile>()
        for (z in fromZoom..toZoom) {
            val xFrom = xOf(west, z)
            val xTo = xOf(east, z)
            val yFrom = yOf(north, z)
            val yTo = yOf(south, z)
            for (x in xFrom..xTo) {
                for (y in yFrom..yTo) {
                    tiles.add(Tile(z, x, y))
                }
            }
        }
        return tiles
    }

    /** How many, without building the list: for a size shown while he drags a corner. */
    fun countFor(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        fromZoom: Int,
        toZoom: Int,
    ): Int {
        val north = maxOf(lat1, lat2)
        val south = minOf(lat1, lat2)
        val west = minOf(lon1, lon2)
        val east = maxOf(lon1, lon2)
        var total = 0
        for (z in fromZoom..toZoom) {
            val across = xOf(east, z) - xOf(west, z) + 1
            val down = yOf(south, z) - yOf(north, z) + 1
            total += across * down
        }
        return total
    }

    /** What it will cost, in words, before he presses anything. */
    fun sizeLabel(tiles: Int): String {
        val bytes = tiles * BYTES_PER_TILE
        return when {
            bytes >= 1_000_000_000L -> "about ${bytes / 100_000_000 / 10.0} GB"
            bytes >= 1_000_000L -> "about ${bytes / 1_000_000} MB"
            else -> "under a megabyte"
        }
    }

    /**
     * THE DEEPEST ZOOM WORTH FETCHING. Sentinel-2 is ten metres to the pixel, so past z16 the app
     * would be storing the same information four times over and calling it detail.
     */
    const val MIN_ZOOM = 10
    const val MAX_ZOOM = 16
}
