package com.mantra.trail

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * FETCHING THE SQUARE OF THE WORLD THAT ROUTING NEEDS.
 *
 * The same shape as the offline map's download and for the same reasons (download-monitor.md):
 * it resumes, it says how far it has got, every wait is bounded, and a half-finished file is
 * never mistaken for a whole one. E15_N45 is 130 MB and somebody may be paying for it by the
 * megabyte, so it is asked for by name and by size before it starts.
 */
object SegmentDownload {

    data class Progress(val done: Long, val total: Long) {
        val percent: Int get() = if (total <= 0) 0 else ((done * 100) / total).toInt()
    }

    suspend fun fetch(
        context: Context,
        name: String,
        onProgress: (Progress) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val finished = File(Routing.segmentDir(context), name)
        if (finished.exists() && finished.length() > 1_000_000) return@withContext null
        val part = File(Routing.segmentDir(context), "$name.part")
        try {
            val connection = URL("${Segments.BASE}/$name").openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "MantraTrail/1")
            val already = if (part.exists()) part.length() else 0L
            if (already > 0) connection.setRequestProperty("Range", "bytes=$already-")

            val code = connection.responseCode
            if (code != 200 && code != 206) {
                connection.disconnect()
                return@withContext "The routing server answered $code"
            }
            val resuming = code == 206
            if (!resuming && already > 0) part.delete()
            val total = connection.contentLengthLong.let { if (resuming) it + already else it }

            connection.inputStream.use { input ->
                java.io.FileOutputStream(part, resuming).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = if (resuming) already else 0L
                    var lastSaid = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - lastSaid > 2_000_000) {
                            lastSaid = done
                            onProgress(Progress(done, total))
                        }
                    }
                }
            }
            connection.disconnect()

            // Only a file of the length the server promised becomes a segment.
            if (total > 0 && part.length() < total) {
                return@withContext "The download stopped early. Press again to carry on."
            }
            if (!part.renameTo(finished)) return@withContext "The file could not be put in place"
            null
        } catch (e: Exception) {
            "The download failed: ${e.javaClass.simpleName}. Press again to carry on."
        }
    }
}
