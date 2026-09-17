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
import java.io.File
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
        // The ground behind every map, before any of them is drawn: black, because this is a dark
        // application and VTM's own default is light grey.
        org.oscim.renderer.MapRenderer.setBackgroundColor(android.graphics.Color.BLACK)
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
    /**
     * SHOW NOTHING, ON PURPOSE (17.9.2026).
     *
     * His screenshot: the top line said Satellite, Google had refused the key, and the offline map
     * was drawn underneath — so the name and the ground disagreed and only the name was visible at
     * a glance. A map that cannot be drawn must draw nothing. Black, not the library's light grey,
     * because this is a dark application and a white screen on a hillside is a torch in the face.
     */
    fun blank() {
        clearBaseLayers()
        org.oscim.renderer.MapRenderer.setBackgroundColor(android.graphics.Color.BLACK)
        map.clearMap()
        map.updateMap(true)
    }

    fun show(layer: MapLayer, session: String? = null, key: String? = null): String? {
        clearBaseLayers()
        return when (layer.kind) {
            LayerKind.VECTOR_FILE -> showVector()
            else -> showRaster(layer, session, key)
        }
    }

    private fun showVector(): String? {
        // WHICH FILE, IN THE ORDER THEY ARE WORTH HAVING (16.9.2026): an OpenAndroMaps region if
        // one has been fetched, because it carries contour lines and waymarked routes; then one
        // he picked himself; then the plain extract this app can download.
        // A file he picked with the file manager has no path at all, only a descriptor, so the
        // stream is what both cases have in common.
        val onDisk: File? = offlineFile()
        val stream: FileInputStream = if (onDisk != null) {
            FileInputStream(onDisk)
        } else {
            openOfflineFile()
                ?: return "No offline map yet. Settings: download OpenAndroMaps, or pick a .map file."
        }
        return try {
            val source = MapFileTileSource()
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
            applyTheme(store.themeName)
            restoreOverlays()
            map.updateMap(true)
            null
        } catch (e: Exception) {
            "The offline map would not open: ${e.javaClass.simpleName}"
        }
    }

    /**
     * The offline map this app should open, best first: an OpenAndroMaps region if one has been
     * fetched, because it carries contour lines and waymarked routes in the data itself; then the
     * plain extract this app can download.
     */
    private fun offlineFile(): File? {
        val installed = OamDownload.installed(context)
        // HIS CHOICE FIRST (16.9.2026): with several regions on the phone, the app must draw the
        // one he ticked rather than whichever the file system happens to list first.
        store.offlineMapName.takeIf { it.isNotBlank() }
            ?.let { chosen -> installed.firstOrNull { it.name == chosen } }
            ?.let { return it }
        installed.firstOrNull()?.let { return it }
        val downloaded = MapDownload.target(context)
        return if (downloaded.exists() && downloaded.length() > 1_000_000) downloaded else null
    }

    /**
     * A map file he picked with the file manager. It has no path — only a descriptor — so it can
     * only be handed over as a stream, which is why the engine is given a stream in both cases.
     */
    private fun openOfflineFile(): FileInputStream? {
        val uri = store.mapFileUri ?: return null
        return runCatching {
            val descriptor = context.contentResolver
                .openFileDescriptor(android.net.Uri.parse(uri), "r")
                ?: return null
            FileInputStream(descriptor.fileDescriptor)
        }.getOrNull()
    }

    private fun showRaster(layer: MapLayer, session: String?, key: String?): String? {
        val (base, path) = Layers.tilePattern(layer, session, key)
            ?: return "That map needs a key first"
        val source = BitmapTileSource.builder()
            .url(base)
            .tilePath(path)
            // THE STACK THAT IS KNOWN TO WORK (17.9.2026). VTM's own client cannot do https and
            // its OkHttp engine did not draw a tile either; this is java.net, the same thing that
            // fetches the session, the routes and the maps, and it says what came back.
            .httpFactory(TileHttp.Factory())
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
    /**
     * OUR OWN THEME, for OpenAndroMaps. Loaded from the APK's assets rather than from VTM's, and
     * it is the only one that draws contour lines, path difficulty and waymarked routes, because
     * those are OpenAndroMaps' own tags and no general theme knows them.
     */
    private fun mantraTheme(): org.oscim.theme.IRenderTheme? = runCatching {
        org.oscim.theme.ThemeLoader.load(
            org.oscim.android.theme.AssetsRenderTheme(context.assets, "", "themes/mantra-walk.xml")
        )
    }.getOrNull()

    private fun themeFor(name: String): VtmThemes = when (name) {
        "OSMARENDER" -> VtmThemes.OSMARENDER
        "BIKER" -> VtmThemes.BIKER
        "MOTORIDER" -> VtmThemes.MOTORIDER
        "NEWTRON" -> VtmThemes.NEWTRON
        "TRONRENDER" -> VtmThemes.TRONRENDER
        "MAPZEN" -> VtmThemes.MAPZEN
        else -> VtmThemes.DEFAULT
    }

    /**
     * Apply a theme by name. MANTRA is ours, from the assets; the rest are VTM's own. A theme
     * that will not load says so and the plain one is used, rather than a map that draws nothing.
     */
    private fun applyTheme(name: String): String? {
        if (name == "MANTRA") {
            val theme = mantraTheme()
            if (theme != null) {
                map.setTheme(theme)
                return null
            }
            map.setTheme(VtmThemes.DEFAULT)
            return "The walking theme would not load; using the plain one"
        }
        map.setTheme(themeFor(name))
        return null
    }

    /** Draw the offline map again under a different theme, keeping everything on top of it. */
    fun setTheme(name: String): String? {
        store.themeName = name
        if (baseLayer == null) return null
        val problem = applyTheme(name)
        map.clearMap()
        map.updateMap(true)
        return problem
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
        // IT ASKED THE WRONG FILE (17.9.2026). This looked only for the extract this app can
        // download, so with an OpenAndroMaps or OpenHiking file drawing perfectly it announced
        // that there was no offline map at all. It asks what is actually open.
        if (offlineFile() != null || openOfflineFile() != null) return null
        return "No offline map on the phone yet"
    }

    fun diagnose(): String {
        // What the tiles are actually doing, where he can read it.
        val position = map.mapPosition
        return "VTM (GPU) · ${Report.tileReport()} · z${position.zoomLevel} · ${Geo.formatLat(position.getLatitude())} " +
            "${Geo.formatLon(position.getLongitude())} · layers ${map.layers().size}"
    }

    fun destroy() {
        runCatching { view.onDestroy() }
        mapFileStream?.let { runCatching { it.close() } }
    }
}
