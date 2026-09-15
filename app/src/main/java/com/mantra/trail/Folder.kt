package com.mantra.trail

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * THE CHOSEN FOLDER, AS A FILE MANAGER FOR ONE KIND OF FILE.
 *
 * Baba, 15.9.2026: the tracks menu is the folder he picked, showing only GPX, with rename and
 * delete and the folder's own name written at the top so he knows where they are. Export is gone
 * with this, because a track that is already in the folder he chose has nowhere left to go.
 *
 * DocumentFile is slow — every call crosses into another app — so the listing is fetched once and
 * held, never inside a composition (the mistake that made the colour row crawl on 15.9.2026).
 */
object Folder {

    data class Entry(val uri: Uri, val fileName: String, val bytes: Long, val modifiedMs: Long) {
        /** What the list shows: the name he gave it, with the extension after it. */
        val name: String get() = Tracks.displayName(fileName)
        val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
    }

    fun tree(context: Context, store: Store): DocumentFile? {
        val uri = store.exportTreeUri ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))
    }

    /** Every GPX in the folder, newest first. Anything else in there is not our business. */
    fun list(context: Context, store: Store): List<Entry> {
        val tree = tree(context, store) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile && (it.name ?: "").endsWith(".gpx", ignoreCase = true) }
            .map { Entry(it.uri, it.name ?: "track.gpx", it.length(), it.lastModified()) }
            .sortedByDescending { it.modifiedMs }
    }

    /**
     * Rename one, keeping its extension: he types a name, not a file name. Returns null when it
     * worked, or the reason.
     *
     * THE BUG THIS FIXES, 15.9.2026: it used to go through DocumentFile.fromSingleUri, and what
     * that returns is a SingleDocumentFile, which does not implement renameTo at all. Pressing
     * rename did nothing and said nothing. DocumentsContract.renameDocument is the call that
     * works on a document taken out of a tree, and it answers with the new address.
     */
    fun rename(context: Context, entry: Entry, newName: String): String? {
        val wanted = Tracks.safeFileName(newName)
        if (wanted.equals(entry.fileName, ignoreCase = true)) return null
        return try {
            val moved = DocumentsContract.renameDocument(context.contentResolver, entry.uri, wanted)
            if (moved != null) null else "The folder would not rename it"
        } catch (e: UnsupportedOperationException) {
            "This folder does not allow renaming. A folder on the phone's own storage does."
        } catch (e: IllegalStateException) {
            // The provider throws this when the name is taken, which is worth saying plainly.
            "There is already a track called $newName"
        } catch (e: Exception) {
            "Renaming failed: ${e.javaClass.simpleName}"
        }
    }

    fun delete(context: Context, entry: Entry): String? = try {
        // The same lesson as rename: go to the provider directly rather than through a wrapper
        // that may not implement what is being asked of it.
        if (DocumentsContract.deleteDocument(context.contentResolver, entry.uri)) {
            null
        } else {
            "The folder would not delete it"
        }
    } catch (e: Exception) {
        "Deleting failed: ${e.javaClass.simpleName}"
    }

    fun read(context: Context, entry: Entry): String? = try {
        context.contentResolver.openInputStream(entry.uri)?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) {
        null
    }

    /**
     * Put a finished recording in the folder under this name. Returns null when it worked.
     * A name already taken is replaced, because it is the same walk being saved again.
     */
    fun save(context: Context, store: Store, source: java.io.File, name: String): String? {
        val tree = tree(context, store) ?: return "No folder chosen yet"
        val fileName = Tracks.safeFileName(name)
        return try {
            tree.findFile(fileName)?.delete()
            // Some providers refuse a mime type they have never met, so the wider ones follow.
            val target = tree.createFile("application/gpx+xml", fileName)
                ?: tree.createFile("text/xml", fileName)
                ?: tree.createFile("application/octet-stream", fileName)
                ?: return "The folder would not accept the file"
            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: return "The file could not be written"
            null
        } catch (e: Exception) {
            "Saving failed: ${e.javaClass.simpleName}"
        }
    }

    /** The folder as somebody would say it: the last part of its path, or its own name. */
    fun label(context: Context, store: Store): String {
        val uri = store.exportTreeUri ?: return "no folder chosen yet"
        val fromName = store.exportFolderName
        if (!fromName.isNullOrBlank()) return fromName
        return Uri.parse(uri).lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "chosen folder"
    }
}
