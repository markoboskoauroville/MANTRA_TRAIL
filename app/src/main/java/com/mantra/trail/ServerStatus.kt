package com.mantra.trail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * IS THE MAP SERVER ON THIS PHONE ANSWERING, AND WHAT IS IT HOLDING?
 *
 * MANTRA_MAP_SERVER renders the offline files and hands them over as ordinary tiles. This asks it
 * the one question that matters before a walk: are you there, and do you have the map I need.
 *
 * It is a loopback request, so it works with the radio off and costs nothing. A refusal is
 * reported in the words somebody can act on — not "connection refused" but "not running: start
 * the map server app" — because the fix for that is one press in another app.
 */
object ServerStatus {

    data class Answer(val running: Boolean, val maps: List<String>, val text: String)

    suspend fun ask(port: Int = Layers.SERVER_PORT): Answer = withContext(Dispatchers.IO) {
        try {
            val connection = URL("http://127.0.0.1:$port/status").openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            val code = connection.responseCode
            if (code != 200) {
                connection.disconnect()
                return@withContext Answer(false, emptyList(), "answered $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val json = JSONObject(body)
            val array = json.optJSONArray("maps")
            val maps = (0 until (array?.length() ?: 0)).mapNotNull { array?.optString(it) }
                .filter { it.isNotBlank() }
            val tiles = json.optInt("tilesServed", 0)
            Answer(
                running = true,
                maps = maps,
                text = if (maps.isEmpty()) {
                    "running, no maps in it yet"
                } else {
                    "running · ${maps.joinToString(", ")} · $tiles tiles"
                },
            )
        } catch (e: Exception) {
            Answer(false, emptyList(), "not running: start the map server app")
        }
    }
}
