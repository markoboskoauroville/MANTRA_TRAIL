package com.mantra.trail

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * THE TRACKS ON THE PHONE: what they are called, what is in the folder, and what renaming and
 * deleting one means. No Android imports (android-app.md 1), so all of it is attacked by Test 1.
 *
 * A track is a file and nothing else. There is no database, no index and no second copy of the
 * truth: the folder IS the list, so a file deleted by any other app is simply gone from the
 * manager next time it looks, rather than showing as a row that opens nothing.
 */
object Tracks {

    /** The date and time a walk started, in the phone's own zone, for a name a person reads. */
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /**
     * What a recording is called when nobody has said otherwise: the day, the time, and the word
     * Track. Baba, 15.9.2026. The date leads it so a folder sorts into the order things happened.
     */
    fun defaultName(startedMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "${STAMP.withZone(zone).format(Instant.ofEpochMilli(startedMs))} Track"

    /**
     * A name a person typed, turned into something a file system will keep. Slashes and the other
     * characters that break a path are replaced rather than removed, so two tracks named a minute
     * apart cannot collapse into one name.
     */
    fun safeFileName(name: String): String {
        val cleaned = name.trim().map { c ->
            when {
                // Parentheses are kept: a route saved from A and B is named "… (AB)" and that
                // is the part somebody reads to tell it from a walk (16.9.2026). They are legal
                // in a file name everywhere this app can write.
                c.isLetterOrDigit() || c == ' ' || c == '-' || c == '_' || c == '.' ||
                    c == '(' || c == ')' -> c
                else -> '-'
            }
        }.joinToString("").trim('-', ' ', '.')
        // "Velebit #2" would otherwise become "Velebit -2": a run of replaced characters and the
        // spaces around it collapse into one dash, which is what somebody would have typed.
        val tidied = cleaned.replace(Regex(" *-[ -]*"), "-")
            // An empty pair of brackets is nothing, and the space before it goes with it.
            .replace(Regex(" *\\(\\s*\\)"), "")
            .trim('-', ' ', '.')
        val base = if (tidied.isEmpty()) "Track" else tidied.take(60)
        return if (base.endsWith(".gpx", ignoreCase = true)) base else "$base.gpx"
    }

    /** The name shown in the manager: the file name without the extension. */
    fun displayName(fileName: String): String = fileName.removeSuffix(".gpx").removeSuffix(".GPX")

    /**
     * Whether a name is still the one the app made. It decides whether the rename field opens
     * empty or opens with the name in it (15.9.2026): nobody wants to delete a date before they
     * can type, and nobody wants to retype a name they already chose.
     */
    fun isDefaultName(name: String): Boolean {
        val trimmed = name.trim()
        // Anything this app named begins with the date it was walked. Nobody types a name that
        // starts with a date, so this also catches the double-stamped names the old builder left
        // on the phone — and those are exactly the ones that were unbearable to edit.
        return Regex("^\\d{4}-\\d{2}-\\d{2}").containsMatchIn(trimmed)
    }

    data class TrackFile(val file: File, val name: String, val bytes: Long, val modifiedMs: Long)

    /** Everything in the folder that is a track, newest first. */
    fun list(dir: File): List<TrackFile> {
        val files = dir.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.name.endsWith(".gpx", ignoreCase = true) }
            .map { TrackFile(it, displayName(it.name), it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedMs }
    }

    /**
     * Rename one. Returns the new file, or null with the reason. It refuses to write over another
     * track: losing a walk to a name collision is not a risk worth taking for the convenience of
     * not being asked.
     */
    fun rename(file: File, newName: String): Pair<File?, String?> {
        if (!file.exists()) return null to "That track is no longer there"
        val target = File(file.parentFile, safeFileName(newName))
        if (target.absolutePath == file.absolutePath) return file to null
        if (target.exists()) return null to "A track called ${displayName(target.name)} already exists"
        return if (file.renameTo(target)) target to null else null to "The file could not be renamed"
    }

    fun delete(file: File): String? = when {
        !file.exists() -> null
        file.delete() -> null
        else -> "The file could not be deleted"
    }

    /** A size somebody can judge. A GPX of a day's walk is a few hundred kilobytes. */
    fun formatSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} kB"
        else -> "$bytes B"
    }
}
