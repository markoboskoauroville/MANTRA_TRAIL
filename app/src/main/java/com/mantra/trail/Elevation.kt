package com.mantra.trail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * THE HEIGHT ALONG A ROUTE (17.9.2026).
 *
 * Distance says how far; this says what it costs. Two kilometres flat and two kilometres up the
 * side of Medvednica are the same number until somebody draws the ground under them.
 *
 * BRouter already answers the climb for its own routes, offline, and that number is kept. This is
 * for Google's routes and for saved tracks with no heights in them, and it is one billed request
 * for the whole line — the points are sampled by ground first (Route.sample), so a hairpin does
 * not eat the budget that a ridge needs.
 *
 * Tested against his key on a desk: the Sljeme line came back 975, 980, 954, 951, 868 metres.
 */
object Elevation {

    /** Set once by the activity; without it an Android-restricted key is refused. */
    @Volatile
    var context: android.content.Context? = null

    private fun identify(connection: java.net.HttpURLConnection) {
        val ctx = context ?: return
        AndroidCaller.headers(ctx).forEach { (k, v) -> connection.setRequestProperty(k, v) }
    }

    private const val API = "https://maps.googleapis.com/maps/api/elevation/json"

    /** The height at each sample, in metres, in the order they were given. */
    data class Profile(val metres: List<Double>) {
        val climbM: Int
            get() = metres.zipWithNext().sumOf { (a, b) -> (b - a).coerceAtLeast(0.0) }.toInt()

        val dropM: Int
            get() = metres.zipWithNext().sumOf { (a, b) -> (a - b).coerceAtLeast(0.0) }.toInt()

        val lowest: Int get() = (metres.minOrNull() ?: 0.0).toInt()

        val highest: Int get() = (metres.maxOrNull() ?: 0.0).toInt()

        /** One line somebody reads before deciding to walk it. */
        fun line(): String = "↑${climbM}m ↓${dropM}m · $lowest to ${highest}m"
    }

    suspend fun along(
        points: List<Pair<Double, Double>>,
        store: Store,
        samples: Int = 40,
    ): Pair<Profile?, String?> = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext null to "Nothing to measure"
        val key = Keyring.best(store.keyring)?.value
            ?: return@withContext null to "No Google key on the ring. Settings: Google maps, keys."
        val asked = Route.sample(points, samples)
        val path = asked.joinToString("|") { "${it.first},${it.second}" }

        try {
            val connection = URL("$API?path=$path&samples=${asked.size}&key=$key")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            identify(connection)
            val code = connection.responseCode
            val text = if (code == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader()?.use { it.readText() }
            }
            connection.disconnect()
            val answer = JSONObject(text ?: "")
            val status = answer.optString("status")
            if (status != "OK") {
                // Google's own words, as everywhere else: they name the project and the fix.
                val said = answer.optString("error_message").takeIf { it.isNotBlank() }
                return@withContext null to (said?.let { "Google: $it" } ?: "Google answered $status")
            }
            val results = answer.optJSONArray("results")
                ?: return@withContext null to "Google gave no heights"
            val metres = (0 until results.length()).map {
                results.getJSONObject(it).optDouble("elevation", 0.0)
            }
            if (metres.isEmpty()) null to "Google gave no heights" else Profile(metres) to null
        } catch (e: Exception) {
            null to "Google could not be reached: ${e.javaClass.simpleName}"
        }
    }
}
