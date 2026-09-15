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
import kotlinx.coroutines.launch
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import org.mapsforge.map.android.graphics.AndroidGraphicFactory

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
    private var canvas: MapCanvas? = null

    private var pendingRecord by mutableStateOf(false)
    private var downloading = false

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
            // One key per service, the newest winning, so re-importing a file after rotating a
            // key replaces the dead one instead of leaving two and a guess about which is live.
            store.keys = store.keys + found.associate { it.provider to it.key }
            GoogleTiles.forget()
            UiTick.bump()
            Trail.say("Held for: ${store.keyState}")
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
        exportLastTrack()
    }

    /** Rename the track that was just recorded, then put it in the chosen folder. */
    private fun renameAndSave(file: java.io.File, newName: String) {
        val (renamed, problem) = Tracks.rename(file, newName)
        if (problem != null) {
            Trail.say(problem)
            return
        }
        LastTrack.set(renamed, LastTrack.points, newName, LastTrack.startedMs)
        exportLastTrack()
        UiTick.bump()
    }

    private fun renameTrack(file: java.io.File, newName: String) {
        val (_, problem) = Tracks.rename(file, newName)
        Trail.say(problem ?: "Renamed to $newName")
        UiTick.bump()
    }

    private fun deleteTrack(file: java.io.File) {
        val name = Tracks.displayName(file.name)
        val problem = Tracks.delete(file)
        Trail.say(problem ?: "Deleted $name")
        UiTick.bump()
    }

    private fun exportTrack(file: java.io.File) {
        LastTrack.set(file, LastTrack.points, Tracks.displayName(file.name), LastTrack.startedMs)
        exportLastTrack()
    }

    private fun trackFolder(): java.io.File = java.io.File(filesDir, "tracks").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From targetSdk 35 Android draws every app edge to edge and insets nothing for us, so
        // the window is the whole glass and the bars are painted over whatever is under them.
        // The screen applies safeDrawingPadding; this line is the other half of the same fact.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        AndroidGraphicFactory.createInstance(application)
        store = Store(this)
        locator = Locator(this)
        sensors = Sensors(this).apply { calibration = store.calibration() }

        setContent {
            TrailApp(
                store = store,
                sensors = sensors,
                version = BuildConfig.VERSION_NAME,
                onCanvas = { canvas = it },
                onWhereAmI = ::whereAmI,
                onRecord = ::toggleRecording,
                onPause = ::togglePause,
                onExport = ::exportLastTrack,
                onChooseMapFile = { pickMapFile.launch(arrayOf("*/*")) },
                onChooseExportFolder = { pickExportFolder.launch(null) },
                onImportKeys = { pickKeyFile.launch(arrayOf("*/*")) },
                onDownloadMap = ::downloadOfflineMap,
                onOpenMapLink = ::openMapLink,
                onZeroLevel = ::zeroLevel,
                onBare = ::setFullScreen,
                tracks = { Tracks.list(trackFolder()) },
                onRenameJustFinished = ::renameAndSave,
                onRenameTrack = ::renameTrack,
                onDeleteTrack = ::deleteTrack,
                onExportTrack = ::exportTrack,
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

    /**
     * THE OFFLINE MAP, FETCHED BY THE APP ITSELF. 176 MB from mapsforge's own server, resumable,
     * with the progress on the screen the whole time (download-monitor.md: never in the dark).
     */
    private fun downloadOfflineMap() {
        if (downloading) {
            Trail.say("Already fetching the map")
            return
        }
        if (MapDownload.isPresent(this)) {
            Trail.say("The offline map is already on the phone")
            return
        }
        downloading = true
        Net.job = "downloading the offline map"
        Trail.say("Fetching ${Layers.OfflineDownload.LABEL}. It can run in the background.")
        lifecycleScope.launch {
            val problem = MapDownload.fetch(this@MainActivity) { p ->
                Trail.say("Map ${p.percent}%, ${p.done / 1_000_000} of ${p.total / 1_000_000} MB")
            }
            downloading = false
            Net.job = null
            UiTick.bump()
            if (problem != null) {
                Trail.say(problem)
            } else {
                Trail.say("The offline map is on the phone. It works with no signal now.")
                if (Layers.byId(store.layerId).kind == LayerKind.VECTOR_FILE) {
                    Trail.say(canvas?.show(Layers.OFFLINE))
                }
            }
        }
    }

    /** The same file, in a browser, for when the phone is the wrong place to fetch 176 MB. */
    private fun openMapLink() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Layers.OfflineDownload.URL)))
        } catch (e: Exception) {
            Trail.say("No browser answered: ${Layers.OfflineDownload.URL}")
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
    private fun exportLastTrack() {
        val source = LastTrack.file
        if (source == null || !source.exists()) {
            Trail.say("There is no finished track to export yet")
            return
        }
        val treeUri = store.exportTreeUri
        if (treeUri == null) {
            pickExportFolder.launch(null)
            return
        }
        try {
            val tree = DocumentFile.fromTreeUri(this, Uri.parse(treeUri))
                ?: run {
                    store.exportTreeUri = null
                    Trail.say("That folder is no longer reachable. Choose it again.")
                    return
                }
            val name = source.name
            tree.findFile(name)?.delete()
            val target = tree.createFile("application/gpx+xml", name)
                ?: run {
                    Trail.say("The folder would not accept the file")
                    return
                }
            contentResolver.openOutputStream(target.uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: run {
                Trail.say("The file could not be written")
                return
            }
            // The message says WHERE, because "exported" with no folder in it is a message that
            // has to be trusted rather than checked.
            Trail.say("Track saved to ${store.exportFolderName ?: "the chosen folder"}: $name")
        } catch (e: Exception) {
            Trail.say("Export failed: ${e.javaClass.simpleName}")
        }
    }

    /** Zero the level on whatever the phone is lying on now. */
    private fun zeroLevel() {
        val raw = sensors.rawReading()
        if (!raw.trustworthy) {
            Trail.say("Put the phone down before zeroing it")
            return
        }
        store.levelPitchZero = raw.pitch
        store.levelRollZero = raw.roll
        sensors.calibration = store.calibration()
        Trail.say("Level zeroed on this surface")
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
