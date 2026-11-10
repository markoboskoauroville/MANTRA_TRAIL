package com.mantra.trail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE SETTINGS, WRITTEN AGAIN FROM NOTHING (17.9.2026).
 *
 * Baba: *"When I click on Google View, the checkbox, it doesn't appear. I need to click multiple
 * times and then it's laggy."* He was right, and the cause was structural rather than a slip.
 *
 * The old face read the store DURING COMPOSITION — store.familyInToggle(...) inside the row that
 * drew the tick. A tap wrote to the preferences and nothing told Compose anything had changed, so
 * the tick did not move until some other thing happened to redraw the screen. Every read was also
 * a preferences read, dozens of them per frame, which is the lag.
 *
 * So this face reads the store ONCE, when it opens, into a holder that Compose watches. A tap
 * changes the holder — the screen redraws immediately, because that is what the holder is for —
 * and writes through to the store behind it. Nothing is read from disk while a frame is drawn.
 */
private class SettingsState(val store: Store) {
    var theme by mutableStateOf(store.themeName)
    // OPEN UNTIL HE CLOSES IT (17.9.2026). The state is his, so it outlives the screen.
    var googleOpen by mutableStateOf(store.opened("google"))
    var keysOpen by mutableStateOf(store.opened("keys"))
    var offlineOpen by mutableStateOf(store.opened("offline"))
    var answer by mutableStateOf<String?>(null)

    /** Opening one is a decision, so it is written down as well as held. */
    fun setOfflineopen(open: Boolean) {
        offlineOpen = open
        store.setOpened("offline", open)
    }

    fun setGoogleopen(open: Boolean) {
        googleOpen = open
        store.setOpened("google", open)
    }

    fun setKeysopen(open: Boolean) {
        keysOpen = open
        store.setOpened("keys", open)
    }

    fun cycleTheme(): String? {
        val next = THEMES[(THEMES.indexOf(theme) + 1) % THEMES.size]
        theme = next
        store.themeName = next
        return CanvasHolder.canvas?.setTheme(next)
    }
}

/** The themes, ours first. */
private val THEMES = listOf("MANTRA", "DEFAULT", "OSMARENDER", "NEWTRON", "BIKER", "TRONRENDER")

@Composable
fun SettingsFace(
    store: Store,
    current: MapLayer,
    installedMaps: List<java.io.File>,
    unfinishedMaps: List<java.io.File>,
    onFetchOffer: (OamIndex.Entry) -> Unit,
    hasImagery: Boolean,
    imageryHeld: String,
    keyring: List<Keyring.Key>,
    onTestKey: (Keyring.Key) -> Unit,
    onRemoveKey: (Keyring.Key) -> Unit,
    drawingMapName: String,
    offlineUnder: String,
    onUseMap: (java.io.File) -> Unit,
    onOfflineView: (Layers.OfflineView) -> Unit,
    chosenGoogleId: String,
    version: String,
    onPick: (MapLayer) -> Unit,
    onChooseMapFile: () -> Unit,
    onChooseExportFolder: () -> Unit,
    onImportKeys: () -> Unit,
    onPause: () -> Unit,
    recordingPaused: Boolean,
    onTracks: () -> Unit,
    trackCount: Int,
    onTestTiles: () -> Unit,
    onMaps: () -> Unit,
    installedCount: Int,
    folderName: String,
    hasGoogleKey: Boolean,
    onClose: () -> Unit,
    compassMode: Int = 0,
    onCompass: () -> Unit = {},
    searchBar: Boolean = true,
    onSearchBar: () -> Unit = {},
) {
    val state = remember(store) { SettingsState(store) }

    Box(Modifier.fillMaxSize().background(Paint.Ground)) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Words("settings", Paint.Sand, 17, TextAlign.Start, Modifier.padding(start = 4.dp))
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Paint.Card)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Words("✕", Paint.Sand, 18) }
            }

            // HIS ORDER AND HIS LOGIC (17.9.2026): tracks first, because that is what he opens
            // the settings for. Then one entry per map — offline and Google — and each of those
            // is TWO controls on one line: the name opens that map's own options, and the arrow
            // drops down its views. Choosing a view closes the settings and shows the map, which
            // is the only reason anybody opened the dropdown.
            // THE VISUAL LANGUAGE (27.9.2026): each row is one kind of control and looks it: a
            // chevron row opens something, a switch is a state, a check marks the chosen one, a
            // bar is a choice. One word where a sentence stood (Look.kt says why).
            Group("Tracks") {
                Opens("Tracks", R.drawable.ic_track, under = "$trackCount", onClick = onTracks)
            }

            Group("On the map") {
                Toggle("Compass", R.drawable.ic_compass, on = compassMode != 2, onChange = { onCompass() })
                Hairline()
                Toggle("Google search bar", R.drawable.ic_search, on = searchBar, onChange = { onSearchBar() })
            }

            Group("Offline maps") {
                // The group says "Offline maps"; the row says what it does (27.9.2026).
                Opens("Download", R.drawable.ic_save, onClick = onMaps)
                installedMaps.forEach { file ->
                    Hairline()
                    Pick(
                        title = file.name.removePrefix("oam-").removeSuffix(".map"),
                        icon = R.drawable.ic_mountain,
                        chosen = current.kind == LayerKind.VECTOR_FILE && file.name == drawingMapName,
                        under = "${file.length() / 1_000_000} MB",
                        onClick = { onUseMap(file) },
                    )
                }
                if (hasImagery) {
                    Hairline()
                    Pick(
                        title = "Satellite",
                        icon = R.drawable.ic_satellite,
                        chosen = current.id == Layers.IMAGERY.id,
                        under = imageryHeld,
                        onClick = { onPick(Layers.IMAGERY) },
                    )
                }
                unfinishedMaps.forEach { file ->
                    Hairline()
                    Action(
                        verb = file.name.removeSuffix(".part").removeSuffix(".zip").removePrefix("oam-").removeSuffix(".map"),
                        icon = R.drawable.ic_save,
                        onClick = onMaps,
                        quiet = true,
                        trailing = "${file.length() / 1_000_000} MB · unfinished",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Oam.OFFERED.forEach { offer ->
                    if (installedMaps.none { it.name == offer.mapName }) {
                        Hairline()
                        Action(
                            verb = offer.label,
                            icon = R.drawable.ic_save,
                            onClick = { onFetchOffer(offer) },
                            quiet = true,
                            trailing = offer.sizeLabel,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Hairline(inset = 16.dp)
                Box(Modifier.padding(12.dp)) {
                    Choice(
                        parts = Layers.OFFLINE_VIEWS.map { Part(it.label) },
                        chosen = Layers.OFFLINE_VIEWS.indexOfFirst { it.theme == state.theme }.coerceAtLeast(0),
                        onChoose = { i ->
                            val view = Layers.OFFLINE_VIEWS[i]
                            state.theme = view.theme
                            onOfflineView(view)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Group("Google maps") {
                Box(Modifier.padding(12.dp)) {
                    Choice(
                        parts = listOf(Part("Map"), Part("Satellite"), Part("Terrain"), Part("Hybrid")),
                        chosen = Layers.GOOGLE_ALL.indexOfFirst { it.id == chosenGoogleId }.coerceAtLeast(0),
                        onChoose = { i -> onPick(Layers.GOOGLE_ALL[i]) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // THE KEYRING: every key he added, what it last answered, a test and a delete for each.
            Group("API keys") {
                Opens(
                    title = "Google keys",
                    icon = R.drawable.ic_key,
                    under = if (keyring.isEmpty()) "none" else "${keyring.size}",
                    onClick = { state.setKeysopen(!state.keysOpen) },
                    open = state.keysOpen,
                )
                if (state.keysOpen) {
                    keyring.forEach { key ->
                        Hairline()
                        Row(
                            Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Words(key.label, Paint.Sand, 15, TextAlign.Start)
                                Words(
                                    if (key.said.isNotBlank() && key.verdict != Keyring.Verdict.GOOD) "${key.masked} · ${key.said}"
                                    else Keyring.describe(key),
                                    Paint.Dim, 12, TextAlign.Start,
                                )
                            }
                            IconAction(R.drawable.ic_play, "test", onClick = { onTestKey(key) })
                            IconAction(R.drawable.ic_trash, "delete", onClick = { onRemoveKey(key) }, tint = Paint.Red)
                        }
                    }
                    Hairline()
                    Action("Add from a file", R.drawable.ic_folder, onClick = onImportKeys, quiet = true, modifier = Modifier.fillMaxWidth())
                }
            }

            Group("About") {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Glyph(R.drawable.ic_info, Paint.Dim)
                    Column {
                        Words("v$version", Paint.Sand, 15, TextAlign.Start)
                        Words("© OpenStreetMap contributors · OpenAndroMaps · OpenHiking · BRouter (MIT) · Google", Paint.Dim, 12, TextAlign.Start)
                    }
                }
            }
            Words("© 2026 Mantra Productions", Paint.Dim, 12, modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
        }
    }
}

/** A titled card, as the phone's own settings draw one. */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Words(
        text = title.uppercase(),
        colour = Paint.Dim,
        size = 11,
        align = TextAlign.Start,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp),
    )
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Paint.Card),
        content = content,
    )
}

/** One row: what it is, and underneath, what it is set to. */
@Composable
private fun Line(
    title: String,
    under: String? = null,
    inset: Boolean = false,
    // WHETHER A TAP OPENS ANOTHER SCREEN. The chevron is drawn only when it does (17.9.2026):
    // "what is the map doing" wore one and opened nothing, which is a promise a row cannot keep.
    opens: Boolean = false,
    // WHETHER THIS ROW IS THE CHOSEN ONE. The whole row is the button; being chosen is an amber
    // outline round it, not a little circle beside it.
    chosen: Boolean = false,
    onPress: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (inset) 10.dp else 0.dp, vertical = if (chosen) 4.dp else 0.dp)
            .then(
                if (chosen) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.5.dp, Paint.Amber, RoundedCornerShape(12.dp))
                } else {
                    Modifier
                }
            )
            .heightIn(min = 60.dp)
            .then(if (onPress != null) Modifier.clickable(onClick = onPress) else Modifier)
            .padding(start = if (inset) 20.dp else 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Words(title, Paint.Sand, 15, TextAlign.Start)
            if (under != null) Words(under, Paint.Dim, 12, TextAlign.Start)
        }
        if (trailing != null) {
            Box(Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.Center) { trailing() }
        } else if (opens) {
            Words("›", Paint.Dim, 18, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

/**
 * THE ARROW THAT ACTUALLY OPENS SOMETHING (17.9.2026).
 *
 * He caught this: a row with a chevron on it that cycled through options instead of opening
 * anything. An arrow is a promise about what a tap does, and that one was lying. This one turns
 * to point down when its list is open, and it is a hit area of its own — the row's own tap opens
 * that map's options, and this opens its views.
 */

@Composable
private fun Caret(open: Boolean, onTap: () -> Unit) {
    Box(
        Modifier.size(52.dp).clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Words(if (open) "▾" else "▸", Paint.Amber, 15)
    }
}

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Paint.Rule))
}


@Composable
private fun Words(
    text: String,
    colour: Color,
    size: Int,
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
    )
}
