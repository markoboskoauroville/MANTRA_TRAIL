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
private val KEY = 52.dp

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
    onZeroLevel: () -> Unit,
    onBare: (Boolean) -> Unit,
) {
    var layer by remember { mutableStateOf(Layers.byId(store.layerId)) }
    var settings by remember { mutableStateOf(false) }
    var bare by remember { mutableStateOf(false) }
    var caching by remember { mutableStateOf(false) }

    val fix by Trail.fix.collectAsState()
    val stats by Trail.stats.collectAsState()
    val recordingSince by Trail.recordingSince.collectAsState()
    val paused by Trail.paused.collectAsState()
    val note by Trail.note.collectAsState()
    val recording = recordingSince != null
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {

        MapSurface(layer = layer, store = store, fix = fix, line = Trail.line.collectAsState().value, onCanvas = onCanvas)

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
            Column(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).safeDrawingPadding().padding(GAP),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                FixLine(fix)
            }

            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).safeDrawingPadding().padding(GAP),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                NoteLine(note)
                TrackLine(stats, recording)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                    Key("⊕", lit = fix != null, onClick = onWhereAmI)
                    RecordKey(recording = recording, paused = paused, onPress = onRecord)
                    Key(
                        glyph = "CH",
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
                                "Caching ${plan.tiles.size} tiles, about " +
                                    Caching.formatBytes(Caching.estimateBytes(plan.tiles.size)) +
                                    if (plan.truncated) ", of ${plan.wanted}: zoom in for the rest" else ""
                            )
                            scope.launch {
                                val failed = canvas.cacheVisible(layer, plan) { done, total, bad ->
                                    Trail.say("Caching $done of $total" + if (bad > 0) ", $bad did not come" else "")
                                }
                                caching = false
                                Trail.say(
                                    if (failed == 0) "Cached ${plan.tiles.size} tiles: this view works offline now"
                                    else "Cached ${plan.tiles.size - failed} of ${plan.tiles.size}, press CH again for the rest"
                                )
                            }
                        },
                    )
                    // ONE BUTTON FOR THE MAP. It says which one is on and turns to the next.
                    Key(
                        glyph = layer.short,
                        lit = false,
                        onClick = {
                            val picked = Layers.next(layer)
                            layer = picked
                            store.layerId = picked.id
                            Trail.say(CanvasHolder.canvas?.show(picked))
                        },
                    )
                    Key("⚙", lit = false, onClick = { settings = true })
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
                    Trail.say(CanvasHolder.canvas?.show(picked))
                },
                onZero = onZeroLevel,
                onChooseMapFile = onChooseMapFile,
                onChooseExportFolder = onChooseExportFolder,
                onImportKeys = onImportKeys,
                onDownloadMap = onDownloadMap,
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
@Composable
private fun CentreMark(hasFix: Boolean) {
    val ink = if (hasFix) Paint.Sand else Paint.Dim
    Canvas(Modifier.size(18.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(ink, radius = size.minDimension / 2f - 1f, center = c, style = Stroke(1.5.dp.toPx()))
        drawCircle(ink, radius = 1.5.dp.toPx(), center = c)
    }
}

/** One line of numbers, and only the ones that decide something. */
@Composable
private fun FixLine(fix: Fix?) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(fix?.let { Geo.formatLat(it.lat) } ?: "N -- --.---", ink(fix != null), size = 13)
            Label(fix?.let { Geo.formatLon(it.lon) } ?: "E -- --.---", ink(fix != null), size = 13)
            Label(fix?.accuracyM?.let { "±${it.toInt()} m" } ?: "± -", accuracyInk(fix?.accuracyM), size = 13)
            Label(fix?.ele?.let { "${it.toInt()} m" } ?: "- m", ink(fix?.ele != null), size = 13)
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
private fun TrackLine(stats: TrackStats, recording: Boolean) {
    Panel(Modifier.alpha(if (recording) 1f else 0f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(Geo.formatDistance(stats.distanceM), Paint.Sand, size = 13)
            Label(Geo.formatDuration(stats.durationMs), Paint.Sand, size = 13)
            Label("↑ ${stats.ascentM.toInt()} m", Paint.Sand, size = 13)
            Label("${stats.points} pts", Paint.Sand, size = 13)
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
        Modifier
            .weight(1f)
            .height(KEY)
            .clip(RoundedCornerShape(10.dp))
            .background(if (lit) Paint.Amber.copy(alpha = 0.85f) else Paint.Veil)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Label(glyph, if (lit) Paint.Ground else Paint.Sand, size = 17)
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
        Canvas(Modifier.size(30.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 2f
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
    onExport: () -> Unit,
    onPause: () -> Unit,
    recordingPaused: Boolean,
    onClose: () -> Unit,
) {
    var heading by remember { mutableStateOf(0.0) }
    var reading by remember { mutableStateOf(sensors.level) }
    val mapState = remember(UiTick.n) { store.offlineMapState }
    val exportState = remember(UiTick.n) { if (store.exportTreeUri != null) "chosen" else "none" }
    val keyState = remember(UiTick.n) { if (store.keyCount > 0) "${store.keyCount} held" else "none" }

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

            // The map, chosen the way one-of-many is always chosen (design-language.md 6).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Layers.ALL.forEach { layer ->
                    val chosen = layer.id == current.id
                    val usable = layer.kind != LayerKind.GOOGLE || BuildConfig.HAS_GOOGLE_KEY
                    Box(
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (chosen) Paint.Amber else Paint.Veil)
                            .alpha(if (usable) 1f else 0.4f)
                            .clickable(enabled = usable) { onPick(layer) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(layer.label, if (chosen) Paint.Ground else Paint.Sand, size = 11)
                    }
                }
            }

            SettingRow("download the offline map, ${Layers.OfflineDownload.LABEL}", mapState, onDownloadMap)
            SettingRow("or choose a .map file", "picker", onChooseMapFile)
            SettingRow("folder for exported tracks", exportState, onChooseExportFolder)
            SettingRow("export the last track", if (LastTrack.file != null) "ready" else "none yet", onExport)
            SettingRow("pause or resume the recording", if (recordingPaused) "paused" else "running", onPause)
            SettingRow("API keys, from a file", keyState, onImportKeys)
            Label(
                text = "Google's key is read from the app at install, so a key imported here is for " +
                    "tile services that take one in the URL, not Google's view.",
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

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Paint.Veil)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
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
