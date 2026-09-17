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
        // If the remembered style has been taken out of the toggle, the toggle offers the first
        // one of that family that is still in it rather than offering nothing.
        if (remembered != null && inToggle(remembered.id)) return remembered
        return Layers.of(family).firstOrNull { inToggle(it.id) } ?: remembered ?: Layers.firstOf(family)
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

    /**
     * Whether a map appears in the toggle on the map screen (15.9.2026). Everything is in it
     * until he takes it out; the settings list still holds all of them, because excluding a map
     * from the toggle is not the same as not having it.
     */
    fun inToggle(layerId: String): Boolean = prefs.getBoolean("toggle-$layerId", true)

    fun setInToggle(layerId: String, value: Boolean) =
        prefs.edit().putBoolean("toggle-$layerId", value).apply()

    /**
     * Whether a whole family is in the toggle. Unticking it hides every map of that family from
     * the switcher WITHOUT forgetting which of them were ticked (15.9.2026), so ticking the
     * family again brings back exactly the maps he had chosen rather than all of them.
     */
    fun familyInToggle(family: MapLayer.Family): Boolean =
        prefs.getBoolean("family-toggle-${family.name}", true)

    fun setFamilyInToggle(family: MapLayer.Family, value: Boolean) =
        prefs.edit().putBoolean("family-toggle-${family.name}", value).apply()

    /** Whether a settings section is folded away. Remembered between sessions (15.9.2026). */
    fun collapsed(section: String): Boolean = prefs.getBoolean("collapsed-$section", section != "thunderforest")

    fun setCollapsed(section: String, value: Boolean) =
        prefs.edit().putBoolean("collapsed-$section", value).apply()

    /**
     * Which of the compass's three states T last left it in: 0 dark, 1 night, 2 off. A number
     * rather than a name because it is cycled, and cycling a number is one modulo.
     */
    var compassMode: Int
        get() = prefs.getInt(KEY_COMPASS, 2).coerceIn(0, 2)
        set(v) = prefs.edit().putInt(KEY_COMPASS, v.coerceIn(0, 2)).apply()

    /**
     * The points of a route, in the order they were placed: A, B, C and on (16.9.2026). Kept
     * between sessions, because a line drawn to the col before a walk is still wanted the next
     * morning. One line of text, decoded by Route where Test 1 can attack it.
     */
    var routePoints: List<Pair<Double, Double>>
        get() = Route.decode(prefs.getString(KEY_ROUTE_POINTS, null))
        set(v) = prefs.edit().putString(KEY_ROUTE_POINTS, Route.encode(v)).apply()


    /**
     * WHICH ENGINE DRAWS THE MAP (16.9.2026). false is mapsforge, which rasterises on the CPU and
     * is what every version until now used; true is VTM, the same project's OpenGL renderer,
     * reading the same files. Both are carried until one of them has been walked with enough to
     * delete the other.
     */
    var useVtm: Boolean
        get() = prefs.getBoolean(KEY_USE_VTM, false)
        set(v) = prefs.edit().putBoolean(KEY_USE_VTM, v).apply()

    /**
     * Which of VTM's render themes draws the offline map. It was fixed at MOTORIDER, which is a
     * motorcycle theme — hence a coast covered in petrol pumps (16.9.2026). Now his to choose.
     */
    var themeName: String
        get() = prefs.getString(KEY_THEME, "MANTRA") ?: "MANTRA"
        set(v) = prefs.edit().putString(KEY_THEME, v).apply()

    /** Which map file on the phone the offline layer draws. Empty means "the newest one". */
    var offlineMapName: String
        get() = prefs.getString(KEY_OFFLINE_MAP, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OFFLINE_MAP, v).apply()

    /**
     * WHICH GOOGLE VIEW THE MAP KEY SHOWS. One at a time, chosen in the settings: the key on the
     * map screen turns between the offline map and Google, and this says which Google (17.9.2026).
     */
    var googleViewId: String
        get() = prefs.getString(KEY_GOOGLE_VIEW, Layers.GOOGLE.id) ?: Layers.GOOGLE.id
        set(v) = prefs.edit().putString(KEY_GOOGLE_VIEW, v).apply()

    /** The keyring, as one line per key. */
    var keyring: List<Keyring.Key>
        get() = Keyring.decode(prefs.getString(KEY_RING, null))
        set(v) = prefs.edit().putString(KEY_RING, Keyring.encode(v)).apply()

    /**
     * WHICH ROUTER ANSWERS (17.9.2026). BRouter is in the app and works with the radio off;
     * Google knows what is open and costs a billed request each time it is asked.
     */
    var useGoogleRouting: Boolean
        get() = prefs.getBoolean(KEY_GOOGLE_ROUTING, false)
        set(v) = prefs.edit().putBoolean(KEY_GOOGLE_ROUTING, v).apply()

    /** Which BRouter profile the ways are found for. */
    var routeProfile: String
        get() = prefs.getString(KEY_ROUTE_PROFILE, "trekking") ?: "trekking"
        set(v) = prefs.edit().putString(KEY_ROUTE_PROFILE, v).apply()

    /** How many route options to ask for. One to five. */
    var routeOptions: Int
        get() = prefs.getInt(KEY_ROUTE_OPTIONS, 3).coerceIn(1, 5)
        set(v) = prefs.edit().putInt(KEY_ROUTE_OPTIONS, v.coerceIn(1, 5)).apply()

    /** The walking speed a time estimate is made from, in kilometres an hour. */
    var walkSpeedKmh: Float
        get() = prefs.getFloat(KEY_WALK_SPEED, 4.0f).coerceIn(1f, 30f)
        set(v) = prefs.edit().putFloat(KEY_WALK_SPEED, v.coerceIn(1f, 30f)).apply()

    /** The colour a loaded track is drawn in, as an ARGB value. */
    var trackColour: Long
        get() = prefs.getLong(KEY_TRACK_COLOUR, 0xFF34D399)
        set(v) = prefs.edit().putLong(KEY_TRACK_COLOUR, v).apply()

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
        private const val KEY_COMPASS = "compassMode"
        private const val KEY_ROUTE_OPTIONS = "routeOptions"
        private const val KEY_ROUTE_PROFILE = "routeProfile"
        private const val KEY_THEME = "themeName"
        private const val KEY_GOOGLE_VIEW = "googleView"
        private const val KEY_RING = "keyring"
        private const val KEY_GOOGLE_ROUTING = "googleRouting"
        private const val KEY_OFFLINE_MAP = "offlineMapName"
        private const val KEY_ROUTE_POINTS = "routePoints"
        private const val KEY_USE_VTM = "useVtm"
        private const val KEY_WALK_SPEED = "walkSpeedKmh"
        private const val KEY_MAP_FILE = "mapFile"
        private const val KEY_EXPORT_TREE = "exportTree"
        private const val KEY_EXPORT_NAME = "exportName"
        private const val KEY_LAST_LAT = "lastLat"
        private const val KEY_LAST_LON = "lastLon"
        private const val KEY_LAST_ZOOM = "lastZoom"

        /** Zagreb, because that is where the phone usually is when the app is opened cold. */
        private val NAN_BITS = java.lang.Double.doubleToRawLongBits(Double.NaN)

        private val DEFAULT_LAT_BITS = java.lang.Double.doubleToRawLongBits(45.8150)
        private val DEFAULT_LON_BITS = java.lang.Double.doubleToRawLongBits(15.9819)
    }
}
