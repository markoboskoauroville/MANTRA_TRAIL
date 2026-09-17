package com.mantra.trail

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * FETCHING AND KEEPING THE IMAGERY (17.9.2026).
 *
 * Tiles land in files/imagery/z/x/y.jpg — the ordinary order, whatever order the server uses —
 * and are read straight off the disk afterwards by the same engine that draws every other raster
 * map. A tile already on the phone is never fetched twice, so a second area overlapping the first
 * costs only what is new, and a download interrupted halfway carries on where it stopped.
 */
object ImageryStore {

    fun folder(context: Context): File = File(context.filesDir, "imagery").apply { mkdirs() }

    fun fileFor(context: Context, tile: Imagery.Tile): File = File(folder(context), tile.path)

    /** Whether there is any imagery at all, which decides whether the layer is offered. */
    fun has(context: Context): Boolean =
        folder(context).walkTopDown().any { it.isFile && it.name.endsWith(".jpg") }

    fun bytes(context: Context): Long =
        folder(context).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun tiles(context: Context): Int =
        folder(context).walkTopDown().count { it.isFile && it.name.endsWith(".jpg") }

    fun label(context: Context): String {
        val n = tiles(context)
        if (n == 0) return "none yet"
        val mb = bytes(context) / 1_000_000
        return "$n tiles · ${if (mb == 0L) "under a megabyte" else "$mb MB"}"
    }

    fun forget(context: Context): Boolean = folder(context).deleteRecursively()

    data class Progress(val done: Int, val total: Int, val skipped: Int) {
        val percent: Int get() = if (total <= 0) 0 else done * 100 / total

        fun line(): String = "imagery $percent% · $done of $total tiles" +
            if (skipped > 0) " · $skipped already here" else ""
    }

    /**
     * Fetch every tile of a box that is not already on the phone.
     *
     * Their servers are a courtesy, not a product he pays for, so this asks politely: one tile at
     * a time, in order, with the app named in the request. A cancelled download leaves whatever it
     * finished, which is the point of skipping what is already here.
     */
    suspend fun fetch(
        context: Context,
        tiles: List<Imagery.Tile>,
        onProgress: (Progress) -> Unit,
        keepGoing: () -> Boolean = { true },
    ): String? = withContext(Dispatchers.IO) {
        var done = 0
        var skipped = 0
        var failed = 0
        tiles.forEach { tile ->
            if (!keepGoing()) return@withContext "Stopped. What was fetched is kept."
            val file = fileFor(context, tile)
            if (file.exists() && file.length() > 0) {
                skipped++
                done++
                return@forEach
            }
            file.parentFile?.mkdirs()
            val ok = runCatching {
                val connection = URL(tile.url).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "MantraTrail/1 (offline hiking map)")
                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    connection.disconnect()
                    return@runCatching false
                }
                val part = File(file.path + ".part")
                connection.inputStream.use { input ->
                    part.outputStream().use { out -> input.copyTo(out) }
                }
                connection.disconnect()
                part.renameTo(file)
            }.getOrDefault(false)
            if (!ok) failed++
            done++
            if (done % 10 == 0 || done == tiles.size) {
                onProgress(Progress(done, tiles.size, skipped))
            }
        }
        when {
            failed == 0 -> null
            failed < tiles.size / 10 -> "$failed tiles could not be fetched; the rest are here"
            else -> "Most tiles could not be fetched. Check the connection and press again."
        }
    }
}
