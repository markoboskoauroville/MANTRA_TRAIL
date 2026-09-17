package com.mantra.trail

import android.content.Context
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions

/**
 * GOOGLE'S OWN RENDERER (17.9.2026).
 *
 * Baba: *"we want vector maps, the same protocol and same way the Google Maps original app is
 * using. Same speed, same quality."* This is that — the Maps SDK for Android, the component their
 * app is built on. Not pictures of a map fetched as tiles: the map itself, drawn on the phone from
 * vector data, with their labels turning as it turns.
 *
 * IT IS THE ONLINE HALF AND ONLY THAT. It needs a signal and a key, and in Velebit it will show
 * nothing, which is why VTM and the offline file remain exactly as they are and this sits beside
 * them rather than over them.
 *
 * WHAT IS DRAWN ON IT is what is drawn on the other one: his position, the route, the lettered
 * points. Google's marks for the first, ours for the rest, so a point looks the same on both maps.
 */
class GoogleCanvas(private val context: Context, private val store: Store) {

    val view: MapView = MapView(context)

    private var map: GoogleMap? = null

    // WHAT WAS ASKED FOR BEFORE THE MAP EXISTED (17.9.2026).
    //
    // getMapAsync hands the GoogleMap over some frames after the view is made, and every method
    // here began "val ready = map ?: return" — so a route drawn, a point placed or a centring
    // asked for in those frames was thrown away in silence. That is both bugs he reported: the
    // centre key did nothing on Google's map, and a way found on the offline file never appeared
    // when he switched. Nothing is dropped now; it waits here and is applied the moment the map
    // arrives.
    private var pendingCentre: Fix? = null
    private var pendingPoints: List<Pair<Double, Double>>? = null
    private var pendingTrack: Pair<List<Fix>, Long>? = null
    private var pendingPosition: Boolean? = null
    private var pendingBearing: Float? = null
    private var routeLine: Polyline? = null
    private var trackLine: Polyline? = null
    private val marks = ArrayList<Marker>()
    private var wanted: MapLayer.GoogleView = MapLayer.GoogleView.NORMAL

    /** Told when the map is real, so the screen can draw what belongs on it. */
    var onReady: (() -> Unit)? = null

    fun onCreate() {
        view.onCreate(null)
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.apply {
                // The app has its own keys for these, in one row, where his thumb already is.
                isZoomControlsEnabled = false
                isMyLocationButtonEnabled = false
                isMapToolbarEnabled = false
                isCompassEnabled = false
                isRotateGesturesEnabled = true
                isTiltGesturesEnabled = false
            }
            apply(wanted)
            ready.moveCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.builder()
                        .target(LatLng(store.lastLat, store.lastLon))
                        .zoom(store.lastZoom.toFloat())
                        .build()
                )
            )
            // Everything he asked for while the map was on its way.
            pendingPoints?.let { setRoutePoints(it) }
            pendingTrack?.let { showTrack(it.first, it.second) }
            pendingPosition?.let { showPosition(it) }
            pendingBearing?.let { setMapRotation(it) }
            pendingCentre?.let { centreOn(it) }
            pendingPoints = null
            pendingTrack = null
            pendingPosition = null
            pendingBearing = null
            pendingCentre = null
            onReady?.invoke()

            ready.setOnCameraIdleListener {
                val at = ready.cameraPosition
                store.lastLat = at.target.latitude
                store.lastLon = at.target.longitude
                store.lastZoom = at.zoom.toInt()
            }
        }
    }

    fun show(layer: MapLayer): String? {
        wanted = layer.googleView ?: MapLayer.GoogleView.NORMAL
        apply(wanted)
        return null
    }

    private fun apply(view: MapLayer.GoogleView) {
        val ready = map ?: return
        ready.mapType = when (view.mapType) {
            "satellite" -> if (view.overlayRoads) GoogleMap.MAP_TYPE_HYBRID else GoogleMap.MAP_TYPE_SATELLITE
            "terrain" -> GoogleMap.MAP_TYPE_TERRAIN
            else -> GoogleMap.MAP_TYPE_NORMAL
        }
    }

    /** Where he is, drawn by Google's own blue dot, which is the one he knows. */
    fun showPosition(allowed: Boolean) {
        val ready = map
        if (ready == null) {
            pendingPosition = allowed
            return
        }
        runCatching { ready.isMyLocationEnabled = allowed }
    }

    fun centreOn(fix: Fix) {
        val ready = map
        if (ready == null) {
            pendingCentre = fix
            return
        }
        ready.animateCamera(CameraUpdateFactory.newLatLng(LatLng(fix.lat, fix.lon)))
    }

    fun zoomBy(steps: Float) {
        map?.animateCamera(CameraUpdateFactory.zoomBy(steps))
    }

    fun setMapRotation(degrees: Float) {
        val ready = map
        if (ready == null) {
            pendingBearing = degrees
            return
        }
        val at = ready.cameraPosition
        ready.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.builder(at).bearing(degrees).build()
            )
        )
    }

    fun zoomLevel(): Int = map?.cameraPosition?.zoom?.toInt() ?: 0

    fun mapRotationDeg(): Float = map?.cameraPosition?.bearing ?: 0f

    fun centre(): Fix? = map?.cameraPosition?.target?.let { Fix(it.latitude, it.longitude, null, 0L, null) }

    /** The lettered points, drawn as the same red crosshair the other map uses. */
    fun setRoutePoints(points: List<Pair<Double, Double>>) {
        val ready = map
        if (ready == null) {
            pendingPoints = points
            return
        }
        marks.forEach { it.remove() }
        marks.clear()
        points.forEachIndexed { index, at ->
            val letter = Route.letterFor(index)
            val mark = ready.addMarker(
                MarkerOptions()
                    .position(LatLng(at.first, at.second))
                    .icon(BitmapDescriptorFactory.fromBitmap(Marks.routePoint(context, letter)))
                    .anchor(0.5f, 0.5f)
                    .title(letter)
            )
            if (mark != null) marks.add(mark)
        }
        routeLine?.remove()
        routeLine = if (points.size >= 2) {
            ready.addPolyline(
                PolylineOptions()
                    .addAll(points.map { LatLng(it.first, it.second) })
                    .color(0x66_60_A5_FA.toInt())
                    .width(6f)
            )
        } else {
            null
        }
    }

    /** A found way or a saved walk, in its own colour. */
    fun showTrack(points: List<Fix>, colour: Long) {
        val ready = map
        if (ready == null) {
            pendingTrack = points to colour
            return
        }
        trackLine?.remove()
        trackLine = if (points.size >= 2) {
            ready.addPolyline(
                PolylineOptions()
                    .addAll(points.map { LatLng(it.lat, it.lon) })
                    .color(colour.toInt())
                    .width(8f)
            )
        } else {
            null
        }
    }

    fun onResume() = view.onResume()

    fun onPause() = view.onPause()

    fun onDestroy() = view.onDestroy()

    fun diagnose(): String {
        val at = map?.cameraPosition
        return "Google SDK (vector) · z${at?.zoom?.toInt() ?: 0} · " +
            "${Geo.formatLat(at?.target?.latitude ?: 0.0)} ${Geo.formatLon(at?.target?.longitude ?: 0.0)}"
    }
}

/** Where the Google canvas is reachable from, as CanvasHolder is for the other one. */
object GoogleHolder {
    @Volatile
    var canvas: GoogleCanvas? = null
}
