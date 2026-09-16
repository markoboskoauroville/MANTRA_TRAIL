package com.mantra.trail

import android.view.View

/**
 * WHAT THE REST OF THE APP ASKS OF A MAP, and nothing about how it is drawn.
 *
 * There are two engines now (16.9.2026). mapsforge rasterises vector data on the CPU into
 * bitmaps; VTM hands the same vector data to the GPU and redraws it every frame, which is why
 * Google's map turns and zooms the way it does and this one did not. Both read the same .map
 * file, so the choice costs no data and no download.
 *
 * The interface exists so the screen never knows which it is talking to. It is deliberately small:
 * everything on it is something the map screen or the activity already needed before VTM arrived.
 */
interface MapSurface {

    /** The Android view to put on the screen. */
    val view: View

    /** Put a layer on the map; null when it worked, or a sentence saying why not. */
    fun show(layer: MapLayer, session: String?, key: String?): String?

    fun zoomIn()

    fun zoomOut()

    fun currentZoom(): Int

    /** Where the middle of the screen is: latitude to longitude. */
    fun centre(): Pair<Double, Double>

    fun centreOn(fix: Fix)

    /** How far the map has been turned from north, in degrees. */
    fun mapRotationDeg(): Float

    fun setMapRotation(degrees: Float)

    /** The line being recorded now. */
    fun drawTrack(points: List<Fix>)

    /** A walk read back from a file, in the colour he chose. */
    fun showSavedTrack(points: List<Fix>, colour: Long)

    fun clearSavedTrack()

    /** The route points A, B, C… and the straight line through them. */
    fun setRoutePoints(points: List<Pair<Double, Double>>)

    /** The ways the router found, each in its own colour. */
    fun showRouteOptions(options: List<Routing.Option>)

    fun clearRouteOptions()

    /** Where he is, and the light in front showing which way the phone points. */
    fun drawPosition(fix: Fix?)

    fun setHeading(degrees: Double)

    /** Null when there is something to draw here, or a sentence about why the screen is bare. */
    fun emptyHere(): String?

    /** A sentence about what the map is doing, for when it is doing nothing. */
    fun diagnose(): String

    fun remember()

    fun pause()

    fun resume()

    fun destroy()
}

/** Which engine draws the map. The names are what the settings row shows. */
enum class Engine(val label: String) {
    MAPSFORGE("mapsforge (tiles)"),
    VTM("VTM (OpenGL)"),
}
