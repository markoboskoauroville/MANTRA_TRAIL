package com.mantra.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEST 1 — THE MECHANISM, ALONE (four-tests.md).
 *
 * No Android, no map, no network, no phone. Every case here can fail while the other three tests
 * pass, and each one was written by asking the only question that matters: what could be true
 * that would make this pass and the feature still be broken?
 *
 * The distances are checked against figures computed independently rather than against what this
 * code happens to return.
 */
class CoreTest {

    // Two corners of Croatia with a known separation, and two points on Ugljan.
    private val zagrebLat = 45.8150
    private val zagrebLon = 15.9819
    private val splitLat = 43.5081
    private val splitLon = 16.4402

    // --- Geo: distance and bearing ---------------------------------------------------------

    @Test fun distanceToItselfIsZero() {
        assertEquals(0.0, Geo.distance(zagrebLat, zagrebLon, zagrebLat, zagrebLon), 1e-9)
    }

    @Test fun zagrebToSplitIsAboutTwoHundredAndFiftyKilometres() {
        val d = Geo.distance(zagrebLat, zagrebLon, splitLat, splitLon)
        assertTrue("got $d", d > 255_000 && d < 262_000)
    }

    @Test fun oneDegreeOfLatitudeIsAboutOneHundredAndElevenKilometres() {
        val d = Geo.distance(45.0, 16.0, 46.0, 16.0)
        assertEquals(111_195.0, d, 300.0)
    }

    @Test fun oneDegreeOfLongitudeShrinksWithLatitude() {
        val atEquator = Geo.distance(0.0, 0.0, 0.0, 1.0)
        val atZagreb = Geo.distance(45.815, 15.0, 45.815, 16.0)
        assertTrue(atZagreb < atEquator * 0.75)
    }

    @Test fun distanceIsSymmetric() {
        val a = Geo.distance(zagrebLat, zagrebLon, splitLat, splitLon)
        val b = Geo.distance(splitLat, splitLon, zagrebLat, zagrebLon)
        assertEquals(a, b, 1e-6)
    }

    @Test fun aMetreApartReadsAsAboutAMetre() {
        val d = Geo.distance(44.0, 15.0, 44.000009, 15.0)
        assertEquals(1.0, d, 0.1)
    }

    @Test fun bearingDueNorthIsZero() {
        assertEquals(0.0, Geo.bearing(44.0, 15.0, 45.0, 15.0), 0.01)
    }

    @Test fun bearingDueEastIsNinety() {
        assertEquals(90.0, Geo.bearing(0.0, 15.0, 0.0, 16.0), 0.01)
    }

    @Test fun bearingDueSouthIsOneEighty() {
        assertEquals(180.0, Geo.bearing(45.0, 15.0, 44.0, 15.0), 0.01)
    }

    @Test fun bearingIsNeverNegative() {
        val b = Geo.bearing(45.0, 15.0, 44.9, 14.9)
        assertTrue("got $b", b in 0.0..360.0)
    }

    @Test fun normaliseBringsNegativesRound() {
        assertEquals(350.0, Geo.normaliseDeg(-10.0), 1e-9)
        assertEquals(10.0, Geo.normaliseDeg(370.0), 1e-9)
        assertEquals(0.0, Geo.normaliseDeg(360.0), 1e-9)
    }

    @Test fun deltaTakesTheShortWayRound() {
        assertEquals(2.0, Geo.deltaDeg(359.0, 1.0), 1e-9)
        assertEquals(-2.0, Geo.deltaDeg(1.0, 359.0), 1e-9)
        assertEquals(180.0, Geo.deltaDeg(0.0, 180.0), 1e-9)
    }

    @Test fun cardinalNamesTheEightObviousOnes() {
        assertEquals("N", Geo.cardinal(0.0))
        assertEquals("NE", Geo.cardinal(45.0))
        assertEquals("E", Geo.cardinal(90.0))
        assertEquals("SE", Geo.cardinal(135.0))
        assertEquals("S", Geo.cardinal(180.0))
        assertEquals("SW", Geo.cardinal(225.0))
        assertEquals("W", Geo.cardinal(270.0))
        assertEquals("NW", Geo.cardinal(315.0))
    }

    @Test fun cardinalWrapsBackToNorth() {
        assertEquals("N", Geo.cardinal(359.9))
        assertEquals("N", Geo.cardinal(360.0))
        assertEquals("NNW", Geo.cardinal(348.0))
    }

    // --- Geo: the tile arithmetic the raster layers depend on ---------------------------------

    @Test fun zoomZeroIsOneTile() {
        assertEquals(0, Geo.tileX(15.0, 0))
        assertEquals(0, Geo.tileY(45.0, 0))
    }

    @Test fun theOriginTileIsTopLeft() {
        assertEquals(0, Geo.tileX(-180.0, 4))
        assertEquals(0, Geo.tileY(85.0, 4))
    }

    @Test fun tileIndicesStayInsideTheGrid() {
        for (z in 0..18) {
            val n = 1 shl z
            assertTrue(Geo.tileX(180.0, z) < n)
            assertTrue(Geo.tileY(-89.0, z) < n)
            assertTrue(Geo.tileX(-180.0, z) >= 0)
            assertTrue(Geo.tileY(89.0, z) >= 0)
        }
    }

    @Test fun zagrebLandsOnTheKnownTileAtZoomTwelve() {
        // Computed from the standard slippy-map formula independently of this code.
        assertEquals(2229, Geo.tileX(zagrebLon, 12))
        assertEquals(1460, Geo.tileY(zagrebLat, 12))
    }

    @Test fun theWholeWorldBoxIsTheMercatorSquare() {
        val b = Geo.tileBbox3857(0, 0, 0)
        assertEquals(-Geo.MERC_MAX, b[0], 1.0)
        assertEquals(-Geo.MERC_MAX, b[1], 1.0)
        assertEquals(Geo.MERC_MAX, b[2], 1.0)
        assertEquals(Geo.MERC_MAX, b[3], 1.0)
    }

    @Test fun aTileBoxIsSquareAndHalvesEachZoom() {
        val z8 = Geo.tileBbox3857(8, 137, 90)
        val z9 = Geo.tileBbox3857(9, 274, 180)
        assertEquals(z8[2] - z8[0], z8[3] - z8[1], 1e-6)
        assertEquals((z8[2] - z8[0]) / 2.0, z9[2] - z9[0], 1e-6)
    }

    @Test fun theTopRowIsNorthOfTheBottomRow() {
        val top = Geo.tileBbox3857(4, 8, 0)
        val bottom = Geo.tileBbox3857(4, 8, 15)
        assertTrue(top[1] > bottom[3])
    }

    @Test fun neighbouringTilesShareAnEdge() {
        val left = Geo.tileBbox3857(10, 550, 360)
        val right = Geo.tileBbox3857(10, 551, 360)
        assertEquals(left[2], right[0], 1e-6)
    }

    // --- Geo: what the screen shows -----------------------------------------------------------

    @Test fun coordinatesAreDegreesAndDecimalMinutes() {
        assertEquals("N 45 48.900", Geo.formatLat(45.815))
        assertEquals("E 15 58.914", Geo.formatLon(15.9819))
    }

    @Test fun theSouthernAndWesternHemispheresAreNamed() {
        assertTrue(Geo.formatLat(-33.9).startsWith("S"))
        assertTrue(Geo.formatLon(-70.6).startsWith("W"))
    }

    @Test fun sixtyMinutesBecomesTheNextDegree() {
        // 44.9999999 is 44 59.99999 minutes, which must not print as 44 60.000
        val s = Geo.formatLat(44.9999999)
        assertTrue("got $s", s == "N 45 00.000")
    }

    @Test fun minutesAreAlwaysTwoDigitsAndThreeDecimals() {
        val s = Geo.formatLat(45.01)
        assertEquals("N 45 00.600", s)
    }

    @Test fun distanceIsMetresThenKilometres() {
        assertEquals("0 m", Geo.formatDistance(0.0))
        assertEquals("999 m", Geo.formatDistance(999.4))
        assertEquals("1.0 km", Geo.formatDistance(1000.0))
        assertEquals("12.35 km", Geo.formatDistance(12_345.0))
    }

    @Test fun durationIsMinutesThenHours() {
        assertEquals("00:00", Geo.formatDuration(0))
        assertEquals("01:05", Geo.formatDuration(65_000))
        assertEquals("1:00:00", Geo.formatDuration(3_600_000))
        assertEquals("9:32:07", Geo.formatDuration(34_327_000))
    }

    // --- The rules that decide whether a fix joins the track ----------------------------------

    private fun fix(lat: Double, lon: Double, t: Long, acc: Float? = 5f, ele: Double? = 100.0) =
        Fix(lat, lon, ele, t, acc)

    @Test fun theFirstGoodFixIsAccepted() {
        assertNull(TrackRules.reject(null, fix(44.0, 15.0, 1000)))
    }

    @Test fun aFixWithNoAccuracyIsRefused() {
        assertEquals(Reject.ACCURACY, TrackRules.reject(null, fix(44.0, 15.0, 1000, null)))
    }

    @Test fun aHopelessFixIsRefused() {
        assertEquals(Reject.ACCURACY, TrackRules.reject(null, fix(44.0, 15.0, 1000, 120f)))
    }

    @Test fun accuracyExactlyAtTheLimitIsAccepted() {
        assertNull(TrackRules.reject(null, fix(44.0, 15.0, 1000, 50f)))
    }

    @Test fun accuracyJustPastTheLimitIsRefused() {
        assertEquals(Reject.ACCURACY, TrackRules.reject(null, fix(44.0, 15.0, 1000, 50.1f)))
    }

    @Test fun aZeroAccuracyIsNotBelieved() {
        assertEquals(Reject.ACCURACY, TrackRules.reject(null, fix(44.0, 15.0, 1000, 0f)))
    }

    @Test fun standingStillAddsNothing() {
        val a = fix(44.0, 15.0, 1000)
        val b = fix(44.000005, 15.0, 3000)
        assertEquals(Reject.DUPLICATE, TrackRules.reject(a, b))
    }

    @Test fun aWalkingPaceIsAccepted() {
        val a = fix(44.0, 15.0, 1000)
        val b = fix(44.00005, 15.0, 4000) // about 5.5 m in 3 s
        assertNull(TrackRules.reject(a, b))
    }

    @Test fun aTeleportIsRefused() {
        val a = fix(44.0, 15.0, 1000)
        val b = fix(44.01, 15.0, 2000) // a kilometre in a second
        assertEquals(Reject.JUMP, TrackRules.reject(a, b))
    }

    @Test fun aClockGoingBackwardsIsRefused() {
        val a = fix(44.0, 15.0, 5000)
        val b = fix(44.0005, 15.0, 4000)
        assertEquals(Reject.BACKWARDS, TrackRules.reject(a, b))
    }

    @Test fun twoFixesWithTheSameStampAndDifferentPlacesAreAJump() {
        val a = fix(44.0, 15.0, 5000)
        val b = fix(44.01, 15.0, 5000)
        assertEquals(Reject.JUMP, TrackRules.reject(a, b))
    }

    @Test fun accuracyIsJudgedBeforeTheJump() {
        // A hopeless fix that is also a jump must be reported as the hopeless fix: a jump computed
        // from a fix nobody believes is not evidence of anything.
        val a = fix(44.0, 15.0, 1000)
        val b = fix(44.5, 15.0, 1100, 400f)
        assertEquals(Reject.ACCURACY, TrackRules.reject(a, b))
    }

    // --- What the track then measures ---------------------------------------------------------

    @Test fun anEmptyTrackMeasuresNothing() {
        val s = TrackMath.stats(emptyList())
        assertEquals(0, s.points)
        assertEquals(0.0, s.distanceM, 1e-9)
    }

    @Test fun oneFixHasNoDistanceAndNoDuration() {
        val s = TrackMath.stats(listOf(fix(44.0, 15.0, 1000)))
        assertEquals(1, s.points)
        assertEquals(0.0, s.distanceM, 1e-9)
        assertEquals(0L, s.durationMs)
    }

    @Test fun distanceIsTheSumOfTheLegs() {
        val pts = listOf(
            fix(44.0, 15.0, 0),
            fix(44.001, 15.0, 60_000),
            fix(44.002, 15.0, 120_000),
        )
        val expected = Geo.distance(44.0, 15.0, 44.001, 15.0) * 2
        assertEquals(expected, TrackMath.stats(pts).distanceM, 0.5)
    }

    @Test fun wanderingAltitudeIsNotAClimb() {
        // A phone lying on a table, its altitude drifting by a metre or two, must report no ascent.
        val pts = (0..20).map { i ->
            fix(44.0, 15.0 + i * 0.0001, i * 10_000L, 5f, 100.0 + (i % 3) - 1)
        }
        assertEquals(0.0, TrackMath.stats(pts).ascentM, 1e-9)
    }

    @Test fun arealClimbIsCounted() {
        val pts = listOf(
            fix(44.0, 15.0, 0, 5f, 100.0),
            fix(44.001, 15.0, 60_000, 5f, 150.0),
            fix(44.002, 15.0, 120_000, 5f, 200.0),
        )
        assertEquals(100.0, TrackMath.stats(pts).ascentM, 1e-6)
    }

    @Test fun aDescentIsNotAnAscent() {
        val pts = listOf(
            fix(44.0, 15.0, 0, 5f, 200.0),
            fix(44.001, 15.0, 60_000, 5f, 100.0),
        )
        val s = TrackMath.stats(pts)
        assertEquals(0.0, s.ascentM, 1e-9)
        assertEquals(100.0, s.descentM, 1e-6)
    }

    @Test fun theHighestAndLowestAreKept() {
        val pts = listOf(
            fix(44.0, 15.0, 0, 5f, 120.0),
            fix(44.001, 15.0, 60_000, 5f, 640.0),
            fix(44.002, 15.0, 120_000, 5f, 300.0),
        )
        val s = TrackMath.stats(pts)
        assertEquals(120.0, s.minEle!!, 1e-9)
        assertEquals(640.0, s.maxEle!!, 1e-9)
    }

    @Test fun aTrackWithNoAltitudesHasNoneRatherThanZero() {
        val pts = listOf(fix(44.0, 15.0, 0, 5f, null), fix(44.001, 15.0, 60_000, 5f, null))
        assertNull(TrackMath.stats(pts).minEle)
    }

    @Test fun standingStillIsNotMovingTime() {
        val pts = listOf(
            fix(44.0, 15.0, 0),
            fix(44.0000001, 15.0, 600_000),
        )
        val s = TrackMath.stats(pts)
        assertEquals(600_000L, s.durationMs)
        assertEquals(0L, s.movingMs)
    }

    // --- The live recording agrees with the arithmetic done afterwards -------------------------

    @Test fun theLiveTotalsMatchTheSumsTakenAtTheEnd() {
        val rec = Recording(0L)
        val offered = (0..50).map { i ->
            fix(44.0 + i * 0.0002, 15.0, i * 10_000L, 5f, 100.0 + i * 2.0)
        }
        offered.forEach { rec.offer(it) }
        val live = rec.stats()
        val after = TrackMath.stats(rec.snapshot())
        assertEquals(after.points, live.points)
        assertEquals(after.distanceM, live.distanceM, 1e-6)
        assertEquals(after.ascentM, live.ascentM, 1e-6)
        assertEquals(after.movingMs, live.movingMs)
    }

    @Test fun aRefusedFixIsCountedAndNotRecorded() {
        val rec = Recording(0L)
        assertNotNull(rec.offer(fix(44.0, 15.0, 0)))
        assertNull(rec.offer(fix(44.5, 15.0, 1000)))
        assertEquals(1, rec.size)
        assertEquals(1, rec.rejectedCount())
        assertEquals(1, rec.rejected(Reject.JUMP))
    }

    @Test fun nothingIsRecordedWhilePaused() {
        val rec = Recording(0L)
        rec.offer(fix(44.0, 15.0, 0))
        rec.pause()
        assertNull(rec.offer(fix(44.001, 15.0, 60_000)))
        assertEquals(1, rec.size)
        rec.resume()
        assertNotNull(rec.offer(fix(44.001, 15.0, 120_000)))
        assertEquals(2, rec.size)
    }

    @Test fun aPauseDoesNotCountAsARejection() {
        val rec = Recording(0L)
        rec.offer(fix(44.0, 15.0, 0))
        rec.pause()
        rec.offer(fix(44.001, 15.0, 60_000))
        assertEquals(0, rec.rejectedCount())
    }

    // --- GPX ----------------------------------------------------------------------------------

    @Test fun theFileOpensAndClosesAsGpx() {
        val s = Gpx.whole("Velebit", listOf(fix(44.0, 15.0, 0)), 0)
        assertTrue(s.startsWith("<?xml"))
        assertTrue(s.contains("<gpx version=\"1.1\""))
        assertTrue(s.trimEnd().endsWith("</gpx>"))
    }

    @Test fun everyTagThatOpensAlsoCloses() {
        val s = Gpx.whole("Velebit", (0..5).map { fix(44.0 + it * 0.001, 15.0, it * 1000L) }, 0)
        for (tag in listOf("gpx", "trk", "trkseg", "trkpt", "metadata")) {
            // "<trk" would also match trkseg and trkpt: the closing bracket is what makes the
            // count a count of that tag rather than of every tag whose name starts the same.
            assertEquals("tag $tag", countOf(s, "<$tag>") + countOf(s, "<$tag "), countOf(s, "</$tag>"))
        }
    }

    private fun countOf(haystack: String, needle: String): Int {
        var i = 0
        var n = 0
        while (true) {
            val at = haystack.indexOf(needle, i)
            if (at < 0) return n
            n++
            i = at + needle.length
        }
    }

    @Test fun aPointCarriesItsPlaceTimeAndHeight() {
        val s = Gpx.point(Fix(45.815, 15.9819, 158.4, 0L, 4.2f, null, 11))
        assertTrue(s.contains("lat=\"45.815000\""))
        assertTrue(s.contains("lon=\"15.981900\""))
        assertTrue(s.contains("<ele>158.4</ele>"))
        assertTrue(s.contains("<time>1970-01-01T00:00:00Z</time>"))
        assertTrue(s.contains("<sat>11</sat>"))
        assertTrue(s.contains("<hdop>4.2</hdop>"))
    }

    @Test fun aPointWithNoHeightOmitsTheTagRatherThanWritingZero() {
        val s = Gpx.point(Fix(45.0, 15.0, null, 0L, 4f))
        assertFalse(s.contains("<ele>"))
    }

    @Test fun timesAreUtcWhateverTheClockIsSetTo() {
        assertEquals("2026-09-14T12:00:00Z", Gpx.isoUtc(1_789_387_200_000L))
    }

    @Test fun ampersandsInANameCannotBreakTheFile() {
        assertEquals("Paklenica &amp; Velebit", Gpx.escape("Paklenica & Velebit"))
        assertEquals("&lt;script&gt;", Gpx.escape("<script>"))
        assertEquals("&quot;x&quot; &apos;y&apos;", Gpx.escape("\"x\" 'y'"))
    }

    @Test fun controlCharactersAreDroppedRatherThanWritten() {
        val s = Gpx.escape("a\u0000b\u0007c")
        assertEquals("abc", s)
    }

    @Test fun croatianLettersSurviveEscaping() {
        assertEquals("Učka Šibenik Đakovo", Gpx.escape("Učka Šibenik Đakovo"))
    }

    @Test fun theWholeFileAndThePointByPointFileAreTheSameBytes() {
        // The two routes into a file must not drift apart: one is used while walking, the other
        // when exporting something already recorded.
        val pts = (0..9).map { fix(44.0 + it * 0.001, 15.0, it * 30_000L, 5f, 100.0 + it) }
        val whole = Gpx.whole("Ugljan", pts, 500L)
        val piecewise = buildString {
            append(Gpx.header("Ugljan", 500L))
            pts.forEach { append(Gpx.point(it)) }
            append(Gpx.footer())
        }
        assertEquals(piecewise, whole)
    }

    @Test fun aSegmentBreakClosesAndOpensOneSegment() {
        val s = Gpx.segmentBreak()
        assertEquals(1, countOf(s, "</trkseg>"))
        assertEquals(1, countOf(s, "<trkseg>"))
    }

    @Test fun theFileNameLeadsWithTheDate() {
        val n = Gpx.fileName(1_789_387_200_000L, "Velebit sjever")
        assertEquals("2026-09-14_1200_velebit-sjever.gpx", n)
    }

    @Test fun aFileNameWithNoNameIsStillAFileName() {
        assertEquals("2026-09-14_1200.gpx", Gpx.fileName(1_789_387_200_000L, "   "))
    }

    @Test fun aFileNameCannotContainASlash() {
        val n = Gpx.fileName(1_789_387_200_000L, "north/south")
        assertFalse(n.contains("/"))
    }

    // --- The level ------------------------------------------------------------------------------

    @Test fun flatOnItsBackIsLevel() {
        val r = Level.read(0.0, 0.0, Level.G)
        assertEquals(0.0, r.pitch, 1e-6)
        assertEquals(0.0, r.roll, 1e-6)
        assertTrue(r.level)
    }

    @Test fun tippedForwardIsPitchOnly() {
        // 30 degrees nose down: gravity swings onto the y axis.
        val r = Level.read(0.0, Level.G * 0.5, Level.G * 0.8660254)
        assertEquals(-30.0, r.pitch, 0.01)
        assertEquals(0.0, r.roll, 0.01)
    }

    @Test fun tippedSidewaysIsRollOnly() {
        val r = Level.read(Level.G * 0.5, 0.0, Level.G * 0.8660254)
        assertEquals(30.0, r.roll, 0.01)
        assertEquals(0.0, r.pitch, 0.01)
    }

    @Test fun twoLeansComposeIntoOneTilt() {
        // 3 degrees one way and 4 the other is 5 degrees off level, not 7.
        val pitch = 3.0
        val roll = 4.0
        val gx = Level.G * Math.sin(Geo.rad(roll))
        val gy = -Level.G * Math.sin(Geo.rad(pitch))
        val gz = Level.G * Math.cos(Geo.rad(pitch)) * Math.cos(Geo.rad(roll))
        val r = Level.read(gx, gy, gz)
        assertEquals(4.99, r.tilt, 0.05)
    }

    @Test fun beingCarriedIsNotTrustworthy() {
        val r = Level.read(0.0, 0.0, Level.G * 3)
        assertFalse(r.trustworthy)
        assertFalse(r.level)
    }

    @Test fun freefallIsNotTrustworthyEither() {
        assertFalse(Level.read(0.0, 0.0, 0.0).trustworthy)
    }

    @Test fun theCalibrationTakesOutTheTiltOfThePlate() {
        val r0 = Level.read(0.0, -Level.G * 0.0349, Level.G * 0.999) // about 2 degrees nose up
        val cal = Level.Calibration(r0.pitch, r0.roll)
        val r1 = Level.read(0.0, -Level.G * 0.0349, Level.G * 0.999, cal)
        assertEquals(0.0, r1.pitch, 1e-6)
        assertTrue(r1.level)
    }

    @Test fun theBubbleIsCentredWhenLevel() {
        val (x, y) = Level.bubble(Level.read(0.0, 0.0, Level.G))
        assertEquals(0.0, x, 1e-9)
        assertEquals(0.0, y, 1e-9)
    }

    @Test fun theBubbleNeverLeavesTheVial() {
        val r = Level.read(Level.G * 0.7, Level.G * 0.7, 0.1)
        val (x, y) = Level.bubble(r)
        assertTrue(Math.hypot(x, y) <= 1.0000001)
    }

    @Test fun theBubbleLeansTheWayThePhoneLeans() {
        val right = Level.bubble(Level.read(Level.G * 0.08, 0.0, Level.G * 0.99))
        assertTrue(right.first > 0)
        val left = Level.bubble(Level.read(-Level.G * 0.08, 0.0, Level.G * 0.99))
        assertTrue(left.first < 0)
    }

    @Test fun smoothingMovesTowardsTheNewValueWithoutReachingIt() {
        val next = Level.smooth(0.0, 10.0, 0.2)
        assertEquals(2.0, next, 1e-9)
    }

    @Test fun smoothingWithAlphaOneIsTheNewValue() {
        assertEquals(10.0, Level.smooth(0.0, 10.0, 1.0), 1e-9)
    }

    @Test fun theNeedleDoesNotSpinTheLongWayAtNorth() {
        // The failure this closes: averaging 359 and 1 the ordinary way gives 180, and the needle
        // points exactly backwards at the moment somebody is watching it.
        val s = Level.smoothAngle(359.0, 1.0, 0.5)
        assertTrue("got $s", s > 359.5 || s < 0.5)
    }

    @Test fun theNeedleStaysInsideTheCircle() {
        var a = 10.0
        repeat(100) { a = Level.smoothAngle(a, 350.0, 0.3) }
        assertTrue(a in 0.0..360.0)
        assertEquals(350.0, a, 0.5)
    }

    // --- The layers -----------------------------------------------------------------------------

    @Test fun thereAreFourLayersAndTheyHaveDistinctIds() {
        assertEquals(4, Layers.ALL.size)
        assertEquals(4, Layers.ALL.map { it.id }.toSet().size)
    }

    @Test fun googlesTilesAreNeverCacheable() {
        // Google's terms forbid pre-fetching, caching or storing tiles, and name offline use as a
        // prohibited case. This is the check that fails if somebody "improves" the app later.
        assertFalse(Layers.GOOGLE.cacheable)
        assertEquals(MapLayer.Offline.NONE, Layers.GOOGLE.offline)
        assertNull(Layers.tileUrl(Layers.GOOGLE, 12, 2229, 1460))
    }

    @Test fun theOfflineMapNeedsNothingFetched() {
        assertEquals(MapLayer.Offline.COMPLETE, Layers.OAM.offline)
        assertNull(Layers.tileUrl(Layers.OAM, 12, 2229, 1460))
    }

    @Test fun theRasterUrlCarriesTheTileNumbers() {
        val u = Layers.tileUrl(Layers.OPENTOPO, 12, 2229, 1460)!!
        assertTrue(u.endsWith("/12/2229/1460.png"))
    }

    @Test fun theCroatianTopoIsAskedForItsOwnBoundingBox() {
        val u = Layers.tileUrl(Layers.TK25, 12, 2229, 1460)!!
        assertTrue(u.contains("SRS=EPSG:3857"))
        assertTrue(u.contains("LAYERS=tk:TK25"))
        assertTrue(u.contains("WIDTH=256&HEIGHT=256"))
        val bbox = u.substringAfter("BBOX=").split(",").map { it.toDouble() }
        val expected = Geo.tileBbox3857(12, 2229, 1460)
        assertEquals(expected[0], bbox[0], 0.001)
        assertEquals(expected[3], bbox[3], 0.001)
    }

    @Test fun anUnknownLayerIdFallsBackToTheOneThatWorksOffline() {
        assertEquals(Layers.OAM.id, Layers.byId("something else").id)
        assertEquals(Layers.TK25.id, Layers.byId("tk25").id)
    }

    @Test fun everyFetchedLayerHasAnAttributionAndAUrl() {
        Layers.ALL.filter { it.cacheable }.forEach {
            assertTrue(it.id, it.attribution.isNotBlank())
            assertNotNull(it.id, it.url)
        }
    }
}
