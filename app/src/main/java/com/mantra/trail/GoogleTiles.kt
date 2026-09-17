package com.mantra.trail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * GOOGLE'S MAP, WITH HIS KEY AND NOT WITH THE APP'S.
 *
 * The Maps SDK reads its key from the app when Android installs it, which is why v4 to v6 had a
 * live key inside a public APK. This does not: the Map Tiles API takes the key at run time, so it
 * can come from the file he picked and nothing is ever compiled in.
 *
 * HOW IT WORKS. One POST creates a session for a map type; the answer is a token, and the tiles
 * are then ordinary z/x/y requests carrying the token and the key. The token has an expiry, so it
 * is kept per view and made again when it runs out.
 *
 * WHAT IT COSTS. Google bills per tile against his account. So the session is made once per view
 * and only when that view is actually chosen — never to "check whether it works".
 *
 * AND ITS TILES ARE NEVER KEPT. Google's terms forbid caching and name offline use as a
 * prohibited case, so every Google layer is cacheable = false and CH refuses them with the reason.
 */
object GoogleTiles {

    private const val CREATE = "https://tile.googleapis.com/v1/createSession"

    private data class Session(val token: String, val madeMs: Long)

    private val sessions = HashMap<String, Session>()

    /** Sessions are good for a while; this is well inside it and costs one call a day at most. */
    private const val LIFETIME_MS = 6L * 3600 * 1000

    /**
     * The token for a view, made if there is none or the last one is old. Returns the token, or
     * null with the reason in [problem].
     */
    suspend fun session(view: MapLayer.GoogleView, key: String): Result = withContext(Dispatchers.IO) {
        val cached = sessions[view.name]
        if (cached != null && System.currentTimeMillis() - cached.madeMs < LIFETIME_MS) {
            return@withContext Result(cached.token, null)
        }
        try {
            val body = JSONObject()
                .put("mapType", view.mapType)
                .put("language", "en-GB")
                .put("region", "HR")
            if (view.overlayRoads) {
                body.put("layerTypes", JSONArray().put("layerRoadmap"))
            }
            val connection = URL("$CREATE?key=$key").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // GOOGLE'S OWN WORDS, NOT MINE (17.9.2026).
                //
                // Tested against his real key on a desk: the key was valid and the refusal was
                // "Map Tiles API has not been used in project 342783832558 before or it is
                // disabled", with the exact console link to switch it on. My sentence said to
                // enable the API but not WHICH PROJECT, and he spent half a day making a second
                // key that was refused for the same reason. Google says it better; pass it on.
                //
                // The key is never in this text: the message quotes the project, not the key, and
                // the URL that carries the key is never shown.
                val said = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                connection.disconnect()
                val googleSays = runCatching {
                    JSONObject(said ?: "").getJSONObject("error").optString("message", "")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                return@withContext Result(
                    null,
                    when {
                        googleSays != null -> "Google: $googleSays"
                        code == 429 -> "Google is rate limiting this key. Try again shortly."
                        code == 401 || code == 403 ->
                            "Google refused the key ($code). In Cloud Console, enable the Map Tiles API for its project."
                        else -> "Google answered $code"
                    },
                )
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            val token = JSONObject(text).optString("session", "")
            if (token.isEmpty()) {
                return@withContext Result(null, "Google gave no session token")
            }
            sessions[view.name] = Session(token, System.currentTimeMillis())
            Result(token, null)
        } catch (e: Exception) {
            Result(null, "Google could not be reached: ${e.javaClass.simpleName}")
        }
    }

    data class Result(val token: String?, val problem: String?)

    /** Forgotten when the key changes, so a new key never rides on an old session. */
    fun forget() {
        sessions.clear()
    }
}
