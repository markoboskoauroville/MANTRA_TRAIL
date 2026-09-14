package com.mantra.trail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * THE WHOLE INTERFACE, AND THERE ARE EXACTLY TWO VIEWS OF IT (design-language.md 10, the system
 * bars section, written from the v4 screenshot).
 *
 *   THE ORDINARY VIEW   every control visible and every one of them inside the safe area. The map
 *                       runs full bleed behind the bars because a map is better for it; nothing
 *                       that can be pressed or read goes under them.
 *   THE FULL SCREEN     the system's bars are hidden and ours go with them. One picture, and one
 *                       way out at the right-hand end of its row, because it has taken away the
 *                       system's own.
 *
 * Within a view nothing appears or disappears: every key exists from the first frame and is dimmed
 * until it can be used.
 */
private val GAP = 10.dp
private val ROW = 58.dp

/** Bumped when a picker changes something the screen shows, so the rows redraw. */
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
    onZeroLevel: () -> Unit,
    onFullScreen: (Boolean) -> Unit,
) {
    var layer by remember { mutableStateOf(Layers.byId(store.layerId)) }
    var tools by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    var caching by remember { mutableStateOf(false) }

    val fix by Trail.fix.collectAsState()
    val stats by Trail.stats.collectAsState()
    val recordingSince by Trail.recordingSince.collectAsState()
    val paused by Trail.paused.collectAsState()
    val rejected by Trail.rejected.collectAsState()
    val line by Trail.line.collectAsState()
    val note by Trail.note.collectAsState()
    val recording = recordingSince != null
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {

        MapSurface(layer = layer, store = store, fix = fix, line = line, onCanvas = onCanvas)

        Crosshair(Modifier.align(Alignment.Center), hasFix = fix != null)

        if (full) {
            // The only control in the full view, in the same corner the ✕ always occupies.
            Box(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(GAP)) {
                ViewKey(glyph = "✕") {
                    full = false
                    onFullScreen(false)
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).safeDrawingPadding().padding(GAP),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(GAP),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.weight(1f)) { FixStrip(fix, sensors) }
                    ViewKey(glyph = "⛶") {
                        full = true
                        onFullScreen(true)
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).safeDrawingPadding().padding(GAP),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                NoteLine(note)
                TrackStrip(stats, rejected, recording)
                LayerRow(
                    current = layer,
                    onPick = { picked ->
                        layer = picked
                        store.layerId = picked.id
                        Trail.say(CanvasHolder.canvas?.show(picked))
                        if (picked.kind == LayerKind.VECTOR_FILE && store.mapFileUri == null) {
                            onChooseMapFile()
                        }
                    },
                )
                // TWO ROWS OF THREE, not six narrow keys: six across a 390 px phone is 53 px each
                // and the word under the glyph clips (design-language.md 10, STACK IT).
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    Key("⊕", "where", enabled = true, lit = fix != null, onClick = onWhereAmI)
                    Key(
                        glyph = if (recording) "■" else "●",
                        name = if (recording) "stop" else "record",
                        enabled = true,
                        lit = recording,
                        tint = Paint.Red,
                        onClick = onRecord,
                    )
                    Key(
                        glyph = if (paused) "▶" else "❚❚",
                        name = if (paused) "resume" else "pause",
                        enabled = recording,
                        lit = paused,
                        onClick = onPause,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    Key(
                        glyph = "CH",
                        name = "cache view",
                        enabled = Caching.refusal(layer) == null && !caching,
                        lit = caching,
                        onClick = {
                            val refusal = Caching.refusal(layer)
                            if (refusal != null) {
                                Trail.say(refusal)
                                return@Key
                            }
                            val canvas = CanvasHolder.canvas
                            val box = canvas?.visibleBox()
                            if (canvas == null || box == null) {
                                Trail.say("The map has not settled yet")
                                return@Key
                            }
                            val plan = Caching.plan(box[0], box[1], box[2], box[3], canvas.currentZoom(), layer)
                            if (plan.tiles.isEmpty()) {
                                Trail.say("Nothing to fetch at this zoom")
                                return@Key
                            }
                            caching = true
                            Trail.say(
                                "Caching ${plan.tiles.size} tiles, zoom ${plan.fromZoom} to ${plan.toZoom}, about " +
                                    Caching.formatBytes(Caching.estimateBytes(plan.tiles.size)) +
                                    if (plan.truncated) " (of ${plan.wanted}: zoom in for the rest)" else ""
                            )
                            scope.launch {
                                val failed = canvas.cacheVisible(layer, plan) { done, total, bad ->
                                    Trail.say("Caching $done of $total" + if (bad > 0) ", $bad did not come" else "")
                                }
                                caching = false
                                Trail.say(
                                    if (failed == 0) {
                                        "Cached ${plan.tiles.size} tiles. This view works offline now."
                                    } else {
                                        "Cached ${plan.tiles.size - failed} of ${plan.tiles.size}. $failed did not come: press CH again."
                                    }
                                )
                            }
                        },
                    )
                    Key("↥", "export", enabled = LastTrack.file != null, lit = false, onClick = onExport)
                    Key("✜", "tools", enabled = true, lit = false, onClick = { tools = true })
                }
            }
        }

        if (tools) {
            ToolsFace(
                sensors = sensors,
                store = store,
                fix = fix,
                version = version,
                onZero = onZeroLevel,
                onChooseMapFile = onChooseMapFile,
                onChooseExportFolder = onChooseExportFolder,
                onImportKeys = onImportKeys,
                onClose = { tools = false },
            )
        }
    }
}

@Composable
private fun MapSurface(
    layer: MapLayer,
    store: Store,
    fix: Fix?,
    line: List<Fix>,
    onCanvas: (MapCanvas) -> Unit,
) {
    if (layer.kind == LayerKind.GOOGLE) {
        if (BuildConfig.HAS_GOOGLE_KEY) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                properties = MapProperties(mapType = MapType.TERRAIN),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = false),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label("Google needs a key in this build", Paint.Dim)
            }
        }
        return
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val made = MapCanvas(context, store)
            CanvasHolder.canvas = made
            onCanvas(made)
            Trail.say(made.show(layer))
            made.view
        },
    )

    LaunchedEffect(line.size, fix?.timeMs, layer.id) {
        CanvasHolder.canvas?.drawTrack(line)
        CanvasHolder.canvas?.drawPosition(fix)
    }
}

/** One place the composable side can reach the view it made, without passing it through state. */
object CanvasHolder {
    var canvas: MapCanvas? = null
}

/**
 * THE CROSSHAIR sits at the centre and does not move: the map moves under it. That is what makes
 * it a sight rather than a decoration.
 */
@Composable
private fun Crosshair(modifier: Modifier = Modifier, hasFix: Boolean) {
    val ink = if (hasFix) Paint.Sand else Paint.Dim
    Canvas(modifier.size(96.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val arm = size.minDimension / 2f
        val gap = arm * 0.28f
        val stroke = 2.dp.toPx()
        drawCircle(ink, radius = arm * 0.55f, center = c, style = Stroke(stroke))
        drawLine(ink, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), stroke)
        drawLine(ink, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), stroke)
        drawLine(ink, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), stroke)
        drawLine(ink, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), stroke)
        drawCircle(ink, radius = stroke, center = c)
    }
}

@Composable
private fun FixStrip(fix: Fix?, sensors: Sensors) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(fix?.let { Geo.formatLat(it.lat) } ?: "N -- --.---", ink(fix != null), size = 13)
            Label(fix?.let { Geo.formatLon(it.lon) } ?: "E -- --.---", ink(fix != null), size = 13)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(fix?.accuracyM?.let { "±${it.toInt()} m" } ?: "± -", accuracyInk(fix?.accuracyM), size = 12)
            Label(fix?.ele?.let { "${it.toInt()} m" } ?: "- m", ink(fix?.ele != null), size = 12)
            Label(fix?.satellites?.let { "$it sat" } ?: "- sat", ink(fix?.satellites != null), size = 12)
            Label(
                text = Geo.cardinal(sensors.heading()) + if (sensors.declination != null) " true" else " mag",
                colour = ink(sensors.hasCompass),
                size = 12,
            )
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

@Composable
private fun TrackStrip(stats: TrackStats, rejected: Int, recording: Boolean) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(Geo.formatDistance(stats.distanceM), ink(recording), size = 12)
            Label(Geo.formatDuration(stats.durationMs), ink(recording), size = 12)
            Label("↑ ${stats.ascentM.toInt()} m", ink(recording), size = 12)
            Label("${stats.points} pts", ink(recording), size = 12)
            Label("$rejected refused", if (rejected > 0) Paint.Amber else Paint.Dim, size = 12)
        }
    }
}

@Composable
private fun NoteLine(note: String?) {
    Panel(Modifier.alpha(if (note == null) 0f else 1f)) {
        Label(note ?: " ", Paint.Amber, size = 12, align = TextAlign.Start)
    }
}

/**
 * Four maps, one in force (design-language.md 6). Each says in one word what it does with no
 * signal, because that is the only question that matters about a map in the mountains.
 */
@Composable
private fun LayerRow(current: MapLayer, onPick: (MapLayer) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        Layers.ALL.forEach { layer ->
            val chosen = layer.id == current.id
            val usable = layer.kind != LayerKind.GOOGLE || BuildConfig.HAS_GOOGLE_KEY
            Box(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (chosen) Paint.Amber else Paint.Surface)
                    .clickable(enabled = usable) { onPick(layer) },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Label(
                        text = shortName(layer),
                        colour = if (chosen) Paint.Ground else if (usable) Paint.Sand else Paint.Dim,
                        size = 12,
                    )
                    Label(
                        text = offlineWord(layer),
                        colour = if (chosen) Paint.Ground else Paint.Dim,
                        size = 9,
                    )
                }
            }
        }
    }
}

private fun shortName(layer: MapLayer): String = when (layer.id) {
    Layers.OAM.id -> "OAM"
    Layers.TK25.id -> "TK25"
    Layers.OPENTOPO.id -> "Topo"
    else -> "Google"
}

private fun offlineWord(layer: MapLayer): String = when (layer.offline) {
    MapLayer.Offline.COMPLETE -> "offline"
    MapLayer.Offline.CACHED_ONLY -> "cached"
    MapLayer.Offline.NONE -> "online"
}

@Composable
private fun RowScope.Key(
    glyph: String,
    name: String,
    enabled: Boolean,
    lit: Boolean,
    onClick: () -> Unit,
    tint: Color = Paint.Amber,
) {
    Box(
        Modifier
            .weight(1f)
            .height(ROW)
            .clip(RoundedCornerShape(8.dp))
            .background(if (lit) tint else Paint.Surface)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(glyph, if (lit) Paint.Ground else tint, size = 18)
            Label(name, if (lit) Paint.Ground else Paint.Dim, size = 10)
        }
    }
}

/** The one key that changes which of the two views is on, always in the same corner. */
@Composable
private fun ViewKey(glyph: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Paint.Surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Label(glyph, Paint.Sand, size = 18) }
}

/**
 * THE SECOND FACE: the compass, the level, and the few settings this app has. All of it works
 * with no signal, and the way out is at the right-hand end of its top row.
 */
@Composable
private fun ToolsFace(
    sensors: Sensors,
    store: Store,
    fix: Fix?,
    version: String,
    onZero: () -> Unit,
    onChooseMapFile: () -> Unit,
    onChooseExportFolder: () -> Unit,
    onImportKeys: () -> Unit,
    onClose: () -> Unit,
) {
    var heading by remember { mutableStateOf(0.0) }
    var reading by remember { mutableStateOf(sensors.level) }

    // What a picker changed is read again when UiTick moves, which is the whole reason it exists.
    val mapFileState = remember(UiTick.n) { if (store.mapFileUri != null) "chosen" else "none chosen" }
    val exportState = remember(UiTick.n) { if (store.exportTreeUri != null) "chosen" else "none chosen" }
    val keyState = remember(UiTick.n) { if (store.keyCount > 0) "${store.keyCount} held" else "none held" }

    // Bounded by the composition: it dies with the face.
    LaunchedEffect(Unit) {
        while (true) {
            heading = sensors.heading()
            reading = sensors.level
            delay(50)
        }
    }

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Row(
                Modifier.fillMaxWidth().height(46.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("compass · level · settings", Paint.Dim, size = 12)
                Label(
                    text = if (sensors.declination != null) "true north" else "magnetic north",
                    colour = if (sensors.declination != null) Paint.Sand else Paint.Amber,
                    size = 12,
                )
                ViewKey(glyph = "✕", onClick = onClose)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CompassDial(heading) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { BubbleVial(reading) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label("${heading.toInt()}° ${Geo.cardinal(heading)}", Paint.Sand, size = 16)
                Label(
                    text = if (reading.trustworthy) {
                        "${fmt(reading.pitch)}° ${fmt(reading.roll)}°  tilt ${fmt(reading.tilt)}°"
                    } else {
                        "hold it still"
                    },
                    colour = when {
                        !reading.trustworthy -> Paint.Dim
                        reading.level -> Paint.Green
                        else -> Paint.Sand
                    },
                    size = 14,
                )
            }

            SettingRow("zero the level on this surface", "set it down first", onZero)
            SettingRow(
                "offline map file",
                mapFileState,
                onChooseMapFile,
            )
            SettingRow(
                "folder for exported tracks",
                exportState,
                onChooseExportFolder,
            )
            // A KEY IS IMPORTED FROM A FILE, BY SHAPE, AND NEVER SHOWN (secrets.md). What is on
            // the screen is how many were found, not any part of one.
            SettingRow(
                "API keys, from a file",
                keyState,
                onImportKeys,
            )
            Label(
                text = "Google's own key is read from the app at install, so a key imported here " +
                    "drives tile services that take one in the URL, not Google's view.",
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
    // Label on its own line above the state and the action, so nothing has to be narrowed to fit
    // and nothing clips in either language (design-language.md 10).
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Paint.Surface)
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
    Canvas(Modifier.size(150.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 6f
        drawCircle(Paint.Slate, radius = r, center = c, style = Stroke(3f))
        for (tick in 0 until 72) {
            val angle = Math.toRadians(tick * 5.0 - heading - 90.0)
            val long = tick % 6 == 0
            val inner = r * if (long) 0.84f else 0.92f
            val ink = if (tick == 0) Paint.Red else Paint.Sand
            drawLine(
                color = if (long) ink else Paint.Dim,
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
    Canvas(Modifier.size(150.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 6f
        drawCircle(Paint.Slate, radius = r, center = c, style = Stroke(3f))
        drawCircle(Paint.Slate, radius = r * 0.18f, center = c, style = Stroke(2f))
        drawLine(Paint.Slate, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.5f)
        drawLine(Paint.Slate, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.5f)
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

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Paint.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

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
    )
}
