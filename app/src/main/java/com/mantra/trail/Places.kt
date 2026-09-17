package com.mantra.trail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * FINDING A PLACE BY NAME (17.9.2026).
 *
 * Until now the only way to put a route point on a hut was to pan the map until it was under the
 * crosshair. This asks Google's Places for a name and hands back somewhere to walk to.
 *
 * TESTED AGAINST HIS OWN KEY on a desk: "planinarski dom Sljeme" returned Runolist, Puntijarka and
 * Grafičar with their coordinates.
 *
 * IT COSTS A BILLED REQUEST PER SEARCH, so nothing searches on its own: he types, he presses, and
 * the answer stays until he searches again. The search is biased to where he is, because a hut
 * called Grafičar exists in more than one country and the one he means is the near one.
 */
object Places {

    private const val SEARCH = "https://places.googleapis.com/v1/places:searchText"
    private const val FIELDS = "places.displayName,places.formattedAddress,places.location"

    data class Place(val name: String, val where: String, val lat: Double, val lon: Double)

    suspend fun search(
        text: String,
        near: Fix?,
        store: Store,
    ): Pair<List<Place>, String?> = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext emptyList<Place>() to null
        val key = Keyring.best(store.keyring)?.value
            ?: return@withContext emptyList<Place>() to
                "No Google key on the ring. Settings: Google maps, keys."

        try {
            val body = JSONObject()
                .put("textQuery", text)
                .put("maxResultCount", 8)
                .put("languageCode", "hr")
            if (near != null) {
                body.put(
                    "locationBias",
                    JSONObject().put(
                        "circle",
                        JSONObject()
                            .put(
                                "center",
                                JSONObject().put("latitude", near.lat).put("longitude", near.lon),
                            )
                            .put("radius", 50_000.0),
                    ),
                )
            }

            val connection = URL(SEARCH).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Goog-Api-Key", key)
            connection.setRequestProperty("X-Goog-FieldMask", FIELDS)
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
                return@withContext emptyList<Place>() to
                    (googleSays?.let { "Google: $it" } ?: "Google answered $code")
            }

            val text2 = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val array = JSONObject(text2).optJSONArray("places")
                ?: return@withContext emptyList<Place>() to "Nothing found"

            val found = ArrayList<Place>()
            for (i in 0 until array.length()) {
                val place = array.getJSONObject(i)
                val location = place.optJSONObject("location") ?: continue
                found.add(
                    Place(
                        name = place.optJSONObject("displayName")?.optString("text").orEmpty()
                            .ifBlank { "unnamed" },
                        where = place.optString("formattedAddress").orEmpty(),
                        lat = location.optDouble("latitude", Double.NaN),
                        lon = location.optDouble("longitude", Double.NaN),
                    )
                )
            }
            val real = found.filterNot { it.lat.isNaN() || it.lon.isNaN() }
            if (real.isEmpty()) real to "Nothing found" else real to null
        } catch (e: Exception) {
            emptyList<Place>() to "Google could not be reached: ${e.javaClass.simpleName}"
        }
    }
}
