package com.mantra.trail

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile

/**
 * ONE ACTIVITY. The map is the app; the compass and the level are a second face of the same
 * screen, not a second application.
 *
 * Everything that can be pressed exists from the first frame and is dimmed until it can be used
 * (design-language.md 1). Nothing here appears when a fix arrives or when a recording starts;
 * things become active.
 */
class MainActivity : ComponentActivity() {

    private lateinit var store: Store
    private lateinit var locator: Locator
    private lateinit var sensors: Sensors
    private var canvas: VtmCanvas? = null

    private var pendingRecord by mutableStateOf(false)
    private var downloading = false
    private var pendingSave: Pair<java.io.File, String>? = null
    private var routeOptions by mutableStateOf<List<Routing.Option>>(emptyList())
    private var routing = false
    private var pendingSegment: String? = null
    private var pendingOam: String? = null
    private var tileAnswer: String? = null

    private val askLocation = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val fine = granted[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (fine) {
            locator.start()
            if (pendingRecord) startRecording()
        } else {
            Trail.say("Without the location permission there is no map position and no track")
        }
        pendingRecord = false
    }

    private val pickMapFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        store.mapFileUri = uri.toString()
        UiTick.bump()
        Trail.say(canvas?.show(Layers.OFFLINE))
    }

    /**
     * A key arrives as a FILE and is read by shape, never by eye (secrets.md 2). What comes back
     * to the screen is a count; the value itself never reaches the interface or a log.
     */
    private val pickKeyFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text == null) {
                Trail.say("That file could not be read")
                return@registerForActivityResult
            }
            val found = Keys.parse(text)
            if (found.isEmpty()) {
                // Zero is never "there were no keys": it is a format nobody here recognises yet.
                Trail.say("No key-shaped string in that file. If the format is new, say so and it gets added.")
                return@registerForActivityResult
            }
            // ONTO THE RING, NOT OVER THE OLD ONE (17.9.2026). A key per service, newest wins,
            // meant that importing a second key threw the first away — and when the new one was
            // refused there was nothing left to fall back to. Every Google key in the file joins
            // the ring and is tested at once; the old single-key store is kept in step so the
            // rest of the app, which asks it, still works.
            store.keys = store.keys + found.associate { it.provider to it.key }
            GoogleTiles.forget()
            addKeysFrom(found.filter { it.provider == Keys.Provider.GOOGLE }.map { it.key })
        } catch (e: Exception) {
            Trail.say("Import failed: ${e.javaClass.simpleName}")
        }
    }


    private val pickExportFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        store.exportTreeUri = uri.toString()
        // THE FOLDER'S OWN NAME IS KEPT, not just its address. "chosen" told him nothing; a
        // settings row that says Documents/Tracks is a row he can act on (15.9.2026).
        store.exportFolderName = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment
        UiTick.bump()
        // Whatever was waiting for a folder goes now, which may be a track from the manager
        // rather than the last one recorded.
        val waiting = pendingSave
        pendingSave = null
        if (waiting != null) saveRecording(waiting.first, waiting.second)
    }

    /**
     * THE WALK IS PUT IN THE FOLDER HE CHOSE, under whatever he called it in the popup. There is
     * no export any more (15.9.2026): a track that is already in the folder has nowhere to go.
     */
    private fun saveRecording(file: java.io.File, name: String) {
        if (store.exportTreeUri == null) {
            pendingSave = file to name
            report("Choose the folder your tracks live in")
            pickExportFolder.launch(null)
            return
        }
        report("Saving ${name}…")
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) { Folder.save(this@MainActivity, store, file, name) }
            report(problem ?: "Saved to ${Folder.label(this@MainActivity, store)}: $name")
            UiTick.bump()
        }
    }


    private fun deleteTrack(entry: Folder.Entry) {
        report(Folder.delete(this, entry) ?: "Deleted ${entry.name}")
        UiTick.bump()
    }

    /** He renames a NAME; the extension is the disk's business and is kept (15.9.2026). */
    private fun renameTrack(entry: Folder.Entry, newName: String) {
        report(Folder.rename(this, entry, newName) ?: "Renamed to $newName")
        UiTick.bump()
    }


    /**
     * FIND THE WAYS BETWEEN A AND B, offline, with the engine in this APK.
     *
     * The square of the world it needs is 130 MB, so it is not fetched behind his back: if it is
     * missing the app says which file and how big, and the same press again starts the download.
     */
    private fun findWays(points: List<Pair<Double, Double>>, profile: String, wanted: Int) {
        if (points.size < 2) {
            Trail.say("Place at least two points")
            return
        }
        if (routing) {
            Trail.say("Still looking")
            return
        }
        val missing = points
            .flatMap { Segments.namesFor(it.first, it.second, it.first, it.second) }
            .distinct()
            .filterNot { java.io.File(Routing.segmentDir(this), it).exists() }
        if (missing.isNotEmpty()) {
            val name = missing.first()
            if (pendingSegment != name) {
                pendingSegment = name
                Trail.say("Routing needs $name, about ${Segments.sizeHint(name)}. Press again to fetch it.")
                return
            }
            fetchSegment(name)
            return
        }
        routing = true
        Trail.say("Looking for ways…")
        lifecycleScope.launch {
            val (options, problem) = Routing.through(this@MainActivity, points, profile, wanted) {
                Trail.say(it)
            }
            routing = false
            routeOptions = options
            canvas?.showRouteOptions(options)
            Trail.say(
                problem ?: when (options.size) {
                    1 -> "One way found"
                    else -> "${options.size} ways found"
                }
            )
        }
    }

    private fun fetchSegment(name: String) {
        if (routing) return
        routing = true
        Trail.say("Fetching $name…")
        lifecycleScope.launch {
            val problem = SegmentDownload.fetch(this@MainActivity, name) { p ->
                Trail.say("$name ${p.percent}%, ${p.done / 1_000_000} of ${p.total / 1_000_000} MB")
            }
            routing = false
            pendingSegment = null
            Trail.say(problem ?: "$name is on the phone. Press find the ways again.")
        }
    }

    /** Keep one of the ways it found as a track, like anything else in the folder. */
    private fun saveOption(option: Routing.Option) {
        val now = System.currentTimeMillis()
        val name = "${Route.nameFor(now, store.routePoints.size)} ${option.metres / 1000}km"
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) {
                val temp = java.io.File(cacheDir, Tracks.safeFileName(name))
                temp.writeText(Gpx.whole(name, option.points, now))
                val answer = Folder.save(this@MainActivity, store, temp, name)
                temp.delete()
                answer
            }
            report(problem ?: "Saved $name")
            UiTick.bump()
        }
    }

    /**
     * SAVE A AND B AS A ROUTE, in the same drawer as a recorded walk and in the same format, with
     * (AB) in its name so it can be told from one that was walked (16.9.2026). Nothing about it
     * is a special case downstream: the manager renames it, shows it and deletes it like any
     * other GPX, because it IS any other GPX.
     */
    private fun saveRoute(points: List<Pair<Double, Double>>) {
        if (points.size < 2) {
            report("Place at least two points")
            return
        }
        val now = System.currentTimeMillis()
        val name = Route.nameFor(now, points.size)
        val fixes = points.map { Fix(it.first, it.second, null, now, null) }
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) {
                val temp = java.io.File(cacheDir, Tracks.safeFileName(name))
                temp.writeText(Gpx.whole(name, fixes, now))
                val answer = Folder.save(this@MainActivity, store, temp, name)
                temp.delete()
                answer
            }
            report(problem ?: "Saved $name to ${Folder.label(this@MainActivity, store)}")
            UiTick.bump()
        }
    }

    /** Read a saved walk back out of the folder and draw it over the map. */
    private fun showTrack(entry: Folder.Entry) {
        lifecycleScope.launch {
            val points = withContext(Dispatchers.IO) {
                Folder.read(this@MainActivity, entry)?.let { GpxRead.points(it) } ?: emptyList()
            }
            if (points.isEmpty()) {
                report("No points could be read from ${entry.name}")
                return@launch
            }
            canvas?.showSavedTrack(points, store.trackColour)
            Trail.say(
                "${entry.name}: ${points.size} points, " +
                    Geo.formatDistance(TrackMath.stats(points).distanceM)
            )
        }
    }

    /**
     * FETCH A REGION FROM THE MIRROR'S OWN LISTING. The size was read from that listing, so the
     * warning before it starts is a measurement and not a guess, and every line of progress goes
     * to OamDownload.state, which the maps face shows wherever he is standing (16.9.2026).
     */
    private fun fetchRegion(entry: OamIndex.Entry) {
        if (downloading) {
            OamDownload.say("Already fetching a map")
            return
        }
        if (pendingOam != entry.fileName) {
            pendingOam = entry.fileName
            OamDownload.say("${entry.label}: ${entry.sizeLabel} to fetch, ${entry.roomLabel}. Press again to start.")
            return
        }
        pendingOam = null
        downloading = true
        OamDownload.say("${entry.label}: starting…")
        lifecycleScope.launch {
            val problem = OamDownload.fetchEntry(this@MainActivity, entry) { p ->
                OamDownload.say(p.line(entry.label))
            }
            downloading = false
            if (problem != null) {
                OamDownload.say(problem)
            } else {
                store.offlineMapName = entry.mapName
                OamDownload.say("${entry.label} is on the phone and being drawn.")
                canvas?.show(Layers.OFFLINE)
            }
            UiTick.bump()
        }
    }

    /** Fetch one tile of the chosen map and report exactly what the service said. */
    /** Put every Google key in the file on the ring, then ask Google about each of them. */
    private fun addKeysFrom(found: List<String>) {
        val before = store.keyring
        store.keyring = Keyring.add(before, found)
        val added = store.keyring.size - before.size
        Trail.say(
            when {
                added == 0 && found.isEmpty() -> "No Google key in that file"
                added == 0 -> "That key is already on the ring"
                else -> "$added added. Testing…"
            }
        )
        UiTick.bump()
        store.keyring.filter { it.verdict == Keyring.Verdict.UNTRIED }.forEach { testKey(it) }
    }

    /** Ask Google about one key and write what it said beside it. */
    private fun testKey(key: Keyring.Key) {
        lifecycleScope.launch {
            val answer = GoogleTiles.session(MapLayer.GoogleView.NORMAL, key.value)
            val verdict = when {
                answer.token != null -> Keyring.Verdict.GOOD
                answer.problem?.contains("could not be reached") == true -> Keyring.Verdict.UNREACHABLE
                else -> Keyring.Verdict.REFUSED
            }
            store.keyring = Keyring.withVerdict(
                store.keyring,
                key.value,
                verdict,
                answer.problem ?: "works",
                System.currentTimeMillis(),
            )
            Trail.say("${key.label}: ${answer.problem ?: "works"}")
            UiTick.bump()
        }
    }

    private fun removeKey(key: Keyring.Key) {
        store.keyring = Keyring.remove(store.keyring, key.value)
        Trail.say("${key.label} taken off the ring")
        UiTick.bump()
    }

    private fun testTiles() {
        val layer = Layers.byId(store.layerId)
        Trail.say("Asking ${layer.name} for one tile…")
        lifecycleScope.launch {
            val googleKey = store.key(Keys.Provider.GOOGLE)
            val session = if (layer.kind == LayerKind.GOOGLE_TILES && googleKey != null) {
                GoogleTiles.session(layer.googleView ?: MapLayer.GoogleView.NORMAL, googleKey).token
            } else {
                null
            }
            tileAnswer = TileTest.check(layer, session, layer.provider?.let { store.key(it) })
            Trail.say(tileAnswer)
            UiTick.bump()
        }
    }

    private fun trackFolder(): java.io.File = java.io.File(filesDir, "tracks").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From targetSdk 35 Android draws every app edge to edge and insets nothing for us, so
        // the window is the whole glass and the bars are painted over whatever is under them.
        // The screen applies safeDrawingPadding; this line is the other half of the same fact.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        store = Store(this)
        Routing.prepare(this)
        locator = Locator(this)
        sensors = Sensors(this)

        setContent {
            TrailApp(
                store = store,
                sensors = sensors,
                version = BuildConfig.VERSION_NAME,
                onCanvas = { canvas = it },
                onWhereAmI = ::whereAmI,
                onRecord = ::toggleRecording,
                onPause = ::togglePause,
                onChooseMapFile = { pickMapFile.launch(arrayOf("*/*")) },
                onChooseExportFolder = { pickExportFolder.launch(null) },
                onImportKeys = { pickKeyFile.launch(arrayOf("*/*")) },
                onBare = ::setFullScreen,
                tracks = { Folder.list(this, store) },
                folderLabel = Folder.label(this, store),
                onRenameJustFinished = ::saveRecording,
                onDeleteTrack = ::deleteTrack,
                onRenameTrack = ::renameTrack,
                onShowTrack = ::showTrack,
                onTestTiles = ::testTiles,
                onFetchRegion = ::fetchRegion,
                onTestKey = ::testKey,
                onRemoveKey = ::removeKey,
                onSaveRoute = ::saveRoute,
                onFindWays = ::findWays,
                onSaveOption = ::saveOption,
                routeOptions = routeOptions,
            )
        }

        if (locator.hasPermission()) locator.start()
        askForNotificationPermission()
    }

    override fun onResume() {
        super.onResume()
        sensors.start()
        if (locator.hasPermission()) locator.start()
        canvas?.resume()
    }

    override fun onPause() {
        sensors.stop()
        canvas?.remember()
        canvas?.pause()
        // The fixes keep coming while a recording is running, because the service is asking for
        // them too. With nothing recording there is no reason to keep the receiver warm.
        if (!Trail.isRecording()) locator.stop()
        super.onPause()
    }

    override fun onDestroy() {
        canvas?.destroy()
        super.onDestroy()
    }

    /**
     * THE BARE VIEW, reached by tapping the middle of the map. The system's bars go out with ours,
     * so there is the map and nothing else; tapping the middle again brings both back. Sticky
     * immersive, so a swipe shows the bars for a moment without dropping out of the view.
     */
    private fun setFullScreen(on: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }




    private fun whereAmI() {
        if (!locator.hasPermission()) {
            askLocation.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        val fix = Trail.fix.value
        if (fix == null) {
            Trail.say("No fix yet. Under a roof it can take a minute.")
            return
        }
        canvas?.centreOn(fix)
        sensors.updateDeclination(fix)
    }

    private fun toggleRecording() {
        if (Trail.isRecording()) {
            TrailService.send(this, TrailService.ACTION_STOP)
            return
        }
        if (!locator.hasPermission()) {
            pendingRecord = true
            askLocation.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        startRecording()
    }

    private fun startRecording() {
        TrailService.start(this, TrailService.defaultName())
    }

    private fun togglePause() {
        if (!Trail.isRecording()) return
        TrailService.send(
            this,
            if (Trail.paused.value) TrailService.ACTION_RESUME else TrailService.ACTION_PAUSE,
        )
    }

    /**
     * The finished track, copied out of the app's own folder into one the person chose — so it is
     * still there after an uninstall, and so it can be opened by anything else on the phone.
     */


    /** Say it on the map's note line AND in the track manager, since either may be in front. */
    private fun report(message: String?) {
        Trail.say(message)
        Trail.sayInManager(message)
    }


    private fun askForNotificationPermission() {
        // POST_NOTIFICATIONS exists from 33. Guarded with >=, never with a negated <, because
        // Lint reads the negated form as an inlined API use and fails the build (android-app.md 5).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) askLocation.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}
