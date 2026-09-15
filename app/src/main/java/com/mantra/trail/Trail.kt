package com.mantra.trail

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * THE LIVE STATE, IN ONE PLACE.
 *
 * Two things deliver fixes: the screen, while it is open, and the recording service, while it is
 * running with the screen off. They both hand them here, and this decides what is true. Two
 * places holding "where am I" is two places to drift apart (README.md rule 1), and the drift
 * would show as the dot and the track disagreeing about the same moment.
 *
 * STATE AND REALITY ARE TWO FACTS AND THEY WILL DISAGREE (design-language.md 14). `recording` is a
 * claim; `recording != null` is checked at the point of use rather than trusted from a flag held
 * somewhere else.
 */
object Trail {

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()

    private val _stats = MutableStateFlow(TrackStats.EMPTY)
    val stats: StateFlow<TrackStats> = _stats.asStateFlow()

    private val _recordingSince = MutableStateFlow<Long?>(null)

    /** Null when nothing is being recorded; the start time when something is. */
    val recordingSince: StateFlow<Long?> = _recordingSince.asStateFlow()

    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _rejected = MutableStateFlow(0)

    /** Fixes the rules refused since recording began. Shown, never hidden. */
    val rejected: StateFlow<Int> = _rejected.asStateFlow()

    private val _line = MutableStateFlow<List<Fix>>(emptyList())

    /** The points to draw. A copy, so the drawing never walks a list somebody is appending to. */
    val line: StateFlow<List<Fix>> = _line.asStateFlow()

    private val _note = MutableStateFlow<String?>(null)

    /** One sentence for the person when something did not work. Never a stack trace. */
    val note: StateFlow<String?> = _note.asStateFlow()

    private var recording: Recording? = null
    private var lastAcceptedMs: Long = Long.MIN_VALUE

    @Synchronized
    fun start(startedMs: Long) {
        recording = Recording(startedMs)
        lastAcceptedMs = Long.MIN_VALUE
        _recordingSince.value = startedMs
        _paused.value = false
        _rejected.value = 0
        _stats.value = TrackStats.EMPTY
        _line.value = emptyList()
    }

    @Synchronized
    fun stop(): List<Fix> {
        val points = recording?.snapshot() ?: emptyList()
        recording = null
        _recordingSince.value = null
        _paused.value = false
        return points
    }

    @Synchronized
    fun pause() {
        recording?.pause()
        _paused.value = true
    }

    @Synchronized
    fun resume() {
        recording?.resume()
        _paused.value = false
    }

    @Synchronized
    fun isRecording(): Boolean = recording != null

    /**
     * A fix has arrived. Returns the point if it went into the track, so the caller writes exactly
     * what was recorded and the file cannot disagree with the list.
     *
     * The same fix can arrive twice, once from the screen's request and once from the service's.
     * The timestamp is the fix's own, so the second copy is recognised and dropped here rather
     * than becoming a duplicate point with a zero-second gap.
     */
    @Synchronized
    fun onFix(fix: Fix): Fix? {
        _fix.value = fix
        val rec = recording ?: return null
        if (fix.timeMs <= lastAcceptedMs) return null
        val before = rec.rejectedCount()
        val accepted = rec.offer(fix)
        if (accepted != null) {
            lastAcceptedMs = fix.timeMs
            _line.value = rec.snapshot()
        }
        if (rec.rejectedCount() != before) _rejected.value = rec.rejectedCount()
        _stats.value = rec.stats()
        return accepted
    }

    private val _justFinished = MutableStateFlow<java.io.File?>(null)

    /** The track that has just been written, until the screen has dealt with it. */
    val justFinished: StateFlow<java.io.File?> = _justFinished.asStateFlow()

    fun finished(file: java.io.File?) {
        _justFinished.value = file
    }

    fun dealtWith() {
        _justFinished.value = null
    }

    private val _managerNote = MutableStateFlow<String?>(null)

    /** The same kind of sentence, for whichever face is in front of the map. */
    val managerNote: StateFlow<String?> = _managerNote.asStateFlow()

    fun sayInManager(message: String?) {
        _managerNote.value = message
    }

    fun say(message: String?) {
        _note.value = message
    }
}
