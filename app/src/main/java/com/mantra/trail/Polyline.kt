package com.mantra.trail

/**
 * GOOGLE'S ENCODED POLYLINE, UNPACKED. No Android imports (android-app.md 1).
 *
 * Their Routes API answers with the whole way as one string: each coordinate a difference from the
 * one before, in fifths of a microdegree, chopped into five-bit pieces, each piece with a
 * continuation bit, each byte shifted into printable ASCII. It is a clever format and an easy one
 * to get subtly wrong — a route that starts right and drifts into the sea two kilometres along —
 * so it is decoded here, in the half Test 1 can attack, against strings whose answers are known.
 */
object Polyline {

    /**
     * Decode a polyline into points. A string that cannot be decoded yields what it could read
     * rather than throwing: half a route drawn is better than a crash on a hillside.
     */
    fun decode(encoded: String): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>()
        var index = 0
        var lat = 0
        var lon = 0
        while (index < encoded.length) {
            val dLat = chunk(encoded, index) ?: break
            index = dLat.second
            lat += dLat.first
            val dLon = chunk(encoded, index) ?: break
            index = dLon.second
            lon += dLon.first
            points.add(lat / 1e5 to lon / 1e5)
        }
        return points
    }

    /** One number and where it ended, or null when the string runs out mid-number. */
    private fun chunk(encoded: String, from: Int): Pair<Int, Int>? {
        var index = from
        var shift = 0
        var result = 0
        while (true) {
            if (index >= encoded.length) return null
            val b = encoded[index].code - 63
            index++
            result = result or ((b and 0x1f) shl shift)
            shift += 5
            if (b < 0x20) break
            if (shift > 30) return null
        }
        val value = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return value to index
    }
}
