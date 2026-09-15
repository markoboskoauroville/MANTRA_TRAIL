package com.mantra.trail

import android.content.Context
import android.net.Uri
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
        Parameters.SQUARE_FRAME_BUFFER = false
        // 16-bit colour for the buffer: half the memory per tile, and on a street map nobody can
        // tell. Memory is what the renderer runs out of first on a dense city at street zoom.
        Parameters.ANDROID_32BIT_COLOR = false
    }

    val view: MapView = MapView(context).apply {
        setClickable(true)
        // 256 px tiles, fixed. Left to itself mapsforge scales the tile to the screen density,
        // which on this phone is 2.75: a 704 px tile at street zoom over a country file is a very
        // different amount of work from a 256 px one, and a tile that takes too long is a tile
        // that is never drawn.
        model.displayModel.setFixedTileSize(256)
        setBuiltInZoomControls(false)
        mapScaleBar.isVisible = true
        model.mapViewPosition.setCenter(LatLong(store.lastLat, store.lastLon))
        model.mapViewPosition.zoomLevel = store.lastZoom.toByte()
    }

    private var baseLayer: Layer? = null
    private var tileCache: TileCache? = null
    private var trackLine: Polyline? = null
    private var here: Circle? = null
    private var accuracyRing: Circle? = null
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
        val cache = AndroidUtil.createTileCache(
            context,
            "tiles-${layer.id}",
            view.model.displayModel.tileSize,
            // THIS NUMBER IS WHY z19 WAS WHITE, and it took reading mapsforge's own source to see
            // it. While a tile renders, mapsforge draws the PARENT tile scaled up — but it looks
            // for that parent with getImmediately(), which only ever consults the first level of
            // the cache, the one in memory. This ratio sizes exactly that level. Too small, the
            // parents are evicted to disk, getImmediately finds nothing, and the screen is white
            // until the new tiles finish — which at street zoom over a country file is long
            // enough to look like a broken app. Three screenfuls of tiles stay in memory now.
            3f,
            view.model.frameBufferModel.overdrawFactor,
            // KEPT ON DISK FOR EVERYTHING EXCEPT GOOGLE. Fetched tiles are the map in the
            // mountains, and tiles WE rendered from a file on the phone are ours twice over —
            // caching them means a zoom that was visited once comes back instantly instead of
            // being rendered from the country file again. Google's terms forbid it, and Google
            // is the only layer this is false for.
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
        view.setZoomLevelMax(
            if (layer.kind == LayerKind.VECTOR_FILE) 22.toByte() else layer.maxZoom.toByte()
        )
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
    fun drawPosition(fix: Fix?) {
        here?.let { view.layerManager.layers.remove(it) }
        accuracyRing?.let { view.layerManager.layers.remove(it) }
        here = null
        accuracyRing = null
        if (fix == null) return
        val at = LatLong(fix.lat, fix.lon)
        fix.accuracyM?.let { metres ->
            val ring = Circle(
                at,
                metres.toFloat(),
                paint(0x22E8A64B, 0f, Style.FILL),
                paint(0x88E8A64B, 2f, Style.STROKE),
            )
            view.layerManager.layers.add(ring)
            accuracyRing = ring
        }
        val dot = Circle(at, 4f, paint(0xFFE8A64B, 0f, Style.FILL), paint(0xFF0B0D10, 2f, Style.STROKE))
        view.layerManager.layers.add(dot)
        here = dot
    }

    private fun restoreOverlays() {
        trackLine?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        accuracyRing?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
        here?.let { if (!view.layerManager.layers.contains(it)) view.layerManager.layers.add(it) }
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
            "z$z · file ${info.fileSize / 1_000_000} MB, zooms ${info.zoomLevelMin}-${info.zoomLevelMax} · " +
                "here ${if (inside) "inside" else "OUTSIDE"} the map · " +
                "this tile: ${read?.ways?.size ?: -1} ways, ${read?.pois?.size ?: -1} points"
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
