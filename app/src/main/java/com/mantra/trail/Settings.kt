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
            Group("Tracks") {
                Line(
                    title = "Tracks",
                    opens = true,
                    under = "$trackCount in the folder · rename, show, delete, the folder itself",
                    onPress = onTracks,
                )
            }

            // TWO MAPS, AND EACH HAS VIEWS YOU CHOOSE ONE OF (17.9.2026).
            //
            // The ticks are gone. A tick said "this view is allowed in the switcher", which is a
            // question nobody asked: he wants to pick a view and see it. So each view is a radio —
            // one at a time, the chosen one marked — and choosing closes the settings and draws it.
            // The key on the map screen turns between the two MAPS, not through eight views.
            Group("Maps") {
                Line(
                    // PLURAL, BECAUSE THERE ARE SEVERAL (17.9.2026): the dropdown lists every map
                    // on the phone, finished or half-fetched, and one of them draws at a time.
                    title = "Offline maps",
                    opens = true,
                    under = offlineUnder,
                    onPress = onMaps,
                    trailing = {
                        Caret(open = state.offlineOpen) { state.setOfflineopen(!state.offlineOpen) }
                    },
                )
                if (state.offlineOpen) {
                    installedMaps.forEach { file ->
                        Rule()
                        Line(
                            title = file.name.removePrefix("oam-").removeSuffix(".map"),
                            under = "${file.length() / 1_000_000} MB",
                            inset = true,
                            chosen = file.name == drawingMapName,
                            onPress = { onUseMap(file) },
                        )
                    }
                    unfinishedMaps.forEach { file ->
                        Rule()
                        Line(
                            title = file.name.removeSuffix(".part").removeSuffix(".zip")
                                .removePrefix("oam-").removeSuffix(".map"),
                            under = "${file.length() / 1_000_000} MB so far · not finished, open this row to carry on",
                            inset = true,
                            onPress = onMaps,
                        )
                    }
                    if (installedMaps.isEmpty() && unfinishedMaps.isEmpty()) {
                        Rule()
                        Line(
                            title = "no map on the phone yet",
                            under = "open this row to fetch one",
                            inset = true,
                            onPress = onMaps,
                        )
                    }

                    // THE TWO HE NAMED, whether or not they are here yet: Balkan and the Croatia
                    // one from his own GitHub. A dropdown that only lists what is already on the
                    // phone cannot be used to switch to the other one.
                    Oam.OFFERED.forEach { offer ->
                        val here = installedMaps.any { it.name == offer.mapName }
                        if (!here) {
                            Rule()
                            Line(
                                title = offer.label,
                                under = "not on the phone · ${offer.sizeLabel} to fetch",
                                inset = true,
                                onPress = { onFetchOffer(offer) },
                            )
                        }
                    }

                    Rule()
                    Line(title = "how they are drawn", inset = true)
                    Layers.OFFLINE_VIEWS.forEach { view ->
                        Rule()
                        Line(
                            title = view.label,
                            under = view.about,
                            inset = true,
                            chosen = view.theme == state.theme,
                            onPress = { onOfflineView(view) },
                        )
                    }
                }

                Rule()
                Line(
                    title = "Google maps",

                    onPress = { state.setGoogleopen(!state.googleOpen) },
                    trailing = {
                        Caret(open = state.googleOpen) { state.setGoogleopen(!state.googleOpen) }
                    },
                )
                if (state.googleOpen) {
                    Layers.GOOGLE_ALL.forEach { layer ->
                        Rule()
                        Line(
                            title = layer.name,
                            inset = true,
                            chosen = layer.id == chosenGoogleId,
                            onPress = { onPick(layer) },
                        )
                    }
                }
            }

            // THE KEYS, AT THE BOTTOM AND ON THEIR OWN (17.9.2026). They belong to no single
            // map: one ring serves whatever asks. Under the Google row they were two lines he had
            // to pass every time he wanted a view.
            Group("API keys") {
                // THE KEYRING (17.9.2026, as in his own KEY_RING_TESTER): every key he has
                // added, what it last answered, a square that tests it and writes the result
                // beside it, and a cross that takes it off. The app walks them in order when
                // it needs one, so a key that stops working is not a dead map.
                Line(
                    title = "Google Maps API keys",
                    under = if (keyring.isEmpty()) {
                        "none yet · add a file with a key in it"
                    } else {
                        "${keyring.size} on the ring · tried in order"
                    },
                    onPress = { state.setKeysopen(!state.keysOpen) },
                    trailing = { Caret(open = state.keysOpen) { state.setKeysopen(!state.keysOpen) } },
                )
                if (state.keysOpen) {
                    keyring.forEach { key ->
                    Rule()
                    Line(
                        title = key.label,
                        under = if (key.said.isNotBlank() && key.verdict != Keyring.Verdict.GOOD) {
                            "${key.masked} · ${key.said}"
                        } else {
                            Keyring.describe(key)
                        },
                        inset = true,
                        // TWO BUTTONS THE SAME SIZE, side by side and not overlapping
                        // (17.9.2026): a cross squeezed against a bordered box read as
                        // one broken control.
                        trailing = {
                            Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            ) {
                            Box(
                                Modifier
                                    .size(width = 58.dp, height = 36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.2.dp, Paint.Amber, RoundedCornerShape(8.dp))
                                    .clickable { onTestKey(key) },
                                contentAlignment = Alignment.Center,
                            ) { Words("test", Paint.Amber, 12) }
                            Box(
                                Modifier
                                    .size(width = 58.dp, height = 36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .border(1.2.dp, Paint.Red, RoundedCornerShape(8.dp))
                                    .clickable { onRemoveKey(key) },
                                contentAlignment = Alignment.Center,
                            ) { Words("delete", Paint.Red, 12) }
                            }
                        },
                    )
                    }
                    Rule()
                    Line(
                    title = "add keys from a file",
                    under = "every Google key in it goes on the ring and is tested",
                    inset = true,
                    opens = true,
                    onPress = onImportKeys,
                    )
                }
            }

            Group("About") {
                Line(
                    // The version is the title's own state; the app's name above it was a row
                    // spent saying what the launcher already says.
                    title = "credits",
                    under = "v$version · © OpenStreetMap contributors · OpenAndroMaps · " +
                        "OpenHiking · BRouter (MIT) · Google",
                )
            }
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
