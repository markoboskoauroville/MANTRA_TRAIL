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
        Trail.say(canvas?.show(Layers.OAM))
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
        exportLastTrack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        store = Store(this)
        locator = Locator(this)
        sensors = Sensors(this).apply { calibration = store.calibration() }

        setContent {
            TrailApp(
                store = store,
                sensors = sensors,
                onCanvas = { canvas = it },
                onWhereAmI = ::whereAmI,
                onRecord = ::toggleRecording,
                onPause = ::togglePause,
                onExport = ::exportLastTrack,
                onChooseMapFile = { pickMapFile.launch(arrayOf("*/*")) },
                onZeroLevel = ::zeroLevel,
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
            Trail.say("Exported $name")
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
