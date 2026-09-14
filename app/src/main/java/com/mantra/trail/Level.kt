package com.mantra.trail

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * THE SPIRIT LEVEL AND THE NEEDLE, AS ARITHMETIC. No Android imports (android-app.md 1).
 *
 * The phone's gravity vector comes in as three numbers in the device's own frame: x to the right
 * of the screen, y to the top, z out of the glass. Lying flat on its back on a true table the
 * vector is (0, 0, +9.81). Everything below is derived from that one fact.
 *
 * WHY A CALIBRATION EXISTS. No tripod plate is true and no phone is flat: the camera bump alone
 * is a degree. A level that cannot be zeroed on the surface you are standing on is an ornament.
 */
object Level {

    /** Standard gravity, for deciding whether the reading is gravity at all. */
    const val G = 9.80665

    /** A vector this far from 1 g is the phone being moved, and the bubble is not to be believed. */
    const val G_TOLERANCE = 1.5

    /** Inside this the surface is called level, and the bubble goes green. */
    const val LEVEL_TOLERANCE_DEG = 0.3

    /** How far from centre the bubble can travel: past this it sits at the edge of the vial. */
    const val VIAL_RANGE_DEG = 10.0

    data class Calibration(val pitchZero: Double = 0.0, val rollZero: Double = 0.0)

    data class Reading(
        /** Nose up is positive, in degrees. */
        val pitch: Double,
        /** Right side down is positive, in degrees. */
        val roll: Double,
        /** The angle between the glass and true horizontal, whichever way it leans. */
        val tilt: Double,
        /** False while the phone is being moved: the numbers are then acceleration, not gravity. */
        val trustworthy: Boolean,
    ) {
        val level: Boolean get() = trustworthy && tilt <= LEVEL_TOLERANCE_DEG
    }

    /**
     * Pitch and roll from the gravity vector, with the calibration taken off. Both are computed
     * with atan2 against the magnitude of the other two axes, so they stay correct when the phone
     * is stood on its edge — the naive asin version goes wrong past 45 degrees and looks right up
     * to it, which is the worst way for it to be wrong.
     */
    fun read(gx: Double, gy: Double, gz: Double, cal: Calibration = Calibration()): Reading {
        val magnitude = sqrt(gx * gx + gy * gy + gz * gz)
        val trustworthy = abs(magnitude - G) <= G_TOLERANCE && magnitude > 0.0
        val pitch = Geo.deg(atan2(-gy, hypot(gx, gz))) - cal.pitchZero
        val roll = Geo.deg(atan2(gx, hypot(gy, gz))) - cal.rollZero
        // The two lean angles composed into the one angle between the glass and horizontal. This
        // is exact rather than a sum: leaning 3 degrees forward and 4 to the side is 5 degrees
        // off level, not 7, and on a tripod head that difference is the whole job.
        val tilt = Geo.deg(acos((cos(Geo.rad(pitch)) * cos(Geo.rad(roll))).coerceIn(-1.0, 1.0)))
        return Reading(pitch, roll, tilt, trustworthy)
    }

    /**
     * Where the bubble sits in a round vial, as two numbers from -1 to 1. It is clamped rather
     * than let run off, because a bubble that has left the glass tells you nothing about which
     * way to tip and a bubble pinned at the edge tells you exactly that.
     */
    fun bubble(reading: Reading, rangeDeg: Double = VIAL_RANGE_DEG): Pair<Double, Double> {
        val x = (reading.roll / rangeDeg).coerceIn(-1.0, 1.0)
        val y = (reading.pitch / rangeDeg).coerceIn(-1.0, 1.0)
        val length = hypot(x, y)
        // Round vial: the bubble travels on a disc, not in a square, so a corner would be further
        // from centre than the edge it is meant to represent.
        return if (length <= 1.0) Pair(x, y) else Pair(x / length, y / length)
    }

    /**
     * A first-order filter. The accelerometer is noisy enough that an unfiltered bubble shivers,
     * and a shivering bubble cannot be read at all. Alpha is how much of the new reading is taken:
     * small is steady and slow, large is quick and jumpy.
     */
    fun smooth(previous: Double, next: Double, alpha: Double = 0.15): Double =
        previous + (next - previous) * alpha.coerceIn(0.0, 1.0)

    /**
     * The same filter for an angle that wraps, used by the compass needle. Averaging 359 and 1 the
     * ordinary way gives 180, which points the needle exactly backwards — the failure that makes a
     * compass useless at precisely the moment it is being watched.
     */
    fun smoothAngle(previous: Double, next: Double, alpha: Double = 0.15): Double =
        Geo.normaliseDeg(previous + Geo.deltaDeg(previous, next) * alpha.coerceIn(0.0, 1.0))
}
