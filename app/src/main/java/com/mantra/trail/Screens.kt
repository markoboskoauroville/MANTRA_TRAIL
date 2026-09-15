package com.mantra.trail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * THE MAP IS THE APP, AND EVERYTHING ELSE GETS OUT OF ITS WAY.
 *
 * Baba, 14.9.2026, on v5: *"When I tap in the middle of the screen, all interface is gone. I just
 * see the map. I'm following the map. That's only what I need... one button settings, not too
 * many buttons and too many numbers on the screen."*
 *
 * So: one line of numbers at the top, four small keys at the bottom, everything over the map at
 * half transparency, and a tap in the middle takes all of it away and brings it back. The map
 * choice is one key that turns through the three, and everything else lives in settings.
 *
 * THE SNIPER IS GONE. It was a target drawn over the middle of the map, and the middle of the map
 * is where the person is looking. What is left is a small ring the size of a fingernail: enough
 * to say where the centre is when panning, not enough to be in the way.
 */
private val GAP = 10.dp
private val KEY = 46.dp

/** The one size for the marks: the centre of the map, the where key and the record circle. */
private val MARK = 22.dp

/** Bumped when a picker or a download changes something a row shows. */
object UiTick {
    var n by mutableIntStateOf(0)
    fun bump() {
        n += 1
    }
}

@Composable
fun TrailApp(
    store: Store,
    sensors: Sensors,
    version: String,
    onCanvas: (MapCanvas) -> Unit,
    onWhereAmI: () -> Unit,
    onRecord: () -> Unit,
    onPause: () -> Unit,
    onExport: () -> Unit,
    onChooseMapFile: () -> Unit,
    onChooseExportFolder: () -> Unit,
    onImportKeys: () -> Unit,
    onDownloadMap: () -> Unit,
    onOpenMapLink: () -> Unit,
    onZeroLevel: () -> Unit,
    onBare: (Boolean) -> Unit,
) {
    var layer by remember { mutableStateOf(Layers.byId(store.layerId)) }
    var settings by remember { mutableStateOf(false) }
    var bare by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(13) }
    var ready by remember { mutableStateOf(false) }

    val fix by Trail.fix.collectAsState()
    val stats by Trail.stats.collectAsState()
    val recordingSince by Trail.recordingSince.collectAsState()
    val paused by Trail.paused.collectAsState()
    val note by Trail.note.collectAsState()
    val recording = recordingSince != null
    val scope = rememberCoroutineScope()

    // The map the app opened on, drawn as soon as the view is real and not a moment before.
    LaunchedEffect(ready) {
        if (ready) showLayer(store, layer)
    }

    // The zoom on the screen follows the map rather than the other way round. Twice a second is
    // enough for a number that changes when a thumb moves, and it stops with the composition.
    LaunchedEffect(Unit) {
        while (true) {
            CanvasHolder.canvas?.currentZoom()?.let { if (it != zoom) zoom = it }
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {

        MapSurface(
            store = store,
            fix = fix,
            line = Trail.line.collectAsState().value,
            onCanvas = onCanvas,
            onReady = { ready = true },
        )

        // THE TAP IN THE MIDDLE. A small target, so panning the map anywhere else is untouched,
        // and the mark that says where the centre is sits inside it.
        Box(
            Modifier
                .align(Alignment.Center)
                .size(72.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    bare = !bare
                    onBare(bare)
                },
            contentAlignment = Alignment.Center,
        ) {
            CentreMark(hasFix = fix != null)
        }

        if (!bare) {
            // A BAR BEHIND THE WORDS. Baba, 15.9.2026: *"80% transparent bar behind the letters
            // and the symbols... because I don't see them in the map."* The shadow alone was not
            // enough on a pale street map. The bar runs the full width and only as tall as the
            // line it carries, so it costs a strip rather than a panel.
            // THE BAR IS THE HEIGHT OF ITS LINE AND NOTHING MORE. Putting the background on the
            // column meant it also covered the safe-area inset and every invisible row inside it,
            // which is how a strip of text ended up shading half the map.
            Column(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).safeDrawingPadding(),
            ) {
                FixLine(fix, zoom)
            }

            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).safeDrawingPadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // A line with nothing in it takes no room at all now. It used to be drawn at zero
                // opacity, which is invisible but still occupies its height — and with a
                // background behind the column, that height was a shaded band over the map.
                // THE CREDIT IS BACK ON THE MAP, and only where it has to be. Thunderforest do not
                // permit removing their attribution or OpenStreetMap's from an app, so the layers
                // whose tiles we fetch carry one dim line. The offline file does not: its credit
                // sits in settings, where it is read once.
                if (layer.creditOnMap) {
                    Label(layer.attribution, Paint.Dim, size = 9, align = TextAlign.Start)
                }
                if (note != null) NoteLine(note)
                if (recording) TrackLine(stats)
                // THE ORDER IS THE THUMB'S, NOT THE LIST'S. Baba, 15.9.2026: the record circle sits
                // in the middle, straight above the phone's own home button, with the centre key
                // beside it; the three that are pressed rarely spread out from there.
                // SEVEN KEYS, AND THE RED ONE IS STILL THE MIDDLE OF THEM. Zoom sits at both ends
                // where either thumb reaches it: Baba, 15.9.2026, *"give me plus and minus so I
                // don't need to zoom with my pinching. It hurts."*
                Row(
                    Modifier.fillMaxWidth().background(Paint.Bar).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Key(glyph = "−", lit = false, onClick = { CanvasHolder.canvas?.zoomOut() })
                    MarkKey(onClick = onWhereAmI) { hasFix -> CentreMark(hasFix) }
                    RecordKey(recording = recording, paused = paused, onPress = onRecord)
                    // ONE BUTTON FOR THE MAP. It says which one is on and turns to the next.
                    Key(
                        glyph = layer.short,
                        lit = false,
                        onClick = {
                            val picked = store.styleOf(Layers.nextFamily(layer))
                            layer = picked
                            store.layerId = picked.id
                            scope.launch { showLayer(store, picked) }
                        },
                    )
                    Key("⚙", lit = false, onClick = { settings = true })
                    Key(glyph = "+", lit = false, onClick = { CanvasHolder.canvas?.zoomIn() })
                }
            }
        }

        if (settings) {
            SettingsFace(
                sensors = sensors,
                store = store,
                current = layer,
                version = version,
                onPick = { picked ->
                    layer = picked
                    store.layerId = picked.id
                    store.rememberStyle(picked)
                    scope.launch { showLayer(store, picked) }
                },
                onZero = onZeroLevel,
                onChooseMapFile = onChooseMapFile,
                onChooseExportFolder = onChooseExportFolder,
                onImportKeys = onImportKeys,
                onDownloadMap = onDownloadMap,
                onOpenMapLink = onOpenMapLink,
                onExport = onExport,
                onPause = onPause,
                recordingPaused = paused,
                onClose = { settings = false },
            )
        }
    }
}

@Composable
private fun MapSurface(
    store: Store,
    fix: Fix?,
    line: List<Fix>,
    onCanvas: (MapCanvas) -> Unit,
    onReady: () -> Unit,
) {
    // ONE SURFACE FOR EVERY MAP. Google's own SDK is gone with the key that was compiled in:
    // its tiles now come through the Map Tiles API with the key from the picker, which makes it
    // the same kind of layer as the others and leaves nothing to switch between.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val made = MapCanvas(context, store)
            CanvasHolder.canvas = made
            onCanvas(made)
            // THE VIEW EXISTS NOW, AND NOT BEFORE. This is the whole bug of v6 to v10: the first
            // showLayer ran from a LaunchedEffect, which fires after composition but BEFORE
            // AndroidView builds its view, so CanvasHolder.canvas was still null, showLayer
            // returned at its first line, and no layer was ever put on the map. A blank white
            // screen at every zoom, with nothing said — and zooming could not fix what was never
            // there. The map is now told to draw itself from here, where the view is real.
            onReady()
            made.view
        },
    )

    LaunchedEffect(line.size, fix?.timeMs) {
        CanvasHolder.canvas?.drawTrack(line)
        CanvasHolder.canvas?.drawPosition(fix)
    }
}

/**
 * PUT A LAYER ON THE MAP, fetching whatever it needs first.
 *
 * A layer that needs a key and has none says which key and where to put it, rather than drawing
 * an empty grid and leaving somebody to guess. Google needs a session as well, and the session is
 * made here — once, when the view is actually chosen, because Google bills per tile.
 */
suspend fun showLayer(store: Store, layer: MapLayer) {
    val canvas = CanvasHolder.canvas
    if (canvas == null) {
        // This return used to be silent, which is how a blank screen kept its secret for five
        // versions (silent-failure.md). It cannot be silent again.
        Trail.say("The map view is not up yet")
        return
    }
    val problem = attempt(canvas, store, layer)
    if (problem == null) {
        // A layer that reports success and still shows nothing is the failure that cost five
        // versions of guessing. For the offline map the file can be asked directly, so it is:
        // if there is no data under the crosshair at this zoom, that is said now rather than
        // waiting for somebody to photograph a white screen.
        Trail.say(if (layer.kind == LayerKind.VECTOR_FILE) canvas.emptyHere() else null)
        return
    }
    // A MAP THAT CANNOT DRAW LEAVES THE SCREEN EMPTY, and an empty screen teaches nothing. So the
    // reason is said AND the one map that always works is put up underneath it, rather than
    // leaving somebody looking at white paper wondering whether the app is broken.
    if (layer.id == Layers.OSM.id) {
        Trail.say(problem)
        return
    }
    val fallback = attempt(canvas, store, Layers.OSM)
    Trail.say(if (fallback == null) "$problem — showing OpenStreetMap meanwhile" else problem)
}

/** One attempt at one layer. Returns null when it drew, or the reason it did not. */
private suspend fun attempt(canvas: MapCanvas, store: Store, layer: MapLayer): String? {
    val key = layer.provider?.let { store.key(it) }
    if (layer.provider != null && key.isNullOrEmpty()) return Layers.missingKey(layer)
    if (layer.kind == LayerKind.GOOGLE_TILES) {
        val view = layer.googleView ?: MapLayer.GoogleView.NORMAL
        Trail.say("Asking Google for a session…")
        val result = GoogleTiles.session(view, key!!)
        return result.token?.let { canvas.show(layer, session = it, key = key) } ?: result.problem
    }
    return canvas.show(layer, key = key)
}

/**
 * A KEY THAT IS A MARK RATHER THAN A GLYPH.
 *
 * Baba, 14.9.2026: the where key should look like the thing in the middle of the map, and both it
 * and the record circle should be smaller. So they are: the same ring and dot that marks the
 * centre, at the same size as the red circle beside it, on nothing.
 */
@Composable
private fun RowScope.MarkKey(onClick: () -> Unit, mark: @Composable (Boolean) -> Unit) {
    val hasFix = Trail.fix.collectAsState().value != null
    Box(
        Modifier.weight(1f).height(KEY).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        mark(hasFix)
    }
}

/** One place the composable side can reach the view it made. */
object CanvasHolder {
    var canvas: MapCanvas? = null
}

/**
 * THE MARK IN THE MIDDLE. A ring the width of a fingernail and a dot in it, at half strength.
 * It says where the centre of the screen is, and it is also the target that takes the interface
 * away. It is not a sight and it is not the person's position: the position is the amber dot on
 * the map, drawn where the phone actually is.
 */
/**
 * THE MARK IN THE MIDDLE, IN THE COLOUR OF THE POSITION ON THE MAP.
 *
 * Baba, 15.9.2026: *"the middle center I don't see at all. It needs to have different color, same
 * as for the center current position on the map."* It was sand on a street map, which is sand on
 * sand. It is amber now, and every stroke is laid down twice: near-black underneath, a little
 * wider, then the amber on top. That black edge is what makes it readable on snow and under
 * fir, and no single colour does that on its own.
 */
@Composable
private fun CentreMark(hasFix: Boolean) {
    val ink = if (hasFix) Paint.AmberBright else Paint.Amber
    Canvas(Modifier.size(MARK)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 2f
        drawCircle(Paint.Ground, radius = r, center = c, style = Stroke(3.5.dp.toPx()))
        drawCircle(ink, radius = r, center = c, style = Stroke(2.dp.toPx()))
        drawCircle(Paint.Ground, radius = 2.4.dp.toPx(), center = c)
        drawCircle(ink, radius = 1.6.dp.toPx(), center = c)
    }
}

/** One line of numbers, and only the ones that decide something. */
@Composable
private fun FixLine(fix: Fix?, zoom: Int) {
    Panel {
        Row(
            Modifier.fillMaxWidth().background(Paint.Bar).padding(horizontal = GAP, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label(fix?.let { Geo.formatLat(it.lat) } ?: "N -- --.---", ink(fix != null), size = 13)
            Label(fix?.let { Geo.formatLon(it.lon) } ?: "E -- --.---", ink(fix != null), size = 13)
            Label(fix?.accuracyM?.let { "±${it.toInt()} m" } ?: "± -", accuracyInk(fix?.accuracyM), size = 13)
            Label(fix?.ele?.let { "${it.toInt()} m" } ?: "- m", ink(fix?.ele != null), size = 13)
            // The zoom is on screen because a map that fails at one zoom and not another cannot
            // be reported without the number, and "it disappeared" is not a number.
            Label("z${zoom}", Paint.Dim, size = 13)
        }
    }
}

private fun accuracyInk(metres: Float?): Color = when {
    metres == null -> Paint.Dim
    metres <= 10f -> Paint.Sand
    metres <= TrackRules.MAX_ACCURACY_M -> Paint.Amber
    else -> Paint.Red
}

private fun ink(active: Boolean): Color = if (active) Paint.Sand else Paint.Dim

/** The walk, shown only while there is one. Idle it is empty space, not a row of zeros. */
@Composable
private fun TrackLine(stats: TrackStats) {
    Panel {
        Row(
            Modifier.fillMaxWidth().background(Paint.Bar).padding(horizontal = GAP, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label(Geo.formatDistance(stats.distanceM), Paint.Sand, size = 13)
            Label(Geo.formatDuration(stats.durationMs), Paint.Sand, size = 13)
            Label("↑ ${stats.ascentM.toInt()} m", Paint.Sand, size = 13)
            Label("${stats.points} pts", Paint.Sand, size = 13)
        }
    }
}

@Composable
private fun NoteLine(note: String?) {
    Panel {
        Box(Modifier.fillMaxWidth().background(Paint.Bar).padding(horizontal = GAP, vertical = 2.dp)) {
            Label(note ?: " ", Paint.Amber, size = 12, align = TextAlign.Start)
        }
    }
}

/**
 * A key: a glyph on half-strength ground, no box, no label under it. Four of them and the record
 * button is the fifth, and none of them is bigger than a thumb needs.
 */
@Composable
private fun RowScope.Key(
    glyph: String,
    lit: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.weight(1f).height(KEY).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Label(glyph, if (lit) Paint.AmberBright else Paint.Sand, size = 17)
    }
}

/**
 * THE RECORD KEY IS A RED CIRCLE AND NOTHING ELSE. Baba, 14.9.2026: *"the record button can be
 * just one red circle without any background."*
 *
 * Hollow to start, filled while recording, a ring with a dot while paused — the face says what
 * the next press does (design-language.md 5). Pause is the rare one and lives in settings rather
 * than taking a key on a screen that is meant to be nearly empty.
 */
@Composable
private fun RowScope.RecordKey(recording: Boolean, paused: Boolean, onPress: () -> Unit) {
    Box(
        Modifier.weight(1f).height(KEY).clickable(onClick = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(MARK)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 2f
            // The black edge first, then the red, for the same reason as the centre mark.
            drawCircle(Paint.Ground, radius = r, center = c, style = Stroke(4.5.dp.toPx()))
            when {
                paused -> {
                    drawCircle(Paint.Red, radius = r, center = c, style = Stroke(3.dp.toPx()))
                    drawCircle(Paint.Red, radius = r * 0.35f, center = c)
                }

                recording -> drawCircle(Paint.Red, radius = r, center = c)
                else -> drawCircle(Paint.Red, radius = r, center = c, style = Stroke(3.dp.toPx()))
            }
        }
    }
}

/**
 * EVERYTHING ELSE LIVES HERE: the map choice, the offline map, the folders, the keys, the compass
 * and the level. One key on the map screen opens it, and the way out is at the right-hand end of
 * its top row, where it is on every face of every app here.
 */
@Composable
private fun SettingsFace(
    sensors: Sensors,
    store: Store,
    current: MapLayer,
    version: String,
    onPick: (MapLayer) -> Unit,
    onZero: () -> Unit,
    onChooseMapFile: () -> Unit,
    onChooseExportFolder: () -> Unit,
    onImportKeys: () -> Unit,
    onDownloadMap: () -> Unit,
    onOpenMapLink: () -> Unit,
    onExport: () -> Unit,
    onPause: () -> Unit,
    recordingPaused: Boolean,
    onClose: () -> Unit,
) {
    var heading by remember { mutableStateOf(0.0) }
    var reading by remember { mutableStateOf(sensors.level) }
    val mapState = remember(UiTick.n) { store.offlineMapState }
    val exportState = remember(UiTick.n) { if (store.exportTreeUri != null) "chosen" else "none" }
    val keyState = remember(UiTick.n) { store.keyState }

    LaunchedEffect(Unit) {
        while (true) {
            heading = sensors.heading()
            reading = sensors.level
            delay(50)
        }
    }

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {
        // IT SCROLLS. Baba, 15.9.2026: *"I cannot reach bottom of the settings... Everything should
        // be scrollable everywhere."* A settings face that is one screen tall today is two screens
        // tall the moment a row is added, and the rows at the bottom are the ones nobody can reach
        // — so it scrolls whether or not it currently needs to, and the safe area keeps the last
        // row clear of the gesture bar.
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Row(
                Modifier.fillMaxWidth().height(46.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("settings", Paint.Dim, size = 13)
                Label(if (sensors.declination != null) "true north" else "magnetic north", Paint.Dim, size = 11)
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Paint.Veil).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Label("✕", Paint.Sand, size = 18) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CompassDial(heading) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { BubbleVial(reading) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label("${heading.toInt()}° ${Geo.cardinal(heading)}", Paint.Sand, size = 15)
                Label(
                    text = if (reading.trustworthy) "tilt ${fmt(reading.tilt)}°" else "hold it still",
                    colour = when {
                        !reading.trustworthy -> Paint.Dim
                        reading.level -> Paint.Green
                        else -> Paint.Sand
                    },
                    size = 15,
                )
            }
            SettingRow("zero the level on this surface", "set it down first", onZero)

            // The map, chosen the way one-of-many is always chosen (design-language.md 6), and
            // grouped by family because sixteen chips in one block is a wall. Three to a row:
            // more than that across a phone clips the words, so it stacks instead.
            Layers.ALL.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    row.forEach { layer ->
                        val chosen = layer.id == current.id
                        val needsKey = layer.provider != null && store.key(layer.provider) == null
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (chosen) Paint.Amber else Paint.Veil)
                                .alpha(if (needsKey) 0.55f else 1f)
                                .clickable { onPick(layer) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Label(layer.label, if (chosen) Paint.Ground else Paint.Sand, size = 11)
                        }
                    }
                }
            }

            SettingRow("download the offline map, ${Layers.OfflineDownload.LABEL}", mapState, onDownloadMap)
            SettingRow("or choose a .map file", "picker", onChooseMapFile)
            // The credits live here, not over the map. Baba, 15.9.2026: *"I don't want to see
            // copyright OpenStreetMap in my first screen."* Both services require attribution to
            // be shown; neither requires it to be shown on top of the map.
            Label(
                text = Layers.ALL.map { it.attribution }.distinct().joinToString(" · "),
                colour = Paint.Dim,
                size = 9,
                align = TextAlign.Start,
            )
            SettingRow("map credits", current.attribution, {
                Trail.say(current.attribution)
            })
            SettingRow("check the offline map here", "ask it", {
                Trail.say(CanvasHolder.canvas?.diagnose() ?: "the map view is not up yet")
            })
            SettingRow("open the map link in the browser", "mapsforge.org", onOpenMapLink)
            // The address itself, in full, so it can be read off the screen and typed into a
            // desktop browser if the phone is the wrong place to fetch 176 MB.
            Label(Layers.OfflineDownload.URL, Paint.Dim, size = 9, align = TextAlign.Start)
            SettingRow("folder for exported tracks", exportState, onChooseExportFolder)
            SettingRow("export the last track", if (LastTrack.file != null) "ready" else "none yet", onExport)
            SettingRow("pause or resume the recording", if (recordingPaused) "paused" else "running", onPause)
            SettingRow("API keys, from a file", keyState, onImportKeys)
            Label(
                text = "No key is built into this app. Google's four views and Outdoors each need " +
                    "your own key, picked from a file here; the offline map and OpenStreetMap need none.",
                colour = Paint.Dim,
                size = 10,
                align = TextAlign.Start,
            )
            Label("Mantra Trail v$version", Paint.Dim, size = 10)
        }
    }
}

@Composable
private fun SettingRow(title: String, state: String, onPress: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Paint.Veil)
            .clickable(onClick = onPress)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, Paint.Sand, size = 12, align = TextAlign.Start)
        Label(state, Paint.Amber, size = 11)
    }
}

private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%+.1f", v)

@Composable
private fun CompassDial(heading: Double) {
    Canvas(Modifier.size(140.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 6f
        drawCircle(Paint.Dim, radius = r, center = c, style = Stroke(2f))
        for (tick in 0 until 72) {
            val angle = Math.toRadians(tick * 5.0 - heading - 90.0)
            val long = tick % 6 == 0
            val inner = r * if (long) 0.84f else 0.92f
            val colour = if (tick == 0) Paint.Red else if (long) Paint.Sand else Paint.Dim
            drawLine(
                color = colour,
                start = Offset(c.x + (inner * Math.cos(angle)).toFloat(), c.y + (inner * Math.sin(angle)).toFloat()),
                end = Offset(c.x + (r * Math.cos(angle)).toFloat(), c.y + (r * Math.sin(angle)).toFloat()),
                strokeWidth = if (long) 3f else 1.5f,
            )
        }
        drawLine(Paint.Amber, Offset(c.x, c.y - r * 0.8f), Offset(c.x, c.y + r * 0.2f), 4f)
        drawCircle(Paint.Amber, radius = 5f, center = c)
    }
}

@Composable
private fun BubbleVial(reading: Level.Reading) {
    val (bx, by) = Level.bubble(reading)
    Canvas(Modifier.size(140.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 6f
        drawCircle(Paint.Dim, radius = r, center = c, style = Stroke(2f))
        drawCircle(Paint.Dim, radius = r * 0.18f, center = c, style = Stroke(1.5f))
        drawLine(Paint.Dim, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1f)
        drawLine(Paint.Dim, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1f)
        val colour = when {
            !reading.trustworthy -> Paint.Dim
            reading.level -> Paint.Green
            else -> Paint.Sand
        }
        drawCircle(
            color = colour,
            radius = r * 0.16f,
            center = Offset(c.x + (bx * r * 0.8f).toFloat(), c.y - (by * r * 0.8f).toFloat()),
        )
    }
}

/**
 * A BAR THE HEIGHT OF ITS OWN TEXT. Baba, 15.9.2026: *"only height of this bar is height of the
 * text, not cover my whole map."* So the background belongs to the line, not to the column that
 * holds it, and the ink is thin enough to read the map through.
 */
@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Paint.Bar)
            .padding(horizontal = GAP, vertical = 3.dp),
        content = content,
    )
}

/**
 * EVERY WORD OVER THE MAP CARRIES ITS OWN SHADOW, AND NOTHING CARRIES A BOX.
 *
 * Baba, 15.9.2026: *"don't put there these squares under the buttons... make a shadow, hard
 * shadow behind, so it's seen from the background but it doesn't take much of real estate."*
 *
 * A panel steals map. A shadow steals nothing and works on both a white street and a dark forest,
 * which is the whole difficulty: the background under this text is not one colour, it is every
 * colour, and no single ink is readable on all of them without something behind the letter itself.
 */
@Composable
private fun Label(
    text: String,
    colour: Color,
    size: Int = 14,
    align: TextAlign = TextAlign.Center,
) {
    Text(
        text = text,
        color = colour,
        fontSize = size.sp,
        fontFamily = FontFamily.Monospace,
        textAlign = align,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(
            shadow = Shadow(color = Paint.Ground, offset = Offset(0f, 1.5f), blurRadius = 5f),
        ),
    )
}
