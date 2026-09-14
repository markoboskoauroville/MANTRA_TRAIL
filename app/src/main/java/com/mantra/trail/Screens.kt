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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import kotlinx.coroutines.delay

/**
 * THE WHOLE INTERFACE, RENDERED FROM THE FIRST FRAME (design-language.md 1).
 *
 * Every control below exists whether or not it can be used. The record button is there before
 * there is a fix; the export is there before there is a track; the Google row is there on a build
 * with no key. What changes is whether they are lit and whether they answer a press. Opacity and
 * a guard, never a layout that rearranges itself under the thumb.
 *
 * The gaps are one gap (GAP), the rows are one height, and the way out of the tools face is at the
 * right-hand end of its row, where a thumb already is.
 */
private val GAP = 10.dp
private val ROW = 56.dp

@Composable
fun TrailApp(
    store: Store,
    sensors: Sensors,
    onCanvas: (MapCanvas) -> Unit,
    onWhereAmI: () -> Unit,
    onRecord: () -> Unit,
    onPause: () -> Unit,
    onExport: () -> Unit,
    onChooseMapFile: () -> Unit,
    onZeroLevel: () -> Unit,
) {
    var layer by remember { mutableStateOf(Layers.byId(store.layerId)) }
    var tools by remember { mutableStateOf(false) }
    var canvas by remember { mutableStateOf<MapCanvas?>(null) }

    val fix by Trail.fix.collectAsState()
    val stats by Trail.stats.collectAsState()
    val recordingSince by Trail.recordingSince.collectAsState()
    val paused by Trail.paused.collectAsState()
    val rejected by Trail.rejected.collectAsState()
    val line by Trail.line.collectAsState()
    val note by Trail.note.collectAsState()

    val recording = recordingSince != null

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {

        MapSurface(
            layer = layer,
            store = store,
            fix = fix,
            line = line,
            onCanvas = {
                canvas = it
                onCanvas(it)
            },
        )

        Crosshair(Modifier.align(Alignment.Center), hasFix = fix != null)

        Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(GAP)) {
            FixStrip(fix, sensors)
        }

        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            NoteLine(note)
            TrackStrip(stats, rejected, recording)
            LayerRow(
                current = layer,
                onPick = { picked ->
                    layer = picked
                    store.layerId = picked.id
                    val problem = canvas?.show(picked)
                    Trail.say(problem)
                    if (picked.kind == LayerKind.VECTOR_FILE && store.mapFileUri == null) {
                        onChooseMapFile()
                    }
                },
            )
            ControlRow(
                hasFix = fix != null,
                recording = recording,
                paused = paused,
                canExport = LastTrack.file != null,
                onWhereAmI = onWhereAmI,
                onRecord = onRecord,
                onPause = onPause,
                onExport = onExport,
                onTools = { tools = true },
            )
        }

        if (tools) {
            ToolsFace(
                sensors = sensors,
                fix = fix,
                onZero = onZeroLevel,
                onClose = { tools = false },
            )
        }
    }
}

/**
 * The map itself. Two surfaces, one position: ours, drawn by mapsforge, and Google's, drawn by
 * Google. The controls above do not know which is underneath.
 */
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

    // The line and the dot belong to the app, not to the layer under them, so they are redrawn
    // from the state that arrived rather than rebuilt when the map changes.
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
 * THE CROSSHAIR. It sits at the centre of the screen and it does not move: the map moves under
 * it. That is what makes it a sight rather than a decoration — whatever is in it is what the
 * coordinates at the top describe.
 *
 * It is sand when there is a fix and dim when there is not, because colour is the state channel.
 */
@Composable
private fun Crosshair(modifier: Modifier = Modifier, hasFix: Boolean) {
    val ink = if (hasFix) Paint.Sand else Paint.Dim
    Canvas(modifier.size(96.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val arm = size.minDimension / 2f
        val gap = arm * 0.28f
        val stroke = 2.dp.toPx()
        drawCircle(ink, radius = arm * 0.55f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawLine(ink, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), stroke)
        drawLine(ink, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), stroke)
        drawLine(ink, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), stroke)
        drawLine(ink, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), stroke)
        drawCircle(ink, radius = stroke, center = c)
    }
}

/** Where the phone says it is, how wrong it might be, and what it is standing on. */
@Composable
private fun FixStrip(fix: Fix?, sensors: Sensors) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(fix?.let { Geo.formatLat(it.lat) } ?: "N --- --.---", ink(fix != null))
            Label(fix?.let { Geo.formatLon(it.lon) } ?: "E --- --.---", ink(fix != null))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(
                text = fix?.accuracyM?.let { "±${it.toInt()} m" } ?: "± -",
                colour = accuracyInk(fix?.accuracyM),
            )
            Label(fix?.ele?.let { "${it.toInt()} m" } ?: "- m", ink(fix?.ele != null))
            Label(fix?.satellites?.let { "$it sat" } ?: "- sat", ink(fix?.satellites != null))
            Label(
                text = sensors.declination?.let { "${Geo.cardinal(sensors.heading())} true" }
                    ?: "${Geo.cardinal(sensors.heading())} mag",
                colour = ink(sensors.hasCompass),
            )
        }
    }
}

/**
 * An accuracy is the one number on this screen that means something on its own, so it carries the
 * state colour: amber while it is worse than ten metres, sand once it is good enough to navigate
 * by, dim when the phone will not say.
 */
private fun accuracyInk(metres: Float?): Color = when {
    metres == null -> Paint.Dim
    metres <= 10f -> Paint.Sand
    metres <= TrackRules.MAX_ACCURACY_M -> Paint.Amber
    else -> Paint.Red
}

private fun ink(active: Boolean): Color = if (active) Paint.Sand else Paint.Dim

/** The walk so far. Present when nothing is recording, and quiet. */
@Composable
private fun TrackStrip(stats: TrackStats, rejected: Int, recording: Boolean) {
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Label(Geo.formatDistance(stats.distanceM), ink(recording))
            Label(Geo.formatDuration(stats.durationMs), ink(recording))
            Label("↑ ${stats.ascentM.toInt()} m", ink(recording))
            Label("${stats.points} pts", ink(recording))
            // Refused fixes are shown, always. A track app that hides what it threw away is
            // asking to be trusted about the thing nobody can check.
            Label("$rejected refused", if (rejected > 0) Paint.Amber else Paint.Dim)
        }
    }
}

@Composable
private fun NoteLine(note: String?) {
    Panel(Modifier.alpha(if (note == null) 0f else 1f)) {
        Label(note ?: " ", Paint.Amber, align = TextAlign.Start)
    }
}

/** Four maps, one in force, chosen the way one-of-many is always chosen (design-language.md 6). */
@Composable
private fun LayerRow(current: MapLayer, onPick: (MapLayer) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        Layers.ALL.forEach { layer ->
            val chosen = layer.id == current.id
            val usable = layer.kind != LayerKind.GOOGLE || BuildConfig.HAS_GOOGLE_KEY
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (chosen) Paint.Amber else Paint.Surface)
                    .clickable(enabled = usable) { onPick(layer) },
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    text = layer.label,
                    colour = when {
                        chosen -> Paint.Ground
                        usable -> Paint.Sand
                        else -> Paint.Dim
                    },
                    size = 12,
                )
            }
        }
    }
}

/**
 * The five controls, evenly spaced, in one row that never changes its shape. The record key says
 * what the next press will DO (design-language.md 5): a circle to start, a square to stop.
 */
@Composable
private fun ControlRow(
    hasFix: Boolean,
    recording: Boolean,
    paused: Boolean,
    canExport: Boolean,
    onWhereAmI: () -> Unit,
    onRecord: () -> Unit,
    onPause: () -> Unit,
    onExport: () -> Unit,
    onTools: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
        Key("⊕", "where", enabled = true, lit = hasFix, onClick = onWhereAmI, weight = 1f)
        Key(
            glyph = if (recording) "■" else "●",
            name = "record",
            enabled = true,
            lit = recording,
            tint = Paint.Red,
            onClick = onRecord,
            weight = 1f,
        )
        Key(
            glyph = if (paused) "▶" else "❚❚",
            name = "pause",
            enabled = recording,
            lit = paused,
            onClick = onPause,
            weight = 1f,
        )
        Key("↥", "export", enabled = canExport, lit = false, onClick = onExport, weight = 1f)
        Key("✜", "tools", enabled = true, lit = false, onClick = onTools, weight = 1f)
    }
}

@Composable
private fun RowScope.Key(
    glyph: String,
    name: String,
    enabled: Boolean,
    lit: Boolean,
    onClick: () -> Unit,
    weight: Float,
    tint: Color = Paint.Amber,
) {
    Box(
        Modifier
            .weight(weight)
            .height(ROW)
            .clip(RoundedCornerShape(8.dp))
            .background(if (lit) tint else Paint.Surface)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Label(glyph, if (lit) Paint.Ground else tint, size = 20)
            Label(name, if (lit) Paint.Ground else Paint.Dim, size = 10)
        }
    }
}

/**
 * THE SECOND FACE: the compass and the spirit level, both of which work with no signal at all.
 *
 * Half the screen each, so the level can be read while the tripod is being turned — an instrument
 * that has to be left to be adjusted is an instrument adjusted blind (design-language.md 11).
 */
@Composable
private fun ToolsFace(sensors: Sensors, fix: Fix?, onZero: () -> Unit, onClose: () -> Unit) {
    var heading by remember { mutableStateOf(0.0) }
    var reading by remember { mutableStateOf(sensors.level) }

    // Bounded by the composition: it dies with the face. Twenty a second is smooth to the eye
    // and cheap enough that the sensors, not the screen, set the pace.
    LaunchedEffect(Unit) {
        while (true) {
            heading = sensors.heading()
            reading = sensors.level
            delay(50)
        }
    }

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {
        Column(Modifier.fillMaxSize().padding(GAP), verticalArrangement = Arrangement.spacedBy(GAP)) {
            Row(
                Modifier.fillMaxWidth().height(40.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("compass and level", Paint.Dim, size = 12)
                Label(
                    text = if (sensors.declination != null) "true north" else "magnetic north",
                    colour = if (sensors.declination != null) Paint.Sand else Paint.Amber,
                    size = 12,
                )
                // THE WAY OUT IS AT THE RIGHT-HAND END OF ITS ROW, on every screen, always.
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Paint.Surface)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Label("✕", Paint.Sand, size = 18) }
            }

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                CompassDial(heading)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label("${heading.toInt()}° ${Geo.cardinal(heading)}", Paint.Sand, size = 18)
                Label(
                    text = fix?.let { "${Geo.formatLat(it.lat)}  ${Geo.formatLon(it.lon)}" } ?: "no fix",
                    colour = ink(fix != null),
                    size = 12,
                )
            }

            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                BubbleVial(reading)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label(
                    text = if (reading.trustworthy) {
                        "${fmt(reading.pitch)}°  ${fmt(reading.roll)}°   tilt ${fmt(reading.tilt)}°"
                    } else {
                        "hold it still"
                    },
                    colour = when {
                        !reading.trustworthy -> Paint.Dim
                        reading.level -> Paint.Green
                        else -> Paint.Sand
                    },
                    size = 16,
                )
                Box(
                    Modifier
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Surface)
                        .clickable(onClick = onZero)
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Label("zero here", Paint.Amber, size = 12) }
            }
        }
    }
}

private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%+.1f", v)

@Composable
private fun CompassDial(heading: Double) {
    Canvas(Modifier.size(260.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 8f
        drawCircle(Paint.Slate, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        // The card turns, the needle does not: a compass is read at the top of the dial.
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

/** A round vial. Green when it is level, sand when it is not, and never red: a tripod is not a fault. */
@Composable
private fun BubbleVial(reading: Level.Reading) {
    val (bx, by) = Level.bubble(reading)
    Canvas(Modifier.size(200.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 6f
        drawCircle(Paint.Slate, radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        drawCircle(Paint.Slate, radius = r * 0.18f, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
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
    )
}
