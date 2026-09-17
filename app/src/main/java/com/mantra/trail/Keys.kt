package com.mantra.trail

/**
 * THE KEYS COME FROM A FILE ON THE PHONE AND FROM NOWHERE ELSE.
 *
 * Baba, 14.9.2026, after a Maps key went out inside a public APK: *"This is public app. My key
 * cannot be inside. Please remove the key. Only work with key picker. Key picker is the key."*
 *
 * So no key is compiled into this app, no key is in the repository, and no build has one. A key
 * arrives when he picks a file, is read BY SHAPE, is kept in the app's own private storage, and
 * is shown to nobody — not even to him, because a key on a screen is a key in a screenshot.
 *
 * WHICH SERVICE A KEY BELONGS TO IS DECIDED BY ITS SHAPE, not by asking him to sort them:
 *
 *   Google         AIza… or AQ.… — Google's own key formats
 *   Thunderforest  thirty-two hexadecimal characters, which is the shape their keys take
 *
 * No Android imports (android-app.md 1), so Test 1 attacks the parser with the awkward files a
 * real note contains: account names above the key, tracking URLs below it, blank lines, prose.
 */
object Keys {

    /**
     * ONE PROVIDER (16.9.2026). Thunderforest's maps left this app and its key left with them: a
     * list that names a service the app cannot draw is a list that lies.
     */
    enum class Provider {
        /** Google Map Tiles: roadmap, satellite, terrain and hybrid, online only. */
        GOOGLE,

        /** Thunderforest Outdoors: contours and marked trails, and it may be cached. */
    }

    private val GOOGLE = Regex("AIza[A-Za-z0-9_\\-]{30,}")
    private val GOOGLE_AQ = Regex("AQ\\.[A-Za-z0-9_\\-.]{20,}")
    private val HEX32 = Regex("\\b[0-9a-f]{32}\\b")

    data class Found(val key: String, val provider: Provider, val label: String?)

    /** The provider a key belongs to, or null when the shape is one nobody here knows. */
    /** Whether a string has the shape Google gives its keys: AIza and thirty-five more. */
    fun looksLikeGoogle(candidate: String): Boolean =
        candidate.length == 39 && candidate.startsWith("AIza") &&
            candidate.drop(4).all { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun providerOf(candidate: String): Provider? = when {
        GOOGLE.matches(candidate) || GOOGLE_AQ.matches(candidate) -> Provider.GOOGLE
        else -> null
    }

    /**
     * Every key-shaped string in the text, with the provider its shape names and the first line
     * of its block that is not itself a key. The same key twice is one key.
     */
    fun parse(text: String): List<Found> {
        val found = LinkedHashMap<String, Found>()
        for (block in text.split(Regex("\\n\\s*\\n"))) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val hits = ArrayList<Pair<String, Provider>>()
            for (line in lines) {
                // "key: AIza…" and "AIza…" are the same line written two ways.
                val candidate = line.substringAfter(':', line).trim()
                val whole = sequenceOf(GOOGLE, GOOGLE_AQ, HEX32)
                    .mapNotNull { it.find(candidate)?.value }
                    .firstOrNull() ?: continue
                providerOf(whole)?.let { hits.add(whole to it) }
            }
            val label = lines.firstOrNull { line ->
                providerOf(line.substringAfter(':', line).trim()) == null &&
                    !line.startsWith("#") && !line.contains("://")
            }?.let { it.substringAfter(':', it).trim().ifEmpty { null } }
            hits.forEach { (k, p) -> found.putIfAbsent(k, Found(k, p, label)) }
        }
        return found.values.toList()
    }

    /**
     * What may be said about a key, ever: its position, its provider and its length. Not its
     * first characters — on Google keys the first four are the same on every one, so a mask that
     * shows them identifies nothing and leaks something (keyring.md 10d).
     */
    fun describe(index: Int, total: Int, found: Found): String =
        "key ${index + 1} of $total, ${found.provider.name.lowercase()}, ${found.key.length} characters"
}
