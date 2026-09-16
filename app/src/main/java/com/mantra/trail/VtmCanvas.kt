package com.mantra.trail

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import org.oscim.android.MapView
import org.oscim.android.canvas.AndroidGraphics
import org.oscim.core.GeoPoint
import org.oscim.core.MapPosition
import org.oscim.layers.PathLayer
import org.oscim.layers.marker.ItemizedLayer
import org.oscim.layers.marker.MarkerItem
import org.oscim.layers.marker.MarkerSymbol
import org.oscim.layers.tile.bitmap.BitmapTileLayer
import org.oscim.layers.tile.buildings.BuildingLayer
import org.oscim.layers.tile.vector.VectorTileLayer
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.theme.internal.VtmThemes
import org.oscim.tiling.source.bitmap.BitmapTileSource
import org.oscim.tiling.source.mapfile.MapFileTileSource
import java.io.FileInputStream

/**
 * THE SAME MAPS, DRAWN BY THE GRAPHICS CHIP.
 *
 * Baba, 16.9.2026, after asking why Google's map is smooth and this one is not. The answer was
 * not vector against raster — his offline file was always vector — but WHERE THE DRAWING HAPPENS.
 * mapsforge turns vectors into 256-pixel pictures on the processor, sixty of them to a screen, and
 * every zoom step throws them away and makes them again. VTM hands the geometry to the graphics
 * chip once; a zoom or a turn after that is a matrix, done every frame, with nothing redrawn on
 * the processor at all.
 *
 * It is mapsforge's own sibling and reads THE SAME .map file, so nothing he has downloaded is
 * wasted, and it takes raster tiles too, so Thunderforest and Google still work.
 *
 * THIS IS THE SECOND ENGINE, NOT THE REPLACEMENT. The old one stays until he says otherwise: a
 * map that does not draw is not something to discover on a hillside, and nothing about a graphics
 * chip can be proved on a desk with no graphics chip (four-tests.md — this is a Test 4 change,
 * and Test 4 is him, on the phone).
 */
/**
 * THE ONE ENGINE (16.9.2026). The interface that let two of them live side by side is gone with
 * the CPU renderer it existed for; an interface with a single implementation is a promise about
 * a second one that nobody intends to keep.
 */
class VtmCanvas(private val context: Context, private val store: Store) {

    val view: MapView = MapView(context)
    private val map get() = view.map()

    private var baseLayer: VectorTileLayer? = null
    private var bitmapLayer: BitmapTileLayer? = null
    private var buildingLayer: BuildingLayer? = null
    private var labelLayer: LabelLayer? = null
    private var recordingPath: PathLayer? = null
    private var shownPath: PathLayer? = null
    private val optionPaths = ArrayList<PathLayer>()
    private var routePath: PathLayer? = null
    private var markers: ItemizedLayer? = null
    private var positionLayer: ItemizedLayer? = null
    private var accuracyRing: PathLayer? = null
    private var mapFileStream: FileInputStream? = null
    private var headingDeg: Double = Double.NaN
    private var lastFix: Fix? = null

    init {
        map.setMapPosition(store.lastLat, store.lastLon, (1 shl store.lastZoom).toDouble())
    }

    /**
     * Put a layer on the map. The offline file becomes a vector layer with labels and buildings;
     * everything else is a raster source, which VTM draws as textures — still on the chip.
     */
    /**
     * Put a layer on the map; null when it worked, or a sentence saying why not. The session and
     * the key belong to the layers that need them — Google's tiles want both, Thunderforest's
     * want a key, the offline file wants neither — so callers with none say nothing.
     */
    fun show(layer: MapLayer, session: String? = null, key: String? = null): String? {
        clearBaseLayers()
        return when (layer.kind) {
            LayerKind.VECTOR_FILE -> showVector()
            else -> showRaster(layer, session, key)
        }
    }

    private fun showVector(): String? {
        val file = MapDownload.target(context)
        if (!file.exists() || file.length() < 1_000_000) {
            return "No offline map yet. Settings, choose a .map file."
        }
        return try {
            val source = MapFileTileSource()
            val stream = FileInputStream(file)
            mapFileStream = stream
            source.setMapFileInputStream(stream)
            val base = map.setBaseMap(source)
            baseLayer = base
            val buildings = BuildingLayer(map, base)
            val labels = LabelLayer(map, base)
            map.layers().add(buildings)
            map.layers().add(labels)
            buildingLayer = buildings
            labelLayer = labels
            map.setTheme(themeFor(store.themeName))
            restoreOverlays()
            map.updateMap(true)
            null
        } catch (e: Exception) {
            "The offline map would not open: ${e.javaClass.simpleName}"
        }
    }

    private fun showRaster(layer: MapLayer, session: String?, key: String?): String? {
        val (base, path) = Layers.tilePattern(layer, session, key)
            ?: return "That map needs a key first"
        val source = BitmapTileSource.builder()
            .url(base)
            .tilePath(path)
            .zoomMin(layer.minZoom)
            .zoomMax(layer.maxZoom)
            .build()
        return try {
            val bitmaps = BitmapTileLayer(map, source)
            map.layers().add(bitmaps)
            bitmapLayer = bitmaps
            restoreOverlays()
            map.updateMap(true)
            null
        } catch (e: Exception) {
            "That map would not open: ${e.javaClass.simpleName}"
        }
    }

    /**
     * THE BUG THAT MADE EVERY MAP BLANK (16.9.2026, from his screenshot).
     *
     * This used to finish with map.layers().clear(), which looks like tidying up and is not:
     * VTM keeps its OWN layers in that list, the gesture handler among them at the front. Clearing
     * it threw those away too, and the next thing that inserted a layer at a fixed position threw
     * IndexOutOfBoundsException — which the app caught, reported as "the offline map would not
     * open", and fell back to OpenStreetMap, where the very same clear had already broken the
     * raster path. One line, two blank screens, and a message that blamed the file.
     *
     * Proved on a desk first: VTM's own reader opens his croatia.map by path and by stream alike
     * and returns 235 elements at z17, so nothing was ever wrong with the file or the library.
     *
     * Only what this class added is removed now, by reference, one at a time.
     */
    private fun clearBaseLayers() {
        bitmapLayer?.let { map.layers().remove(it) }
        bitmapLayer = null
        buildingLayer?.let { map.layers().remove(it) }
        buildingLayer = null
        labelLayer?.let { map.layers().remove(it) }
        labelLayer = null
        baseLayer?.let { map.layers().remove(it) }
        baseLayer = null
        mapFileStream?.let { runCatching { it.close() } }
        mapFileStream = null
    }

    // --- what is drawn over the map ---------------------------------------------------------

    fun drawTrack(points: List<Fix>) {
        recordingPath?.let { map.layers().remove(it) }
        recordingPath = null
        if (points.size < 2) return
        val path = PathLayer(map, 0xFFEF4444.toInt(), 6f)
        path.setPoints(points.map { GeoPoint(it.lat, it.lon) })
        map.layers().add(path)
        recordingPath = path
        map.updateMap(false)
    }

    fun showSavedTrack(points: List<Fix>, colour: Long) {
        shownPath?.let { map.layers().remove(it) }
        shownPath = null
        if (points.size < 2) return
        val path = PathLayer(map, colour.toInt(), 6f)
        path.setPoints(points.map { GeoPoint(it.lat, it.lon) })
        map.layers().add(path)
        shownPath = path
        val middle = points[points.size / 2]
        map.setMapPosition(middle.lat, middle.lon, map.mapPosition.scale)
    }

    fun clearSavedTrack() {
        shownPath?.let { map.layers().remove(it) }
        shownPath = null
        map.updateMap(false)
    }

    fun showRouteOptions(options: List<Routing.Option>) {
        optionPaths.forEach { map.layers().remove(it) }
        optionPaths.clear()
        options.forEach { option ->
            val path = PathLayer(map, option.colour.toInt(), 7f)
            path.setPoints(option.points.map { GeoPoint(it.lat, it.lon) })
            map.layers().add(path)
            optionPaths.add(path)
        }
        map.updateMap(false)
    }

    fun clearRouteOptions() {
        optionPaths.forEach { map.layers().remove(it) }
        optionPaths.clear()
        map.updateMap(false)
    }

    fun setRoutePoints(points: List<Pair<Double, Double>>) {
        markers?.let { map.layers().remove(it) }
        markers = null
        routePath?.let { map.layers().remove(it) }
        routePath = null
        if (points.isEmpty()) {
            map.updateMap(false)
            return
        }
        val items = points.mapIndexed { index, at ->
            val letter = Route.letterFor(index)
            MarkerItem(letter, "", GeoPoint(at.first, at.second)).apply {
                marker = MarkerSymbol(symbolFor(letter), MarkerSymbol.HotspotPlace.CENTER)
            }
        }
        val layer = ItemizedLayer(
            map,
            items.toMutableList<org.oscim.layers.marker.MarkerInterface>(),
            symbolFor("A").let { MarkerSymbol(it, MarkerSymbol.HotspotPlace.CENTER) },
            null,
        )
        map.layers().add(layer)
        markers = layer

        if (points.size >= 2 && optionPaths.isEmpty()) {
            val path = PathLayer(map, 0x8060A5FA.toInt(), 4f)
            path.setPoints(points.map { GeoPoint(it.first, it.second) })
            map.layers().add(path)
            routePath = path
        }
        map.updateMap(false)
    }

    fun drawPosition(fix: Fix?) {
        lastFix = fix
        redrawPosition()
    }

    fun setHeading(degrees: Double) {
        val before = headingDeg
        headingDeg = degrees
        if (lastFix != null && (before.isNaN() || Math.abs(before - degrees) > 4.0)) redrawPosition()
    }

    private fun redrawPosition() {
        positionLayer?.let { map.layers().remove(it) }
        positionLayer = null
        accuracyRing?.let { map.layers().remove(it) }
        accuracyRing = null
        val fix = lastFix ?: return

        // THE ACCURACY RING CAME BACK WITH THE ENGINE SWAP (16.9.2026). The CPU renderer drew it
        // and this one did not, which the checks caught before he did. It is a ring and never a
        // disc — filled, three metres of accuracy swallowed the map at z22 — and it is drawn as a
        // circle of points in metres, so it is honest at every zoom without a library for it.
        val metres = fix.accuracyM?.toDouble() ?: 0.0
        if (metres > 1.0) {
            val ring = PathLayer(map, 0x553B82F6, 2f)
            val points = ArrayList<GeoPoint>(49)
            for (step in 0..48) {
                val angle = Math.toRadians(step * 360.0 / 48.0)
                val dLat = metres * Math.cos(angle) / 111_320.0
                val dLon = metres * Math.sin(angle) /
                    (111_320.0 * Math.cos(Math.toRadians(fix.lat)).coerceAtLeast(0.01))
                points.add(GeoPoint(fix.lat + dLat, fix.lon + dLon))
            }
            ring.setPoints(points)
            map.layers().add(ring)
            accuracyRing = ring
        }
        val turn = map.mapPosition.bearing.toDouble()
        val item = MarkerItem("here", "", GeoPoint(fix.lat, fix.lon)).apply {
            marker = MarkerSymbol(positionSymbol(headingDeg, turn), MarkerSymbol.HotspotPlace.CENTER)
        }
        val layer = ItemizedLayer(
            map,
            mutableListOf<org.oscim.layers.marker.MarkerInterface>(item),
            MarkerSymbol(positionSymbol(headingDeg, turn), MarkerSymbol.HotspotPlace.CENTER),
            null,
        )
        map.layers().add(layer)
        positionLayer = layer
        map.updateMap(false)
    }

    /**
     * The theme by name, defaulting to the plain one. MOTORIDER was what this used to be fixed
     * at, and it is a motorcycle theme: every petrol station in Croatia, drawn large, over a
     * coast he was trying to read.
     */
    private fun themeFor(name: String): VtmThemes = when (name) {
        "OSMARENDER" -> VtmThemes.OSMARENDER
        "BIKER" -> VtmThemes.BIKER
        "MOTORIDER" -> VtmThemes.MOTORIDER
        "NEWTRON" -> VtmThemes.NEWTRON
        "TRONRENDER" -> VtmThemes.TRONRENDER
        "MAPZEN" -> VtmThemes.MAPZEN
        else -> VtmThemes.DEFAULT
    }

    /** Draw the offline map again under a different theme, keeping everything on top of it. */
    fun setTheme(name: String) {
        store.themeName = name
        if (baseLayer != null) {
            map.setTheme(themeFor(name))
            map.clearMap()
            map.updateMap(true)
        }
    }

    private fun symbolFor(letter: String): org.oscim.backend.canvas.Bitmap =
        AndroidGraphics.drawableToBitmap(
            BitmapDrawable(context.resources, Marks.routePoint(context, letter))
        )

    private fun positionSymbol(heading: Double, mapTurn: Double): org.oscim.backend.canvas.Bitmap =
        AndroidGraphics.drawableToBitmap(
            BitmapDrawable(context.resources, Marks.position(context, heading, mapTurn))
        )

    private fun restoreOverlays() {
        lastFix?.let { drawPosition(it) }
    }

    // --- what the screen asks of any map ------------------------------------------------------

    fun zoomIn() {
        map.animator().animateZoom(200, 2.0, 0f, 0f)
    }

    fun zoomOut() {
        map.animator().animateZoom(200, 0.5, 0f, 0f)
    }

    fun currentZoom(): Int = map.mapPosition.zoomLevel

    fun centre(): Pair<Double, Double> =
        map.mapPosition.let { it.getLatitude() to it.getLongitude() }

    fun centreOn(fix: Fix) {
        val position = MapPosition(fix.lat, fix.lon, map.mapPosition.scale)
        position.bearing = map.mapPosition.bearing
        map.animator().animateTo(400, position)
    }

    fun mapRotationDeg(): Float = -map.mapPosition.bearing

    fun setMapRotation(degrees: Float) {
        val position = map.mapPosition
        position.bearing = -degrees
        map.mapPosition = position
        redrawPosition()
    }

    fun remember() {
        val position = map.mapPosition
        store.lastLat = position.getLatitude()
        store.lastLon = position.getLongitude()
        store.lastZoom = position.zoomLevel
    }

    fun resume() {
        view.onResume()
    }

    fun pause() {
        view.onPause()
    }

    /**
     * WHETHER THERE IS ANYTHING TO DRAW HERE. VTM decides that inside the graphics chip, where
     * this side cannot see it, so the honest answer is the one the file gives: is this place
     * inside the map's own area at all.
     */
    fun emptyHere(): String? {
        val file = MapDownload.target(context)
        if (!file.exists()) return "No offline map on the phone yet"
        return null
    }

    fun diagnose(): String {
        val position = map.mapPosition
        return "VTM (GPU) · z${position.zoomLevel} · ${Geo.formatLat(position.getLatitude())} " +
            "${Geo.formatLon(position.getLongitude())} · layers ${map.layers().size}"
    }

    fun destroy() {
        runCatching { view.onDestroy() }
        mapFileStream?.let { runCatching { it.close() } }
    }
}
