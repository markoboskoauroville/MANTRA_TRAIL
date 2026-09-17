package com.mantra.trail

import android.content.Context
import btools.router.OsmNodeNamed
import btools.router.RoutingContext
import btools.router.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * BROUTER, INSIDE THIS APP AND OFFLINE.
 *
 * The engine is vendored under btools/ (MIT, abrensch/brouter, LICENSE-BROUTER at the root of
 * this repository). It reads the same kind of file the map does — a square of the world kept on
 * the phone — so a route is found with the radio off, in a valley, which is the only place the
 * question ever really matters.
 *
 * PROVED ON A DESK FIRST (16.9.2026): the same sources, profile and segment file routed 3.8 km
 * over Medvednica in 400 ms, 129 points, 64 m of climb, and gave a different second alternative.
 * tools/RouteProbe.java in this repository is that run, kept so the next person can repeat it.
 *
 * What this adds on top of the engine: the profiles live in the APK and are unpacked once, the
 * segment file is found from the coordinates rather than by asking him to name a square, and
 * alternatives that come back identical are dropped rather than shown as choices.
 */
object Routing {

    data class Option(
        val points: List<Fix>,
        val metres: Int,
        val climbM: Int,
        val colour: Long,
        /** Google's turn instructions when they came with the route; empty for BRouter's. */
        val turns: List<String> = emptyList(),
        /** The height along it, once he has asked for it. */
        val profile: Elevation.Profile? = null,
    )

    /** The five colours the options are drawn in, in the order they are given out. */
    val COLOURS = listOf(0xFF60A5FAL, 0xFF34D399L, 0xFFE8A64BL, 0xFFEF4444L, 0xFFF2DDB4L)

    val PROFILES = listOf("trekking", "hiking-mountain", "shortest")

    private fun profileDir(context: Context): File = File(context.filesDir, "brouter").apply { mkdirs() }

    fun segmentDir(context: Context): File = File(context.filesDir, "segments").apply { mkdirs() }

    /** Unpack the profiles out of the APK once. They are small and they never change. */
    fun prepare(context: Context) {
        val dir = profileDir(context)
        listOf("lookups.dat", "trekking.brf", "hiking-mountain.brf", "shortest.brf").forEach { name ->
            val target = File(dir, name)
            if (target.exists() && target.length() > 0) return@forEach
            runCatching {
                context.assets.open("brouter/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    fun hasSegmentFor(context: Context, lat: Double, lon: Double): Boolean =
        File(segmentDir(context), Segments.nameFor(lat, lon)).let { it.exists() && it.length() > 1_000_000 }

    /**
     * Find up to [wanted] ways between the two points. Returns the options, or an empty list with
     * the reason on [problem].
     */
    /**
     * Find up to [wanted] ways THROUGH the points, in the order they were placed (16.9.2026).
     * BRouter takes the whole list as waypoints, so A to B to C is one route with the engine
     * choosing the way between each pair, not three routes stitched together afterwards.
     */
    suspend fun through(
        context: Context,
        points: List<Pair<Double, Double>>,
        profile: String,
        wanted: Int,
        onProgress: (String) -> Unit,
    ): Pair<List<Option>, String?> = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext emptyList<Option>() to "Place at least two points"
        prepare(context)
        val missing = points
            .flatMap { Segments.namesFor(it.first, it.second, it.first, it.second) }
            .distinct()
            .filterNot { File(segmentDir(context), it).exists() }
        if (missing.isNotEmpty()) {
            return@withContext emptyList<Option>() to
                "Missing map data for routing: ${missing.joinToString(", ")}"
        }
        val profileFile = File(profileDir(context), "$profile.brf")
        if (!profileFile.exists()) {
            return@withContext emptyList<Option>() to "The $profile profile is not unpacked"
        }

        val found = ArrayList<Option>()
        val seen = HashSet<String>()
        for (alternative in 0 until wanted.coerceIn(1, 5)) {
            onProgress("Looking for way ${alternative + 1} of $wanted…")
            val option = runCatching { one(context, points, profileFile, alternative) }
                .getOrElse { return@withContext found to "Routing failed: ${it.javaClass.simpleName}" }
                ?: continue
            // BROUTER WILL HAND BACK THE SAME WAY TWICE when there is no real alternative, and
            // two identical lines drawn in two colours is a lie about there being a choice.
            val fingerprint = "${option.metres}:${option.points.size}:${option.climbM}"
            if (!seen.add(fingerprint)) continue
            found.add(option.copy(colour = COLOURS[found.size % COLOURS.size]))
        }
        if (found.isEmpty()) {
            found to "No way found between those two points"
        } else {
            found to null
        }
    }

    private fun one(
        context: Context,
        points: List<Pair<Double, Double>>,
        profileFile: File,
        alternative: Int,
    ): Option? {
        val rc = RoutingContext()
        rc.localFunction = profileFile.absolutePath
        rc.setAlternativeIdx(alternative)

        val waypoints = ArrayList<OsmNodeNamed>()
        points.forEachIndexed { index, at -> waypoints.add(node(Route.letterFor(index), at.first, at.second)) }

        val engine = RoutingEngine(
            null,
            null,
            segmentDir(context),
            waypoints,
            rc,
        )
        // A ceiling in milliseconds, so a route that cannot be found gives up rather than holding
        // a phone warm in a pocket (delivery-gate.md: every wait is bounded).
        engine.doRun(25_000L)
        if (engine.errorMessage != null) return null
        val track = engine.foundTrack ?: return null
        if (track.nodes.isEmpty()) return null

        val points = track.nodes.map { node ->
            Fix(
                // The engine keeps coordinates as microdegrees with the poles and the meridian
                // added in, which is what makes them fit in an int. Kotlin sees the interface's
                // getters as these properties.
                lat = node.iLat / 1_000_000.0 - 90.0,
                lon = node.iLon / 1_000_000.0 - 180.0,
                ele = null,
                timeMs = 0L,
                accuracyM = null,
            )
        }
        return Option(points, track.distance, track.ascend, COLOURS.first())
    }

    private fun node(name: String, lat: Double, lon: Double): OsmNodeNamed {
        val n = OsmNodeNamed()
        n.name = name
        n.ilat = ((lat + 90.0) * 1_000_000.0 + 0.5).toInt()
        n.ilon = ((lon + 180.0) * 1_000_000.0 + 0.5).toInt()
        return n
    }
}
