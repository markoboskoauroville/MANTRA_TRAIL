package com.mantra.trail

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.download.DownloadJob
import org.mapsforge.map.layer.download.TileDownloadLayer
import org.mapsforge.map.layer.download.tilesource.AbstractTileSource
import org.mapsforge.map.layer.overlay.Circle
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.net.HttpURLConnection
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

    val view: MapView = MapView(context).apply {
        setClickable(true)
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

        val cache = AndroidUtil.createTileCache(
            context,
            "tiles-${layer.id}",
            view.model.displayModel.tileSize,
            1f,
            view.model.frameBufferModel.overdrawFactor,
            // Persistent: the tiles fetched on the road are the map in the mountains. Only for
            // the layers whose licence allows it — Google's never reaches this code.
            layer.cacheable,
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
        view.setZoomLevelMax(layer.maxZoom.toByte())
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
     * CH: FETCH EVERY TILE OF THIS VIEW INTO THE CACHE THE LAYER ALREADY READS FROM.
     *
     * Tiles already held are skipped rather than fetched again, so pressing it twice on the same
     * view costs nothing and pressing it after moving a little costs only the edge.
     *
     * A tile that will not come is counted, not thrown: one dead tile in four hundred is a
     * server hiccup, and stopping the whole run for it would leave the map worse than before.
     * The count is reported, because a cache that silently has holes in it is the thing you find
     * out about on the mountain.
     */
    suspend fun cacheVisible(
        layer: MapLayer,
        plan: Caching.Plan,
        key: String?,
        onProgress: (done: Int, total: Int, failed: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val cache = tileCache ?: return@withContext 0
        val source = WebTileSource(layer, key = key)
        val tileSize = view.model.displayModel.tileSize
        var done = 0
        var failed = 0
        for (ref in plan.tiles) {
            val tile = Tile(ref.x, ref.y, ref.zoom.toByte(), tileSize)
            val job = DownloadJob(tile, source)
            if (!cache.containsKey(job)) {
                try {
                    val connection = source.getTileUrl(tile).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 15_000
                    connection.setRequestProperty("User-Agent", source.userAgent)
                    connection.inputStream.use { stream ->
                        val bitmap = factory.createTileBitmap(stream, tileSize, false)
                        cache.put(job, bitmap)
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    failed++
                }
            }
            done++
            if (done % 8 == 0 || done == plan.tiles.size) onProgress(done, plan.tiles.size, failed)
        }
        view.repaint()
        failed
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
