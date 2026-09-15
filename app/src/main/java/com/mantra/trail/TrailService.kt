package com.mantra.trail

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * THE RECORDING, WHICH MUST SURVIVE THE SCREEN GOING OFF AND THE BATTERY GOING FLAT.
 *
 * A foreground service with the location type, because that is the only way Android keeps giving
 * an app fixes with the screen off, and because a recording that quietly stops in a rucksack is
 * the exact silent failure that makes a track app worthless.
 *
 * THE FILE IS WRITTEN AS THE WALK HAPPENS. Header on start, one point per accepted fix, flushed
 * every time. A file assembled at the end does not exist when the battery dies on the ridge; this
 * one costs the closing tags at worst, and those are added back when it is next opened.
 */
class TrailService : Service() {

    private lateinit var locator: Locator
    private var writer: OutputStreamWriter? = null
    private var file: File? = null
    private var startedMs: Long = 0L
    private var name: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent.getStringExtra(EXTRA_NAME) ?: defaultName())
            ACTION_PAUSE -> {
                Trail.pause()
                writer?.appendSafely(Gpx.segmentBreak())
                notifyNow()
            }

            ACTION_RESUME -> {
                Trail.resume()
                notifyNow()
            }

            ACTION_STOP -> finish()
            else -> Unit
        }
        return START_STICKY
    }

    private fun begin(trackName: String) {
        if (Trail.isRecording()) return
        startedMs = System.currentTimeMillis()
        name = trackName
        Trail.start(startedMs)

        // The working copy is app-private, because it is written to a thousand times and a
        // document tree is not the place for that. The export is a copy, made when it is finished.
        val dir = File(filesDir, "tracks").apply { mkdirs() }
        val f = File(dir, Gpx.fileName(startedMs, name))
        file = f
        writer = try {
            OutputStreamWriter(FileOutputStream(f), Charsets.UTF_8).also {
                it.append(Gpx.header(name, startedMs))
                it.flush()
            }
        } catch (e: Exception) {
            Trail.say("Could not open the track file: ${e.javaClass.simpleName}")
            null
        }

        locator = Locator(this)
        FixWriter.attach { fix -> onAccepted(fix) }
        locator.start(1_000L)
        startForeground(NOTIFICATION_ID, buildNotification(), foregroundType())
    }

    private fun onAccepted(fix: Fix) {
        writer?.appendSafely(Gpx.point(fix))
        val n = Trail.stats.value.points
        if (n % 10 == 0) notifyNow()
    }

    private fun finish() {
        val points = Trail.stop()
        writer?.appendSafely(Gpx.footer())
        try {
            writer?.flush()
            writer?.close()
        } catch (e: Exception) {
            Trail.say("The track file did not close cleanly: ${e.javaClass.simpleName}")
        }
        writer = null
        if (::locator.isInitialized) locator.stop()
        FixWriter.detach()
        LastTrack.set(file, points, name, startedMs)
        // The screen is told a track has just been written, which is what opens the rename
        // popup. It carries the file so nothing has to be looked up again.
        Trail.finished(file)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // A dial switched off must be cancelled (android-app.md 4): a notification nobody takes
        // down keeps showing a figure that has stopped being refreshed.
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun foregroundType(): Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    private fun notifyNow() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stats = Trail.stats.value
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val state = if (Trail.paused.value) "paused" else "recording"
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Mantra Trail, $state")
            .setContentText(
                "${Geo.formatDistance(stats.distanceM)} · ${Geo.formatDuration(stats.durationMs)} · ${stats.points} points"
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun OutputStreamWriter.appendSafely(text: String) {
        try {
            append(text)
            flush()
        } catch (e: Exception) {
            Trail.say("The track file stopped accepting points: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        const val ACTION_START = "com.mantra.trail.START"
        const val ACTION_PAUSE = "com.mantra.trail.PAUSE"
        const val ACTION_RESUME = "com.mantra.trail.RESUME"
        const val ACTION_STOP = "com.mantra.trail.STOP"
        const val EXTRA_NAME = "name"
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 41

        fun defaultName(): String = Tracks.defaultName(System.currentTimeMillis())

        fun start(context: Context, name: String) {
            context.startForegroundService(
                Intent(context, TrailService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_NAME, name)
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, TrailService::class.java).setAction(action))
        }
    }
}

/**
 * The one place a recorded point is handed to whoever is writing the file. Trail decides what is
 * recorded; this carries the decision to the writer without the state object knowing about files.
 */
object FixWriter {
    private var sink: ((Fix) -> Unit)? = null

    fun attach(f: (Fix) -> Unit) {
        sink = f
    }

    fun detach() {
        sink = null
    }

    fun wrote(fix: Fix) {
        sink?.invoke(fix)
    }
}

/** What the screen needs after a recording ends: the file it can export and the shape of it. */
object LastTrack {
    var file: File? = null
        private set
    var points: List<Fix> = emptyList()
        private set
    var name: String = ""
        private set
    var startedMs: Long = 0L
        private set

    fun set(f: File?, p: List<Fix>, n: String, started: Long) {
        file = f
        points = p
        name = n
        startedMs = started
    }
}
