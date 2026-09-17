package com.mantra.trail

/**
 * THE KEYRING: SEVERAL KEYS, NOT ONE. No Android imports (android-app.md 1).
 *
 * Baba, 17.9.2026, pointing at his own KEY_RING_TESTER: a list of keys he can add to, test one by
 * one, see the result of beside each, delete, and have the app fall back through when one stops
 * working. One key in a preference could not do any of that, and when it failed — as his did for
 * half a day — there was nothing to compare it against.
 *
 * A key is kept whole because it has to be used, and shown masked because it has to be read over
 * somebody's shoulder. What is remembered about each is what it answered the last time it was
 * asked, which is the only thing that decides whether it is worth trying next.
 */
object Keyring {

    /** What happened when a key was last tried. */
    enum class Verdict { UNTRIED, GOOD, REFUSED, UNREACHABLE }

    data class Key(
        val value: String,
        val label: String,
        val verdict: Verdict = Verdict.UNTRIED,
        val said: String = "",
        val testedMs: Long = 0L,
    ) {
        /** Enough to tell two keys apart, never enough to use one. */
        val masked: String
            get() = if (value.length > 12) {
                value.take(8) + "…" + value.takeLast(4)
            } else {
                "…"
            }
    }

    /** One line per key, so the whole ring is one preference: value, label, verdict, message. */
    fun encode(keys: List<Key>): String = keys.joinToString("\n") {
        listOf(
            it.value,
            it.label.replace('\t', ' '),
            it.verdict.name,
            it.said.replace('\t', ' ').replace('\n', ' '),
            it.testedMs.toString(),
        ).joinToString("\t")
    }

    /**
     * And back. A line that has been corrupted is skipped rather than throwing: a broken
     * preference must never be able to stop the app opening.
     */
    fun decode(text: String?): List<Key> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lines().mapNotNull { line ->
            val bits = line.split('\t')
            if (bits.size < 3) return@mapNotNull null
            val value = bits[0].trim()
            if (!Keys.looksLikeGoogle(value)) return@mapNotNull null
            Key(
                value = value,
                label = bits[1].ifBlank { "key" },
                verdict = runCatching { Verdict.valueOf(bits[2]) }.getOrDefault(Verdict.UNTRIED),
                said = bits.getOrNull(3).orEmpty(),
                testedMs = bits.getOrNull(4)?.toLongOrNull() ?: 0L,
            )
        }
    }

    /** Add keys, keeping what is known about the ones already here and refusing duplicates. */
    fun add(existing: List<Key>, found: List<String>): List<Key> {
        val known = existing.map { it.value }.toSet()
        val fresh = found.filter { Keys.looksLikeGoogle(it) && it !in known }.distinct()
        return existing + fresh.mapIndexed { i, value ->
            Key(value = value, label = "key ${existing.size + i + 1}")
        }
    }

    fun remove(existing: List<Key>, value: String): List<Key> = existing.filterNot { it.value == value }

    fun withVerdict(existing: List<Key>, value: String, verdict: Verdict, said: String, whenMs: Long): List<Key> =
        existing.map {
            if (it.value == value) it.copy(verdict = verdict, said = said, testedMs = whenMs) else it
        }

    /**
     * THE ORDER TO TRY THEM IN. One that worked comes first, then ones nobody has tried, then ones
     * that could not be reached, and last the ones that were refused — a key Google turned down is
     * not worth a walk's worth of retries, but it is worth one when everything else has failed.
     */
    fun order(keys: List<Key>): List<Key> = keys.sortedBy {
        when (it.verdict) {
            Verdict.GOOD -> 0
            Verdict.UNTRIED -> 1
            Verdict.UNREACHABLE -> 2
            Verdict.REFUSED -> 3
        }
    }

    /** The one to use now, or null when the ring is empty. */
    fun best(keys: List<Key>): Key? = order(keys).firstOrNull()

    /** What a row says under a key's name. */
    fun describe(key: Key): String = when (key.verdict) {
        Verdict.UNTRIED -> "${key.masked} · not tested"
        Verdict.GOOD -> "${key.masked} · works"
        Verdict.REFUSED -> "${key.masked} · refused"
        Verdict.UNREACHABLE -> "${key.masked} · could not be reached"
    }
}
