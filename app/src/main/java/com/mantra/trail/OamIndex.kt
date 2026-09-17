package com.mantra.trail

/**
 * READING OPENANDROMAPS' OWN INDEX, so every region in the world is offered rather than the four
 * somebody typed into the app. No Android imports (android-app.md 1).
 *
 * The mirror publishes a plain directory listing per continent, and each row carries the file's
 * name, its date and its size in bytes. Parsing it means the list is never out of date and the
 * size is never a guess — measured against the real page on 16.9.2026, where Balkan.zip is
 * 1,194,219,384 bytes.
 *
 * A page that cannot be parsed yields no regions rather than nonsense: better an empty list and a
 * sentence than a row that downloads something nobody asked for.
 */
object OamIndex {

    const val MIRROR = "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps/mapsV5"

    /** The continents the mirror keeps, in the order they are offered. */
    val CONTINENTS = listOf(
        "europe" to "Europe",
        "asia" to "Asia",
        "africa" to "Africa",
        "canada" to "Canada",
        "china" to "China",
        "germany" to "Germany",
        "oceania" to "Australia and Oceania",
        "russia" to "Russia",
        "southamerica" to "South America",
        "eurovelo" to "EuroVelo routes",
    )

    data class Entry(val fileName: String, val bytes: Long, val continent: String) {
        /** Balkan.zip becomes Balkan; BalticStates.zip stays as it is written. */
        val label: String get() = fileName.removeSuffix(".zip")

        /** What the unpacked map is called on the phone. */
        val mapName: String get() = "oam-${label.lowercase()}.map"

        val url: String get() = "$MIRROR/$continent/$fileName"

        val sizeLabel: String
            get() = when {
                bytes >= 1_000_000_000L -> "${bytes / 100_000_000 / 10.0} GB"
                bytes > 0 -> "${bytes / 1_000_000} MB"
                else -> "unknown"
            }

        /** What it needs free while it unpacks: the zip, and the map that comes out of it. */
        val roomLabel: String get() = "needs about ${(bytes * 23 / 10_000_000_000.0).let { "%.1f".format(it) }} GB free"
    }

    fun urlFor(continent: String): String = "$MIRROR/$continent/"

    /**
     * MAPS FROM SOMEWHERE ELSE, for when a whole continent is more than somebody wants.
     *
     * Baba, 17.9.2026, having fetched the 1.2 GB Balkan file: can the app cut Croatia out of it?
     * It cannot, and neither can anything else — a .map file is compiled, with its tile index and
     * zoom intervals built in, and cropping one means running the map writer again over the
     * original OpenStreetMap extract. That is a desk job of hours and gigabytes, not a phone one.
     *
     * What CAN be done is fetch a smaller map instead, so these are offered beside the big ones.
     * Sizes measured against the servers on 17.9.2026.
     */
    val ELSEWHERE: List<Entry> = listOf(
        Entry(
            fileName = "openhiking-croatia.zip",
            bytes = 294_214_142L,
            continent = "openhiking",
        ),
    )

    /** Where a map from one of those other places is fetched from. */
    fun elsewhereUrl(entry: Entry): String = when (entry.fileName) {
        "openhiking-croatia.zip" ->
            "https://www.openhiking.eu/en/component/phocadownload/category/" +
                "1-terkepek/5-turistaterkepek-mapsforge?download=21:openhiking-croatia"

        else -> entry.url
    }

    /**
     * Every .zip in a directory listing, with its size. Anything that is not a row with a size on
     * it is skipped, so a page that changes shape gives fewer regions rather than wrong ones.
     */
    fun parse(html: String, continent: String): List<Entry> {
        val row = Regex("""href="([^"]+\.zip)"[^>]*>[^<]*</a>\s+[^\s]+\s+[^\s]+\s+(\d+)""")
        return row.findAll(html).mapNotNull { match ->
            val name = match.groupValues[1]
            val bytes = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            if (name.contains("/")) return@mapNotNull null
            Entry(name, bytes, continent)
        }.toList().sortedBy { it.label.lowercase() }
    }
}
