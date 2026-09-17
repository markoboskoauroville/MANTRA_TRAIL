package com.mantra.trail

import org.oscim.core.Tile
import org.oscim.tiling.source.HttpEngine
import org.oscim.tiling.source.UrlTileSource
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * FETCHING A TILE WITH THE STACK THAT IS KNOWN TO WORK (17.9.2026).
 *
 * The URL this app builds for Google is right — proved on a desk, byte for byte, returning a
 * 256-pixel PNG — and still nothing drew. VTM's own client cannot do https; its OkHttp engine
 * should, and did not either. Rather than guess a third time at somebody else's networking, the
 * tiles now go through java.net.HttpURLConnection: the same thing that fetches the session, the
 * routes, the elevations and the map downloads, every one of which has worked all week.
 *
 * It also REPORTS. What came back for the last tile — the status, the size, the reason — is kept
 * where the settings can show it, because "the map is white" is not something anybody can debug
 * and "403 on tile 16/35762/23697" is.
 */
class TileHttp(private val source: UrlTileSource) : HttpEngine {

    private var connection: HttpURLConnection? = null
    private var stream: InputStream? = null
    private var cache: OutputStream? = null

    override fun sendRequest(tile: Tile?) {
        if (tile == null) throw java.io.IOException("no tile asked for")
        val url = source.getTileUrl(tile)
        // IMAGERY HE HAS ALREADY KEPT (17.9.2026): a layer whose address begins with file:// is
        // read off the phone, by the same engine and the same code path as everything else. A
        // tile that was never fetched is simply missing, which VTM draws as nothing.
        if (url.startsWith("file://")) {
            val onDisk = java.io.File(url.removePrefix("file://"))
            if (!onDisk.exists()) throw java.io.IOException("not kept: ${onDisk.name}")
            stream = onDisk.inputStream()
            return
        }
        val open = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "MantraTrail/1")
            // The same two headers the SDK sends: an Android-restricted key is refused without
            // them, and every tile came back "application <empty> are blocked" (17.9.2026).
            GoogleTiles.context?.let { ctx ->
                AndroidCaller.headers(ctx).forEach { (k, v) -> setRequestProperty(k, v) }
            }
            instanceFollowRedirects = true
        }
        connection = open
        val code = open.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            val said = runCatching {
                open.errorStream?.bufferedReader()?.use { it.readText().take(200) }
            }.getOrNull().orEmpty()
            Report.tiles("tile ${tile.zoomLevel}/${tile.tileX}/${tile.tileY}: HTTP $code $said")
            throw java.io.IOException("tile answered $code")
        }
        stream = open.inputStream
    }

    override fun read(): InputStream {
        val open = stream ?: throw java.io.IOException("nothing was fetched")
        return open
    }

    override fun close() {
        runCatching { stream?.close() }
        runCatching { connection?.disconnect() }
        stream = null
        connection = null
    }

    override fun setCache(os: OutputStream?) {
        cache = os
    }

    override fun requestCompleted(success: Boolean): Boolean {
        if (success) Report.tiles("tiles are arriving")
        close()
        return success
    }

    class Factory : HttpEngine.Factory {
        override fun create(tileSource: UrlTileSource): HttpEngine = TileHttp(tileSource)
    }
}

/** The last thing the tile fetcher saw, for the settings to show. One line, no history. */
object Report {
    @Volatile
    private var lastTiles: String? = null

    fun tiles(line: String) {
        lastTiles = line
    }

    fun tileReport(): String = lastTiles ?: "no tile has been asked for yet"
}
