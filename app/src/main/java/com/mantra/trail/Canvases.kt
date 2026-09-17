package com.mantra.trail

/**
 * WHICHEVER MAP IS UP (17.9.2026).
 *
 * There are two engines now: VTM draws the file on the phone, Google's SDK draws their vector map.
 * Every key on the map screen was still calling the offline one by name, so on a Google map the
 * minus, plus, centre and point keys did nothing and the screen said "the map view is not up yet"
 * — which was true of the canvas they were asking, and a lie about the map he could see.
 *
 * Everything the keys do goes through here. Neither engine is named at the call site again.
 */
object Canvases {

    private val vtm: VtmCanvas? get() = CanvasHolder.canvas

    private val google: GoogleCanvas? get() = GoogleHolder.canvas

    /** Which one the screen is showing, decided by the layer rather than by what exists. */
    @Volatile
    var googleIsUp: Boolean = false

    private val anyUp: Boolean get() = if (googleIsUp) google != null else vtm != null

    fun zoomIn() {
        if (googleIsUp) google?.zoomBy(1f) else vtm?.zoomIn()
    }

    fun zoomOut() {
        if (googleIsUp) google?.zoomBy(-1f) else vtm?.zoomOut()
    }

    fun centreOn(fix: Fix) {
        if (googleIsUp) google?.centreOn(fix) else vtm?.centreOn(fix)
    }

    /** The middle of the screen, which is where a point is dropped. */
    fun centre(): Pair<Double, Double>? = if (googleIsUp) {
        google?.centre()?.let { it.lat to it.lon }
    } else {
        vtm?.centre()
    }

    fun currentZoom(): Int = if (googleIsUp) {
        google?.zoomLevel() ?: 0
    } else {
        vtm?.currentZoom() ?: 0
    }

    fun setMapRotation(degrees: Float) {
        if (googleIsUp) google?.setMapRotation(degrees) else vtm?.setMapRotation(degrees)
    }

    fun mapRotationDeg(): Float = if (googleIsUp) {
        google?.mapRotationDeg() ?: 0f
    } else {
        vtm?.mapRotationDeg() ?: 0f
    }

    fun setRoutePoints(points: List<Pair<Double, Double>>) {
        google?.setRoutePoints(points)
        vtm?.setRoutePoints(points)
    }

    /** A found way: remembered here, so a change of engine does not lose it. */
    fun drawRoute(points: List<Fix>, colour: Long) {
        Shown.route = points to colour
        if (googleIsUp) google?.showTrack(points, colour) else vtm?.showSavedTrack(points, colour)
    }

    /** A saved walk, the same way. */
    fun drawSavedTrack(points: List<Fix>, colour: Long) {
        Shown.track = points to colour
        if (googleIsUp) google?.showTrack(points, colour) else vtm?.showSavedTrack(points, colour)
    }

    fun drawTrack(points: List<Fix>) {
        // The walk being recorded: VTM draws it in its own colour, Google in the same blue.
        if (googleIsUp) google?.showTrack(points, 0xFF60A5FA) else vtm?.drawTrack(points)
    }

    fun clearSavedTrack() {
        Shown.forget()
        google?.showTrack(emptyList(), 0L)
        vtm?.clearSavedTrack()
    }

    /** The heading light and the position dot: VTM draws its own, Google draws theirs. */
    fun setHeading(heading: Double) {
        vtm?.setHeading(heading)
    }

    fun drawPosition(fix: Fix?) {
        if (googleIsUp) google?.showPosition(fix != null) else vtm?.drawPosition(fix)
    }

    fun emptyHere(): String? = if (googleIsUp) null else vtm?.emptyHere()

    fun diagnose(): String = when {
        googleIsUp -> google?.diagnose() ?: "the Google map is not up yet"
        vtm != null -> vtm?.diagnose() ?: ""
        else -> "the map view is not up yet"
    }

    fun ready(): Boolean = anyUp
}

/**
 * WHAT IS ON THE MAP, kept apart from whichever engine is drawing it (17.9.2026).
 *
 * He asked for this plainly: a line from A to B drawn on the offline map must still be there when
 * he switches to Google's, and the other way round. The routers were already map-independent —
 * BRouter reads the file, Google answers over the wire, and neither cares what is being drawn —
 * but the ANSWER was being handed to one canvas and lost with it.
 */
object Shown {
    /** The way he chose, and its colour. */
    @Volatile
    var route: Pair<List<Fix>, Long>? = null

    /** A saved walk he asked to see. */
    @Volatile
    var track: Pair<List<Fix>, Long>? = null

    fun forget() {
        route = null
        track = null
    }
}
