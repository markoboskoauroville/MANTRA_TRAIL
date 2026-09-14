package com.mantra.trail

/**
 * KEYS ARRIVE AS A FILE AND ARE READ BY SHAPE, NEVER BY EYE (secrets.md 2, keyring.md 10d).
 *
 * No Android imports (android-app.md 1), so the parser is attacked by Test 1 with the awkward
 * files a real note contains: account names above the key, a URL with tracking parameters under
 * it, blank lines, and the word somebody wrote to remind themselves which account it was.
 *
 * SPLITTING ON WHITESPACE HAS GENUINELY PRODUCED AN ATTEMPT TO AUTHENTICATE WITH THE WORD
 * "cafeteria". The shape is what identifies a key; the position in the file identifies nothing.
 *
 * WHAT A KEY IMPORTED HERE CAN AND CANNOT DO, said once and plainly:
 * Google's own map reads its key from the app's manifest when the app is installed, so a key put
 * in here cannot switch Google on — that one is built in from a repository secret. A key here is
 * for the tile services that take a key in the URL, and for the day this app talks to one.
 */
object Keys {

    /** Google-style keys, the shape the Maps and tile APIs issue. */
    private val GOOGLE = Regex("AIza[A-Za-z0-9_\\-]{30,}")

    /** The newer Google format, which the AIza filter silently misses (secrets.md 2a). */
    private val GOOGLE_AQ = Regex("AQ\\.[A-Za-z0-9_\\-.]{20,}")

    /** A key with the name of the account it belongs to, when the file gave one. */
    data class Found(val key: String, val label: String?)

    /**
     * Every key-shaped string in the text, each with the first line of its block that is not
     * itself a key. Duplicates are collapsed: one key pasted twice is one key.
     */
    fun parse(text: String): List<Found> {
        val found = LinkedHashMap<String, String?>()
        for (block in text.split(Regex("\\n\\s*\\n"))) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val keys = ArrayList<String>()
            for (line in lines) {
                val candidate = line.substringAfter(':', line).trim()
                val hit = GOOGLE.find(candidate)?.value ?: GOOGLE_AQ.find(candidate)?.value
                if (hit != null) keys.add(hit)
            }
            val label = lines.firstOrNull { line ->
                GOOGLE.find(line) == null && GOOGLE_AQ.find(line) == null &&
                    !line.startsWith("#") && !line.contains("://")
            }?.let { line ->
                // "label: kalabhumi" and "kalabhumi" are the same account written two ways.
                val after = line.substringAfter(':', line).trim()
                after.ifEmpty { null }
            }
            keys.forEach { k -> found.putIfAbsent(k, label) }
        }
        return found.map { (k, v) -> Found(k, v) }
    }

    /**
     * What may be shown about a key, ever: its position and its length. Not its first six
     * characters — on Google keys the first four are identical on every one, so a mask that
     * shows them identifies nothing and leaks something (keyring.md 10d).
     */
    fun describe(index: Int, total: Int, key: String): String =
        "key ${index + 1} of $total, ${key.length} characters"
}
