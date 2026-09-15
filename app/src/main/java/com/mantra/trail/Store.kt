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

    private val app: Context = context.applicationContext

    private val prefs: SharedPreferences =
        app.getSharedPreferences("mantra-trail", Context.MODE_PRIVATE)

    var layerId: String
        get() = prefs.getString(KEY_LAYER, Layers.OFFLINE.id) ?: Layers.OFFLINE.id
        set(v) = prefs.edit().putString(KEY_LAYER, v).apply()

    /** The .map file for the offline vector layer, chosen with the file picker. */
    var mapFileUri: String?
        get() = prefs.getString(KEY_MAP_FILE, null)
        set(v) = prefs.edit().putString(KEY_MAP_FILE, v).apply()

    /** The folder finished tracks are copied into, so they outlive the app. */
    /** The folder's own name, so settings can say Documents/Tracks instead of "chosen". */
    var exportFolderName: String?
        get() = prefs.getString(KEY_EXPORT_NAME, null)
        set(v) = prefs.edit().putString(KEY_EXPORT_NAME, v).apply()

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
    var keys: Map<Keys.Provider, String>
        get() = (prefs.getString(KEY_KEYS, "") ?: "").split("\n")
            .filter { it.contains('|') }
            .mapNotNull { line ->
                val provider = runCatching { Keys.Provider.valueOf(line.substringBefore('|')) }.getOrNull()
                val value = line.substringAfter('|')
                if (provider != null && value.isNotBlank()) provider to value else null
            }
            .toMap()
        set(v) = prefs.edit()
            .putString(KEY_KEYS, v.entries.joinToString("\n") { "${it.key.name}|${it.value}" })
            .apply()

    fun key(provider: Keys.Provider): String? = keys[provider]

    /**
     * Which style of a family was last chosen. The map key turns through families, so this is
     * what decides whether "Thunderforest" means Outdoors or Landscape when it comes round.
     */
    /** True when there is an offline map to draw, from either of its two places. */
    val hasOfflineMap: Boolean
        get() = MapDownload.isPresent(app) || mapFileUri != null

    fun styleOf(family: MapLayer.Family): MapLayer {
        val id = prefs.getString("style-${family.name}", null)
        val remembered = id?.let { saved -> Layers.of(family).firstOrNull { it.id == saved } }
        return remembered ?: Layers.firstOf(family)
    }

    fun rememberStyle(layer: MapLayer) {
        prefs.edit().putString("style-${layer.family.name}", layer.id).apply()
    }

    val keyCount: Int get() = keys.size

    /** Which services have a key, by name, for the settings row. Never a key, never a piece of one. */
    val keyState: String
        get() = if (keys.isEmpty()) "none" else keys.keys.joinToString(", ") { it.name.lowercase() }

    /** What the settings row says about the offline map: on the phone, or not yet. */
    val offlineMapState: String
        get() = when {
            MapDownload.isPresent(app) -> "on the phone"
            mapFileUri != null -> "a file is chosen"
            else -> "not yet"
        }

    /** Whether a settings section is folded away. Remembered between sessions (15.9.2026). */
    fun collapsed(section: String): Boolean = prefs.getBoolean("collapsed-$section", section != "thunderforest")

    fun setCollapsed(section: String, value: Boolean) =
        prefs.edit().putBoolean("collapsed-$section", value).apply()

    /** The colour a loaded track is drawn in, as an ARGB value. */
    var trackColour: Long
        get() = prefs.getLong(KEY_TRACK_COLOUR, 0xFF34D399)
        set(v) = prefs.edit().putLong(KEY_TRACK_COLOUR, v).apply()

    /** The map name to ask the local server for. */
    var serverMap: String
        get() = prefs.getString(KEY_SERVER_MAP, "croatia") ?: "croatia"
        set(v) = prefs.edit().putString(KEY_SERVER_MAP, v).apply()

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
        private const val KEY_TRACK_COLOUR = "trackColour"
        private const val KEY_SERVER_MAP = "serverMap"
        private const val KEY_MAP_FILE = "mapFile"
        private const val KEY_EXPORT_TREE = "exportTree"
        private const val KEY_EXPORT_NAME = "exportName"
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
