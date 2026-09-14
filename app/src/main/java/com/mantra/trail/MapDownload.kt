package com.mantra.trail

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * FETCHING THE OFFLINE MAP, WITH THE PERSON WATCHING IT HAPPEN.
 *
 * 176 MB is minutes on a phone signal, and download-monitor.md's rule is that nothing longer than
 * a minute happens in the dark: the size is said before it starts, the progress is a number while
 * it runs, and a failure says what failed rather than leaving a button that did nothing.
 *
 * IT RESUMES. A half-finished file is kept as `.part` and the next attempt asks the server for
 * the rest with a Range header. Somebody on a mountain road loses the signal at 80% once, and
 * starting again from zero is how an app gets deleted.
 *
 * THE FILE IS ONLY RENAMED WHEN IT IS WHOLE. A `.map` of the right name and the wrong length is
 * the worst outcome: mapsforge opens it, draws part of a country, and nothing says why.
 */
object MapDownload {

    data class Progress(val done: Long, val total: Long) {
        val percent: Int get() = if (total > 0) ((done * 100) / total).toInt() else 0
    }

    fun target(context: Context): File = File(File(context.filesDir, "maps").apply { mkdirs() }, Layers.OfflineDownload.NAME)

    fun isPresent(context: Context): Boolean = target(context).let { it.exists() && it.length() > 1_000_000 }

    fun sizeOnDisk(context: Context): Long = target(context).let { if (it.exists()) it.length() else 0L }

    /**
     * Fetch it, resuming if there is a part file. Returns null when the file is whole, or a
     * sentence saying what went wrong.
     */
    suspend fun fetch(
        context: Context,
        onProgress: (Progress) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val finished = target(context)
        val part = File(finished.parentFile, "${finished.name}.part")
        try {
            val already = if (part.exists()) part.length() else 0L
            val connection = URL(Layers.OfflineDownload.URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "MantraTrail/1")
            if (already > 0) connection.setRequestProperty("Range", "bytes=$already-")
            connection.connect()

            val code = connection.responseCode
            // 206 means the server honoured the Range and we keep what we have; 200 means it did
            // not, and keeping the old bytes would splice two different files together.
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resuming) {
                return@withContext "The map server answered $code"
            }
            val total = (if (resuming) already else 0L) + connection.contentLengthLong.coerceAtLeast(0L)

            val out = java.io.FileOutputStream(part, resuming)
            var done = if (resuming) already else 0L
            if (!resuming && part.exists()) done = 0L
            connection.inputStream.use { input ->
                out.use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        done += read
                        if (done - lastReport > 2_000_000) {
                            lastReport = done
                            onProgress(Progress(done, total))
                        }
                    }
                }
            }
            connection.disconnect()

            // Only a file of the length the server promised becomes the map.
            if (total > 0 && part.length() < total) {
                return@withContext "The download stopped at ${part.length() * 100 / total}%. Press it again to continue."
            }
            if (finished.exists()) finished.delete()
            if (!part.renameTo(finished)) return@withContext "The file could not be put in place"
            onProgress(Progress(finished.length(), finished.length()))
            null
        } catch (e: Exception) {
            "The download stopped: ${e.javaClass.simpleName}. Press it again to continue."
        }
    }
}
