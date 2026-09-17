package com.mantra.trail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

/** The crosshair over the map: bigger than the key's mark, and far quieter. */
private val CROSS = 34.dp

/** The compass's three states, in the order T turns through them. */
private const val COMPASS_DARK = 0
private const val COMPASS_NIGHT = 1
private const val COMPASS_OFF = 2

/** What the little compass is doing: north at the top, or the way he is walking at the top. */
private const val NORTH_UP = 1
private const val NORTH_FOLLOW = 2

/** The five a line can be drawn in: green, amber, red, blue, white. */
/** VTM's own themes, in the order they are offered. The plain one leads because it is plainest. */
private val THEMES = listOf("MANTRA", "DEFAULT", "OSMARENDER", "NEWTRON", "BIKER", "TRONRENDER")

private val TRACK_COLOURS = listOf(0xFF34D399L, 0xFFE8A64BL, 0xFFEF4444L, 0xFF60A5FAL, 0xFFF2DDB4L)

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
    onCanvas: (VtmCanvas) -> Unit,
    onWhereAmI: () -> Unit,
    onRecord: () -> Unit,
    onPause: () -> Unit,
    onChooseMapFile: () -> Unit,
    onChooseExportFolder: () -> Unit,
    onImportKeys: () -> Unit,
    onDownloadMap: () -> Unit,
    onOpenMapLink: () -> Unit,
    onBare: (Boolean) -> Unit,
    tracks: () -> List<Folder.Entry>,
    folderLabel: String,
    onRenameJustFinished: (java.io.File, String) -> Unit,
    onDeleteTrack: (Folder.Entry) -> Unit,
    onRenameTrack: (Folder.Entry, String) -> Unit,
    onShowTrack: (Folder.Entry) -> Unit,
    onTestTiles: () -> Unit,
    onDownloadOam: (Oam.Region) -> Unit,
    onSaveRoute: (List<Pair<Double, Double>>) -> Unit,
    onFindWays: (List<Pair<Double, Double>>, String, Int) -> Unit,
    onSaveOption: (Routing.Option) -> Unit,
    routeOptions: List<Routing.Option>,
) {
    var layer by remember { mutableStateOf(Layers.byId(store.layerId)) }
    var settings by remember { mutableStateOf(false) }
    var compass by remember { mutableIntStateOf(store.compassMode) }
    var points by remember { mutableStateOf(store.routePoints) }
    var routeMenu by remember { mutableStateOf(false) }
    var bare by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(13) }
    var ready by remember { mutableStateOf(false) }
    // LOCKED TO THE MIDDLE (15.9.2026). One press centres and holds; the next lets the map go.
    var follow by remember { mutableStateOf(false) }
    var lastCentreTap by remember { mutableLongStateOf(0L) }
    // 0 free, 1 north up, 2 turning with the walk (16.9.2026, as Google's little compass does).
    var northMode by remember { mutableIntStateOf(store.northMode) }
    var mapTurn by remember { mutableFloatStateOf(0f) }

    val fix by Trail.fix.collectAsState()
    val stats by Trail.stats.collectAsState()
    val recordingSince by Trail.recordingSince.collectAsState()
    val paused by Trail.paused.collectAsState()
    val note by Trail.note.collectAsState()
    val net by Net.line.collectAsState()
    val recording = recordingSince != null
    val justFinished by Trail.justFinished.collectAsState()
    var showTracks by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // The map the app opened on, drawn as soon as the view is real and not a moment before.
    LaunchedEffect(ready) {
        if (ready) showLayer(store, layer)
    }

    // The network line: sampled from the phone's own byte counters once a second, so it reports
    // mapsforge's tile fetching too, which happens inside the library. Bounded by the composition.
    LaunchedEffect(Unit) {
        while (true) {
            Net.sample(System.currentTimeMillis())
            delay(1_000)
        }
    }

    // The zoom on the screen follows the map rather than the other way round. Twice a second is
    // enough for a number that changes when a thumb moves, and it stops with the composition.
    LaunchedEffect(Unit) {
        while (true) {
            CanvasHolder.canvas?.currentZoom()?.let {
                if (it != zoom) {
                    zoom = it
                    // A blank offline map at a zoom explains itself now, rather than waiting to
                    // be photographed: the file is asked what it holds under the crosshair.
                    if (layer.kind == LayerKind.VECTOR_FILE) {
                        Trail.say(CanvasHolder.canvas?.emptyHere())
                    }
                }
            }
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {

        MapSurface(
            store = store,
            fix = fix,
            line = Trail.line.collectAsState().value,
            follow = follow,
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
            CentreCross()

            // THE LITTLE COMPASS (16.9.2026), where Google keeps it. It shows which way the map
            // is facing; one tap puts north up and centres him, the next turns the map so the
            // way he is walking is up. Its needle is the map's own angle, so it never disagrees
            // with what is under it.
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 44.dp, end = 8.dp)) {
                LittleCompass(
                    turn = mapTurn,
                    following = northMode == NORTH_FOLLOW,
                    modifier = Modifier.align(Alignment.TopEnd),
                    onTap = {
                        northMode = if (northMode == NORTH_FOLLOW) NORTH_UP else NORTH_FOLLOW
                        store.northMode = northMode
                        if (northMode == NORTH_UP) {
                            CanvasHolder.canvas?.setMapRotation(0f)
                            onWhereAmI()
                        }
                    },
                )
            }
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
                FixLine(fix, zoom, layer)
            }

            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).safeDrawingPadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // A line with nothing in it takes no room at all now. It used to be drawn at zero
                // opacity, which is invisible but still occupies its height — and with a
                // background behind the column, that height was a shaded band over the map.
                if (net != null) StatusLine(net)
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
                    // T CYCLES THE COMPASS: dark, night, off. Dark ink for a light map, light ink
                    // for a dark one, and off for neither — three presses to come round.
                    Key(
                        glyph = "T",
                        lit = compass != COMPASS_OFF,
                        onClick = {
                            compass = (compass + 1) % 3
                            store.compassMode = compass
                        },
                    )
                    // ONE TAP CENTRES, TWO IN A ROW LOCK (15.9.2026). A second tap inside a
                    // second is somebody saying "and keep it there"; a second tap later is just
                    // somebody centring again. While it is locked, one tap lets the map go.
                    MarkKey(
                        lit = follow,
                        onClick = {
                            val now = System.currentTimeMillis()
                            when {
                                // No sentence for either: the mark's own centre fills when it is
                                // locked, and a line of text for a state that is already drawn is
                                // a line of text over the map (15.9.2026).
                                follow -> follow = false

                                now - lastCentreTap < 1_000L -> follow = true

                                else -> onWhereAmI()
                            }
                            lastCentreTap = now
                        },
                    ) { hasFix -> PositionMark(hasFix, locked = follow) }
                    RecordKey(recording = recording, paused = paused, onPress = onRecord)
                    // ONE BUTTON FOR THE MAP. It says which one is on and turns to the next.
                    Key(
                        glyph = layer.short,
                        lit = false,
                        onClick = {
                            // ONLY THROUGH WHAT CAN ACTUALLY DRAW. Baba, 15.9.2026: *"I want to
                            // show only map which is selected in the settings, not going through
                            // all these different ways."* A family with no key and a family with
                            // no file are presses that do nothing, so the key skips them and the
                            // settings list still holds every map.
                            val picked = nextUsable(store, layer)
                            layer = picked
                            store.layerId = picked.id
                            scope.launch { showLayer(store, picked) }
                        },
                    )
                    Key("⚙", lit = false, onClick = { settings = true })
                    // ONE KEY FOR POINTS (16.9.2026). Pressing it drops the next one where the
                    // crosshair is — A, then B, then C — and it shows which letter is next. A
                    // long press opens the manager, where they are removed and the ways found.
                    // Two keys for this were two ways of doing one thing.
                    PointKey(
                        letter = Route.letterFor(points.size),
                        placed = points.isNotEmpty(),
                        onTap = {
                            val at = CanvasHolder.canvas?.centre() ?: return@PointKey
                            if (points.size >= Route.MAX_POINTS) {
                                Trail.say("That is as many points as one route holds")
                                return@PointKey
                            }
                            points = points + at
                            store.routePoints = points
                            CanvasHolder.canvas?.setRoutePoints(points)
                        },
                        onLongPress = { routeMenu = true },
                    )
                    Key(glyph = "+", lit = false, onClick = { CanvasHolder.canvas?.zoomIn() })
                }
            }
        }

        // THE POPUP THAT ASKS WHAT THE WALK WAS CALLED. It opens when a recording is written and
        // takes itself away after three seconds, so a walk can be ended with one press and no
        // second thought — but the countdown STOPS the moment he touches the field, because a
        // box that closes while somebody is typing in it is worse than no box at all.
        justFinished?.let { file ->
            // EITHER ANSWER SAVES THE WALK. Cancel means "do not rename it", not "throw it
            // away": the track goes into the folder under the date it already has.
            NameBox(
                current = Tracks.displayName(file.name),
                onCancel = {
                    Trail.dealtWith()
                    onRenameJustFinished(file, Tracks.displayName(file.name))
                },
                onOk = { name ->
                    Trail.dealtWith()
                    onRenameJustFinished(file, name)
                },
            )
        }

        LaunchedEffect(ready) {
            if (ready) CanvasHolder.canvas?.setRoutePoints(points)
        }

        // THE LIGHT IN FRONT OF THE DOT NEEDS THE HEADING, whether or not the compass overlay is
        // on. Five times a second is enough for a light that only moves when the phone turns, and
        // the canvas redraws only when the heading has really moved (bounded by the composition).
        LaunchedEffect(ready) {
            while (true) {
                if (ready) {
                    val heading = sensors.heading()
                    CanvasHolder.canvas?.setHeading(heading)
                    // TURNING WITH THE WALK: the map is turned so that where he is going is up.
                    // Only when he asked for it, and only when the needle has really moved, or
                    // the map would shiver in the hand at every wobble of the magnetometer.
                    if (northMode == NORTH_FOLLOW) {
                        val wanted = (-heading).toFloat()
                        val now = CanvasHolder.canvas?.mapRotationDeg() ?: 0f
                        if (Math.abs(Geo.deltaDeg(now.toDouble(), wanted.toDouble())) > 2.0) {
                            CanvasHolder.canvas?.setMapRotation(wanted)
                        }
                    }
                    mapTurn = CanvasHolder.canvas?.mapRotationDeg() ?: 0f
                }
                delay(200)
            }
        }

        if (routeMenu) {
            RouteMenu(
                store = store,
                points = points,
                found = routeOptions,
                onAdd = {
                    val at = CanvasHolder.canvas?.centre()
                    if (at != null && points.size < Route.MAX_POINTS) {
                        points = points + at
                        store.routePoints = points
                        CanvasHolder.canvas?.setRoutePoints(points)
                    }
                },
                onRemove = { index ->
                    points = points.filterIndexed { i, _ -> i != index }
                    store.routePoints = points
                    CanvasHolder.canvas?.setRoutePoints(points)
                },
                onRoute = { profile, wanted -> onFindWays(points, profile, wanted) },
                onSaveOption = { option -> onSaveOption(option) },
                onSave = { onSaveRoute(points) },
                onClose = { routeMenu = false },
            )
        }

        if (compass != COMPASS_OFF) {
            CompassOverlay(sensors = sensors, night = compass == COMPASS_NIGHT)
        }

        if (showTracks) {
            // THE LIST IS LOADED, NOT COMPUTED. It used to be read from the folder inside the
            // composition, so every tap — a colour, a note, anything — listed the directory again
            // on the main thread. That is why changing the line colour was slow (15.9.2026).
            var loaded by remember { mutableStateOf<List<Folder.Entry>>(emptyList()) }
            var colour by remember { mutableStateOf(store.trackColour) }
            LaunchedEffect(UiTick.n, showTracks) {
                loaded = withContext(Dispatchers.IO) { tracks() }
            }
            TracksFace(
                tracks = loaded,
                folder = folderLabel,
                onChooseFolder = onChooseExportFolder,
                colour = colour,
                onColour = {
                    colour = it
                    store.trackColour = it
                },
                onShow = { entry ->
                    showTracks = false
                    onShowTrack(entry)
                },
                onRename = onRenameTrack,
                onHide = {
                    CanvasHolder.canvas?.clearSavedTrack()
                    Trail.say(null)
                },
                onDelete = onDeleteTrack,
                onClose = { showTracks = false },
            )
        }

        if (settings) {
            SettingsFace(
                store = store,
                current = layer,
                version = version,
                onPick = { picked ->
                    layer = picked
                    store.layerId = picked.id
                    store.rememberStyle(picked)
                    // CHOOSING A MAP CLOSES THE SETTINGS AND SHOWS IT (15.9.2026). Picking one and
                    // then having to find the way out is two decisions where there was one.
                    settings = false
                    scope.launch { showLayer(store, picked) }
                },
                onChooseMapFile = onChooseMapFile,
                onChooseExportFolder = onChooseExportFolder,
                onImportKeys = onImportKeys,
                onDownloadMap = onDownloadMap,
                onOpenMapLink = onOpenMapLink,
                onPause = onPause,
                recordingPaused = paused,
                onTracks = {
                    settings = false
                    showTracks = true
                },
                trackCount = tracks().size,
                onTestTiles = onTestTiles,
                onDownloadOam = onDownloadOam,
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
    follow: Boolean,
    onCanvas: (VtmCanvas) -> Unit,
    onReady: () -> Unit,
) {
    // ONE SURFACE FOR EVERY MAP. Google's own SDK is gone with the key that was compiled in:
    // its tiles now come through the Map Tiles API with the key from the picker, which makes it
    // the same kind of layer as the others and leaves nothing to switch between.
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            // ONE ENGINE (16.9.2026). The CPU renderer is gone from this app: it was the reason
            // the map lagged, and keeping it as a fallback only kept the lag one setting away.
            val made = VtmCanvas(context, store)
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

    LaunchedEffect(line.size, fix?.timeMs, follow) {
        CanvasHolder.canvas?.drawTrack(line)
        CanvasHolder.canvas?.drawPosition(fix)
        if (follow && fix != null) CanvasHolder.canvas?.centreOn(fix)
    }
}

/**
 * THE NEXT MAP THE KEY TURNS TO: the next TICKED one, wherever it lives.
 *
 * This used to walk the FAMILIES and show each one's remembered style, which meant that ticking
 * three Thunderforest maps got him one of them and the other two were unreachable from the map
 * screen (15.9.2026). The list is the order, the ticks are the filter, and a family unticked
 * takes all of its maps out at once while remembering which were ticked inside it.
 *
 * A map that cannot draw — no key, no file — is stepped over too, and if nothing else can be
 * shown the key stays where it is rather than moving to a blank screen.
 */
private fun nextUsable(store: Store, current: MapLayer): MapLayer {
    val all = Layers.ALL
    val from = all.indexOfFirst { it.id == current.id }.let { if (it < 0) 0 else it }
    for (step in 1..all.size) {
        val candidate = all[(from + step) % all.size]
        if (!store.familyInToggle(candidate.family)) continue
        if (!store.inToggle(candidate.id)) continue
        val ready = when {
            candidate.provider != null -> !store.key(candidate.provider).isNullOrEmpty()
            candidate.kind == LayerKind.VECTOR_FILE -> store.hasOfflineMap
            else -> true
        }
        if (ready) return candidate
    }
    return current
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
    // A MAP THAT CANNOT DRAW LEAVES THE SCREEN EMPTY, and an empty screen teaches nothing. The
    // fallback used to be OpenStreetMap, which left the app on 16.9.2026; the offline file is
    // what always works now, and when it is the offline file that failed there is nothing to
    // fall back TO, so the reason is all there is to give.
    if (layer.id == Layers.OFFLINE.id) {
        Trail.say(problem)
        return
    }
    val fallback = attempt(canvas, store, Layers.OFFLINE)
    Trail.say(if (fallback == null) "$problem — showing the offline map meanwhile" else problem)
}

/** One attempt at one layer. Returns null when it drew, or the reason it did not. */
private suspend fun attempt(canvas: VtmCanvas, store: Store, layer: MapLayer): String? {
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
private fun RowScope.MarkKey(
    lit: Boolean = false,
    onClick: () -> Unit,
    mark: @Composable (Boolean) -> Unit,
) {
    val hasFix = Trail.fix.collectAsState().value != null
    Box(
        Modifier
            .weight(1f)
            .height(KEY)
            .clip(RoundedCornerShape(10.dp))
            .background(if (lit) Paint.Amber.copy(alpha = 0.25f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        mark(hasFix)
    }
}

/** One place the composable side can reach the view it made. */
object CanvasHolder {
    var canvas: VtmCanvas? = null
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

/**
 * THE LITTLE COMPASS. A needle in a dark disc: red half to the north, pale half the other way,
 * turned by however much the map has been turned. When it is following the walk it gains an amber
 * ring, so the two states are a colour and not a word.
 *
 * It is 44dp, which is a thumb, and it sits under the top bar at the right-hand edge.
 */
@Composable
private fun LittleCompass(
    turn: Float,
    following: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Paint.Bar)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(30.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            if (following) drawCircle(Paint.Amber, radius = r, center = c, style = Stroke(1.5.dp.toPx()))
            // The needle turns the other way from the map: the map turning east means north is
            // now to the left, and the needle has to say so.
            val angle = Math.toRadians(-turn.toDouble() - 90.0)
            val tip = Offset(
                c.x + (r * 0.78f * Math.cos(angle)).toFloat(),
                c.y + (r * 0.78f * Math.sin(angle)).toFloat(),
            )
            val tail = Offset(
                c.x - (r * 0.78f * Math.cos(angle)).toFloat(),
                c.y - (r * 0.78f * Math.sin(angle)).toFloat(),
            )
            val side = Math.toRadians(-turn.toDouble())
            val left = Offset(
                c.x + (r * 0.26f * Math.cos(side)).toFloat(),
                c.y + (r * 0.26f * Math.sin(side)).toFloat(),
            )
            val right = Offset(
                c.x - (r * 0.26f * Math.cos(side)).toFloat(),
                c.y - (r * 0.26f * Math.sin(side)).toFloat(),
            )
            drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(left.x, left.y)
                    lineTo(right.x, right.y)
                    close()
                },
                Paint.Red,
            )
            drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(tail.x, tail.y)
                    lineTo(left.x, left.y)
                    lineTo(right.x, right.y)
                    close()
                },
                Paint.Sand,
            )
        }
    }
}

/**
 * THE CROSSHAIR IN THE MIDDLE OF THE MAP: four black hairlines, half transparent, and nothing in
 * the middle. Baba, 15.9.2026, and it is the whole specification.
 *
 * Half-transparent black rather than a colour because it has to sit over every map this app can
 * show — a white street, a satellite photograph, a dark forest — and a shadow of the ground is
 * readable on all of them without being a mark anybody looks at. The middle is empty because the
 * middle is the thing being pointed at.
 */
@Composable
private fun CentreCross() {
    Canvas(Modifier.size(CROSS)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val arm = size.minDimension / 2f
        val gap = arm * 0.36f
        val hair = 1.dp.toPx()
        val ink = androidx.compose.ui.graphics.Color(0x80000000)
        drawLine(ink, Offset(c.x - arm, c.y), Offset(c.x - gap, c.y), hair)
        drawLine(ink, Offset(c.x + gap, c.y), Offset(c.x + arm, c.y), hair)
        drawLine(ink, Offset(c.x, c.y - arm), Offset(c.x, c.y - gap), hair)
        drawLine(ink, Offset(c.x, c.y + gap), Offset(c.x, c.y + arm), hair)
    }
}

/**
 * THE MARK ON THE KEY, WHICH DOES NOT CHANGE (15.9.2026: "keep the icon on the action bar same as
 * before"). It is the ring and dot in the position colour, ringed in near-black so it reads on
 * any map, and its centre fills while the map is locked to the middle.
 */
@Composable
private fun PositionMark(hasFix: Boolean, locked: Boolean = false) {
    val ink = if (hasFix) Paint.AmberBright else Paint.Amber
    Canvas(Modifier.size(MARK)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 2f
        drawCircle(Paint.Ground, radius = r, center = c, style = Stroke(3.5.dp.toPx()))
        drawCircle(ink, radius = r, center = c, style = Stroke(2.dp.toPx()))
        drawCircle(Paint.Ground, radius = 2.4.dp.toPx(), center = c)
        drawCircle(ink, radius = if (locked) 2.0.dp.toPx() else 1.6.dp.toPx(), center = c)
        if (locked) drawCircle(ink, radius = r * 0.55f, center = c, style = Stroke(1.dp.toPx()))
    }
}

/** One line of numbers, and only the ones that decide something. */
@Composable
private fun FixLine(fix: Fix?, zoom: Int, layer: MapLayer) {
    Panel {
        Row(
            Modifier.fillMaxWidth().background(Paint.Bar).padding(horizontal = GAP, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // ONE LINE, AND THE MAP'S NAME IS ON IT (15.9.2026). Everything here went to 11sp to
            // make the room rather than anything being dropped: about 55 monospace characters fit
            // across a 390 px phone, and this is fifty. The name carries no family — the key just
            // below says THU or GOO — so "Thunderforest Landscape" reads "Landscape" here.
            Label(fix?.let { Geo.formatLat(it.lat) } ?: "N -- --.---", ink(fix != null), size = 11)
            Label(fix?.let { Geo.formatLon(it.lon) } ?: "E -- --.---", ink(fix != null), size = 11)
            Label(fix?.accuracyM?.let { "±${it.toInt()}m" } ?: "±-", accuracyInk(fix?.accuracyM), size = 11)
            // Speed, because a walking pace is the one number that says whether the fix is
            // moving with him or wandering on its own (15.9.2026).
            Label(Geo.formatSpeed(fix?.speedMs), ink(fix?.speedMs != null), size = 11)
            Label(fix?.ele?.let { "${it.toInt()}m" } ?: "-m", ink(fix?.ele != null), size = 11)
            Label("z$zoom", Paint.Dim, size = 11)
            Label(layer.name, Paint.Amber, size = 11)
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

/**
 * A TICK, and it is a tick rather than two words. Dimmed when the group it belongs to is out of
 * the switcher: the map is still ticked, and it is still not in the toggle, and both of those
 * are true at once.
 */
@Composable
private fun Tick(checked: Boolean, onChange: (Boolean) -> Unit, dimmed: Boolean = false) {
    Box(
        Modifier
            .width(56.dp)
            .height(42.dp)
            .clickable { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val side = size.minDimension
            val stroke = 1.5.dp.toPx()
            val ink = if (dimmed) Paint.Dim else Paint.Amber
            drawRoundRect(
                color = if (checked) ink else Paint.Dim,
                size = androidx.compose.ui.geometry.Size(side, side),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
                style = if (checked) androidx.compose.ui.graphics.drawscope.Fill else Stroke(stroke),
            )
            if (checked) {
                // The mark inside, drawn rather than typed, so it cannot be a font that is missing.
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(side * 0.24f, side * 0.53f)
                    lineTo(side * 0.43f, side * 0.72f)
                    lineTo(side * 0.78f, side * 0.29f)
                }
                drawPath(path, Paint.Ground, style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** The families, named as somebody would say them. */
private fun familyLabel(family: MapLayer.Family): String = when (family) {
    MapLayer.Family.OFFLINE -> "Offline map"
    MapLayer.Family.GOOGLE -> "Google"
}

/** What the network is doing. Absent when nothing is moving and nothing is being waited for. */
@Composable
private fun StatusLine(status: String?) {
    Panel {
        Box(Modifier.fillMaxWidth().background(Paint.Bar).padding(horizontal = GAP, vertical = 2.dp)) {
            Label(status ?: " ", Paint.Sand, size = 11, align = TextAlign.Start)
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
 * THE NAME BOX: empty, OK, cancel, and it waits.
 *
 * Baba, 15.9.2026, rebuilding it from the ground up: *"no timeout anymore, it stays forever...
 * menu just have option OK or cancel, no other confusing text there."*
 *
 * The countdown is gone, and with it the whole apparatus of deciding whether somebody had started
 * typing. A box that waits needs no such apparatus. The field starts empty whatever the track is
 * called, because he is typing a new name, not correcting an old one; the name it has now is the
 * grey text behind, so cancel is never a guess about what it will be left as.
 */
@Composable
private fun NameBox(current: String, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var text by remember(current) { mutableStateOf("") }
    val focus = remember { FocusRequester() }

    // THE CURSOR IS IN THE BOX BEFORE HE TOUCHES IT. Without this the field is focused by a tap
    // he has to know to make, and an empty unfocused field on a dark panel is indistinguishable
    // from a label (15.9.2026).
    LaunchedEffect(current) { focus.requestFocus() }

    Box(
        Modifier.fillMaxSize().background(Paint.Veil).safeDrawingPadding().padding(GAP * 2),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Paint.Ground)
                .padding(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Label("now: $current", Paint.Dim, size = 11, align = TextAlign.Start)
            Label("new name", Paint.Amber, size = 11, align = TextAlign.Start)
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Paint.Sand,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                // The cursor is the bright amber and it blinks, which is Compose's own doing once
                // the field has focus. What was missing was the focus and the frame.
                cursorBrush = SolidColor(Paint.AmberBright),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Paint.Ground)
                    // A FRAME, so the box is a box. On a dark panel a dark field is a label.
                    .border(1.5.dp, Paint.Amber, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 14.dp)
                    .focusRequester(focus),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Veil)
                        .clickable(onClick = onCancel),
                    contentAlignment = Alignment.Center,
                ) { Label("cancel", Paint.Sand, size = 14) }
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (text.isBlank()) Paint.Veil else Paint.Amber)
                        .clickable { if (text.isNotBlank()) onOk(text.trim()) },
                    contentAlignment = Alignment.Center,
                ) {
                    Label("OK", if (text.isBlank()) Paint.Dim else Paint.Ground, size = 14)
                }
            }
        }
    }
}

/**
 * THE TRACK MANAGER. Every recording on the phone, newest first, with what it weighs. Rename it,
 * send it to the chosen folder again, or delete it — and deleting asks a second time, because a
 * walk deleted by a thumb on a hillside cannot be walked again.
 */
@Composable
private fun TracksFace(
    tracks: List<Folder.Entry>,
    folder: String,
    colour: Long,
    onColour: (Long) -> Unit,
    onRename: (Folder.Entry, String) -> Unit,
    onDelete: (Folder.Entry) -> Unit,
    onShow: (Folder.Entry) -> Unit,
    onHide: () -> Unit,
    onChooseFolder: () -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { Trail.sayInManager(null) }
    var renaming by remember { mutableStateOf<Folder.Entry?>(null) }
    var confirming by remember { mutableStateOf<Folder.Entry?>(null) }
    val note by Trail.managerNote.collectAsState()

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Row(
                Modifier.fillMaxWidth().height(46.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("tracks (gpx)", Paint.Dim, size = 13)
                Label("${tracks.size}", Paint.Dim, size = 11)
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Paint.Veil).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Label("✕", Paint.Sand, size = 18) }
            }

            // WHERE THEY ARE. This menu is the folder he chose, showing only GPX, so the folder's
            // own name is the first thing on it: a list of files nobody can find is a list.
            SettingRow("folder", folder, onChooseFolder)

            if (note != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Veil)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Label(note ?: "", Paint.Amber, size = 12, align = TextAlign.Start)
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Label("line", Paint.Dim, size = 11)
                TRACK_COLOURS.forEach { option ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(option))
                            .clickable { onColour(option) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (option == colour) Label("✓", Paint.Ground, size = 12)
                    }
                }
            }
            Label(
                text = "hide the shown track",
                colour = Paint.Dim,
                size = 11,
                modifier = Modifier.clickable { onHide() },
            )

            if (tracks.isEmpty()) {
                Label(
                    text = "No GPX in that folder yet. The red circle starts a walk.",
                    colour = Paint.Dim,
                    size = 12,
                    align = TextAlign.Start,
                )
            }

            tracks.forEach { track ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Veil)
                        .padding(GAP),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // The name he gave it, and the extension after it in the quieter ink: he
                    // renames a name, but what is on the disk is a file (15.9.2026).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Label(track.name, Paint.Sand, size = 13, align = TextAlign.Start)
                        Label(".${track.extension}", Paint.Dim, size = 11)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Label(Tracks.formatSize(track.bytes), Paint.Dim, size = 11)
                        Label(
                            text = "show",
                            colour = Paint.Green,
                            size = 12,
                            modifier = Modifier.clickable { onShow(track) },
                        )
                        Label(
                            text = "rename",
                            colour = Paint.Amber,
                            size = 12,
                            modifier = Modifier.clickable { renaming = track },
                        )
                        Label(
                            text = if (confirming?.uri == track.uri) "sure? delete" else "delete",
                            colour = Paint.Red,
                            size = 12,
                            modifier = Modifier.clickable {
                                if (confirming?.uri == track.uri) {
                                    onDelete(track)
                                    confirming = null
                                } else {
                                    confirming = track
                                }
                            },
                        )
                    }
                }
            }
        }

        renaming?.let { track ->
            NameBox(
                current = track.fileName,
                onCancel = { renaming = null },
                onOk = { name ->
                    renaming = null
                    onRename(track, name)
                },
            )
        }
    }
}

/**
 * THE TOOLS, IN A WINDOW OF THEIR OWN, over the map.
 *
 * Baba, 15.9.2026: the compass and the level are not settings and should not be in the settings.
 * They are instruments somebody reaches for on a hillside, so they are one key away — T — and
 * they cover the map while they are open, because reading a level is the whole of what you are
 * doing while you are doing it.
 */
/**
 * THE COMPASS, OVER THE MAP, EDGE TO EDGE. That is the whole window.
 *
 * Baba, 15.9.2026, twice: *"overlay the compass over the map from edge to the edge... and this
 * bubble thing is going away, please. I'm persistent."* So there is no ground drawn behind it, no
 * second mode to choose between, and no bubble anywhere in the app. The map shows through the
 * dial; the heading is the one number; the way out is where it always is.
 */
@Composable
private fun CompassOverlay(sensors: Sensors, night: Boolean) {
    var heading by remember { mutableStateOf(0.0) }

    // Bounded by the composition: it dies with the overlay.
    LaunchedEffect(Unit) {
        while (true) {
            heading = sensors.heading()
            delay(50)
        }
    }

    // TWO INKS, BECAUSE THERE ARE TWO KINDS OF MAP (15.9.2026). A dark compass disappears on a
    // satellite photograph and a light one disappears on a street map, so the same dial is drawn
    // in near-black for the pale maps and in sand for the dark ones, and T turns from one to the
    // other. Both are half transparent: the map underneath is the thing being read.
    val ink = if (night) Paint.Sand.copy(alpha = 0.75f) else Color(0xB3000000)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CompassDial(heading, full = true, ink = ink)
            Label("${heading.toInt()}° ${Geo.cardinal(heading)}", ink, size = 16)
        }
    }
}


/**
 * A KEY FOR A ROUTE POINT. A tap drops it where the crosshair is; a long press opens the menu.
 * Lit while the point is on the map, so the row says what has been placed without a word on it.
 */
@Composable
private fun RowScope.PointKey(
    letter: String,
    placed: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .height(KEY)
            .clip(RoundedCornerShape(10.dp))
            .background(if (placed) Paint.Amber.copy(alpha = 0.3f) else Color.Transparent)
            .pointerInput(letter) {
                detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Label(letter, if (placed) Paint.AmberBright else Paint.Sand, size = 17)
    }
}

/**
 * THE ROUTE MENU, opened by a long press on either point.
 *
 * Baba, 16.9.2026: A, B and Save in one menu; unticking a point deletes it from the map; Save
 * keeps the pair as a route in the same drawer as a recorded walk, with (AB) in its name so it
 * can be told from one that was walked.
 *
 * Route is the button for the routing that is not written yet. It is here, it says what it will
 * do, and it says plainly that it does not do it — a key that lies about being ready is worse
 * than a key that is missing (silent-failure.md).
 */
@Composable
private fun RouteMenu(
    store: Store,
    points: List<Pair<Double, Double>>,
    found: List<Routing.Option>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onRoute: (String, Int) -> Unit,
    onSaveOption: (Routing.Option) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    var options by remember { mutableIntStateOf(store.routeOptions) }
    var speed by remember { mutableStateOf(store.walkSpeedKmh) }
    var profile by remember { mutableStateOf(store.routeProfile) }
    val enough = points.size >= 2
    val straight = Route.straightMetres(points)

    Box(
        Modifier.fillMaxSize().background(Paint.Veil).safeDrawingPadding().padding(GAP),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Paint.Ground)
                .verticalScroll(rememberScrollState())
                .padding(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Label("route", Paint.Dim, size = 12, align = TextAlign.Start)
                Label(
                    text = if (enough) "${points.size} points · ${Geo.formatDistance(straight)} straight" else "place at least two",
                    colour = if (enough) Paint.Sand else Paint.Dim,
                    size = 11,
                )
            }

            // ONE ROW PER POINT, in the order they will be walked, each with its own way out.
            points.forEachIndexed { index, at ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Veil)
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(Route.letterFor(index), Paint.Amber, size = 15)
                    Label(
                        text = "${Geo.formatLat(at.first)}  ${Geo.formatLon(at.second)}",
                        colour = Paint.Sand,
                        size = 11,
                    )
                    Label(
                        text = "remove",
                        colour = Paint.Red,
                        size = 11,
                        modifier = Modifier.clickable { onRemove(index) },
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Paint.Veil)
                    .clickable(onClick = onAdd)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(
                        text = "+  add ${Route.letterFor(points.size)} where the crosshair is",
                        colour = Paint.Amber,
                        size = 12,
                        align = TextAlign.Start,
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Routing.PROFILES.forEach { name ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (name == profile) Paint.Amber else Paint.Veil)
                            .clickable {
                                profile = name
                                store.routeProfile = name
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(
                            text = when (name) {
                                "trekking" -> "trekking"
                                "hiking-mountain" -> "mountain"
                                else -> "shortest"
                            },
                            colour = if (name == profile) Paint.Ground else Paint.Sand,
                            size = 11,
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("options", Paint.Dim, size = 11)
                (1..5).forEach { n ->
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (n == options) Paint.Amber else Paint.Veil)
                            .clickable {
                                options = n
                                store.routeOptions = n
                            },
                        contentAlignment = Alignment.Center,
                    ) { Label("$n", if (n == options) Paint.Ground else Paint.Sand, size = 12) }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("speed", Paint.Dim, size = 11)
                listOf(3f, 4f, 5f, 6f).forEach { option ->
                    Box(
                        Modifier
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (option == speed) Paint.Amber else Paint.Veil)
                            .clickable {
                                speed = option
                                store.walkSpeedKmh = option
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(
                            text = "${option.toInt()} km/h",
                            colour = if (option == speed) Paint.Ground else Paint.Sand,
                            size = 11,
                        )
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (enough) Paint.Amber else Paint.Veil)
                    .clickable { if (enough) onRoute(profile, options) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(
                        text = "find the ways",
                        colour = if (enough) Paint.Ground else Paint.Dim,
                        size = 13,
                        align = TextAlign.Start,
                    )
                    Label(
                        text = if (enough) "$options to look for" else "place at least two",
                        colour = if (enough) Paint.Ground else Paint.Dim,
                        size = 11,
                    )
                }
            }

            found.forEach { option ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Veil)
                        .clickable { onSaveOption(option) }
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Color(option.colour)))
                        Label(
                            text = "  ${Geo.formatDistance(option.metres.toDouble())}  ↑${option.climbM}m",
                            colour = Paint.Sand,
                            size = 12,
                            align = TextAlign.Start,
                        )
                    }
                    Label(
                        text = Geo.formatDuration(
                            (option.metres / (speed * 1000.0 / 3600.0)).toLong() * 1000L
                        ) + "  save",
                        colour = Paint.Amber,
                        size = 11,
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Veil)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Label("close", Paint.Sand, size = 14) }
                Box(
                    Modifier
                        .weight(1f)
                        .height(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (enough) Paint.Amber else Paint.Veil)
                        .clickable { if (enough) onSave() },
                    contentAlignment = Alignment.Center,
                ) { Label("save points", if (enough) Paint.Ground else Paint.Dim, size = 14) }
            }
        }
    }
}

/**
 * EVERYTHING ELSE LIVES HERE: the tracks, the map choice, the offline map, the folders and the
 * keys. The compass has its own key now and the level is gone. One key on the map screen opens
 * this, and the way out is at the right-hand end of its top row, as on every face of every app
 * here.
 */
@Composable
private fun SettingsFace(
    store: Store,
    current: MapLayer,
    version: String,
    onPick: (MapLayer) -> Unit,
    onChooseMapFile: () -> Unit,
    onChooseExportFolder: () -> Unit,
    onImportKeys: () -> Unit,
    onDownloadMap: () -> Unit,
    onOpenMapLink: () -> Unit,
    onPause: () -> Unit,
    recordingPaused: Boolean,
    onTracks: () -> Unit,
    trackCount: Int,
    onTestTiles: () -> Unit,
    onDownloadOam: (Oam.Region) -> Unit,
    onClose: () -> Unit,
) {
    var answer by remember { mutableStateOf<String?>(null) }
    var theme by remember { mutableStateOf(store.themeName) }
    val mapState = remember(UiTick.n) { store.offlineMapState }
    // The folder BY NAME. "chosen" told him nothing he could act on (15.9.2026).
    val exportState = remember(UiTick.n) {
        store.exportFolderName ?: if (store.exportTreeUri != null) "chosen" else "none yet"
    }
    val keyState = remember(UiTick.n) { store.keyState }

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
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Paint.Veil).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Label("✕", Paint.Sand, size = 18) }
            }

            // TRACKS FIRST, because it is the thing he opens the settings for most (15.9.2026).
            SettingRow("tracks (gpx)", "$trackCount", onTracks)

            // THE MAPS: A GROUP AND THE MAPS INSIDE IT, and they are told apart by more than
            // position (15.9.2026). The group is in capitals, in the amber, flush to the edge;
            // its maps are in title case, in the sand, pushed in. A tick decides whether a thing
            // is in the switcher; a tick on the group takes all of its maps out at once and
            // remembers which of them were ticked for when it comes back.
            MapLayer.Family.entries.forEach { family ->
                val maps = Layers.of(family)
                val key = family.name.lowercase()

                // A GROUP OF ONE IS NOT A GROUP (15.9.2026). Offline file and OpenStreetMap have
                // one map each, so a triangle that folds away a single child with the same name
                // as its parent is a thing to press for nothing. They are one row: the name, and
                // one tick that means both the map and the group, because here they are the same.
                if (maps.size == 1) {
                    val layer = maps.first()
                    val chosen = layer.id == current.id
                    val needsKey = layer.provider != null && store.key(layer.provider) == null
                    var on by remember(key, UiTick.n) {
                        mutableStateOf(store.familyInToggle(family) && store.inToggle(layer.id))
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (chosen) Paint.Amber else Paint.Veil),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .alpha(if (needsKey) 0.55f else 1f)
                                .clickable { onPick(layer) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Label(
                                text = familyLabel(family).uppercase() +
                                    if (needsKey) "  · needs a key" else "",
                                colour = if (chosen) Paint.Ground else Paint.Amber,
                                size = 13,
                                align = TextAlign.Start,
                            )
                        }
                        Tick(
                            checked = on,
                            onChange = {
                                on = it
                                store.setFamilyInToggle(family, it)
                                store.setInToggle(layer.id, it)
                            },
                        )
                    }
                    return@forEach
                }

                var folded by remember(key) { mutableStateOf(store.collapsed(key)) }
                var familyOn by remember(key, UiTick.n) { mutableStateOf(store.familyInToggle(family)) }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Veil),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clickable {
                                folded = !folded
                                store.setCollapsed(key, folded)
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Label(if (folded) "▸" else "▾", Paint.Amber, size = 12)
                            Label(
                                text = "  " + familyLabel(family).uppercase(),
                                colour = Paint.Amber,
                                size = 13,
                                align = TextAlign.Start,
                            )
                        }
                    }
                    Tick(
                        checked = familyOn,
                        onChange = {
                            familyOn = it
                            store.setFamilyInToggle(family, it)
                        },
                    )
                }

                if (!folded) {
                    maps.forEach { layer ->
                        val chosen = layer.id == current.id
                        val needsKey = layer.provider != null && store.key(layer.provider) == null
                        var included by remember(layer.id, UiTick.n) {
                            mutableStateOf(store.inToggle(layer.id))
                        }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 22.dp)
                                .height(42.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (chosen) Paint.Amber else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .alpha(if (needsKey) 0.55f else 1f)
                                    .clickable { onPick(layer) }
                                    .padding(horizontal = 12.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Label(
                                    text = layer.name + if (needsKey) "  · needs a key" else "",
                                    colour = if (chosen) Paint.Ground else Paint.Sand,
                                    size = 13,
                                    align = TextAlign.Start,
                                )
                            }
                            Tick(
                                checked = included,
                                dimmed = !familyOn,
                                onChange = {
                                    included = it
                                    store.setInToggle(layer.id, it)
                                },
                            )
                        }
                    }
                }
            }

            SettingRow("choose a .map file for the offline layer", "picker", onChooseMapFile)
            // THE ANSWER APPEARS HERE, where the question was asked. It used to go to the map's
            // note line, which is behind this screen — so pressing it looked like nothing
            // happening at all, exactly as export did before it (16.9.2026).
            // THE THEME. It decides what the offline map SHOWS, which is why a coast came back
            // covered in petrol pumps: it was fixed at a motorcycle theme (16.9.2026).
            Label("offline map theme", Paint.Dim, size = 12, align = TextAlign.Start)
            THEMES.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { name ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (name == theme) Paint.Amber else Paint.Veil)
                                .clickable {
                                    theme = name
                                    Trail.say(CanvasHolder.canvas?.setTheme(name))
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Label(
                                // Ours is named for what it is for, not for what it is called.
                                text = if (name == "MANTRA") "walking" else name.lowercase(),
                                colour = if (name == theme) Paint.Ground else Paint.Sand,
                                size = 11,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            // OPENANDROMAPS: the hiking maps, with contour lines and waymarked routes in the data
            // rather than painted over it (16.9.2026). They arrive as a zip and are unpacked here.
            Label("openandromaps, for walking", Paint.Dim, size = 12, align = TextAlign.Start)
            Oam.ALL.forEach { region ->
                SettingRow(region.label, Oam.sizeLabel(region).substringBefore(","), {
                    onDownloadOam(region)
                })
            }

            SettingRow("ask this map's service for one tile", "test it", onTestTiles)
            SettingRow("what is the map doing", "ask it", {
                answer = CanvasHolder.canvas?.diagnose() ?: "the map view is not up yet"
            })
            if (answer != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Veil)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Label(answer ?: "", Paint.Amber, size = 11, align = TextAlign.Start)
                }
            }
            SettingRow("folder the tracks live in", exportState, onChooseExportFolder)
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

            // EVERY CREDIT, IN ONE PLACE, AT THE VERY BOTTOM. Off the map, where they were in the
            // way of the ground he is walking on, and here where they can be read once.
            Label("map credits", Paint.Dim, size = 11, align = TextAlign.Start)
            Layers.ALL.map { it.attribution }.distinct().forEach {
                Label(it, Paint.Dim, size = 9, align = TextAlign.Start)
            }
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

@Composable
private fun CompassDial(heading: Double, full: Boolean = false, ink: Color = Paint.Sand) {
    Canvas(if (full) Modifier.fillMaxWidth().aspectRatio(1f) else Modifier.size(140.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f - 6f
        drawCircle(ink.copy(alpha = 0.45f), radius = r, center = c, style = Stroke(2f))
        for (tick in 0 until 72) {
            val angle = Math.toRadians(tick * 5.0 - heading - 90.0)
            val long = tick % 6 == 0
            val inner = r * if (long) 0.84f else 0.92f
            val colour = when {
                tick == 0 -> Paint.Red
                long -> ink
                else -> ink.copy(alpha = 0.45f)
            }
            drawLine(
                color = colour,
                start = Offset(c.x + (inner * Math.cos(angle)).toFloat(), c.y + (inner * Math.sin(angle)).toFloat()),
                end = Offset(c.x + (r * Math.cos(angle)).toFloat(), c.y + (r * Math.sin(angle)).toFloat()),
                strokeWidth = if (long) 3f else 1.5f,
            )
        }
        drawLine(ink, Offset(c.x, c.y - r * 0.8f), Offset(c.x, c.y + r * 0.2f), 4f)
        drawCircle(ink, radius = 5f, center = c)
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
    modifier: Modifier = Modifier,
) {
    Text(
        modifier = modifier,
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
