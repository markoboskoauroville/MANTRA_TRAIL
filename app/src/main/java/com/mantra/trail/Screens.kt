package com.mantra.trail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.drawscope.Fill
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
    onBare: (Boolean) -> Unit,
    tracks: () -> List<Folder.Entry>,
    folderLabel: String,
    onRenameJustFinished: (java.io.File, String) -> Unit,
    onDiscardRecording: (java.io.File) -> Unit,
    onDeleteTrack: (Folder.Entry) -> Unit,
    onRenameTrack: (Folder.Entry, String) -> Unit,
    onShowTrack: (Folder.Entry) -> Unit,
    onTestTiles: () -> Unit,
    onFetchRegion: (OamIndex.Entry) -> Unit,
    onFetchImagery: (Int) -> Unit,
    onTestKey: (Keyring.Key) -> Unit,
    onRemoveKey: (Keyring.Key) -> Unit,
    onSaveRoute: (List<Pair<Double, Double>>) -> Unit,
    onFindWays: (List<Pair<Double, Double>>, String, Int) -> Unit,
    onSaveOption: (Routing.Option) -> Unit,
    onHeights: (Int) -> Unit,
    routeOptions: List<Routing.Option>,
) {
    var layer by remember { mutableStateOf(Layers.byId(store.layerId)) }
    var settings by remember { mutableStateOf(false) }
    var compass by remember { mutableIntStateOf(store.compassMode) }
    var points by remember { mutableStateOf(store.routePoints) }
    var routeMenu by remember { mutableStateOf(false) }
    var showPlaces by remember { mutableStateOf(false) }
    var bare by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(13) }
    // EVERY CANVAS GETS TOLD TO DRAW (17.9.2026). This was a one-shot flag: true the first time a
    // map view existed, and true for ever after. Switching to Google's renderer and back builds a
    // NEW VTM canvas, and nothing told it anything, because the flag was already true — a black
    // screen with his position dot on it, which is exactly what he photographed. A counter goes
    // up with each canvas, so each one is drawn on.
    var ready by remember { mutableIntStateOf(0) }
    // LOCKED TO THE MIDDLE (15.9.2026). One press centres and holds; the next lets the map go.
    var follow by remember { mutableStateOf(false) }
    var lastCentreTap by remember { mutableLongStateOf(0L) }
    // 0 free, 1 north up, 2 turning with the walk (16.9.2026, as Google's little compass does).
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
    var showMaps by remember { mutableStateOf(false) }
    // How deep the kept imagery goes, and what the area on the screen would cost at that depth.
    var imageryDepth by remember { mutableIntStateOf(15) }
    val imageryCost = remember(UiTick.n, imageryDepth, showMaps) {
        val box = CanvasHolder.canvas?.visibleBox() ?: GoogleHolder.canvas?.visibleBox()
        if (box == null) {
            "move the map to the ground you want"
        } else {
            val tiles = Imagery.countFor(
                box[0], box[1], box[2], box[3],
                Imagery.MIN_ZOOM.coerceAtMost(imageryDepth),
                imageryDepth,
            )
            "$tiles tiles · ${Imagery.sizeLabel(tiles)}"
        }
    }
    var listing by remember { mutableStateOf<List<OamIndex.Entry>>(emptyList()) }
    var listingOf by remember { mutableStateOf<String?>(null) }
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val installedMaps = remember(UiTick.n, showMaps, settings) { OamDownload.installed(appContext) }
    val imageryKept = remember(UiTick.n, showMaps, settings) { ImageryStore.label(appContext) }
    val unfinishedMaps = remember(UiTick.n, showMaps, settings) { OamDownload.unfinished(appContext) }
    val scope = rememberCoroutineScope()

    // The map the app opened on, drawn as soon as the view is real and not a moment before.
    LaunchedEffect(ready, layer.id) {
        if (ready > 0) {
            showLayer(store, layer)
            // and whatever was on the old map goes onto the new one
            Canvases.setRoutePoints(store.routePoints)
            Shown.route?.let { Canvases.drawRoute(it.first, it.second) }
            Shown.track?.let { Canvases.drawSavedTrack(it.first, it.second) }
        }
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
            Canvases.currentZoom()?.let {
                if (it != zoom) {
                    zoom = it
                    // A blank offline map at a zoom explains itself now, rather than waiting to
                    // be photographed: the file is asked what it holds under the crosshair.
                    if (layer.kind == LayerKind.VECTOR_FILE) {
                        Trail.say(Canvases.emptyHere())
                    }
                }
            }
            delay(500)
        }
    }

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {

        MapSurface(
            store = store,
            layer = layer,
            generation = ready,
            points = points,
            fix = fix,
            line = Trail.line.collectAsState().value,
            follow = follow,
            onCanvas = onCanvas,
            onReady = { ready += 1 },
        )


        // THE LITTLE COMPASS, at the top right where Google keeps it.
        //
        // It was written on 16.9.2026 and he never saw it, because it was nested INSIDE the 72dp
        // target in the middle of the screen: fillMaxSize inside a 72dp box is 72dp, so the thing
        // was drawn behind the crosshair, thumb-sized, in the centre. It sits on the screen now.
        //
        // One tap puts north at the top and centres him; the next turns the map so the way he is
        // walking is up, and it wears an amber ring while it does.
        if (!bare) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().padding(top = 52.dp, end = 10.dp)) {
                LittleCompass(
                    turn = mapTurn,
                    modifier = Modifier.align(Alignment.TopEnd),
                    onTap = { Canvases.setMapRotation(0f) },
                )
            }
        }

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
                    Key(glyph = "−", lit = false, onClick = { Canvases.zoomOut() })
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
                            val at = Canvases.centre() ?: return@PointKey
                            if (points.size >= Route.MAX_POINTS) {
                                Trail.say("That is as many points as one route holds")
                                return@PointKey
                            }
                            points = points + at
                            store.routePoints = points
                            Canvases.setRoutePoints(points)
                        },
                        onLongPress = { routeMenu = true },
                    )
                    Key(glyph = "+", lit = false, onClick = { Canvases.zoomIn() })
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
                // CANCEL MEANS CANCELLED (17.9.2026, his word). It used to save the walk under
                // its own name, on the reasoning that a walk is hard-won and should never be lost
                // by a stray press. But a key called cancel that keeps the thing is a key that
                // lies, and he pressed it meaning to throw the walk away. It throws it away.
                onCancel = {
                    Trail.dealtWith()
                    onDiscardRecording(file)
                },
                onOk = { name ->
                    Trail.dealtWith()
                    onRenameJustFinished(file, name)
                },
            )
        }

        // WHAT IS DRAWN BELONGS TO HIM, NOT TO AN ENGINE (17.9.2026). The lettered points and the
        // way found between them are put back on every new canvas, so switching between the
        // offline file and Google's map keeps the line he is walking rather than losing it.
        LaunchedEffect(ready, points) {
            if (ready > 0) Canvases.setRoutePoints(points)
        }

        // THE LIGHT IN FRONT OF THE DOT NEEDS THE HEADING, whether or not the compass overlay is
        // on. Five times a second is enough for a light that only moves when the phone turns, and
        // the canvas redraws only when the heading has really moved (bounded by the composition).
        LaunchedEffect(ready) {
            while (true) {
                if (ready > 0) {
                    Canvases.setHeading(sensors.heading())
                    // The compass reports the map's own angle, so it is read where the heading is.
                    mapTurn = Canvases.mapRotationDeg()
                }
                delay(200)
            }
        }

        if (showPlaces) {
            PlacesFace(
                fix = fix,
                store = store,
                onAdd = { place ->
                    if (points.size < Route.MAX_POINTS) {
                        points = points + (place.lat to place.lon)
                        store.routePoints = points
                        Canvases.setRoutePoints(points)
                        Canvases.centreOn(Fix(place.lat, place.lon, null, 0L, null))
                    }
                    showPlaces = false
                    Trail.say("${place.name} added as ${Route.letterFor(points.size - 1)}")
                },
                onClose = { showPlaces = false },
            )
        }

        if (routeMenu) {
            RouteMenu(
                store = store,
                points = points,
                found = routeOptions,
                onFind = {
                    routeMenu = false
                    showPlaces = true
                },
                onAdd = {
                    val at = Canvases.centre()
                    if (at != null && points.size < Route.MAX_POINTS) {
                        points = points + at
                        store.routePoints = points
                        Canvases.setRoutePoints(points)
                    }
                },
                onRemove = { index ->
                    points = points.filterIndexed { i, _ -> i != index }
                    store.routePoints = points
                    Canvases.setRoutePoints(points)
                },
                onRoute = { profile, wanted -> onFindWays(points, profile, wanted) },
                onSaveOption = { option -> onSaveOption(option) },
                onHeights = onHeights,
                onSave = { onSaveRoute(points) },
                onClose = { routeMenu = false },
            )
        }

        if (compass != COMPASS_OFF) {
            CompassOverlay(sensors = sensors, night = compass == COMPASS_NIGHT)
        }

        if (showMaps) {
            MapsFace(
                store = store,
                installed = installedMaps,
                imageryDepth = imageryDepth,
                imageryCost = imageryCost,
                imageryHeld = imageryKept,
                onImageryDepth = { imageryDepth = it },
                onFetchImagery = { onFetchImagery(imageryDepth) },
                onForgetImagery = {
                    ImageryStore.forget(appContext)
                    Trail.say("The kept imagery was deleted")
                    UiTick.bump()
                },
                folder = OamDownload.folderLabel(appContext),
                onUse = { file ->
                    store.offlineMapName = file.name
                    UiTick.bump()
                    scope.launch { showLayer(store, Layers.OFFLINE) }
                },
                onRemove = { file ->
                    OamDownload.remove(file)
                    if (store.offlineMapName == file.name) store.offlineMapName = ""
                    UiTick.bump()
                },
                onBrowse = { continent ->
                    listingOf = continent
                    listing = emptyList()
                    scope.launch {
                        OamDownload.say("Reading the list for $continent…")
                        val (entries, problem) = OamDownload.index(continent)
                        listing = entries
                        OamDownload.say(problem)
                    }
                },
                listing = listing,
                listingOf = listingOf,
                onFetch = onFetchRegion,
                onClose = { showMaps = false },
            )
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
                    Canvases.clearSavedTrack()
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
                installedMaps = installedMaps,
                unfinishedMaps = unfinishedMaps,
                keyring = remember(UiTick.n, settings) { store.keyring },
                onTestKey = onTestKey,
                onRemoveKey = onRemoveKey,
                hasImagery = imageryKept != "none yet",
                imageryHeld = imageryKept,
                onFetchOffer = { entry ->
                    settings = false
                    showMaps = true
                    onFetchRegion(entry)
                },
                drawingMapName = store.offlineMapName.ifBlank {
                    installedMaps.firstOrNull()?.name ?: ""
                },
                offlineUnder = if (installedMaps.isEmpty()) {
                    if (unfinishedMaps.isEmpty()) "none yet" else "one unfinished — open to carry on"
                } else {
                    val drawing = store.offlineMapName.ifBlank { installedMaps.first().name }
                    drawing.removePrefix("oam-").removeSuffix(".map") +
                        " · " + (if (store.themeName == "MANTRA") "walking" else store.themeName.lowercase())
                },
                onUseMap = { file ->
                    store.offlineMapName = file.name
                    settings = false
                    scope.launch { showLayer(store, Layers.OFFLINE) }
                },
                onOfflineView = { view ->
                    store.themeName = view.theme
                    CanvasHolder.canvas?.setTheme(view.theme)
                    settings = false
                    scope.launch { showLayer(store, Layers.OFFLINE) }
                },
                chosenGoogleId = store.googleViewId,

                onPick = { picked ->
                    if (picked.family == MapLayer.Family.GOOGLE) store.googleViewId = picked.id
                    settings = false
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
                onPause = onPause,
                recordingPaused = paused,
                onTracks = {
                    settings = false
                    showTracks = true
                },
                trackCount = tracks().size,
                onTestTiles = onTestTiles,
                onMaps = {
                    settings = false
                    showMaps = true
                },
                installedCount = installedMaps.size,
                folderName = store.exportFolderName ?: "none chosen yet",
                hasGoogleKey = store.key(Keys.Provider.GOOGLE) != null,
                onClose = { settings = false },
            )
        }
    }
}

@Composable
private fun MapSurface(
    store: Store,
    layer: MapLayer,
    generation: Int,
    points: List<Pair<Double, Double>>,
    fix: Fix?,
    line: List<Fix>,
    follow: Boolean,
    onCanvas: (VtmCanvas) -> Unit,
    onReady: () -> Unit,
) {
    // TWO ENGINES, ONE SCREEN (17.9.2026), which reverses the note that stood here — that Google's
    // SDK was gone with the key that was compiled in. It is back with a key that is locked to this
    // package and this signing certificate, because he asked for their vector map at their speed
    // and the Map Tiles API serves pictures of a map rather than the map.
    //
    // Google's renderer draws the Google views; VTM draws the file on the phone. Whichever is not
    // in use is not in the tree at all, so a walk with no signal carries nothing waiting to fail.
    Canvases.googleIsUp = layer.family == MapLayer.Family.GOOGLE
    if (layer.family == MapLayer.Family.GOOGLE) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val made = GoogleCanvas(context, store)
                GoogleHolder.canvas = made
                // AS THE OTHER ENGINE DOES (17.9.2026): say when the map is real, and the screen
                // puts his points and his route on it. Before this, whatever was drawn while
                // Google's map was still arriving went nowhere.
                made.onReady = { onReady() }
                made.onCreate()
                made.onResume()
                made.show(layer)
                made.showPosition(true)
                made.setRoutePoints(points)
                made.view
            },
            update = { GoogleHolder.canvas?.show(layer) },
            // A VIEW THAT LEAVES THE SCREEN MUST LET GO OF THE SCREEN (17.9.2026). Compose took
            // the Google map out of the tree when he switched back to the offline file, but
            // nobody told the MapView, so it kept its lifecycle and its GL surface — and VTM,
            // handed a surface that was still somebody else's, drew black. His offline map went
            // dark the moment Google's renderer had been shown once.
            onRelease = {
                // Where he was looking goes with him to the other engine.
                Canvases.rememberCamera(store)
                GoogleHolder.canvas?.onPause()
                GoogleHolder.canvas?.onDestroy()
                GoogleHolder.canvas = null
            },
        )
        return
    }

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
        onRelease = {
            // The same courtesy in the other direction: VTM stops drawing when it leaves, and
            // says where it was looking first.
            Canvases.rememberCamera(store)
            CanvasHolder.canvas?.pause()
            CanvasHolder.canvas = null
        },
    )

    // NOTHING IS LEFT BEHIND WHEN THE VIEW CHANGES (17.9.2026).
    //
    // His words: changing the map is changing the VIEW, and everything laid over it is constant.
    // This effect was keyed on the walk and the lock alone, so when a new engine took the screen
    // it ran nothing — the track he was recording vanished, the lock stopped holding, the route
    // and the points went with them. It is keyed on the canvas too now, so every map that
    // appears inherits the whole state rather than an empty screen.
    LaunchedEffect(generation, line.size, fix?.timeMs, follow) {
        Canvases.handOver(points, line, fix, follow)
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
    // TWO MAPS, AND THE KEY TURNS BETWEEN THEM (17.9.2026). It used to walk every ticked view,
    // which is eight presses to come back to where he started. Which Google view it shows is
    // chosen in the settings; the key only decides offline or Google.
    if (current.family == MapLayer.Family.OFFLINE) {
        val google = Layers.byId(store.googleViewId)
        val hasKey = google.provider?.let { !store.key(it).isNullOrEmpty() } ?: false
        return if (hasKey) google else current
    }
    return Layers.OFFLINE
}

/**
 * PUT A LAYER ON THE MAP, fetching whatever it needs first.
 *
 * A layer that needs a key and has none says which key and where to put it, rather than drawing
 * an empty grid and leaving somebody to guess. Google needs a session as well, and the session is
 * made here — once, when the view is actually chosen, because Google bills per tile.
 */
suspend fun showLayer(store: Store, layer: MapLayer) {
    // GOOGLE'S MAPS ARE GOOGLE'S TO DRAW (17.9.2026). This routine asked the offline canvas for
    // every layer, so choosing a Google view — which their own renderer had already drawn
    // perfectly — ended with "the map view is not up yet" written across a working map. The
    // sentence was true of the canvas it asked and false about everything he could see.
    if (layer.family == MapLayer.Family.GOOGLE) {
        val google = GoogleHolder.canvas
        if (google == null) {
            // Not an error: the view is a frame or two behind the choice, and the canvas applies
            // whatever it was asked for as soon as it arrives.
            return
        }
        Trail.say(google.show(layer))
        return
    }

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
    // A MAP THAT CANNOT BE DRAWN DRAWS NOTHING (17.9.2026).
    //
    // It used to fall back to the offline map, and his screenshot showed why that was wrong: the
    // top line said Satellite, Google had refused the key, and the offline map was underneath. The
    // name and the ground disagreed, and only the name was legible at a glance — which is worse
    // than an empty screen, because an empty screen with a sentence on it is not ambiguous.
    CanvasHolder.canvas?.blank()
    Trail.say(problem)
}

/** One attempt at one layer. Returns null when it drew, or the reason it did not. */
private suspend fun attempt(canvas: VtmCanvas, store: Store, layer: MapLayer): String? {
    if (layer.kind == LayerKind.GOOGLE_TILES) {
        // THE RING, IN ORDER (17.9.2026): the key that worked last is tried first, and if it has
        // stopped working the next is tried before anybody is told the map is unavailable.
        if (store.keyring.isEmpty()) return Layers.missingKey(layer)
        val view = layer.googleView ?: MapLayer.GoogleView.NORMAL
        Trail.say("Asking Google for a session…")
        val result = GoogleTiles.sessionFromRing(view, store)
        val used = Keyring.best(store.keyring)?.value
        return result.token?.let { canvas.show(layer, session = it, key = used) } ?: result.problem
    }
    val key = layer.provider?.let { store.key(it) }
    if (layer.provider != null && key.isNullOrEmpty()) return Layers.missingKey(layer)
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
/**
 * THE COMPASS (17.9.2026, third attempt, to his description and no further).
 *
 * One white ring. One needle, wholly inside it, red to the north and pale to the south. Nothing
 * else: no letter, no disc, no second stroke under anything.
 *
 * What was wrong before, in his words and mine: the needle reached past the ring; the ring and
 * the needle were each drawn twice, near-black under colour, and the two passes did not meet
 * cleanly at the point — which is the "double sword" on the edges; and the N carried a shadow
 * because it too was drawn twice. Drawing a thing twice to make it readable is a trick for a
 * hairline over a map, and a filled needle is not a hairline. It needs none of it.
 *
 * The needle ends at 0.58 of the radius, so there is air between its point and the ring.
 */
@Composable
private fun LittleCompass(turn: Float, onTap: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(55.dp).clip(CircleShape).clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        // A QUARTER LARGER (17.9.2026), with a black ring OUTSIDE the white one — concentric, not
        // one drawn under the other, so there is no double edge anywhere on it.
        Canvas(Modifier.size(38.dp)) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 1.dp.toPx()

            // ONE LINE, ONE PIXEL, NO OUTLINE (17.9.2026, his rule three). The black ring around
            // this was me outlining by reflex; it is gone, and so is every other outline I added.
            drawCircle(Color.White, radius = r, center = c, style = Stroke(1.dp.toPx()))

            // The needle turns against the map: the map turned east puts north to the left.
            val along = Math.toRadians(-turn.toDouble() - 90.0)
            val across = Math.toRadians(-turn.toDouble())
            fun at(distance: Float, radians: Double) = Offset(
                c.x + (distance * Math.cos(radians)).toFloat(),
                c.y + (distance * Math.sin(radians)).toFloat(),
            )
            val reach = r * 0.58f
            val waist = r * 0.13f
            val tip = at(reach, along)
            val tail = at(-reach, along)
            val left = at(waist, across)
            val right = at(-waist, across)

            fun half(point: Offset, colour: Color) {
                drawPath(
                    androidx.compose.ui.graphics.Path().apply {
                        moveTo(point.x, point.y)
                        lineTo(left.x, left.y)
                        lineTo(right.x, right.y)
                        close()
                    },
                    colour,
                )
            }
            half(tip, Color(0xFFE53935))
            half(tail, Color.White)
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
        // ONE HAIRLINE. A hairline is one pixel and nothing under it (17.9.2026, his rule three).
        val hair = 1.dp.toPx()
        val ink = Color(0x99000000)
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
                ) { Label("discard", Paint.Red, size = 14) }
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
 * THE MAPS ON THIS PHONE, AND EVERY ONE IN THE WORLD THAT COULD BE.
 *
 * Baba, 16.9.2026: he started a 1.2 GB download and could not tell it was running, could not see
 * where it went, and could not tell afterwards which map the app was drawing. So this face says
 * all three at once — what is here, what is happening, and what can be fetched, continent by
 * continent, read from the mirror's own listing rather than from a list somebody typed.
 */
@Composable
private fun MapsFace(
    store: Store,
    installed: List<java.io.File>,
    folder: String,
    onUse: (java.io.File) -> Unit,
    onRemove: (java.io.File) -> Unit,
    onBrowse: (String) -> Unit,
    listing: List<OamIndex.Entry>,
    listingOf: String?,
    onFetch: (OamIndex.Entry) -> Unit,
    imageryDepth: Int,
    imageryCost: String,
    imageryHeld: String,
    onImageryDepth: (Int) -> Unit,
    onFetchImagery: () -> Unit,
    onForgetImagery: () -> Unit,
    onClose: () -> Unit,
) {
    val busy by OamDownload.state.collectAsState()
    val chosen = remember(UiTick.n) { store.offlineMapName }

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
                Label("maps", Paint.Dim, size = 13)
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Paint.Veil).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Label("✕", Paint.Sand, size = 18) }
            }

            // WHAT IS HAPPENING, at the top, because a gigabyte is worth knowing about.
            // WHAT IS HAPPENING. Dark like everything else, with an amber outline round it —
            // his instruction, 17.9.2026: this is a dark application, so nothing is filled in a
            // light colour and black is never written on amber.
            if (busy != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Paint.Card)
                        .border(1.5.dp, Paint.Amber, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Label(busy ?: "", Paint.Amber, size = 12, align = TextAlign.Start)
                }
            }

            Label("on this phone", Paint.Dim, size = 12, align = TextAlign.Start)
            if (installed.isEmpty()) {
                Label("None yet. Fetch one below.", Paint.Dim, size = 12, align = TextAlign.Start)
            }
            installed.forEach { file ->
                val inUse = file.name == chosen || (chosen.isBlank() && file == installed.first())
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Paint.Card)
                        .then(
                            if (inUse) {
                                Modifier.border(1.5.dp, Paint.Amber, RoundedCornerShape(12.dp))
                            } else {
                                Modifier
                            }
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth().clickable { onUse(file) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Label(
                                text = file.name.removePrefix("oam-").removeSuffix(".map"),
                                colour = Paint.Sand,
                                size = 14,
                                align = TextAlign.Start,
                            )
                            Label(
                                text = "${file.length() / 1_000_000} MB" +
                                    if (inUse) " · drawing" else "",
                                colour = if (inUse) Paint.Amber else Paint.Dim,
                                size = 11,
                                align = TextAlign.Start,
                            )
                        }
                    }
                    Box(
                        Modifier.width(72.dp).fillMaxWidth().clickable { onRemove(file) },
                        contentAlignment = Alignment.Center,
                    ) { Label("delete", Paint.Red, size = 12) }
                }
            }
            Label("kept in $folder", Paint.Dim, size = 10, align = TextAlign.Start)

            // SMALLER MAPS, FIRST (17.9.2026). He asked whether Croatia could be cut out of the
            // Balkan file: it cannot, by this app or any other, so the answer offered instead is
            // a map that is only Croatia — a quarter of the size, and made for walking too.

            // SATELLITE HE KEEPS (17.9.2026). Google forbid storing theirs — their policy lists
            // offline use among the things their tiles may not be used for — so this is Sentinel-2
            // cloudless from EOX, CC BY 4.0, which may be kept. Ten metres to the pixel: forest
            // from clearing, ridge from valley, not cars.
            //
            // The area is what is on the screen. Drawing a rectangle with a finger on a map that
            // pans under it is a fiddle; moving the map until it shows what he wants is not.
            Label("satellite for offline use", Paint.Dim, size = 12, align = TextAlign.Start)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(14, 15, 16).forEach { depth ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Paint.Card)
                            .then(
                                if (depth == imageryDepth) {
                                    Modifier.border(1.5.dp, Paint.Amber, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .clickable { onImageryDepth(depth) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(
                            text = when (depth) {
                                14 -> "coarse"
                                15 -> "closer"
                                else -> "closest"
                            },
                            colour = if (depth == imageryDepth) Paint.Amber else Paint.Sand,
                            size = 12,
                        )
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Paint.Card)
                    .clickable(onClick = onFetchImagery)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label("keep what is on the screen", Paint.Sand, size = 13, align = TextAlign.Start)
                    Label(imageryCost, Paint.Dim, size = 11, align = TextAlign.Start)
                }
                Label("fetch", Paint.Amber, size = 12)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Label("kept: $imageryHeld", Paint.Dim, size = 11, align = TextAlign.Start)
                if (imageryHeld != "none yet") {
                    Box(Modifier.clickable(onClick = onForgetImagery)) {
                        Label("delete all", Paint.Red, size = 11)
                    }
                }
            }
            Label(Imagery.ATTRIBUTION, Paint.Dim, size = 10, align = TextAlign.Start)

            Label("a smaller map, from elsewhere", Paint.Dim, size = 12, align = TextAlign.Start)
            OamIndex.ELSEWHERE.forEach { entry ->
                val here = installed.any { it.name == entry.mapName }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Card)
                        .clickable { if (!here) onFetch(entry) }
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(
                        text = "Croatia, for walking — from your own GitHub",
                        colour = if (here) Paint.Dim else Paint.Sand,
                        size = 12,
                        align = TextAlign.Start,
                    )
                    Label(
                        text = if (here) "on the phone" else entry.sizeLabel,
                        colour = if (here) Paint.Green else Paint.Amber,
                        size = 11,
                    )
                }
            }

            Label("fetch a region", Paint.Dim, size = 12, align = TextAlign.Start)
            OamIndex.CONTINENTS.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (id, label) ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Paint.Card)
                                .then(
                                    if (id == listingOf) {
                                        Modifier.border(1.5.dp, Paint.Amber, RoundedCornerShape(10.dp))
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable { onBrowse(id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Label(label, if (id == listingOf) Paint.Amber else Paint.Sand, size = 12)
                        }
                    }
                    repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            listing.forEach { entry ->
                val here = installed.any { it.name == entry.mapName }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Card)
                        .clickable { if (!here) onFetch(entry) }
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(entry.label, if (here) Paint.Dim else Paint.Sand, size = 12, align = TextAlign.Start)
                    Label(
                        text = if (here) "on the phone" else entry.sizeLabel,
                        colour = if (here) Paint.Green else Paint.Amber,
                        size = 11,
                    )
                }
            }
        }
    }
}


/**
 * SEARCHING FOR A PLACE (17.9.2026).
 *
 * He types a name, presses search, and each answer is a row: press it and that place becomes the
 * next point of the route, which is what he was panning the map to do by hand. The map goes there
 * too, so he can see what he has chosen before walking to it.
 *
 * Nothing is searched while he types. Each search is a billed request on his account, so it
 * happens when he presses and not before.
 */
@Composable
private fun PlacesFace(
    fix: Fix?,
    store: Store,
    onAdd: (Places.Place) -> Unit,
    onClose: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<Places.Place>>(emptyList()) }
    var note by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { focus.requestFocus() }

    fun go() {
        if (text.isBlank() || looking) return
        looking = true
        note = "Looking…"
        scope.launch {
            val (places, problem) = Places.search(text, fix, store)
            found = places
            note = problem ?: "${places.size} found"
            looking = false
        }
    }

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
                Label("find a place", Paint.Dim, size = 13)
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Paint.Card).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Label("✕", Paint.Sand, size = 18) }
            }

            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Paint.Sand,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(Paint.AmberBright),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { go() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Paint.Card)
                    .border(1.5.dp, Paint.Amber, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 14.dp)
                    .focusRequester(focus),
            )

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Paint.Card)
                    .border(1.5.dp, if (text.isBlank()) Paint.Dim else Paint.Amber, RoundedCornerShape(8.dp))
                    .clickable { go() },
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    text = if (looking) "looking…" else "search",
                    colour = if (text.isBlank()) Paint.Dim else Paint.Amber,
                    size = 14,
                )
            }

            if (note != null) Label(note ?: "", Paint.Dim, size = 11, align = TextAlign.Start)

            found.forEach { place ->
                val away = fix?.let { Geo.distance(it.lat, it.lon, place.lat, place.lon) }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Paint.Card)
                        .clickable { onAdd(place) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Label(place.name, Paint.Sand, size = 14, align = TextAlign.Start)
                        if (place.where.isNotBlank()) {
                            Label(place.where, Paint.Dim, size = 11, align = TextAlign.Start)
                        }
                    }
                    Label(
                        text = away?.let { Geo.formatDistance(it) } ?: "",
                        colour = Paint.Amber,
                        size = 11,
                    )
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
    onFind: () -> Unit,
    onRemove: (Int) -> Unit,
    onRoute: (String, Int) -> Unit,
    onSaveOption: (Routing.Option) -> Unit,
    onHeights: (Int) -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    var options by remember { mutableIntStateOf(store.routeOptions) }
    var speed by remember { mutableStateOf(store.walkSpeedKmh) }
    var profile by remember { mutableStateOf(store.routeProfile) }
    var useGoogle by remember { mutableStateOf(store.useGoogleRouting) }
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
                    text = if (enough) "${points.size} of ${Route.MAX_POINTS} points · ${Geo.formatDistance(straight)} straight" else "place at least two",
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

            if (enough) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Paint.Card)
                        .border(1.5.dp, Paint.Amber, RoundedCornerShape(8.dp))
                        .clickable(onClick = onSave)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Label("save these points as a track", Paint.Amber, size = 12, align = TextAlign.Start)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Paint.Card)
                    .clickable(onClick = onFind)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Label("find a place by name", Paint.Amber, size = 12, align = TextAlign.Start)
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

            // WHICH ROUTER ANSWERS (17.9.2026). BRouter is in the app and needs no signal; Google
            // knows what is open and costs a billed request each time it is asked. The two are
            // here side by side so he can compare them on ground he knows.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(false to "BRouter", true to "Google").forEach { (google, label) ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Paint.Card)
                            .then(
                                if (google == useGoogle) {
                                    Modifier.border(1.5.dp, Paint.Amber, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .clickable {
                                useGoogle = google
                                store.useGoogleRouting = google
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(label, if (google == useGoogle) Paint.Amber else Paint.Sand, size = 11)
                    }
                }
            }

            if (!useGoogle) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Routing.PROFILES.forEach { name ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Paint.Card)
                            .then(
                                if (name == profile) {
                                    Modifier.border(1.5.dp, Paint.Amber, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                }
                            )
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
                            colour = if (name == profile) Paint.Amber else Paint.Sand,
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
                            .background(Paint.Card)
                            .then(
                                if (n == options) {
                                    Modifier.border(1.5.dp, Paint.Amber, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .clickable {
                                options = n
                                store.routeOptions = n
                            },
                        contentAlignment = Alignment.Center,
                    ) { Label("$n", if (n == options) Paint.Amber else Paint.Sand, size = 12) }
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
                            .background(Paint.Card)
                            .then(
                                if (option == speed) {
                                    Modifier.border(1.5.dp, Paint.Amber, RoundedCornerShape(8.dp))
                                } else {
                                    Modifier
                                }
                            )
                            .clickable {
                                speed = option
                                store.walkSpeedKmh = option
                            }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Label(
                            text = "${option.toInt()} km/h",
                            colour = if (option == speed) Paint.Amber else Paint.Sand,
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
                    .background(Paint.Card)
                    .then(
                        if (enough) Modifier.border(1.5.dp, Paint.Amber, RoundedCornerShape(8.dp)) else Modifier
                    )
                    .clickable { if (enough) onRoute(profile, options) }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Label(
                        text = "route",
                        colour = if (enough) Paint.Amber else Paint.Dim,
                        size = 13,
                        align = TextAlign.Start,
                    )
                    Label(
                        text = if (enough) "$options to look for" else "place at least two",
                        colour = if (enough) Paint.Amber else Paint.Dim,
                        size = 11,
                    )
                }
            }

            found.forEachIndexed { index, option ->
                if (option.profile != null) {
                    // THE GROUND UNDER THE ROUTE (17.9.2026). Distance says how far; this says what
                    // it costs. Drawn from the heights themselves, so the shape is the hill.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Paint.Card)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Label(option.profile.line(), Paint.Amber, size = 11, align = TextAlign.Start)
                        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                            val heights = option.profile.metres
                            if (heights.size < 2) return@Canvas
                            val low = heights.min()
                            val high = heights.max()
                            val span = (high - low).coerceAtLeast(1.0)
                            val step = size.width / (heights.size - 1)
                            val path = androidx.compose.ui.graphics.Path()
                            heights.forEachIndexed { i, metres ->
                                val x = i * step
                                val y = size.height - ((metres - low) / span * size.height).toFloat()
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(path, Color(option.colour), style = Stroke(2.dp.toPx()))
                        }
                    }
                }
                // NO TURN-BY-TURN HERE (17.9.2026, his fifth telling). This is a walking app: he
                // wants the way drawn on the map, not a list of streets to read. Google's
                // instructions are still fetched with the route — they come in the same answer —
                // and they are simply not shown.
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
                if (option.profile == null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Paint.Card)
                            .clickable { onHeights(index) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Label("what the ground does along it", Paint.Amber, size = 11, align = TextAlign.Start)
                    }
                }
            }

            // CLOSE STANDS ALONE (17.9.2026). Save and close sat side by side at the bottom and he
            // hit save when he meant close, keeping points he did not want. Two keys of the same
            // size in the same corner, one destructive: that is a trap, not a layout. Save has
            // gone to the top of the menu, beside the points it saves; the bottom is only the way
            // out.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Paint.Veil)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Label("close", Paint.Sand, size = 14) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Label(
            text = title.uppercase(),
            colour = Paint.Dim,
            size = 11,
            align = TextAlign.Start,
            modifier = Modifier.padding(start = 16.dp, top = 10.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Paint.Card),
            content = content,
        )
    }
}

/** A row inside a section: what it is, and underneath, what it says. */
/** The hairline between two rows of one card, inset like the phone's own. */
@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(1.dp)
            .background(Paint.Rule),
    )
}

@Composable
private fun Row2(
    title: String,
    value: String? = null,
    tint: Color = Paint.Amber,
    onPress: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .then(if (onPress != null) Modifier.clickable(onClick = onPress) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Label(title, Paint.Sand, size = 14, align = TextAlign.Start)
            if (value != null) Label(value, tint, size = 11, align = TextAlign.Start)
        }
        if (trailing != null) trailing()
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
