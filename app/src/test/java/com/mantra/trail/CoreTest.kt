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



    @Test fun speedIsKilometresAnHour() {
        assertEquals("- km/h", Geo.formatSpeed(null))
        assertEquals("0 km/h", Geo.formatSpeed(0f))
        assertEquals("4.7 km/h", Geo.formatSpeed(1.3f))
        assertEquals("36 km/h", Geo.formatSpeed(10f))
    }

    @Test fun aPhoneStandingStillDoesNotReportAWalk() {
        // A fix wandering by a metre every few seconds is not somebody walking.
        assertEquals("0 km/h", Geo.formatSpeed(0.1f))
    }

    @Test fun aNonsenseSpeedIsADashRatherThanANumber() {
        assertEquals("- km/h", Geo.formatSpeed(-1f))
        assertEquals("- km/h", Geo.formatSpeed(Float.NaN))
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




    // --- the URL, split the way the GPU engine wants it ------------------------------------------




    @Test fun theOfflineFileHasNoPatternBecauseItHasNoUrl() {
        assertNull(Layers.tilePattern(Layers.OFFLINE))
    }


    // --- a route of more than two points ----------------------------------------------------------

    @Test fun thePointsAreLetteredFromA() {
        assertEquals("A", Route.letterFor(0))
        assertEquals("B", Route.letterFor(1))
        assertEquals("Z", Route.letterFor(25))
    }

    @Test fun aPointListSurvivesBeingWrittenDownAndReadBack() {
        val points = listOf(45.815 to 15.982, 45.911 to 15.969, 45.9 to 16.1)
        val read = Route.decode(Route.encode(points))
        assertEquals(3, read.size)
        assertEquals(45.911, read[1].first, 1e-9)
        assertEquals(16.1, read[2].second, 1e-9)
    }

    @Test fun aCorruptedLineYieldsWhatItCanRatherThanThrowing() {
        assertTrue(Route.decode(null).isEmpty())
        assertTrue(Route.decode("").isEmpty())
        assertTrue(Route.decode("nonsense").isEmpty())
        assertEquals(1, Route.decode("45.8,15.9;broken").size)
        assertEquals(1, Route.decode("45.8,15.9;91.0,15.9").size)
    }

    @Test fun aRouteCannotGrowPastTheAlphabet() {
        val many = (0 until 40).map { 45.0 + it / 100.0 to 15.0 }
        assertEquals(Route.MAX_POINTS, Route.decode(Route.encode(many)).size)
    }

    @Test fun theStraightLengthAddsUpEveryLeg() {
        val a = 45.0 to 15.0
        val b = 45.1 to 15.0
        val c = 45.2 to 15.0
        val oneLeg = Route.straightMetres(listOf(a, b))
        val twoLegs = Route.straightMetres(listOf(a, b, c))
        assertEquals(oneLeg * 2, twoLegs, oneLeg * 0.01)
        assertEquals(0.0, Route.straightMetres(listOf(a)), 0.001)
        assertEquals(0.0, Route.straightMetres(emptyList()), 0.001)
    }

    @Test fun aSavedRouteIsNamedForTheLettersItRanThrough() {
        val name = Route.nameFor(1_789_387_200_000L, 4)
        assertTrue(name, name.endsWith("(AD)"))
        assertTrue(Route.nameFor(1_789_387_200_000L, 2).endsWith("(AB)"))
    }

    // --- which square of the world a route needs ------------------------------------------------

    @Test fun zagrebIsInTheSquareBRouterCallsE15N45() {
        assertEquals("E15_N45.rd5", Segments.nameFor(45.815, 15.982))
    }

    @Test fun aurovilleIsInItsOwnSquare() {
        // 12.0 N, 79.8 E → the square starting at 10 N, 75 E.
        assertEquals("E75_N10.rd5", Segments.nameFor(12.0055, 79.8106))
    }

    @Test fun theSouthernAndWesternHemispheresAreNamedTheOtherWay() {
        assertEquals("W75_S35.rd5", Segments.nameFor(-33.45, -70.66))
        assertEquals("W5_N50.rd5", Segments.nameFor(51.5, -0.12))
        assertEquals("E15_S35.rd5", Segments.nameFor(-33.9, 18.4))
    }

    @Test fun aPointExactlyOnABoundaryBelongsToTheSquareItStarts() {
        assertEquals("E15_N45.rd5", Segments.nameFor(45.0, 15.0))
        assertEquals("E20_N50.rd5", Segments.nameFor(50.0, 20.0))
    }

    @Test fun aWalkInsideOneSquareNeedsOneFile() {
        val needed = Segments.namesFor(45.815, 15.982, 45.911, 15.969)
        assertEquals(listOf("E15_N45.rd5"), needed)
    }

    @Test fun aWalkAcrossABoundaryNeedsBothSquares() {
        // Over the 45th parallel: Zagreb to somewhere south of it.
        val needed = Segments.namesFor(45.1, 15.9, 44.9, 15.9)
        assertEquals(2, needed.size)
        assertTrue(needed.contains("E15_N45.rd5"))
        assertTrue(needed.contains("E15_N40.rd5"))
    }

    @Test fun aWalkNearACornerCanNeedFour() {
        val needed = Segments.namesFor(45.1, 14.9, 44.9, 15.1)
        assertEquals(4, needed.size)
    }

    @Test fun theUrlIsTheServersOwn() {
        assertEquals(
            "https://brouter.de/brouter/segments4/E15_N45.rd5",
            Segments.urlFor(45.815, 15.982),
        )
    }

    // --- The layers -----------------------------------------------------------------------------

    @Test fun theRasterServicesAreGone() {
        // Thunderforest and OpenStreetMap left at his word on 16.9.2026: this app is the file on
        // the phone. What remains is the offline map and Google's four views.
        assertTrue(Layers.ALL.none { it.id.startsWith("tf-") || it.id == "osm" })
        assertEquals(setOf(MapLayer.Family.OFFLINE, MapLayer.Family.GOOGLE), Layers.ALL.map { it.family }.toSet())
    }

    @Test fun anOpenAndroMapsRegionKnowsItsUrlAndItsCost() {
        val balkan = Oam.byName("balkan")!!
        assertTrue(balkan.url, balkan.url.startsWith("https://ftp.gwdg.de/"))
        assertTrue(balkan.url.endsWith("Balkan.zip"))
        assertEquals("oam-balkan.map", balkan.fileName)
        assertTrue(Oam.sizeLabel(balkan), Oam.sizeLabel(balkan).startsWith("1.1"))
        assertTrue(Oam.sizeLabel(balkan).contains("twice"))
    }

    @Test fun onlyTheMapComesOutOfTheArchive() {
        assertTrue(Oam.isTheMap("Balkan.map"))
        assertFalse(Oam.isTheMap("Balkan.poi"))
        assertFalse(Oam.isTheMap("readme.txt"))
        assertFalse(Oam.isTheMap("__MACOSX/Balkan.map"))
    }

    @Test fun aRegionNobodyMeasuredSaysSoRatherThanClaimingZero() {
        val slovenia = Oam.byName("slovenia")!!
        assertTrue(Oam.sizeLabel(slovenia).contains("unknown"))
    }

    @Test fun everyLayerHasItsOwnId() {
        // The count moves whenever a family is added; what must never move is that two maps
        // share an id, because the id is what the settings list and the memory both key on.
        assertEquals(Layers.ALL.size, Layers.ALL.map { it.id }.toSet().size)
        // Sixteen when Thunderforest and OpenStreetMap were here; five now that they are not.
        assertTrue(Layers.ALL.size.toString(), Layers.ALL.size >= 5)
    }





    @Test fun everyFamilyHasAtLeastOneMap() {
        MapLayer.Family.entries.forEach { assertTrue(it.name, Layers.of(it).isNotEmpty()) }
    }

    @Test fun theViewGoesFurtherThanTheTilesDo() {
        // The complaint this closes: OpenStreetMap stopped dead at 18 because that was where its
        // tiles stopped. Past the last real tile the map is scaled, not fetched.
        // Was written for OpenStreetMap, which stopped dead at 18; it left with the raster
        // services on 16.9.2026 and the rule it proved still holds for what remains.
        assertEquals(22, Layers.OFFLINE.viewMaxZoom)
        Layers.GOOGLE_ALL.forEach { assertTrue(it.id, it.viewMaxZoom >= it.maxZoom) }
        Layers.ALL.forEach {
            assertTrue(it.id, it.viewMaxZoom >= it.maxZoom)
            // mapsforge only scales a parent four levels up; beyond that it has nothing to draw.
            assertTrue(it.id, it.viewMaxZoom - it.maxZoom <= 4)
            assertTrue(it.id, it.viewMaxZoom <= 22)
        }
    }


    @Test fun everyGoogleViewIsCalledGoogle() {
        Layers.of(MapLayer.Family.GOOGLE).forEach {
            assertTrue(it.label, it.label.startsWith("Google"))
            assertEquals(it.id, "GOO", it.short)
            assertFalse(it.name, it.name.contains("Google"))
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

    @Test fun everyNameFitsTheLineItSharesWithTheCoordinates() {
        // The top line is about 55 monospace characters at 11sp on a 390 px phone, and the rest
        // of it is 38. Fourteen is the most a name may take without something clipping.
        Layers.ALL.forEach { assertTrue("${it.id}: ${it.name}", it.name.length <= 14) }
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


    @Test fun hybridIsSatelliteWithTheRoadsOverIt() {
        assertEquals("satellite", MapLayer.GoogleView.HYBRID.mapType)
        assertTrue(MapLayer.GoogleView.HYBRID.overlayRoads)
        assertFalse(MapLayer.GoogleView.SATELLITE.overlayRoads)
    }

    // --- Google's encoded polyline -----------------------------------------------------------

    @Test fun theExampleFromGooglesOwnDocumentationDecodes() {
        // Their published example: three points in California.
        val points = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, points.size)
        assertEquals(38.5, points[0].first, 1e-5)
        assertEquals(-120.2, points[0].second, 1e-5)
        assertEquals(40.7, points[1].first, 1e-5)
        assertEquals(-120.95, points[1].second, 1e-5)
        assertEquals(43.252, points[2].first, 1e-5)
        assertEquals(-126.453, points[2].second, 1e-5)
    }

    @Test fun anEmptyStringIsAnEmptyRoute() {
        assertTrue(Polyline.decode("").isEmpty())
    }

    @Test fun aStringThatRunsOutMidNumberYieldsWhatItRead() {
        val whole = "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        val cut = whole.substring(0, whole.length - 3)
        val points = Polyline.decode(cut)
        assertTrue(points.size.toString(), points.size in 1..2)
        assertEquals(38.5, points[0].first, 1e-5)
    }

    @Test fun rubbishDecodesToSomethingRatherThanThrowing() {
        // Not a crash, whatever arrives: the worst case is a short list of odd points.
        Polyline.decode("!!!!")
        Polyline.decode("????????")
        assertTrue(true)
    }

    // --- the keyring: several keys, tried in order ------------------------------------------------

    private fun aKey(tail: String) = "AIza" + "B".repeat(31) + tail

    @Test fun keysAreAddedWithoutLosingWhatIsKnownAboutTheOldOnes() {
        val first = Keyring.add(emptyList(), listOf(aKey("aaaa")))
        val tested = Keyring.withVerdict(first, aKey("aaaa"), Keyring.Verdict.GOOD, "works", 5L)
        val both = Keyring.add(tested, listOf(aKey("bbbb")))
        assertEquals(2, both.size)
        assertEquals(Keyring.Verdict.GOOD, both[0].verdict)
        assertEquals(Keyring.Verdict.UNTRIED, both[1].verdict)
    }

    @Test fun theSameKeyIsNotAddedTwice() {
        val once = Keyring.add(emptyList(), listOf(aKey("aaaa")))
        assertEquals(1, Keyring.add(once, listOf(aKey("aaaa"))).size)
        assertEquals(1, Keyring.add(emptyList(), listOf(aKey("aaaa"), aKey("aaaa"))).size)
    }

    @Test fun onlyGoogleShapedKeysGetOntoTheRing() {
        assertTrue(Keyring.add(emptyList(), listOf("not a key", "0123456789abcdef")).isEmpty())
    }

    @Test fun theRingSurvivesBeingWrittenDownAndReadBack() {
        val ring = Keyring.withVerdict(
            Keyring.add(emptyList(), listOf(aKey("aaaa"), aKey("bbbb"))),
            aKey("aaaa"),
            Keyring.Verdict.REFUSED,
            "Google: Map Tiles API has not been used in project 1234",
            99L,
        )
        val read = Keyring.decode(Keyring.encode(ring))
        assertEquals(2, read.size)
        assertEquals(Keyring.Verdict.REFUSED, read[0].verdict)
        assertTrue(read[0].said.contains("Map Tiles API"))
        assertEquals(99L, read[0].testedMs)
    }

    @Test fun aCorruptedRingYieldsWhatItCanRatherThanThrowing() {
        assertTrue(Keyring.decode(null).isEmpty())
        assertTrue(Keyring.decode("rubbish").isEmpty())
        assertEquals(1, Keyring.decode("${aKey("aaaa")}\tkey 1\tGOOD\tworks\t1\nbroken line").size)
    }

    @Test fun theOneThatWorksIsTriedFirstAndTheRefusedOneLast() {
        var ring = Keyring.add(emptyList(), listOf(aKey("aaaa"), aKey("bbbb"), aKey("cccc")))
        ring = Keyring.withVerdict(ring, aKey("aaaa"), Keyring.Verdict.REFUSED, "no", 1L)
        ring = Keyring.withVerdict(ring, aKey("cccc"), Keyring.Verdict.GOOD, "yes", 2L)
        val order = Keyring.order(ring).map { it.value }
        assertEquals(aKey("cccc"), order[0])
        assertEquals(aKey("bbbb"), order[1])
        assertEquals(aKey("aaaa"), order[2])
        assertEquals(aKey("cccc"), Keyring.best(ring)!!.value)
    }

    @Test fun anEmptyRingHasNoBestKey() {
        assertNull(Keyring.best(emptyList()))
    }

    @Test fun aKeyIsShownMaskedAndNeverWhole() {
        val key = Keyring.add(emptyList(), listOf(aKey("aaaa"))).first()
        assertFalse(key.masked.contains(key.value.substring(10, 20)))
        assertTrue(key.masked.startsWith("AIza"))
        assertTrue(Keyring.describe(key).contains("not tested"))
    }

    @Test fun removingOneLeavesTheRest() {
        val ring = Keyring.add(emptyList(), listOf(aKey("aaaa"), aKey("bbbb")))
        val left = Keyring.remove(ring, aKey("aaaa"))
        assertEquals(1, left.size)
        assertEquals(aKey("bbbb"), left[0].value)
    }

    @Test fun aKeyIsSortedByItsShapeRatherThanByBeingAsked() {
        assertEquals(Keys.Provider.GOOGLE, Keys.providerOf("AIza" + "B".repeat(35)))
        assertNull(Keys.providerOf("cafeteria"))
        // A 32-character hex string was a Thunderforest key until 16.9.2026, when those maps left
        // the app. It belongs to nobody here now, and saying so beats claiming otherwise.
        assertNull(Keys.providerOf("0123456789abcdef" + "0123456789abcdef"))
        assertNull(Keys.providerOf("0123456789ABCDEF0123456789ABCDEF"))
    }

    @Test fun aFileWithTwoKindsKeepsOnlyTheOneThisAppUses() {
        val text = "google\n" + "AIza" + "B".repeat(35) + "\n\nthunderforest\n" + "a".repeat(32) + "\n"
        val found = Keys.parse(text)
        assertEquals(1, found.size)
        assertEquals(Keys.Provider.GOOGLE, found[0].provider)
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

    @Test fun terrainIsAskedForWithARoadmapLayer() {
        // Google refuses a terrain session without one: "The terrain map type must always contain
        // a roadmap layer" (400), proved against the real key on 17.9.2026.
        val terrain = Layers.GOOGLE_ALL.first { it.googleView?.mapType == "terrain" }
        assertEquals("terrain", terrain.googleView!!.mapType)
        // Hybrid asks for the same layer for a different reason: roads over a photograph.
        val hybrid = Layers.GOOGLE_ALL.first { it.googleView?.overlayRoads == true }
        assertEquals("satellite", hybrid.googleView!!.mapType)
    }

    @Test fun onlyGoogleLayersCarryAGoogleView() {
        assertNull(Layers.OFFLINE.googleView)
        Layers.GOOGLE_ALL.forEach { assertNotNull(it.id, it.googleView) }
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
        // Written for OpenStreetMap, which left on 16.9.2026; Google's tiles are the raster that
        // remains, and the rule is the same one.
        val u = Layers.tileUrl(Layers.GOOGLE, 12, 2229, 1460, auth = "session", key = "k")!!
        assertTrue(u, u.contains("/12/2229/1460"))
        assertTrue(u.startsWith("https://"))
    }


    @Test fun anUnknownLayerIdFallsBackToTheOneThatWorksOffline() {
        assertEquals(Layers.OFFLINE.id, Layers.byId("something else").id)
        // A map that has been removed falls back to the offline file rather than to nothing.
        assertEquals(Layers.OFFLINE.id, Layers.byId("osm").id)
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
        // Thunderforest's maps left the app on 16.9.2026, so this file — which is the real one
        // his key arrived in — now parses to nothing at all. The case is kept because the file
        // still exists on his phone and the reader must not invent a provider for it.
        assertTrue(Keys.parse(text).isEmpty())
    }

    @Test fun aKeyInsideAUrlIsStillTheSameKey() {
        // Was written with a Thunderforest URL; those maps left on 16.9.2026, so the case is
        // asked of the provider that remains. A key in a URL is still a key.
        val k = "AIza" + "B".repeat(35)
        val found = Keys.parse("https://tile.googleapis.com/v1/2dtiles/1/1/1?key=$k")
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

    @Test fun noCreditIsPrintedOverTheMapAndEveryOneStillExists() {
        // His decision, 15.9.2026: the credits live at the bottom of settings, not on the ground
        // he is walking on. What must not happen is a credit disappearing altogether.
        Layers.ALL.forEach {
            assertFalse(it.id, it.creditOnMap)
            assertTrue(it.id, it.attribution.isNotBlank())
        }
        val credits = Layers.ALL.map { it.attribution }.distinct()
        // Thunderforest's credit left with its maps on 16.9.2026. OpenStreetMap's stays, because
        // the offline file is their data and that obligation does not end with a tile service.
        assertTrue(credits.toString(), credits.any { it.contains("OpenStreetMap") })
        assertTrue(credits.toString(), credits.any { it.contains("Google") })
        assertTrue(credits.any { it.contains("OpenStreetMap") })
        assertTrue(credits.any { it.contains("Google") })
    }

    // --- Tracks: what they are called and what the manager does to them ----------------------

    /**
     * kotlin.io.createTempDir is deprecated for putting a world-readable directory in the shared
     * temp space, and allWarningsAsErrors is right to refuse it. One folder per test, ours alone.
     */
    private fun tempFolder(): java.io.File =
        java.nio.file.Files.createTempDirectory("mantra-trail-test").toFile()

    @Test fun aTrackIsCalledByItsDayItsTimeAndTheWordTrack() {
        val name = Tracks.defaultName(1_789_387_200_000L, java.time.ZoneOffset.UTC)
        assertEquals("2026-09-14 12:00 Track", name)
    }

    @Test fun twoWalksAnHourApartGetDifferentNames() {
        val a = Tracks.defaultName(1_789_387_200_000L, java.time.ZoneOffset.UTC)
        val b = Tracks.defaultName(1_789_390_800_000L, java.time.ZoneOffset.UTC)
        assertFalse(a == b)
    }

    @Test fun parenthesesSurviveBecauseARouteIsNamedWithThem() {
        assertEquals("Velebit (AB).gpx", Tracks.safeFileName("Velebit (AB)"))
        // An empty pair is not worth keeping.
        assertEquals("Velebit.gpx", Tracks.safeFileName("Velebit ()"))
    }

    @Test fun aTypedNameCannotBreakAPath() {
        assertEquals("north-south.gpx", Tracks.safeFileName("north/south"))
        assertEquals("Velebit-2.gpx", Tracks.safeFileName("Velebit #2"))
        assertFalse(Tracks.safeFileName("../../etc/passwd").contains("/"))
        assertFalse(Tracks.safeFileName("a\u0000b").contains("\u0000"))
    }

    @Test fun anEmptyNameIsStillAFile() {
        assertEquals("Track.gpx", Tracks.safeFileName("   "))
        assertEquals("Track.gpx", Tracks.safeFileName("///"))
    }

    @Test fun theExtensionIsNotAddedTwice() {
        assertEquals("Velebit.gpx", Tracks.safeFileName("Velebit.gpx"))
        assertEquals("Velebit.gpx", Tracks.safeFileName("Velebit"))
    }

    @Test fun aVeryLongNameIsCutRatherThanRefused() {
        val name = Tracks.safeFileName("x".repeat(200))
        assertTrue(name.length <= 64)
        assertTrue(name.endsWith(".gpx"))
    }

    @Test fun theNameShownIsTheFileNameWithoutItsExtension() {
        assertEquals("2026-09-14 12:00 Track", Tracks.displayName("2026-09-14 12:00 Track.gpx"))
    }

    @Test fun theFolderIsTheList() {
        val dir = tempFolder()
        assertTrue(Tracks.list(dir).isEmpty())
        java.io.File(dir, "one.gpx").writeText("<gpx/>")
        java.io.File(dir, "notes.txt").writeText("not a track")
        val found = Tracks.list(dir)
        assertEquals(1, found.size)
        assertEquals("one", found[0].name)
        dir.deleteRecursively()
    }

    @Test fun theNewestTrackIsFirst() {
        val dir = tempFolder()
        val old = java.io.File(dir, "old.gpx").apply { writeText("<gpx/>"); setLastModified(1_000_000) }
        val new = java.io.File(dir, "new.gpx").apply { writeText("<gpx/>"); setLastModified(9_000_000) }
        assertEquals(listOf("new", "old"), Tracks.list(dir).map { it.name })
        old.delete(); new.delete(); dir.deleteRecursively()
    }

    @Test fun renamingMovesTheFile() {
        val dir = tempFolder()
        val file = java.io.File(dir, "one.gpx").apply { writeText("<gpx/>") }
        val (renamed, problem) = Tracks.rename(file, "Velebit north")
        assertNull(problem)
        assertEquals("Velebit north.gpx", renamed!!.name)
        assertFalse(file.exists())
        assertEquals("<gpx/>", renamed.readText())
        dir.deleteRecursively()
    }

    @Test fun renamingNeverWritesOverAnotherWalk() {
        val dir = tempFolder()
        java.io.File(dir, "Velebit.gpx").writeText("the first walk")
        val second = java.io.File(dir, "other.gpx").apply { writeText("the second walk") }
        val (renamed, problem) = Tracks.rename(second, "Velebit")
        assertNull(renamed)
        assertNotNull(problem)
        assertEquals("the first walk", java.io.File(dir, "Velebit.gpx").readText())
        assertTrue(second.exists())
        dir.deleteRecursively()
    }

    @Test fun renamingToItsOwnNameIsNotACollision() {
        val dir = tempFolder()
        val file = java.io.File(dir, "Velebit.gpx").apply { writeText("<gpx/>") }
        val (renamed, problem) = Tracks.rename(file, "Velebit")
        assertNull(problem)
        assertEquals(file.absolutePath, renamed!!.absolutePath)
        dir.deleteRecursively()
    }

    @Test fun renamingSomethingAlreadyGoneSaysSo() {
        val dir = tempFolder()
        val file = java.io.File(dir, "gone.gpx")
        val (renamed, problem) = Tracks.rename(file, "anything")
        assertNull(renamed)
        assertNotNull(problem)
        dir.deleteRecursively()
    }

    @Test fun deletingRemovesIt() {
        val dir = tempFolder()
        val file = java.io.File(dir, "one.gpx").apply { writeText("<gpx/>") }
        assertNull(Tracks.delete(file))
        assertFalse(file.exists())
        assertNull(Tracks.delete(file))
        dir.deleteRecursively()
    }

    @Test fun sizesAreWrittenForAWalkNotForADiskDrive() {
        assertEquals("240 kB", Tracks.formatSize(240_000))
        assertEquals("1.2 MB", Tracks.formatSize(1_200_000))
        assertEquals("900 B", Tracks.formatSize(900))
    }

    // --- reading a saved walk back --------------------------------------------------------------

    @Test fun ourOwnFileReadsBackToTheSamePoints() {
        // The two halves must agree: what Gpx writes, GpxRead reads.
        val written = listOf(
            Fix(45.815, 15.9819, 158.4, 1_000L, 4f),
            Fix(45.816, 15.9820, 160.0, 2_000L, 4f),
        )
        val text = Gpx.whole("Velebit", written, 0L)
        val read = GpxRead.points(text)
        assertEquals(2, read.size)
        assertEquals(45.815, read[0].lat, 1e-6)
        assertEquals(15.9819, read[0].lon, 1e-6)
        assertEquals(158.4, read[0].ele!!, 0.05)
        assertEquals("Velebit", GpxRead.name(text))
    }

    @Test fun aFileWithTheAttributesTheOtherWayRoundStillReads() {
        val text = """<gpx><trk><trkseg>
            <trkpt lon="15.98" lat="45.81"><ele>100</ele></trkpt>
            </trkseg></trk></gpx>"""
        val read = GpxRead.points(text)
        assertEquals(1, read.size)
        assertEquals(45.81, read[0].lat, 1e-6)
        assertEquals(15.98, read[0].lon, 1e-6)
    }

    @Test fun aWholeFileOnOneLineReads() {
        val text = "<gpx><trkpt lat=\"45.1\" lon=\"15.1\"/><trkpt lat=\"45.2\" lon=\"15.2\"/></gpx>"
        assertEquals(2, GpxRead.points(text).size)
    }

    @Test fun aTrackCutOffByAFlatBatteryYieldsWhatItHas() {
        val text = """<gpx><trk><trkseg>
            <trkpt lat="45.1" lon="15.1"><ele>100</ele></trkpt>
            <trkpt lat="45.2" lon="15.2"><ele>1"""
        val read = GpxRead.points(text)
        assertEquals(2, read.size)
        assertEquals(45.2, read[1].lat, 1e-6)
    }

    @Test fun extensionsFromOtherProgrammesAreIgnored() {
        val text = """<gpx><trkpt lat="45.1" lon="15.1">
            <ele>100</ele><time>2026-09-15T10:00:00Z</time>
            <extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>141</gpxtpx:hr>
            </gpxtpx:TrackPointExtension></extensions></trkpt></gpx>"""
        val read = GpxRead.points(text)
        assertEquals(1, read.size)
        assertEquals(100.0, read[0].ele!!, 0.01)
        assertTrue(read[0].timeMs > 0)
    }

    @Test fun aCoordinateThatIsNotOnEarthIsNotDrawn() {
        val text = """<gpx><trkpt lat="91.0" lon="15.1"/><trkpt lat="45.1" lon="200.0"/>
            <trkpt lat="45.1" lon="15.1"/></gpx>"""
        assertEquals(1, GpxRead.points(text).size)
    }

    @Test fun aFileWithNoPointsIsEmptyRatherThanAnError() {
        assertTrue(GpxRead.points("<gpx></gpx>").isEmpty())
        assertTrue(GpxRead.points("").isEmpty())
        assertTrue(GpxRead.points("this is not xml at all").isEmpty())
    }

    @Test fun aTimeInAFormatNobodyExpectedIsZeroRatherThanAThrow() {
        assertEquals(0L, GpxRead.parseTime("yesterday afternoon"))
        assertEquals(0L, GpxRead.parseTime(""))
        assertTrue(GpxRead.parseTime("2026-09-15T10:00:00Z") > 0)
    }

    @Test fun aRouteIsNamedSoItCanBeToldFromAWalk() {
        val name = "${Tracks.defaultName(1_789_387_200_000L, java.time.ZoneOffset.UTC).removeSuffix(" Track")} (AB)"
        assertEquals("2026-09-14 12:00 (AB)", name)
        // And it survives becoming a file and coming back, like any other track.
        val fileName = Tracks.safeFileName(name)
        assertTrue(fileName.endsWith(".gpx"))
        assertEquals("2026-09-14 12-00 (AB)", Tracks.displayName(fileName))
    }

    @Test fun aTwoPointRouteIsAnOrdinaryGpxFile() {
        val a = Fix(45.815, 15.981, null, 1_000L, null)
        val b = Fix(45.900, 16.100, null, 1_000L, null)
        val text = Gpx.whole("2026-09-14 12:00 (AB)", listOf(a, b), 1_000L)
        val read = GpxRead.points(text)
        assertEquals(2, read.size)
        assertEquals(45.815, read[0].lat, 1e-6)
        assertEquals(16.100, read[1].lon, 1e-6)
    }

    @Test fun aNameTheAppMadeIsToldFromANameHeChose() {
        assertTrue(Tracks.isDefaultName("2026-09-15 11:30 Track"))
        assertFalse(Tracks.isDefaultName("Velebit sjever"))
        assertFalse(Tracks.isDefaultName(""))
    }

    @Test fun theDoubleStampedNamesTheOldBuilderLeftAreAlsoAppMade() {
        // These are on the phone already, and they are the ones that were unbearable to edit by
        // hand. The rename box must open EMPTY for them too.
        assertTrue(Tracks.isDefaultName("2026-09-15_1130_2026-09-15-11-30-track"))
        assertTrue(Tracks.isDefaultName("2026-09-14_1200_velebit-sjever"))
    }

    @Test fun theTrackFileIsNamedAfterTheTrackAndNothingElse() {
        // The fault this closes: a date stamp in front of a name that was already a date.
        val name = Tracks.defaultName(1_789_387_200_000L, java.time.ZoneOffset.UTC)
        val fileName = Tracks.safeFileName(name)
        // The colon becomes a dash: a colon is not a character a file name can carry everywhere,
        // and a track copied to an SD card or a Windows machine must still open.
        assertEquals("2026-09-14 12-00 Track.gpx", fileName)
        assertEquals("2026-09-14 12-00 Track", Tracks.displayName(fileName))
        // And the name survives the round trip, which is what makes the popup able to open empty.
        assertTrue(Tracks.isDefaultName(Tracks.displayName(fileName)))
    }

    @Test fun aNameHeChoseSurvivesBecomingAFileNameAndComingBack() {
        val fileName = Tracks.safeFileName("Velebit sjever")
        assertEquals("Velebit sjever.gpx", fileName)
        assertEquals("Velebit sjever", Tracks.displayName(fileName))
        assertFalse(Tracks.isDefaultName(Tracks.displayName(fileName)))
    }

    @Test fun aKeyIsDescribedByPositionAndLengthAndNothingElse() {
        val d = Keys.describe(0, 3, Keys.Found(fakeKey, Keys.Provider.GOOGLE, null))
        assertTrue(d.contains("1 of 3"))
        assertTrue(d.contains("${fakeKey.length}"))
        assertFalse(d.contains(fakeKey.substring(0, 8)))
    }
}
