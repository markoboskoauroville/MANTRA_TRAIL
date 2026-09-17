package com.mantra.trail

/**
 * OPENANDROMAPS, AND WHAT IT COSTS TO HAVE ONE. No Android imports (android-app.md 1).
 *
 * Baba, 16.9.2026: the high-end hiking map. It is the same mapsforge file format the engine
 * already reads, with contour lines and waymarked routes built into the data rather than painted
 * on top — which is why it is worth more than a plain extract, and why it is bigger.
 *
 * IT ARRIVES AS A ZIP. Measured against the mirror on 16.9.2026: Balkan.zip is 1.19 GB and holds
 * the .map, a .poi and a text file. The .poi is not ours to want, so only the map comes out, and
 * the zip is deleted after. That is 2.6 GB of room needed at the peak, which is a thing to say
 * before starting and not after.
 */
object Oam {

    /**
     * THE TWO HE ASKED FOR BY NAME (17.9.2026): the Balkan file that covers Croatia and its
     * neighbours, and the Croatia-only one mirrored on his own GitHub. They are offered in the
     * offline maps dropdown whether or not they are on the phone, so switching between them is
     * one press when they are and two when they are not.
     */
    val OFFERED: List<OamIndex.Entry> = listOf(
        OamIndex.Entry("Balkan.zip", 1_194_219_384L, "europe"),
        OamIndex.ELSEWHERE.first(),
    )

    const val MIRROR = "https://ftp.gwdg.de/pub/misc/openstreetmap/openandromaps"

    data class Region(val name: String, val label: String, val path: String, val zipBytes: Long) {
        val url: String get() = "$MIRROR/mapsV5/$path"

        /** What the extracted map is called on the phone. */
        val fileName: String get() = "oam-$name.map"
    }

    /** The regions this app offers. Sizes are of the zip, measured against the mirror. */
    val ALL: List<Region> = listOf(
        Region("balkan", "Balkan (Croatia and its neighbours)", "europe/Balkan.zip", 1_194_219_384L),
        Region("slovenia", "Slovenia", "europe/Slovenia.zip", 0L),
        Region("austria", "Austria", "europe/Austria.zip", 0L),
        Region("italy", "Italy", "europe/Italy.zip", 0L),
    )

    fun byName(name: String): Region? = ALL.firstOrNull { it.name == name }

    /** The size of the download, and of the room it needs on the way. */
    fun sizeLabel(region: Region): String = when {
        region.zipBytes <= 0L -> "size unknown until it starts"
        region.zipBytes >= 1_000_000_000L ->
            "${region.zipBytes / 100_000_000 / 10.0} GB, and about twice that free while it unpacks"
        else -> "${region.zipBytes / 1_000_000} MB, and about twice that free while it unpacks"
    }

    /**
     * Whether an entry inside the zip is the map. The archive also carries a .poi database of
     * places, which this app has nothing to do with, and a readme.
     */
    fun isTheMap(entryName: String): Boolean =
        entryName.endsWith(".map", ignoreCase = true) && !entryName.contains("__MACOSX")
}
