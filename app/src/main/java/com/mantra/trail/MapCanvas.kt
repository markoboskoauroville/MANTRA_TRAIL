package com.mantra.trail

import android.content.Context
import android.net.Uri
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColour
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.ParcelFileDescriptor
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.Parameters
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.download.tilesource.AbstractTileSource
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.FileInputStream
import java.net.MalformedURLException
import java.net.URL

/**
 * A TILE SOURCE FOR ANY OF OUR RASTER LAYERS, xyz or WMS.
 *
 * The User-Agent is set and it matters: OpenStreetMap-derived tile services refuse a request that
 * does not identify the application, and the refusal is a 403 that reads exactly like a dead
 * service (apis/README.md — "without it ten good keys look dead").
 *
 * The time to live is thirty days. A topographic map is not news: keeping a tile for a month is
 * the difference between a map that is there on the mountain and one that is a grey grid.
 */
class WebTileSource(
    private val layer: MapLayer,
    /** The session token for Google, ignored by everything else. */
    private val session: String? = null,
    /** The key from the file he picked, for the services that take one in the address. */
    private val key: String? = null,
) : AbstractTileSource(arrayOf(URL(layer.url).host), 443) {

    init {
        userAgent = "MantraTrail/1 (+https://github.com/markoboskoauroville/MANTRA_TRAIL)"
        defaultTimeToLive = 30L * 24 * 3600 * 1000
    }

    override fun getParallelRequestsLimit(): Int = 4

    @Throws(MalformedURLException::class)
    override fun getTileUrl(tile: Tile): URL {
        val url = Layers.tileUrl(layer, tile.zoomLevel.toInt(), tile.tileX, tile.tileY, session, key)
            ?: throw MalformedURLException("layer ${layer.id} has no address without its key")
        return URL(url)
    }

    override fun getZoomLevelMax(): Byte = layer.maxZoom.toByte()

    override fun getZoomLevelMin(): Byte = layer.minZoom.toByte()

    override fun hasAlpha(): Boolean = false
}

/**
 * THE MAP VIEW AND EVERYTHING DRAWN ON IT.
 *
 * One view for every layer that is ours, swapped underneath rather than rebuilt: the position,
 * the zoom, the track line and the dot do not belong to a layer and must not blink when the
 * layer changes (design-language.md 1, nothing appears or disappears).
 *
 * Google is not drawn here at all. Its SDK draws its own view and its tiles may not be cached, so
 * it is a different surface behind the same controls.
 */
class MapCanvas(private val context: Context, private val store: Store) {

    init {
        // WHY THE MAP DREW HALFWAY AND THEN STOPPED.
        //
        // Left alone mapsforge allocates a SQUARE frame buffer: on a 1080 by 2400 screen that is
        // 2400 by 2400, so it renders roughly two and a half screens of tiles before the one you
        // are looking at is finished. Every zoom threw that work away and began again — which is
        // exactly what a map that "draws halfway, and at the next zoom nothing" looks like. The
        // square buffer exists for rotating the map, and this map does not rotate.
        // REVERSED ON 16.9.2026, WITH THE REASON (never-back-to-zero.md). This was set false on
        // 15.9.2026 while hunting the blank map above z18, on the theory that a square buffer
        // asked for too many tiles. The desk reproduction later proved the blank was the tile
        // cache and nothing else, and turning rotation on made the cost of this setting plain:
        // a screen-shaped buffer turned by 30 degrees shows white wedges in the corners, because
        // nothing was ever drawn there. Square is what the setting is FOR — a buffer as wide as
        // the screen's diagonal, so every corner is covered at every angle.
        Parameters.SQUARE_FRAME_BUFFER = true
        // 16-bit colour for the buffer: half the memory per tile, and on a street map nobody can
        // tell. Memory is what the renderer runs out of first on a dense city at street zoom.
        Parameters.ANDROID_32BIT_COLOR = false
    }

    val view: MapView = MapView(context).apply {
        setClickable(true)
        // TWO FINGERS TURN THE MAP (16.9.2026). mapsforge has the gesture and ships it switched
        // off; everything drawn on the map — the position, the route marks, the lines — turns
        // with it, because the same view draws them all.
        touchGestureHandler.setRotationEnabled(true)
        // 256 px tiles, fixed. Left to itself mapsforge scales the tile to the screen density,
        // which on this phone is 2.75: a 704 px tile at street zoom over a country file is a very
        // different amount of work from a 256 px one, and a tile that takes too long is a tile
        // that is never drawn.
        model.displayModel.setFixedTileSize(256)
        setBuiltInZoomControls(false)
        // No scale bar: he does not use it and it sits in the corner of every screenshot
        // (15.9.2026). The zoom number on the top line says the same thing in five characters.
        mapScaleBar.isVisible = false
        model.mapViewPosition.setCenter(LatLong(store.lastLat, store.lastLon))
        model.mapViewPosition.zoomLevel = store.lastZoom.toByte()
    }

    private var baseLayer: Layer? = null
    private var tileCache: TileCache? = null
    private var trackLine: Polyline? = null
    private var shownLine: Polyline? = null
    private var routeLine: Polyline? = null
    private var positionMark: Marker? = null
    private var accuracyRing: Circle? = null
    private val optionLines = ArrayList<Polyline>()
    private val routeMarkers = HashMap<String, Marker>()
    private var mapFile: MapFile? = null

    private val factory get() = AndroidGraphicFactory.INSTANCE

    private fun paint(colour: Long, width: Float, style: Style) = factory.createPaint().apply {
        setColor(colour.toInt())
        strokeWidth = width
        setStyle(style)
    }

    /**
     * Put a layer under everything else. Returns the reason when it cannot, so the screen can say
     * it in a sentence instead of showing an empty grid and letting somebody wonder.
     */
    fun show(layer: MapLayer, session: String? = null, key: String? = null): String? {
        baseLayer?.let { view.layerManager.layers.remove(it) }
        (baseLayer as? TileDownloadLayer)?.onPause()
        baseLayer = null
        tileCache?.purge()
        tileCache = null
        mapFile?.close()
        mapFile = null

        // TWO SCREENFULS, NOT ONE. A cache sized to exactly what is on screen has nothing left
        // for the zoom level being rendered into, and the symptom of that is a map that goes
        // blank on the way in and comes back on the way out.
        // THE CACHE IS SIZED FROM THE REAL SCREEN, NOT FROM A RATIO.
        //
        // Proved on a desk on 15.9.2026: mapsforge renders this very file at z19, z20 and z21
        // without complaint — 49 ways and a thousand distinct colours in the tile over Zagreb.
        // So the blank above z18 was never the library, the data, or the zoom: it was this cache.
        //
        // mapsforge draws a frame by asking the cache for every tile of it, and it warns that the
        // screenRatio overload is an approximation made before the view has a size. Guess too low
        // and the tiles of ONE frame evict each other as they arrive: each is rendered, put, and
        // thrown out before it can be drawn, and the screen stays white while the phone works
        // hard. The other overload takes real pixels, and the real pixels are known here.
        val metrics = context.resources.displayMetrics
        // The buffer is square and as wide as the diagonal, so the cache is sized for the
        // diagonal too: sizing it for the screen would leave the corners evicting each other,
        // which is the same fault as the blank map, wearing a different hat.
        val diagonal = Math.hypot(metrics.widthPixels.toDouble(), metrics.heightPixels.toDouble()).toInt()
        val cache = AndroidUtil.createTileCache(
            context,
            "tiles-${layer.id}",
            view.model.displayModel.tileSize,
            diagonal,
            diagonal,
            // Twice the frame, so a zoom has room for the level it is going to as well as the one
            // it is leaving — which is what mapsforge scales up while the new tiles render.
            view.model.frameBufferModel.overdrawFactor * 2.0,
            layer.kind != LayerKind.GOOGLE_TILES,
        )
        tileCache = cache

        val problem: String? = when (layer.kind) {
            LayerKind.VECTOR_FILE -> openVectorLayer(cache)
            LayerKind.RASTER_XYZ, LayerKind.GOOGLE_TILES -> {
                if (layer.provider != null && key.isNullOrEmpty()) {
                    return Layers.missingKey(layer)
                }
                if (layer.kind == LayerKind.GOOGLE_TILES && session.isNullOrEmpty()) {
                    return "Google has no session yet"
                }
                val download = TileDownloadLayer(
                    cache,
                    view.model.mapViewPosition,
                    WebTileSource(layer, session, key),
                    factory,
                )
                view.layerManager.layers.add(0, download)
                download.onResume()
                baseLayer = download
                null
            }

            // Google draws its own view. That is not a problem and must not be reported as
            // one: a note the person cannot act on teaches them to ignore the note line.
        }
        view.setZoomLevelMin(layer.minZoom.toByte())
        // The offline map is drawn from vector data, so it can be enlarged past the zoom any
        // tile service stops at: the detail thins out but the map does not end. Clamping it at
        // 18, as this did, is one of the two things that could have made it vanish on the way in.
        // The view goes further than the tiles do; mapsforge scales the last real one.
        view.setZoomLevelMax(layer.viewMaxZoom.toByte())
        restoreOverlays()
        view.repaint()
        return problem
    }

    /**
     * The offline map, from whichever of the two places it is in: the file this app downloaded
     * itself, or one chosen with the picker. The downloaded one wins, because it is the one the
     * app can prove is whole.
     */
    private fun openVectorLayer(cache: TileCache): String? {
        val downloaded = MapDownload.target(context)
        if (MapDownload.isPresent(context)) {
            return try {
                attachVector(cache, MapFile(downloaded))
                null
            } catch (e: Exception) {
                "The downloaded map could not be read: ${e.javaClass.simpleName}"
            }
        }
        val uri = store.mapFileUri
            ?: return "No offline map yet. Settings: download Croatia, or choose a .map file."
        return try {
            val descriptor: ParcelFileDescriptor = context.contentResolver
                .openFileDescriptor(Uri.parse(uri), "r")
                ?: return "That map file could not be opened"
            attachVector(cache, MapFile(FileInputStream(descriptor.fileDescriptor)))
            null
        } catch (e: Exception) {
            // A folder permission can be lost by a reinstall and a file can be deleted. Either
            // way the honest answer is one sentence and the way to choose it again.
            store.mapFileUri = null
            "The offline map could not be read: ${e.javaClass.simpleName}"
        }
    }

    private fun attachVector(cache: TileCache, file: MapFile) {
        mapFile = file
        val renderer = TileRendererLayer(cache, file, view.model.mapViewPosition, factory)
        renderer.setXmlRenderTheme(MapsforgeThemes.DEFAULT)
        view.layerManager.layers.add(0, renderer)
        baseLayer = renderer
    }

    /** The line of the walk so far, in the recording red. */
    fun drawTrack(points: List<Fix>) {
        val existing = trackLine
        if (existing != null) view.layerManager.layers.remove(existing)
        if (points.size < 2) {
            trackLine = null
            return
        }
        val line = Polyline(paint(0xFFEF4444, 7f, Style.STROKE), factory)
        points.forEach { line.addPoint(LatLong(it.lat, it.lon)) }
        view.layerManager.layers.add(line)
        trackLine = line
    }

    /**
     * Where the phone says it is, and how wrong it might be. The ring is the accuracy in metres
     * drawn to scale, because a number in a corner is read as a score and a circle is read as
     * what it is: the ground the fix cannot tell apart.
     */
    /**
     * A SAVED WALK, DRAWN IN THE COLOUR HE CHOSE (15.9.2026). A separate line from the one being
     * recorded and a separate colour, because both on screen at once is the point: yesterday's
     * route under today's position.
     */
    fun showSavedTrack(points: List<Fix>, colour: Long) {
        shownLine?.let { view.layerManager.layers.remove(it) }
        shownLine = null
        if (points.size < 2) {
            view.repaint()
            return
        }
        val line = Polyline(paint(colour, 6f, Style.STROKE), factory)
        points.forEach { line.addPoint(LatLong(it.lat, it.lon)) }
        view.layerManager.layers.add(line)
        shownLine = line
        // Put the map where the walk is: a track drawn in Velebit is invisible from Zagreb.
        val middle = points[points.size / 2]
        view.model.mapViewPosition.setCenter(LatLong(middle.lat, middle.lon))
        view.repaint()
    }

    fun clearSavedTrack() {
        shownLine?.let { view.layerManager.layers.remove(it) }
        shownLine = null
        view.repaint()
    }

    /**
     * THE TWO POINTS OF A ROUTE AND THE LINE BETWEEN THEM (16.9.2026).
     *
     * A where the walk starts, B where it ends. Each is the same hairline cross that marks the
     * centre of the screen, with its letter beside it, so a placed point and the place it was
     * taken from look like each other. The line appears when both exist and goes when either does.
     */
    fun setRoutePoints(points: List<Pair<Double, Double>>) {
        routeMarkers.values.forEach { view.layerManager.layers.remove(it) }
        routeMarkers.clear()
        points.forEachIndexed { index, at ->
            val letter = Route.letterFor(index)
            val marker = Marker(LatLong(at.first, at.second), markerBitmap(letter), 0, 0)
            view.layerManager.layers.add(marker)
            routeMarkers[letter] = marker
        }
        drawRouteLine(points)
        view.repaint()
    }

    /**
     * THE WAYS BROUTER FOUND, each in its own colour, drawn under the A and B marks. Drawn thick
     * and half transparent so the path beneath still reads: the line is an answer about the
     * ground, not a replacement for it.
     */
    fun showRouteOptions(options: List<Routing.Option>) {
        optionLines.forEach { view.layerManager.layers.remove(it) }
        optionLines.clear()
        options.forEach { option ->
            val line = Polyline(paint(option.colour, 7f, Style.STROKE), factory)
            option.points.forEach { line.addPoint(LatLong(it.lat, it.lon)) }
            view.layerManager.layers.add(line)
            optionLines.add(line)
        }
        view.repaint()
    }

    fun clearRouteOptions() {
        optionLines.forEach { view.layerManager.layers.remove(it) }
        optionLines.clear()
        view.repaint()
    }

    /**
     * The straight line through the points in order, which is what there is to draw before the
     * engine has been asked. It goes as soon as real ways are drawn: two meanings for one line is
     * one too many.
     */
    private fun drawRouteLine(points: List<Pair<Double, Double>>) {
        routeLine?.let { view.layerManager.layers.remove(it) }
        routeLine = null
        if (optionLines.isNotEmpty() || points.size < 2) return
        val line = Polyline(paint(0x8060A5FA, 4f, Style.STROKE), factory)
        points.forEach { line.addPoint(LatLong(it.first, it.second)) }
        view.layerManager.layers.add(line)
        routeLine = line
    }

    /**
     * The marker, drawn rather than shipped as an image: a hairline cross in near-black under the
     * colour so it reads on a satellite photograph and on a street map, with its letter beside it.
     */
    private fun markerBitmap(letter: String): org.mapsforge.core.graphics.Bitmap {
        val scale = context.resources.displayMetrics.density
        val side = (44 * scale).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(
            side,
            side,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = AndroidCanvas(bitmap)
        val centre = side / 2f
        val arm = side / 2f - 2 * scale
        val gap = arm * 0.36f

        fun cross(colour: Int, width: Float) {
            val p = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                this.color = colour
                strokeWidth = width
                style = AndroidPaint.Style.STROKE
            }
            canvas.drawLine(centre - arm, centre, centre - gap, centre, p)
            canvas.drawLine(centre + gap, centre, centre + arm, centre, p)
            canvas.drawLine(centre, centre - arm, centre, centre - gap, p)
            canvas.drawLine(centre, centre + gap, centre, centre + arm, p)
            canvas.drawCircle(centre, centre, gap, p)
        }
        cross(AndroidColour.argb(200, 11, 13, 16), 3f * scale)
        cross(AndroidColour.argb(255, 96, 165, 250), 1.4f * scale)

        val text = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColour.argb(255, 96, 165, 250)
            textSize = 13f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(3f * scale, 0f, 0f, AndroidColour.argb(220, 11, 13, 16))
        }
        canvas.drawText(letter, centre + gap + 2 * scale, centre - gap, text)
        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bitmap))
    }

    /** Where the middle of the screen is, which is where a point is placed from. */
    fun centre(): Pair<Double, Double> {
        val c = view.model.mapViewPosition.center
        return c.latitude to c.longitude
    }

    private var lastFix: Fix? = null
    private var headingDeg: Double = Double.NaN
    private var drawnHeadingBucket: Int = Int.MIN_VALUE

    /**
     * WHERE HE IS, AND WHICH WAY HE IS FACING.
     *
     * Baba, 16.9.2026, with a Google Maps screenshot: that dot, with the light in front of it.
     * So: a blue dot with a white collar, and a cone of light thrown in the direction the phone
     * is pointing, fading out as it goes. The cone is the compass, drawn where the eye already
     * is, rather than a number on a bar somewhere else.
     *
     * It is a bitmap, so it stays the same size on the screen at every zoom — the filled circle
     * this replaces was three metres of accuracy drawn to scale, which at z22 was the size of a
     * football ground. Accuracy is the thin ring around it and nothing more.
     */
    fun drawPosition(fix: Fix?) {
        lastFix = fix
        redrawPosition()
    }

    /** The phone's heading, in degrees from true north. Redraws only when it has really moved. */
    fun setHeading(degrees: Double) {
        headingDeg = degrees
        val bucket = ((degrees + 2.5) / 5.0).toInt()
        if (bucket != drawnHeadingBucket && lastFix != null) redrawPosition()
    }

    private fun redrawPosition() {
        positionMark?.let { view.layerManager.layers.remove(it) }
        positionMark = null
        accuracyRing?.let { view.layerManager.layers.remove(it) }
        accuracyRing = null
        val fix = lastFix
        if (fix == null) {
            view.repaint()
            return
        }
        val here = LatLong(fix.lat, fix.lon)

        if (fix.accuracyM != null && fix.accuracyM > 0f) {
            val ring = Circle(here, fix.accuracyM, null, paint(0x553B82F6, 1.5f, Style.STROKE))
            view.layerManager.layers.add(ring)
            accuracyRing = ring
        }

        // The cone is drawn against the MAP, not against the screen, so when the map is turned
        // with two fingers the light still points where the phone points.
        val mapTurn = view.model.mapViewPosition.rotation?.degrees?.toDouble() ?: 0.0
        drawnHeadingBucket = ((headingDeg + 2.5) / 5.0).toInt()
        val mark = Marker(here, positionBitmap(headingDeg, mapTurn), 0, 0)
        view.layerManager.layers.add(mark)
        positionMark = mark
        view.repaint()
    }

    private fun positionBitmap(heading: Double, mapTurn: Double): org.mapsforge.core.graphics.Bitmap {
        val scale = context.resources.displayMetrics.density
        val side = (72 * scale).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(
            side,
            side,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = AndroidCanvas(bitmap)
        val c = side / 2f
        val dotRadius = 7f * scale

        if (!heading.isNaN()) {
            // THE LIGHT IN FRONT. A wedge fading from the dot outwards: bright at the phone,
            // gone by the end, because a heading is a direction and not a claim about distance.
            val reach = c - 1f
            val sweep = 62f
            val start = (heading + mapTurn - 90.0 - sweep / 2).toFloat()
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
                start,
                sweep,
                true,
                cone,
            )
        }

        // The dot: white collar, blue middle, and a shadow under both so it reads on snow.
        val shadow = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColour.argb(60, 0, 0, 0)
        }
        canvas.drawCircle(c, c + 0.5f * scale, dotRadius + 2.5f * scale, shadow)
        val collar = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColour.argb(255, 255, 255, 255)
        }
        canvas.drawCircle(c, c, dotRadius + 2f * scale, collar)
        val middle = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColour.argb(255, 59, 130, 246)
        }
        canvas.drawCircle(c, c, dotRadius, middle)

        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(context.resources, bitmap))
    }

    private fun restoreOverlays() {
        optionLines.forEach { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        accuracyRing?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        positionMark?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        routeMarkers.values.forEach {
            if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it)
        }
        routeLine?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        optionLines.forEach { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        accuracyRing?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        positionMark?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        routeMarkers.values.forEach {
            if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it)
        }
        routeLine?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        shownLine?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        trackLine?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        accuracyRing?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
    }

    /** The corners of what is on the screen now, which is what CH means by "this view". */
    fun visibleBox(): DoubleArray? {
        val b = view.boundingBox ?: return null
        return doubleArrayOf(b.minLatitude, b.minLongitude, b.maxLatitude, b.maxLongitude)
    }

    fun currentZoom(): Int = view.model.mapViewPosition.zoomLevel.toInt()

    /**
     * WHAT THE OFFLINE MAP ACTUALLY HAS, HERE, AT THIS ZOOM. A blank map has several causes and
     * they look identical on the glass: no file, a file that does not cover this place, no data
     * at this zoom, or a renderer that is failing. This asks the file and reports the counts.
     */
    fun diagnose(): String {
        val file = mapFile ?: return "No offline map file is open. Settings: download Croatia."
        return try {
            val info = file.mapFileInfo
            val centre = view.model.mapViewPosition.center
            val z = currentZoom()
            val tile = Tile(
                Geo.tileX(centre.longitude, z),
                Geo.tileY(centre.latitude, z),
                z.toByte(),
                view.model.displayModel.tileSize,
            )
            val inside = info.boundingBox.contains(centre)
            val read = runCatching { file.readMapData(tile) }.getOrNull()
            val metrics = context.resources.displayMetrics
            val size = view.model.displayModel.tileSize
            val perFrame = (metrics.widthPixels / size + 2) * (metrics.heightPixels / size + 2)
            "z$z · here ${if (inside) "inside" else "OUTSIDE"} the map · " +
                "tile: ${read?.ways?.size ?: -1} ways, ${read?.pois?.size ?: -1} points · " +
                "cache ${tileCache?.capacityFirstLevel ?: -1} in memory of ${tileCache?.capacity ?: -1}, " +
                "frame needs $perFrame"
        } catch (e: Exception) {
            "The map file could not be questioned: ${e.javaClass.simpleName}"
        }
    }

    /**
     * Null when the file has something to draw under the crosshair, or a sentence when it has
     * nothing. Cheap: one tile's worth of a read that the renderer is about to do anyway.
     */
    fun emptyHere(): String? {
        val file = mapFile ?: return null
        return try {
            val centre = view.model.mapViewPosition.center
            val z = currentZoom()
            val tile = Tile(
                Geo.tileX(centre.longitude, z),
                Geo.tileY(centre.latitude, z),
                z.toByte(),
                view.model.displayModel.tileSize,
            )
            if (!file.mapFileInfo.boundingBox.contains(centre)) {
                return "This place is outside the offline map"
            }
            val read = file.readMapData(tile) ?: return "The offline map returned nothing at z$z"
            if (read.ways.isEmpty() && read.pois.isEmpty()) {
                "The offline map has nothing here at z$z"
            } else {
                null
            }
        } catch (e: Exception) {
            "The offline map could not be read here: ${e.javaClass.simpleName}"
        }
    }

    fun centreOn(fix: Fix) {
        view.model.mapViewPosition.setCenter(LatLong(fix.lat, fix.lon))
    }

    fun zoomIn() {
        view.model.mapViewPosition.zoomIn()
    }

    fun zoomOut() {
        view.model.mapViewPosition.zoomOut()
    }

    /** Where the map is looking now, remembered so the next opening starts where this one ended. */
    fun remember() {
        val centre = view.model.mapViewPosition.center
        store.lastLat = centre.latitude
        store.lastLon = centre.longitude
        store.lastZoom = view.model.mapViewPosition.zoomLevel.toInt()
    }

    fun pause() {
        (baseLayer as? TileDownloadLayer)?.onPause()
    }

    fun resume() {
        (baseLayer as? TileDownloadLayer)?.onResume()
    }

    fun destroy() {
        remember()
        view.destroyAll()
        mapFile?.close()
        mapFile = null
    }
}
