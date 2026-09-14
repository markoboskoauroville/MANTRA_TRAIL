package com.mantra.trail

import kotlin.math.abs

/**
 * WHAT A FIX IS, WHICH ONES ARE ALLOWED INTO A TRACK, AND WHAT THE TRACK THEN MEASURES.
 *
 * No Android imports (android-app.md 1). A fix here is already plain numbers; whether they came
 * from GPS, from a Wi-Fi network or from a cell tower is the system's business, and the only
 * thing this file cares about is the accuracy that came with them.
 *
 * THE RULE THAT DECIDES EVERYTHING BELOW: a wrong answer delivered confidently is worse than no
 * answer (four-tests.md, Test 3). A phone under a cliff will hand out fixes that are hundreds of
 * metres wrong and look exactly like good ones. So a point is admitted only if it can be
 * defended, and every point that is refused is counted and shown rather than dropped in silence.
 */
data class Fix(
    val lat: Double,
    val lon: Double,
    /** Metres above the ellipsoid as the system reports it, or null when there is none. */
    val ele: Double?,
    /** Milliseconds since the epoch, from the fix itself, never from the clock on the wall. */
    val timeMs: Long,
    /** The system's own 68% horizontal error in metres, or null when it will not say. */
    val accuracyM: Float?,
    val speedMs: Float? = null,
    /** How many satellites are being used in the fix, for the screen only. */
    val satellites: Int? = null,
)

/** Why a fix was not put in the track. Each one is counted; none of them is silent. */
enum class Reject { ACCURACY, JUMP, BACKWARDS, DUPLICATE }

object TrackRules {

    /**
     * Fixes worse than this are not recorded. Fifty metres is deliberately generous: in a gorge
     * or under fir cover a real fix is often 20 to 30 metres, and a stricter gate produces a
     * track with holes in the places somebody most wants one.
     */
    const val MAX_ACCURACY_M = 50.0

    /**
     * Above this implied speed the pair is a jump, not a walk. 12 m/s is 43 km/h: faster than
     * anyone runs and slower than a car, so a track recorded on the drive home is refused rather
     * than quietly added to the day's climb.
     */
    const val MAX_SPEED_MS = 12.0

    /** Under this the two fixes are the same place and the second adds nothing but file size. */
    const val MIN_MOVE_M = 2.0

    /**
     * Height gain is counted only after this much rise, because a stationary phone's altitude
     * wanders by several metres a minute and an unfiltered sum turns a rest stop into a climb.
     */
    const val ASCENT_THRESHOLD_M = 3.0

    /** Below this the walker is standing still, for the moving-time clock. */
    const val MOVING_SPEED_MS = 0.4

    /**
     * Whether a fix may join the track, given the last one that did. Returns null when it may.
     * Order matters: accuracy first, because a jump computed from a hopeless fix is not evidence
     * of anything.
     */
    fun reject(previous: Fix?, next: Fix): Reject? {
        val acc = next.accuracyM
        if (acc == null || acc <= 0f || acc > MAX_ACCURACY_M) return Reject.ACCURACY
        if (previous == null) return null
        if (next.timeMs < previous.timeMs) return Reject.BACKWARDS
        val dt = (next.timeMs - previous.timeMs) / 1000.0
        val d = Geo.distance(previous.lat, previous.lon, next.lat, next.lon)
        if (dt <= 0.0) return if (d < MIN_MOVE_M) Reject.DUPLICATE else Reject.JUMP
        if (d < MIN_MOVE_M) return Reject.DUPLICATE
        if (d / dt > MAX_SPEED_MS) return Reject.JUMP
        return null
    }
}

/** What the screen shows about a track while it is being walked, and what the record says after. */
data class TrackStats(
    val points: Int,
    val distanceM: Double,
    val ascentM: Double,
    val descentM: Double,
    val durationMs: Long,
    val movingMs: Long,
    val minEle: Double?,
    val maxEle: Double?,
) {
    companion object {
        val EMPTY = TrackStats(0, 0.0, 0.0, 0.0, 0L, 0L, null, null)
    }
}

/**
 * The statistics of a whole track, computed from the points alone so the same numbers come out of
 * a live recording and out of a file read back. Two implementations of one sum are two places to
 * drift apart (design-language.md 2, and the same logic applies to arithmetic).
 */
object TrackMath {

    fun stats(points: List<Fix>): TrackStats {
        if (points.isEmpty()) return TrackStats.EMPTY
        var distance = 0.0
        var ascent = 0.0
        var descent = 0.0
        var moving = 0L
        var minEle: Double? = null
        var maxEle: Double? = null
        // The height the climb is measured from. It only moves when the walker has gone far
        // enough from it to be believed, which is what keeps the wander out of the total.
        var anchor: Double? = points.first().ele

        for (p in points) {
            p.ele?.let { e ->
                minEle = if (minEle == null || e < minEle!!) e else minEle
                maxEle = if (maxEle == null || e > maxEle!!) e else maxEle
                val a = anchor
                if (a == null) {
                    anchor = e
                } else if (abs(e - a) >= TrackRules.ASCENT_THRESHOLD_M) {
                    if (e > a) ascent += e - a else descent += a - e
                    anchor = e
                }
            }
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val d = Geo.distance(a.lat, a.lon, b.lat, b.lon)
            distance += d
            val dt = b.timeMs - a.timeMs
            if (dt > 0 && d / (dt / 1000.0) >= TrackRules.MOVING_SPEED_MS) moving += dt
        }
        val duration = points.last().timeMs - points.first().timeMs
        return TrackStats(
            points = points.size,
            distanceM = distance,
            ascentM = ascent,
            descentM = descent,
            durationMs = if (duration > 0) duration else 0L,
            movingMs = moving,
            minEle = minEle,
            maxEle = maxEle,
        )
    }
}

/**
 * THE LIVE RECORDING, kept as running totals rather than by re-walking the list every second.
 *
 * It holds the points too, because the GPX file is written as it goes and the screen draws the
 * line, but the numbers do not depend on the list: a recording that lasts nine hours must not get
 * slower as it goes.
 */
class Recording(val startedMs: Long) {

    private val points = ArrayList<Fix>()
    private var anchorEle: Double? = null
    private var distance = 0.0
    private var ascent = 0.0
    private var descent = 0.0
    private var moving = 0L
    private val rejects = HashMap<Reject, Int>()

    var paused: Boolean = false
        private set

    fun pause() { paused = true }

    fun resume() { paused = false }

    val size: Int get() = points.size

    fun last(): Fix? = points.lastOrNull()

    fun snapshot(): List<Fix> = points.toList()

    fun rejectedCount(): Int = rejects.values.sum()

    fun rejected(kind: Reject): Int = rejects[kind] ?: 0

    /**
     * Offer a fix. Returns it when it was recorded, null when it was refused — and the caller
     * writes the returned point to the file, so the file and the list can never disagree.
     */
    fun offer(fix: Fix): Fix? {
        if (paused) return null
        val why = TrackRules.reject(points.lastOrNull(), fix)
        if (why != null) {
            rejects[why] = (rejects[why] ?: 0) + 1
            return null
        }
        val previous = points.lastOrNull()
        if (previous != null) {
            val d = Geo.distance(previous.lat, previous.lon, fix.lat, fix.lon)
            distance += d
            val dt = fix.timeMs - previous.timeMs
            if (dt > 0 && d / (dt / 1000.0) >= TrackRules.MOVING_SPEED_MS) moving += dt
        }
        fix.ele?.let { e ->
            val a = anchorEle
            if (a == null) {
                anchorEle = e
            } else if (abs(e - a) >= TrackRules.ASCENT_THRESHOLD_M) {
                if (e > a) ascent += e - a else descent += a - e
                anchorEle = e
            }
        }
        points.add(fix)
        return fix
    }

    fun stats(): TrackStats {
        if (points.isEmpty()) return TrackStats.EMPTY
        var minEle: Double? = null
        var maxEle: Double? = null
        for (p in points) {
            p.ele?.let { e ->
                minEle = if (minEle == null || e < minEle!!) e else minEle
                maxEle = if (maxEle == null || e > maxEle!!) e else maxEle
            }
        }
        return TrackStats(
            points = points.size,
            distanceM = distance,
            ascentM = ascent,
            descentM = descent,
            durationMs = (points.last().timeMs - points.first().timeMs).coerceAtLeast(0L),
            movingMs = moving,
            minEle = minEle,
            maxEle = maxEle,
        )
    }
}
