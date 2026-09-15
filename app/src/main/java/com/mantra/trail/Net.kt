package com.mantra.trail

import android.net.TrafficStats
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * WHAT THE NETWORK IS DOING, ON THE SCREEN, ALWAYS.
 *
 * Baba, 15.9.2026: *"I want to have in this app download indicator. If something is downloading,
 * I want to see a speed... I'm waiting for it to download. Since I don't have indicator, I don't
 * know what's going on."*
 *
 * That is the whole complaint about this app in one sentence: it does long work in silence, and
 * silence and failure look identical (silent-failure.md). A map tile that is being fetched, a map
 * file that is being downloaded, and a map that is simply not coming are three different things,
 * and until now the screen showed the same nothing for all three.
 *
 * THE MEASUREMENT IS THE PHONE'S OWN. TrafficStats counts every byte this app's user id has
 * received since boot, so it covers mapsforge's own tile fetching — which happens inside the
 * library where no counter of ours could reach it — as well as our downloads. The number is
 * therefore real rather than reported by the thing being measured.
 *
 * AND WHEN NOTHING IS MOVING IT SAYS SO. Zero bytes a second while a map is blank is itself the
 * answer: the map is not waiting for the network, so the problem is elsewhere.
 */
object Net {

    private val _line = MutableStateFlow<String?>(null)

    /** One short line for the bottom of the screen, or null when there is nothing to report. */
    val line: StateFlow<String?> = _line.asStateFlow()

    private var lastRx = 0L
    private var lastTx = 0L
    private var lastAt = 0L

    /** Set while a named long job is running, so the line can say what the bytes are for. */
    @Volatile
    var job: String? = null

    /** How many seconds of quiet before the line goes away. */
    private const val QUIET_MS = 2_500L
    private var lastActivityAt = 0L

    /**
     * Sample the counters. Called about once a second from the screen; returns nothing and
     * publishes a line instead.
     */
    fun sample(nowMs: Long) {
        val rx = TrafficStats.getUidRxBytes(Process.myUid())
        val tx = TrafficStats.getUidTxBytes(Process.myUid())
        // UNSUPPORTED is -1 on some devices, and a negative speed is worse than no speed.
        if (rx < 0 || tx < 0) {
            _line.value = job
            return
        }
        if (lastAt == 0L) {
            lastRx = rx
            lastTx = tx
            lastAt = nowMs
            return
        }
        val seconds = (nowMs - lastAt) / 1000.0
        if (seconds <= 0.0) return
        val down = ((rx - lastRx) / seconds).toLong().coerceAtLeast(0L)
        val up = ((tx - lastTx) / seconds).toLong().coerceAtLeast(0L)
        lastRx = rx
        lastTx = tx
        lastAt = nowMs

        val moving = down > 1_000 || up > 1_000
        if (moving) lastActivityAt = nowMs

        val what = job
        _line.value = when {
            what != null && moving -> "$what · ↓ ${Geo.formatRate(down)}" + if (up > 5_000) " ↑ ${Geo.formatRate(up)}" else ""
            what != null -> what
            moving -> "↓ ${Geo.formatRate(down)}" + if (up > 5_000) " ↑ ${Geo.formatRate(up)}" else ""
            nowMs - lastActivityAt < QUIET_MS -> "↓ 0"
            else -> null
        }
    }

    fun forget() {
        job = null
        _line.value = null
        lastAt = 0L
    }
}
