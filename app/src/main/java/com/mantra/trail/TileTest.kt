package com.mantra.trail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * ASK THE SERVICE FOR ONE TILE AND SAY WHAT CAME BACK.
 *
 * "Thunderforest doesn't work" is a sentence about a blank screen; it could be a wrong key, a
 * refused key, an exhausted quota, a URL built wrongly, or no network at all — and every one of
 * those looks identical on the map. This fetches a single tile over the same URL the engine uses
 * and reports the status and the size, which tells the five apart in one press (16.9.2026).
 */
object TileTest {

    suspend fun check(layer: MapLayer, session: String?, key: String?): String =
        withContext(Dispatchers.IO) {
            val url = Layers.tileUrl(layer, 12, 2231, 1479, session, key)
                ?: return@withContext "${layer.name}: no URL — it needs a key first"
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.setRequestProperty("User-Agent", "MantraTrail/1")
                val code = connection.responseCode
                val type = connection.contentType ?: "no type"
                val bytes = if (code == 200) {
                    connection.inputStream.use { it.readBytes().size }
                } else {
                    connection.errorStream?.use { it.readBytes().size } ?: 0
                }
                connection.disconnect()
                when {
                    code == 200 && type.startsWith("image") && bytes > 500 ->
                        "${layer.name}: the service answered with a $bytes byte image. The map data is fine."
                    code == 200 ->
                        "${layer.name}: answered 200 but sent $type, $bytes bytes — not a tile."
                    code == 401 || code == 403 -> {
                        // Google's own sentence when there is one: it names the project and the
                        // API to switch on, which is what he actually needs (17.9.2026).
                        val said = runCatching {
                            connection.errorStream?.bufferedReader()?.use { it.readText() }
                        }.getOrNull()
                        val googleSays = runCatching {
                            org.json.JSONObject(said ?: "").getJSONObject("error").optString("message", "")
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                        googleSays?.let { "${layer.name}: $it" }
                            ?: "${layer.name}: refused ($code). The key is wrong, expired, or not allowed here."
                    }
                    code == 429 ->
                        "${layer.name}: too many requests (429). The key's quota is used up."
                    code == 404 ->
                        "${layer.name}: no tile there (404). The URL shape is wrong for this service."
                    else -> "${layer.name}: the service answered $code."
                }
            } catch (e: Exception) {
                "${layer.name}: could not be reached — ${e.javaClass.simpleName}. No network?"
            }
        }
}
