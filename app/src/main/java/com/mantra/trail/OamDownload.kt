package com.mantra.trail

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * FETCHING AN OPENANDROMAPS REGION AND UNPACKING THE MAP OUT OF IT.
 *
 * The download resumes, because 1.2 GB over a phone connection will be interrupted and starting
 * again from nothing is not an option anybody should be offered twice. The unpacking does not
 * resume — it cannot — but it is the fast part.
 *
 * A half-written map is never left where the app would open it: the entry is unpacked beside
 * itself and moved into place only when the zip says it is whole.
 */
object OamDownload {

    data class Progress(
        val done: Long,
        val total: Long,
        val unpacking: Boolean = false,
        val bytesPerSecond: Long = 0L,
    ) {
        val percent: Int get() = if (total <= 0) 0 else ((done * 100) / total).toInt()

        /** What is happening, in the words somebody wants while it happens. */
        fun line(label: String): String = when {
            unpacking -> "$label: unpacking…"
            total <= 0 -> "$label: ${done / 1_000_000} MB"
            else -> "$label $percent% · ${done / 1_000_000} of ${total / 1_000_000} MB" +
                (if (bytesPerSecond > 0) " · ${bytesPerSecond / 1_000_000.0} MB/s".take(12) else "") +
                (if (bytesPerSecond > 0) " · ${remaining(done, total, bytesPerSecond)} left" else "")
        }

        private fun remaining(done: Long, total: Long, rate: Long): String {
            val seconds = ((total - done) / rate.coerceAtLeast(1)).toInt()
            return if (seconds >= 60) "${seconds / 60} min" else "${seconds}s"
        }
    }

    /**
     * WHAT IS HAPPENING NOW, for any screen that cares (16.9.2026). He started a 1.2 GB download
     * and had no way to see it was running: the sentence went to the map's note line and the
     * settings he was looking at said nothing. A download this size must be visible from wherever
     * he is standing.
     */
    private val _state = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val state: kotlinx.coroutines.flow.StateFlow<String?> = _state

    fun say(line: String?) {
        _state.value = line
    }

    fun folder(context: Context): File = File(context.filesDir, "maps").apply { mkdirs() }

    fun target(context: Context, region: Oam.Region): File = File(folder(context), region.fileName)

    fun isPresent(context: Context, region: Oam.Region): Boolean =
        target(context, region).let { it.exists() && it.length() > 1_000_000 }

    /** Where the maps are kept, in words he can find on the phone. */
    fun folderLabel(context: Context): String = "Android/data/${context.packageName}/files/maps"

    /** Fetch a listing of one continent from the mirror, or null and the reason. */
    suspend fun index(continent: String): Pair<List<OamIndex.Entry>, String?> =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(OamIndex.urlFor(continent)).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", "MantraTrail/1")
                val code = connection.responseCode
                if (code != 200) {
                    connection.disconnect()
                    return@withContext emptyList<OamIndex.Entry>() to "The mirror answered $code"
                }
                val html = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                val entries = OamIndex.parse(html, continent)
                if (entries.isEmpty()) {
                    entries to "Nothing readable in that listing"
                } else {
                    entries to null
                }
            } catch (e: Exception) {
                emptyList<OamIndex.Entry>() to "The mirror could not be reached: ${e.javaClass.simpleName}"
            }
        }

    /** Fetch a region named by the mirror's own index. */
    suspend fun fetchEntry(
        context: Context,
        entry: OamIndex.Entry,
        onProgress: (Progress) -> Unit,
    ): String? = fetch(
        context,
        Oam.Region(entry.label.lowercase(), entry.label, "${entry.continent}/${entry.fileName}", entry.bytes),
        onProgress,
    )

    fun remove(file: File): String? = if (file.delete()) null else "That map could not be deleted"

    /** Every OpenAndroMaps file already on the phone. */
    fun installed(context: Context): List<File> =
        folder(context).listFiles()
            ?.filter { it.isFile && it.name.startsWith("oam-") && it.name.endsWith(".map") }
            ?.sortedBy { it.name }
            ?: emptyList()

    suspend fun fetch(
        context: Context,
        region: Oam.Region,
        onProgress: (Progress) -> Unit,
    ): String? = withContext(Dispatchers.IO) {
        val finished = target(context, region)
        if (finished.exists() && finished.length() > 1_000_000) return@withContext null
        val zip = File(folder(context), "${region.name}.zip.part")

        try {
            val connection = URL(region.url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "MantraTrail/1")
            val already = if (zip.exists()) zip.length() else 0L
            if (already > 0) connection.setRequestProperty("Range", "bytes=$already-")

            val code = connection.responseCode
            if (code != 200 && code != 206) {
                connection.disconnect()
                return@withContext "The map server answered $code"
            }
            val resuming = code == 206
            if (!resuming && already > 0) zip.delete()
            val total = connection.contentLengthLong.let { if (resuming) it + already else it }

            connection.inputStream.use { input ->
                java.io.FileOutputStream(zip, resuming).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var done = if (resuming) already else 0L
                    var said = 0L
                    var lastMs = System.currentTimeMillis()
                    var lastBytes = done
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - said > 5_000_000) {
                            said = done
                            val now = System.currentTimeMillis()
                            val seconds = ((now - lastMs) / 1000.0).coerceAtLeast(0.001)
                            val rate = ((done - lastBytes) / seconds).toLong()
                            lastMs = now
                            lastBytes = done
                            onProgress(Progress(done, total, bytesPerSecond = rate))
                        }
                    }
                }
            }
            connection.disconnect()

            if (total > 0 && zip.length() < total) {
                return@withContext "The download stopped early. Press again to carry on."
            }

            onProgress(Progress(zip.length(), zip.length(), unpacking = true))
            val part = File(folder(context), "${region.fileName}.part")
            var found = false
            ZipInputStream(zip.inputStream().buffered(256 * 1024)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (!Oam.isTheMap(entry.name)) {
                        zin.closeEntry()
                        continue
                    }
                    part.outputStream().buffered(256 * 1024).use { out -> zin.copyTo(out) }
                    found = true
                    zin.closeEntry()
                    break
                }
            }
            if (!found) {
                part.delete()
                return@withContext "That archive holds no .map file"
            }
            if (!part.renameTo(finished)) {
                part.delete()
                return@withContext "The map could not be put in place"
            }
            zip.delete()
            null
        } catch (e: Exception) {
            "The download failed: ${e.javaClass.simpleName}. Press again to carry on."
        }
    }
}
