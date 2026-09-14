package com.mantra.trail

import android.content.Context
import android.content.SharedPreferences

/**
 * WHAT THE APP REMEMBERS. Four things, and each one is here because forgetting it costs the
 * person a walk to the settings before they can use the app at all.
 *
 * The map file and the export folder are held as SAF tree or document URIs, with their permission
 * taken persistently: a folder chosen once must survive a reboot, an update and an uninstall
 * (the same decision as ANDROID VOICES, for the same reason — the file is large and the person
 * chose where it lives).
 */
class Store(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mantra-trail", Context.MODE_PRIVATE)

    var layerId: String
        get() = prefs.getString(KEY_LAYER, Layers.OAM.id) ?: Layers.OAM.id
        set(v) = prefs.edit().putString(KEY_LAYER, v).apply()

    /** The .map file for the offline vector layer, chosen with the file picker. */
    var mapFileUri: String?
        get() = prefs.getString(KEY_MAP_FILE, null)
        set(v) = prefs.edit().putString(KEY_MAP_FILE, v).apply()

    /** The folder finished tracks are copied into, so they outlive the app. */
    var exportTreeUri: String?
        get() = prefs.getString(KEY_EXPORT_TREE, null)
        set(v) = prefs.edit().putString(KEY_EXPORT_TREE, v).apply()

    var levelPitchZero: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_PITCH_ZERO, 0L))
        set(v) = prefs.edit().putLong(KEY_PITCH_ZERO, java.lang.Double.doubleToRawLongBits(v)).apply()

    var levelRollZero: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_ROLL_ZERO, 0L))
        set(v) = prefs.edit().putLong(KEY_ROLL_ZERO, java.lang.Double.doubleToRawLongBits(v)).apply()

    /**
     * The keys imported from a file. They live in the app's own private storage and nowhere else:
     * not in the repository, not in a log line, not on the screen. What the screen may show is
     * how many there are (secrets.md 3, keyring.md 10d).
     */
    var keys: List<String>
        get() = prefs.getString(KEY_KEYS, "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = prefs.edit().putString(KEY_KEYS, v.joinToString("\n")).apply()

    val keyCount: Int get() = keys.size

    fun calibration(): Level.Calibration = Level.Calibration(levelPitchZero, levelRollZero)

    /** The last place the map was looking, so opening the app does not start in the Atlantic. */
    var lastLat: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LAT, DEFAULT_LAT_BITS))
        set(v) = prefs.edit().putLong(KEY_LAST_LAT, java.lang.Double.doubleToRawLongBits(v)).apply()

    var lastLon: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_LON, DEFAULT_LON_BITS))
        set(v) = prefs.edit().putLong(KEY_LAST_LON, java.lang.Double.doubleToRawLongBits(v)).apply()

    var lastZoom: Int
        get() = prefs.getInt(KEY_LAST_ZOOM, 13)
        set(v) = prefs.edit().putInt(KEY_LAST_ZOOM, v.coerceIn(2, 20)).apply()

    companion object {
        private const val KEY_LAYER = "layer"
        private const val KEY_KEYS = "keys"
        private const val KEY_MAP_FILE = "mapFile"
        private const val KEY_EXPORT_TREE = "exportTree"
        private const val KEY_PITCH_ZERO = "pitchZero"
        private const val KEY_ROLL_ZERO = "rollZero"
        private const val KEY_LAST_LAT = "lastLat"
        private const val KEY_LAST_LON = "lastLon"
        private const val KEY_LAST_ZOOM = "lastZoom"

        /** Zagreb, because that is where the phone usually is when the app is opened cold. */
        private val DEFAULT_LAT_BITS = java.lang.Double.doubleToRawLongBits(45.8150)
        private val DEFAULT_LON_BITS = java.lang.Double.doubleToRawLongBits(15.9819)
    }
}
