package com.mantra.trail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColour
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.net.Uri
import org.oscim.android.MapView
import org.oscim.android.canvas.AndroidBitmap
import org.oscim.backend.canvas.Color
import org.oscim.core.GeoPoint
import org.oscim.core.MapPosition
import org.oscim.layers.marker.ItemizedLayer
import org.oscim.layers.marker.MarkerItem
import org.oscim.layers.marker.MarkerSymbol
import org.oscim.layers.tile.bitmap.BitmapTileLayer
import org.oscim.layers.tile.buildings.BuildingLayer
import org.oscim.layers.tile.vector.VectorTileLayer
import org.oscim.layers.tile.vector.labeling.LabelLayer
import org.oscim.layers.PathLayer
import org.oscim.theme.internal.VtmThemes
import org.oscim.tiling.source.bitmap.BitmapTileSource
import org.oscim.tiling.source.mapfile.MapFileTileSource
import java.io.FileInputStream

/**
 * THE SAME MAPS, DRAWN BY THE GPU.
 *
 * Baba, 16.9.2026: *"Google Maps works fantastic on my phone... why is this slow?"* Because
 * mapsforge rasterises tiles on the CPU — every zoom step re-renders sixty bitmaps, and until
 * they arrive you are looking at the old ones stretched. VTM is mapsforge's own OpenGL renderer:
 * the geometry goes to the GPU once and a zoom or a turn is a matrix per frame. The map file is
 * the same file. The render theme is VTM's own.
 *
 * THIS IS THE SECOND ENGINE, NOT A REPLACEMENT. The mapsforge canvas stays exactly as it is and
 * the setting chooses between them, so a map that will not draw on a hillside is one press away
 * from the one that did yesterday. When this has been walked with for a while, one of the two
 * will be deleted rather than both being carried for ever.
 *
 * WHAT IS NOT TESTED: everything. Nothing about a GPU can be proved on the desk this was written
 * on — no OpenGL context, no phone. The first real test is his.
 */
class VtmCanvas(private val context: Context, private val store: Store) : MapSurface {

    override val view: MapView = MapView(context)

    private var baseLayer: VectorTileLayer? = null
    private var bitmapLayer: BitmapTileLayer? = null
    private var trackPath: PathLayer? = null
    private var shownPath: PathLayer? = null
    private var routePath: PathLayer? = null
    private val optionPaths = ArrayList<PathLayer>()
    private var markerLayer: ItemizedLayer? = null
    private var positionLayer: ItemizedLayer? = null
    private var headingDeg: Double = Double.NaN
    private var lastFix: Fix? = null

    private val map get() = view.map()

    init {
        map.mapPosition = MapPosition(store.lastLat, store.lastLon, (1 shl store.lastZoom).toDouble())
    }

    /** Put a map on the screen. Returns null when it worked, or the reason it did not. */
    override fun show(layer: MapLayer, session: String?, key: String?): String? {
        clearBase()
        return when (layer.kind) {
            LayerKind.VECTOR_FILE -> showOffline()
            LayerKind.RASTER_XYZ -> showRaster(layer)
            // Google's tiles need a session and a key on every request, which this engine's tile
            // source cannot carry. They stay on the other engine and the settings say so.
            else -> "That map is drawn by the other engine. Settings, map engine."
        }
    }

    private fun showOffline(): String? {
        val uri = store.mapFileUri
        val file = MapDownload.target(context)
        return try {
            val source = MapFileTileSource()
            val opened = when {
                uri != null -> {
                    val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")
                        ?: return "That map file cannot be opened any more. Choose it again."
                    source.setMapFileInputStream(FileInputStream(descriptor.fileDescriptor))
                    true
                }

                file.exists() && file.length() > 1_000_000 -> source.setMapFile(file.absolutePath)
                else -> return "No offline map yet. Settings, choose a .map file."
            }
            if (!opened) return "mapsforge would not open that file"
            val base = map.setBaseMap(source)
            map.setTheme(VtmThemes.DEFAULT)
            map.layers().add(BuildingLayer(map, base))
            map.layers().add(LabelLayer(map, base))
            baseLayer = base
            restoreOverlays()
            map.updateMap(true)
            null
        } catch (e: Exception) {
            "The map could not be opened: ${e.javaClass.simpleName}"
        }
    }

    private fun showRaster(layer: MapLayer): String? {
        val key = layer.provider?.let { store.key(it) }
        if (layer.provider != null && key.isNullOrEmpty()) return "${layer.name} needs a key first."
        return try {
            // The template is the one every layer already carries; VTM wants it split into a host
            // and a path, with its own placeholders.
            val url = Layers.tileUrl(layer, 0, 0, 0, null, key)
                ?: return "That map has no address"
            val host = url.substringBefore("/0/0/0")
            val source = BitmapTileSource(host, "/{Z}/{X}/{Y}.png", layer.minZoom, layer.maxZoom)
            val tiles = BitmapTileLayer(map, source)
            map.layers().add(tiles)
            bitmapLayer = tiles
            restoreOverlays()
            map.updateMap(true)
            null
        } catch (e: Exception) {
            "That map could not be opened: ${e.javaClass.simpleName}"
        }
    }

    private fun clearBase() {
        bitmapLayer?.let { map.layers().remove(it) }
        bitmapLayer = null
        baseLayer = null
        map.layers().clear()
    }

    // --- what is drawn over the map -----------------------------------------------------------

    override fun drawTrack(points: List<Fix>) {
        trackPath?.let { map.layers().remove(it) }
        trackPath = null
        if (points.size < 2) return
        val path = PathLayer(map, Color.get(232, 166, 75), 6f)
        points.forEach { path.addPoint(GeoPoint(it.lat, it.lon)) }
        map.layers().add(path)
        trackPath = path
        map.updateMap(true)
    }

    override fun showSavedTrack(points: List<Fix>, colour: Long) {
        shownPath?.let { map.layers().remove(it) }
        shownPath = null
        if (points.size < 2) return
        val path = PathLayer(map, colour.toInt(), 6f)
        points.forEach { path.addPoint(GeoPoint(it.lat, it.lon)) }
        map.layers().add(path)
        shownPath = path
        val middle = points[points.size / 2]
        map.setMapPosition(middle.lat, middle.lon, map.mapPosition.scale)
    }

    override fun clearSavedTrack() {
        shownPath?.let { map.layers().remove(it) }
        shownPath = null
        map.updateMap(true)
    }

    override fun setRoutePoints(points: List<Pair<Double, Double>>) {
        markerLayer?.let { map.layers().remove(it) }
        markerLayer = null
        routePath?.let { map.layers().remove(it) }
        routePath = null
        if (points.isEmpty()) {
            map.updateMap(true)
            return
        }
        val layer = ItemizedLayer(
            map,
            MarkerSymbol(AndroidBitmap(letterBitmap("A")), MarkerSymbol.HotspotPlace.CENTER),
        )
        points.forEachIndexed { index, at ->
            val letter = Route.letterFor(index)
            val item = MarkerItem(letter, "", GeoPoint(at.first, at.second))
            item.marker = MarkerSymbol(
                AndroidBitmap(letterBitmap(letter)),
                MarkerSymbol.HotspotPlace.CENTER,
            )
            layer.addItem(item)
        }
        map.layers().add(layer)
        markerLayer = layer

        if (points.size > 1 && optionPaths.isEmpty()) {
            val path = PathLayer(map, Color.get(96, 165, 250), 4f)
            points.forEach { path.addPoint(GeoPoint(it.first, it.second)) }
            map.layers().add(path)
            routePath = path
        }
        map.updateMap(true)
    }

    override fun showRouteOptions(options: List<Routing.Option>) {
        optionPaths.forEach { map.layers().remove(it) }
        optionPaths.clear()
        options.forEach { option ->
            val path = PathLayer(map, option.colour.toInt(), 7f)
            option.points.forEach { path.addPoint(GeoPoint(it.lat, it.lon)) }
            map.layers().add(path)
            optionPaths.add(path)
        }
        map.updateMap(true)
    }

    override fun clearRouteOptions() {
        optionPaths.forEach { map.layers().remove(it) }
        optionPaths.clear()
        map.updateMap(true)
    }

    override fun drawPosition(fix: Fix?) {
        lastFix = fix
        redrawPosition()
    }

    override fun setHeading(degrees: Double) {
        val moved = Math.abs(degrees - headingDeg) > 4.0 || headingDeg.isNaN()
        headingDeg = degrees
        if (moved && lastFix != null) redrawPosition()
    }

    private fun redrawPosition() {
        positionLayer?.let { map.layers().remove(it) }
        positionLayer = null
        val fix = lastFix ?: return
        val bitmap = AndroidBitmap(positionBitmap(headingDeg))
        val symbol = MarkerSymbol(bitmap, MarkerSymbol.HotspotPlace.CENTER)
        val layer = ItemizedLayer(map, symbol)
        layer.addItem(MarkerItem("here", "", GeoPoint(fix.lat, fix.lon)))
        map.layers().add(layer)
        positionLayer = layer
        map.updateMap(true)
    }

    private fun restoreOverlays() {
        lastFix?.let { drawPosition(it) }
    }

    // --- the bitmaps, drawn rather than shipped -------------------------------------------------

    private fun positionBitmap(heading: Double): Bitmap {
        val scale = context.resources.displayMetrics.density
        val side = (72 * scale).toInt()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val c = side / 2f
        val dot = 7f * scale
        if (!heading.isNaN()) {
            val reach = c - 1f
            val cone = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    c,
                    c,
                    reach,
                    intArrayOf(
                        AndroidColour.argb(150, 59, 130, 246),
                        AndroidColour.argb(70, 59, 130, 246),
                        AndroidColour.argb(0, 59, 130, 246),
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    android.graphics.Shader.TileMode.CLAMP,
                )
            }
            canvas.drawArc(
                android.graphics.RectF(c - reach, c - reach, c + reach, c + reach),
                (heading - 90.0 - 31.0).toFloat(),
                62f,
                true,
                cone,
            )
        }
        canvas.drawCircle(
            c,
            c,
            dot + 2f * scale,
            AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { color = AndroidColour.WHITE },
        )
        canvas.drawCircle(
            c,
            c,
            dot,
            AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                color = AndroidColour.argb(255, 59, 130, 246)
            },
        )
        return bitmap
    }

    private fun letterBitmap(letter: String): Bitmap {
        val scale = context.resources.displayMetrics.density
        val side = (44 * scale).toInt()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        val c = side / 2f
        val arm = side / 2f - 2 * scale
        val gap = arm * 0.36f

        fun cross(colour: Int, width: Float) {
            val p = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                this.color = colour
                strokeWidth = width
                style = AndroidPaint.Style.STROKE
            }
            canvas.drawLine(c - arm, c, c - gap, c, p)
            canvas.drawLine(c + gap, c, c + arm, c, p)
            canvas.drawLine(c, c - arm, c, c - gap, p)
            canvas.drawLine(c, c + gap, c, c + arm, p)
            canvas.drawCircle(c, c, gap, p)
        }
        cross(AndroidColour.argb(200, 11, 13, 16), 3f * scale)
        cross(AndroidColour.argb(255, 96, 165, 250), 1.4f * scale)
        val text = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColour.argb(255, 96, 165, 250)
            textSize = 13f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(3f * scale, 0f, 0f, AndroidColour.argb(220, 11, 13, 16))
        }
        canvas.drawText(letter, c + gap + 2 * scale, c - gap, text)
        return bitmap
    }

    // --- moving the map ------------------------------------------------------------------------

    override fun zoomIn() { map.animator().animateZoom(200, 2.0, 0f, 0f)
    }

    override fun zoomOut() { map.animator().animateZoom(200, 0.5, 0f, 0f)
    }

    override fun currentZoom(): Int = map.mapPosition.zoomLevel

    override fun centre(): Pair<Double, Double> = map.mapPosition.let { it.latitude to it.longitude }

    override fun centreOn(fix: Fix) {
        map.setMapPosition(fix.lat, fix.lon, map.mapPosition.scale)
    }

    /** Which way the map faces. VTM calls it bearing and counts it the other way round. */
    override fun mapRotationDeg(): Float = -map.mapPosition.bearing

    override fun setMapRotation(degrees: Float) {
        val position = map.mapPosition
        position.bearing = -degrees
        map.mapPosition = position
    }

    override fun remember() {
        val position = map.mapPosition
        store.lastLat = position.latitude
        store.lastLon = position.longitude
        store.lastZoom = position.zoomLevel
    }

    override fun resume() = view.onResume()

    override fun pause() = view.onPause()

    override fun destroy() = view.onDestroy()

    /**
     * What this engine can say about itself. Less than the other one can: the tile cache and the
     * frame buffer it used to report belong to CPU rasterising and have no meaning here.
     */
    override fun diagnose(): String {
        val position = map.mapPosition
        return "VTM · z${position.zoomLevel} · bearing ${position.bearing.toInt()}° · " +
            "base ${if (baseLayer != null) "vector" else if (bitmapLayer != null) "raster" else "none"}"
    }

    /** VTM draws what the file has; when the file has nothing here it simply draws nothing. */
    override fun emptyHere(): String? = null
}
