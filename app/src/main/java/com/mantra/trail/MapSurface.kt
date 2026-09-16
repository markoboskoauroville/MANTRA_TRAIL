package com.mantra.trail

import android.view.View

/**
 * WHAT THE REST OF THE APP ASKS OF A MAP, whichever engine is drawing it.
 *
 * Two engines now (16.9.2026): mapsforge, which rasterises tiles on the CPU, and VTM, the same
 * project's OpenGL renderer reading the same files. Everything above this line — the key row, the
 * route menu, the track manager, the recording — must not know or care which is underneath.
 *
 * The list is short on purpose. It is exactly what Screens.kt and MainActivity.kt already called,
 * no more, so neither engine has to grow a method for the other's sake.
 */
interface MapSurface {

    val view: View

    /** Put a map on the screen. Null when it worked, or the reason it did not. */
    fun show(layer: MapLayer, session: String? = null, key: String? = null): String?

    fun drawTrack(points: List<Fix>)

    fun drawPosition(fix: Fix?)

    fun setHeading(degrees: Double)

    fun showSavedTrack(points: List<Fix>, colour: Long)

    fun clearSavedTrack()

    fun setRoutePoints(points: List<Pair<Double, Double>>)

    fun showRouteOptions(options: List<Routing.Option>)

    fun clearRouteOptions()

    fun centre(): Pair<Double, Double>

    fun centreOn(fix: Fix)

    fun zoomIn()

    fun zoomOut()

    fun currentZoom(): Int

    fun mapRotationDeg(): Float

    fun setMapRotation(degrees: Float)

    /** Keep where we are looking, for next time. */
    fun remember()

    /** What the engine can say about itself when the map looks wrong. */
    fun diagnose(): String

    /** Whether the map has nothing to draw here, and why. Null when it has. */
    fun emptyHere(): String?

    fun resume()

    fun pause()

    fun destroy()
}
