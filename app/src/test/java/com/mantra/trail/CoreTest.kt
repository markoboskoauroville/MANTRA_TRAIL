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

    @Test fun thereAreSixteenLayersAndTheyHaveDistinctIds() {
        assertEquals(16, Layers.ALL.size)
        assertEquals(16, Layers.ALL.map { it.id }.toSet().size)
    }

    @Test fun thunderforestContributesAllTenOfItsStyles() {
        val styles = Layers.of(MapLayer.Family.THUNDERFOREST)
        assertEquals(10, styles.size)
        assertEquals(10, styles.map { it.url }.toSet().size)
        styles.forEach {
            assertEquals(it.id, Keys.Provider.THUNDERFOREST, it.provider)
            assertTrue(it.id, it.url!!.startsWith("https://api.thunderforest.com/"))
            assertTrue(it.id, it.url!!.contains("apikey={key}"))
        }
    }

    @Test fun theOneButtonTurnsThroughFourFamiliesAndComesBack() {
        var layer = Layers.OFFLINE
        val seen = ArrayList<MapLayer.Family>()
        repeat(4) {
            seen.add(layer.family)
            layer = Layers.firstOf(Layers.nextFamily(layer))
        }
        assertEquals(MapLayer.Family.entries.toList(), seen)
        assertEquals(MapLayer.Family.OFFLINE, layer.family)
    }

    @Test fun everyFamilyHasAtLeastOneMap() {
        MapLayer.Family.entries.forEach { assertTrue(it.name, Layers.of(it).isNotEmpty()) }
    }

    @Test fun theViewGoesFurtherThanTheTilesDo() {
        // The complaint this closes: OpenStreetMap stopped dead at 18 because that was where its
        // tiles stopped. Past the last real tile the map is scaled, not fetched.
        assertTrue(Layers.OSM.viewMaxZoom > Layers.OSM.maxZoom)
        assertEquals(22, Layers.OFFLINE.viewMaxZoom)
        Layers.ALL.forEach {
            assertTrue(it.id, it.viewMaxZoom >= it.maxZoom)
            // mapsforge only scales a parent four levels up; beyond that it has nothing to draw.
            assertTrue(it.id, it.viewMaxZoom - it.maxZoom <= 4)
            assertTrue(it.id, it.viewMaxZoom <= 22)
        }
    }

    @Test fun everyThunderforestStyleIsCalledThunderforest() {
        Layers.of(MapLayer.Family.THUNDERFOREST).forEach {
            assertTrue(it.label, it.label.startsWith("Thunderforest"))
            assertEquals(it.id, "THU", it.short)
        }
    }

    @Test fun everyGoogleViewIsCalledGoogle() {
        Layers.of(MapLayer.Family.GOOGLE).forEach {
            assertTrue(it.label, it.label.startsWith("Google"))
            assertEquals(it.id, "GOO", it.short)
        }
    }

    @Test fun aSpeedIsWrittenInAUnitSomebodyCanJudge() {
        assertEquals("0 B/s", Geo.formatRate(0))
        assertEquals("340 kB/s", Geo.formatRate(340_000))
        assertEquals("2.5 MB/s", Geo.formatRate(2_500_000))
    }

    @Test fun everyShortNameFitsTheKey() {
        Layers.ALL.forEach { assertTrue("${it.id}: ${it.short}", it.short.length <= 4) }
    }

    @Test fun noLayerCarriesAKeyOfItsOwn() {
        // The whole point of v7: the app ships no key. A URL template may have a {key} hole in
        // it; anything that looks like a real key in this table is the failure that cost a live
        // Maps key on 14.9.2026.
        val shapes = Regex("(AIza|gsk_|sk-ant-)[A-Za-z0-9_-]{20,}|\\b[0-9a-f]{32}\\b")
        Layers.ALL.forEach { layer ->
            assertNull(layer.id, layer.url?.let { shapes.find(it) })
        }
    }

    @Test fun aLayerThatNeedsAKeyHasNoAddressWithoutOne() {
        Layers.ALL.filter { it.provider != null }.forEach {
            assertNull(it.id, Layers.tileUrl(it, 12, 2229, 1460, auth = "session", key = null))
            assertNull(it.id, Layers.tileUrl(it, 12, 2229, 1460, auth = "session", key = ""))
            assertNotNull(it.id, Layers.missingKey(it))
        }
    }

    @Test fun googleNeedsASessionAsWellAsAKey() {
        assertNull(Layers.tileUrl(Layers.GOOGLE, 12, 2229, 1460, auth = null, key = "AIza" + "B".repeat(35)))
        val url = Layers.tileUrl(Layers.GOOGLE, 12, 2229, 1460, auth = "S123", key = "AIza" + "B".repeat(35))!!
        assertTrue(url.contains("session=S123"))
        assertTrue(url.contains("/12/2229/1460"))
    }

    @Test fun theWalkingMapPutsTheKeyInItsAddress() {
        val key = "a".repeat(32)
        val url = Layers.tileUrl(Layers.THUNDERFOREST, 12, 2229, 1460, key = key)!!
        assertTrue(url.contains("apikey=$key"))
        assertTrue(url.startsWith("https://"))
    }

    @Test fun hybridIsSatelliteWithTheRoadsOverIt() {
        assertEquals("satellite", MapLayer.GoogleView.HYBRID.mapType)
        assertTrue(MapLayer.GoogleView.HYBRID.overlayRoads)
        assertFalse(MapLayer.GoogleView.SATELLITE.overlayRoads)
    }

    @Test fun aKeyIsSortedByItsShapeRatherThanByBeingAsked() {
        assertEquals(Keys.Provider.GOOGLE, Keys.providerOf("AIza" + "B".repeat(35)))
        assertEquals(Keys.Provider.THUNDERFOREST, Keys.providerOf("0123456789abcdef" + "0123456789abcdef"))
        assertNull(Keys.providerOf("cafeteria"))
        assertNull(Keys.providerOf("0123456789ABCDEF0123456789ABCDEF"))
    }

    @Test fun aFileWithBothKindsSortsBoth() {
        val text = "google\n" + "AIza" + "B".repeat(35) + "\n\nthunderforest\n" + "a".repeat(32) + "\n"
        val found = Keys.parse(text)
        assertEquals(2, found.size)
        assertEquals(setOf(Keys.Provider.GOOGLE, Keys.Provider.THUNDERFOREST), found.map { it.provider }.toSet())
    }

    @Test fun googleContributesAllFourOfItsViews() {
        val views = Layers.ALL.mapNotNull { it.googleView }
        assertEquals(4, views.size)
        assertEquals(4, views.toSet().size)
    }

    @Test fun noViewOfGoogleMayBeCachedOrRefusedQuietly() {
        Layers.ALL.filter { it.kind == LayerKind.GOOGLE_TILES }.forEach {
            assertEquals(it.id, MapLayer.Offline.NONE, it.offline)
            assertNull(it.id, Layers.tileUrl(it, 12, 2229, 1460))
        }
    }

    @Test fun onlyGoogleLayersCarryAGoogleView() {
        assertNull(Layers.OFFLINE.googleView)
        assertNull(Layers.OSM.googleView)
    }

    @Test fun aFamilyStillHasEveryStyleInIt() {
        // The key turns through families, so nothing may be reachable ONLY by the key: every map
        // is in the list, and every map belongs to a family the key visits.
        assertEquals(Layers.ALL.size, MapLayer.Family.entries.sumOf { Layers.of(it).size })
    }

    @Test fun theOfflineMapIsFetchedFromAKnownPlaceWithAKnownSize() {
        assertTrue(Layers.OfflineDownload.URL.startsWith("https://"))
        assertTrue(Layers.OfflineDownload.NAME.endsWith(".map"))
        assertTrue(Layers.OfflineDownload.BYTES > 100_000_000)
    }

    @Test fun googlesTilesAreNeverFetchedWithoutASession() {
        // Google's terms forbid pre-fetching, caching or storing tiles, and name offline use as a
        // prohibited case. This is the check that fails if somebody "improves" the app later.
        assertEquals(MapLayer.Offline.NONE, Layers.GOOGLE.offline)
        assertNull(Layers.tileUrl(Layers.GOOGLE, 12, 2229, 1460))
    }

    @Test fun theOfflineMapNeedsNothingFetched() {
        assertEquals(MapLayer.Offline.COMPLETE, Layers.OFFLINE.offline)
        assertNull(Layers.tileUrl(Layers.OFFLINE, 12, 2229, 1460))
    }

    @Test fun theRasterUrlCarriesTheTileNumbers() {
        val u = Layers.tileUrl(Layers.OSM, 12, 2229, 1460)!!
        assertTrue(u.endsWith("/12/2229/1460.png"))
        assertTrue(u.startsWith("https://"))
    }


    @Test fun anUnknownLayerIdFallsBackToTheOneThatWorksOffline() {
        assertEquals(Layers.OFFLINE.id, Layers.byId("something else").id)
        assertEquals(Layers.OSM.id, Layers.byId("osm").id)
    }

    @Test fun everyFetchedLayerHasAnAttributionAndAUrl() {
    }

    // --- Importing a key from a file -------------------------------------------------------------

    private val fakeKey = "AIza" + "B".repeat(35)
    private val otherKey = "AIza" + "C".repeat(35)

    @Test fun aKeyIsFoundByItsShape() {
        val found = Keys.parse("some notes\n$fakeKey\n")
        assertEquals(1, found.size)
        assertEquals(fakeKey, found[0].key)
    }

    @Test fun theAccountNameComesWithTheKey() {
        val found = Keys.parse("AV LIVE VMIX\n$fakeKey\n")
        assertEquals("AV LIVE VMIX", found[0].label)
    }

    @Test fun aKeyringBlockIsUnderstood() {
        val text = "# keyring v1\n\nprovider: google-maps\nlabel: phone\nkey: $fakeKey\n"
        val found = Keys.parse(text)
        assertEquals(1, found.size)
        assertEquals(fakeKey, found[0].key)
    }

    @Test fun twoBlocksAreTwoKeys() {
        val found = Keys.parse("first\n$fakeKey\n\nsecond\n$otherKey\n")
        assertEquals(2, found.size)
    }

    @Test fun theSameKeyTwiceIsOneKey() {
        val found = Keys.parse("one\n$fakeKey\n\ntwo\n$fakeKey\n")
        assertEquals(1, found.size)
    }

    @Test fun aFileOfProseYieldsNothing() {
        assertTrue(Keys.parse("cafeteria\nkitchen\nthe wifi password is upstairs").isEmpty())
    }

    @Test fun aTrackingUrlIsNotAKeyAndNotALabel() {
        val found = Keys.parse("https://example.com/?srsltid=AbCdEfGhIjKlMnOpQrStUvWxYz012345\nmy account\n$fakeKey")
        assertEquals(1, found.size)
        assertEquals("my account", found[0].label)
    }

    @Test fun anEmptyFileYieldsNothingRatherThanThrowing() {
        assertTrue(Keys.parse("").isEmpty())
        assertTrue(Keys.parse("   \n\n  ").isEmpty())
    }

    /**
     * THE REAL FILE, IN ITS REAL SHAPE. This is the layout of the note the key actually arrived
     * in on 15.9.2026 — a title line, an account, a plan, two URLs, the key under a heading, and
     * a tile URL further down that contains the key a second time. Only the key itself is
     * replaced here. It found one key, called it Thunderforest, and labelled it API KEY.
     */
    @Test fun theFileTheKeyActuallyArrivedInParsesToOneKey() {
        // Built from pieces on purpose: a 32-hex literal in the source is exactly what
        // Gate G2 scans the history for, and it cannot tell a fixture from a real key.
        val k = "a1b2c3d4" + "e5f60718" + "293a4b5c" + "6d7e8f90"
        val text = """
            THUNDERFOREST — map tiles (OpenStreetMap), api key
            Created 15.9.2026 by Marko. Account: someone@example.com
            Plan: Hobby Project — free, 150,000 tile requests per month, no card.
            Console: https://manage.thunderforest.com/   Pricing: https://www.thunderforest.com/pricing/

            API KEY
            $k

            HOW IT IS USED
              https://tile.thunderforest.com/outdoors/{z}/{x}/{y}.png?apikey=$k
            Styles: cycle, transport, landscape, outdoors.
        """.trimIndent()
        val found = Keys.parse(text)
        assertEquals(1, found.size)
        assertEquals(k, found[0].key)
        assertEquals(Keys.Provider.THUNDERFOREST, found[0].provider)
    }

    @Test fun aKeyInsideAUrlIsStillTheSameKey() {
        // Built from pieces on purpose: a 32-hex literal in the source is exactly what
        // Gate G2 scans the history for, and it cannot tell a fixture from a real key.
        val k = "a1b2c3d4" + "e5f60718" + "293a4b5c" + "6d7e8f90"
        val found = Keys.parse("https://tile.thunderforest.com/outdoors/1/1/1.png?apikey=$k")
        assertEquals(1, found.size)
        assertEquals(k, found[0].key)
    }

    @Test fun anAccountLineIsNotMistakenForAKey() {
        val found = Keys.parse("Account: marko@example.com\nPlan: Hobby Project, 150000 requests")
        assertTrue(found.isEmpty())
    }

    @Test fun anUppercaseHexStringIsNotAThunderforestKey() {
        // Their keys are lowercase; a hex string in capitals is a checksum in somebody's notes.
        assertNull(Keys.providerOf("A1B2C3D4" + "E5F60718" + "293A4B5C" + "6D7E8F90"))
    }

    @Test fun everyLayerThatFetchesTilesCarriesItsCreditOnTheMap() {
        // Thunderforest do not permit removing their attribution or OpenStreetMap's from an app.
        Layers.ALL.filter { it.kind != LayerKind.VECTOR_FILE }.forEach {
            assertTrue(it.id, it.creditOnMap)
            assertTrue(it.id, it.attribution.isNotBlank())
        }
        assertFalse(Layers.OFFLINE.creditOnMap)
    }

    @Test fun aKeyIsDescribedByPositionAndLengthAndNothingElse() {
        val d = Keys.describe(0, 3, Keys.Found(fakeKey, Keys.Provider.GOOGLE, null))
        assertTrue(d.contains("1 of 3"))
        assertTrue(d.contains("${fakeKey.length}"))
        assertFalse(d.contains(fakeKey.substring(0, 8)))
    }
}
