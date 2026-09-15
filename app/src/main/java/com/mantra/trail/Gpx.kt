package com.mantra.trail

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * GPX 1.1, AND IT IS WRITTEN AS THE WALK HAPPENS.
 *
 * WHY GPX. It is the one track format everything reads: Garmin, Strava, Komoot, OsmAnd, Locus,
 * QGIS, Google Earth. It is plain XML with a published schema, so a file recorded here can still
 * be opened in twenty years by something nobody has written yet. FIT is Garmin's binary, TCX is
 * its retired predecessor, KML is Google's and carries no track semantics worth the name.
 *
 * WHY POINT BY POINT. A file assembled at the end is a file that does not exist when the battery
 * dies on the ridge. The header is written when recording starts, every accepted fix is appended
 * and flushed, and the footer is the only thing left to lose — so a truncated file costs the
 * closing tags, which any repair adds back, rather than the walk.
 *
 * No Android imports (android-app.md 1): this builds strings, the caller owns the stream.
 */
object Gpx {

    private val STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    /** UTC, always, whatever the phone's clock is set to. A track is read in other time zones. */
    fun isoUtc(millis: Long): String = STAMP.format(Instant.ofEpochMilli(millis))

    /**
     * The five characters XML cannot carry raw. A track name comes from a text field, so this is
     * the boundary between what somebody types and what a parser has to survive.
     */
    fun escape(text: String): String {
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&apos;")
                else -> if (c.code in 0x20..0xD7FF || c == '\n' || c == '\t' || c.code >= 0xE000) {
                    out.append(c)
                } // control characters are dropped: they make a file no parser will open
            }
        }
        return out.toString()
    }

    /** Six decimals is about 11 cm at this latitude, and more digits are a claim nothing supports. */
    private fun coord(v: Double): String = String.format(Locale.US, "%.6f", v)

    private fun metres(v: Double): String = String.format(Locale.US, "%.1f", v)

    fun header(trackName: String, createdMs: Long): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Mantra Trail\"\n")
        append("     xmlns=\"http://www.topografix.com/GPX/1/1\"\n")
        append("     xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        append("     xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
        append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        append("  <metadata>\n")
        append("    <name>").append(escape(trackName)).append("</name>\n")
        append("    <time>").append(isoUtc(createdMs)).append("</time>\n")
        append("  </metadata>\n")
        append("  <trk>\n")
        append("    <name>").append(escape(trackName)).append("</name>\n")
        append("    <trkseg>\n")
    }

    /**
     * One point. The accuracy is carried too, in the only field GPX has for it that every reader
     * ignores politely: hdop. It is not a real dilution of precision and it is not pretended to
     * be one — it is the metres the phone claimed, so a track can be judged later rather than
     * only looked at.
     */
    fun point(fix: Fix): String = buildString {
        append("      <trkpt lat=\"").append(coord(fix.lat))
        append("\" lon=\"").append(coord(fix.lon)).append("\">\n")
        fix.ele?.let { append("        <ele>").append(metres(it)).append("</ele>\n") }
        append("        <time>").append(isoUtc(fix.timeMs)).append("</time>\n")
        fix.satellites?.let { append("        <sat>").append(it).append("</sat>\n") }
        fix.accuracyM?.let { append("        <hdop>").append(metres(it.toDouble())).append("</hdop>\n") }
        append("      </trkpt>\n")
    }

    /** A new segment after a pause, so a break in the walk is a break in the line. */
    fun segmentBreak(): String = "    </trkseg>\n    <trkseg>\n"

    fun footer(): String = "    </trkseg>\n  </trk>\n</gpx>\n"

    /**
     * A whole file in one string, for a track that is already finished — the export of something
     * read back, and the thing Test 1 checks against the point-by-point writer to prove the two
     * routes produce the same file.
     */
    fun whole(trackName: String, points: List<Fix>, createdMs: Long): String = buildString {
        append(header(trackName, createdMs))
        points.forEach { append(point(it)) }
        append(footer())
    }
}
