package com.mantra.trail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GOOGLE'S WALKING DIRECTIONS, BESIDE BROUTER'S (17.9.2026).
 *
 * He asked for a toggle so the two can be compared. They answer different questions and both
 * answers are worth having: BRouter knows the paths in the offline file and works with the radio
 * off; Google knows what is open, what is a staircase, and where people actually walk, and needs
 * a signal and a key.
 *
 * TESTED AGAINST HIS OWN KEY on a desk before it shipped: Sljeme road to the summit came back
 * 6894 m in 5781 seconds with a 667-character polyline.
 *
 * IT COSTS MONEY. Every press is a billed request on his account, so nothing here runs on its
 * own — no route is asked for unless he asks for it, and the reply is drawn once and kept until
 * he asks again.
 */
object GoogleRoutes {

    /** Set once by the activity; without it an Android-restricted key is refused. */
    @Volatile
    var context: android.content.Context? = null

    private fun identify(connection: java.net.HttpURLConnection) {
        val ctx = context ?: return
        AndroidCaller.headers(ctx).forEach { (k, v) -> connection.setRequestProperty(k, v) }
    }

    private const val URL_BASE = "https://routes.googleapis.com/directions/v2:computeRoutes"

    /** The fields asked for. Asking for fewer is cheaper, and these are all that is drawn. */
    private const val FIELDS =
        "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.legs.steps.navigationInstruction"

    suspend fun between(
        points: List<Pair<Double, Double>>,
        store: Store,
        wanted: Int,
    ): Pair<List<Routing.Option>, String?> = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext emptyList<Routing.Option>() to "Place at least two points"
        val key = Keyring.best(store.keyring)?.value
            ?: return@withContext emptyList<Routing.Option>() to
                "No Google key on the ring. Settings: Google maps, keys."

        try {
            val body = JSONObject()
                .put("origin", place(points.first()))
                .put("destination", place(points.last()))
                .put("travelMode", "WALK")
                .put("computeAlternativeRoutes", wanted > 1)
            if (points.size > 2) {
                val between = JSONArray()
                points.subList(1, points.size - 1).forEach { between.put(place(it)) }
                body.put("intermediates", between)
            }

            val connection = URL(URL_BASE).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Goog-Api-Key", key)
            connection.setRequestProperty("X-Goog-FieldMask", FIELDS)
            identify(connection)
            connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val said = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                connection.disconnect()
                val googleSays = runCatching {
                    JSONObject(said ?: "").getJSONObject("error").optString("message", "")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                return@withContext emptyList<Routing.Option>() to
                    (googleSays?.let { "Google: $it" } ?: "Google answered $code")
            }

            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val routes = JSONObject(text).optJSONArray("routes")
                ?: return@withContext emptyList<Routing.Option>() to "Google found no way there"

            val found = ArrayList<Routing.Option>()
            for (i in 0 until minOf(routes.length(), wanted.coerceIn(1, 5))) {
                val route = routes.getJSONObject(i)
                val encoded = route.optJSONObject("polyline")?.optString("encodedPolyline").orEmpty()
                val line = Polyline.decode(encoded)
                if (line.size < 2) continue
                found.add(
                    Routing.Option(
                        points = line.map { Fix(it.first, it.second, null, 0L, null) },
                        metres = route.optInt("distanceMeters"),
                        // Google gives the time, not the climb; the climb is BRouter's to know,
                        // or Google's Elevation API's when he asks for it.
                        climbM = 0,
                        colour = Routing.COLOURS[found.size % Routing.COLOURS.size],
                        // THE TURNS WERE ALREADY IN THE ANSWER and were being thrown away
                        // (17.9.2026). On a road they are the difference between a line and
                        // directions; on a path they are usually silence, which is honest too.
                        turns = turnsOf(route),
                    )
                )
            }
            if (found.isEmpty()) {
                emptyList<Routing.Option>() to "Google found no way there"
            } else {
                found to null
            }
        } catch (e: Exception) {
            emptyList<Routing.Option>() to "Google could not be reached: ${e.javaClass.simpleName}"
        }
    }

    /** Every instruction in the answer, in order, as sentences. */
    private fun turnsOf(route: JSONObject): List<String> {
        val legs = route.optJSONArray("legs") ?: return emptyList()
        val said = ArrayList<String>()
        for (l in 0 until legs.length()) {
            val steps = legs.getJSONObject(l).optJSONArray("steps") ?: continue
            for (s in 0 until steps.length()) {
                val instruction = steps.getJSONObject(s)
                    .optJSONObject("navigationInstruction")
                    ?.optString("instructions")
                    .orEmpty()
                if (instruction.isNotBlank()) said.add(instruction)
            }
        }
        return said
    }

    private fun place(at: Pair<Double, Double>): JSONObject = JSONObject().put(
        "location",
        JSONObject().put(
            "latLng",
            JSONObject().put("latitude", at.first).put("longitude", at.second),
        ),
    )
}
