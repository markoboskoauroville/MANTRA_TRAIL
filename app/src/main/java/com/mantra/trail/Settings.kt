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
    var googleOpen by mutableStateOf(false)
    var offlineOpen by mutableStateOf(false)
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
            Group("tracks") {
                Line(
                    title = "tracks",
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
            Group("maps") {
                Line(
                    title = "offline map",
                    under = offlineUnder,
                    onPress = onMaps,
                    trailing = {
                        Caret(open = state.offlineOpen) { state.offlineOpen = !state.offlineOpen }
                    },
                )
                if (state.offlineOpen) {
                    Layers.OFFLINE_VIEWS.forEach { view ->
                        Rule()
                        Line(
                            title = view.label,
                            under = view.about,
                            inset = true,
                            onPress = { onOfflineView(view) },
                            trailing = { Dot(chosen = view.theme == state.theme) },
                        )
                    }
                }

                Rule()
                Line(
                    title = "Google map",
                    under = if (hasGoogleKey) "key set" else "needs your own key",
                    onPress = { state.googleOpen = !state.googleOpen },
                    trailing = {
                        Caret(open = state.googleOpen) { state.googleOpen = !state.googleOpen }
                    },
                )
                if (state.googleOpen) {
                    Layers.GOOGLE_ALL.forEach { layer ->
                        Rule()
                        Line(
                            title = layer.name,
                            inset = true,
                            onPress = { onPick(layer) },
                            trailing = { Dot(chosen = layer.id == chosenGoogleId) },
                        )
                    }
                    Rule()
                    Line(
                        title = "Google Maps API key",
                        under = if (hasGoogleKey) "set — from a file you picked" else "not set",
                        inset = true,
                        onPress = onImportKeys,
                    )
                    Rule()
                    Line(
                        title = "test the key",
                        under = "asks the service for one tile",
                        inset = true,
                        onPress = onTestTiles,
                    )
                }
            }

            Group("about") {
                Line(
                    title = "what is the map doing",
                    under = state.answer ?: "ask it",
                    onPress = {
                        state.answer = CanvasHolder.canvas?.diagnose() ?: "the map view is not up yet"
                    },
                )
                Rule()
                Line(title = "Mantra Trail", under = "v$version")
                Rule()
                Line(
                    title = "credits",
                    under = "© OpenStreetMap contributors · OpenAndroMaps · OpenHiking · " +
                        "BRouter (MIT) · Google",
                )
            }

            Box(Modifier.height(24.dp))
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
    onPress: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .then(if (onPress != null) Modifier.clickable(onClick = onPress) else Modifier)
            .padding(start = if (inset) 32.dp else 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Words(title, Paint.Sand, 15, TextAlign.Start)
            if (under != null) Words(under, Paint.Dim, 12, TextAlign.Start)
        }
        if (trailing != null) {
            Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) { trailing() }
        } else if (onPress != null) {
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
/**
 * A RADIO MARK: one of these is filled and the rest are rings. Not a tick — a tick is a question
 * about permission, and the question here is which one he wants to see (17.9.2026).
 */
@Composable
private fun Dot(chosen: Boolean) {
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(20.dp)) {
            val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f - 1.dp.toPx()
            drawCircle(
                color = if (chosen) Paint.Amber else Paint.Dim,
                radius = r,
                center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.4.dp.toPx()),
            )
            if (chosen) drawCircle(Paint.Amber, radius = r * 0.5f, center = c)
        }
    }
}

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
