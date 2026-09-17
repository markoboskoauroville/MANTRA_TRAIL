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
    var googleFamily by mutableStateOf(store.familyInToggle(MapLayer.Family.GOOGLE))
    var answer by mutableStateOf<String?>(null)
    val googleViews = mutableStateListOf<Boolean>().apply {
        Layers.GOOGLE_ALL.forEach { add(store.inToggle(it.id)) }
    }

    fun chooseGoogleFamily(on: Boolean) {
        googleFamily = on
        store.setFamilyInToggle(MapLayer.Family.GOOGLE, on)
    }

    fun chooseGoogleView(index: Int, on: Boolean) {
        googleViews[index] = on
        store.setInToggle(Layers.GOOGLE_ALL[index].id, on)
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

            Group("maps") {
                Line(
                    title = "maps on this phone",
                    under = if (installedCount == 0) {
                        "none yet — fetch Croatia or any region"
                    } else {
                        "$installedCount here · fetch any region"
                    },
                    onPress = onMaps,
                )
                Rule()
                Line(
                    title = "how the map is drawn",
                    under = when (state.theme) {
                        "MANTRA" -> "walking — contours, path difficulty, waymarks"
                        else -> state.theme.lowercase()
                    },
                    onPress = { Trail.say(state.cycleTheme()) },
                )
                Rule()
                Line(
                    title = "add a .map file from the phone",
                    under = "file picker",
                    onPress = onChooseMapFile,
                )
                Rule()
                Line(
                    title = "Google's views",
                    under = if (hasGoogleKey) "key set · tap to open" else "needs your own key",
                    onPress = { state.googleOpen = !state.googleOpen },
                    trailing = { Box2(state.googleFamily) { state.chooseGoogleFamily(it) } },
                )
                if (state.googleOpen) {
                    Layers.GOOGLE_ALL.forEachIndexed { index, layer ->
                        Rule()
                        Line(
                            title = layer.name,
                            under = if (layer.id == current.id) "drawing now" else null,
                            inset = true,
                            onPress = { onPick(layer) },
                            trailing = {
                                Box2(state.googleViews[index]) { state.chooseGoogleView(index, it) }
                            },
                        )
                    }
                }
            }

            Group("tracks") {
                Line(title = "tracks (gpx)", under = "$trackCount in the folder", onPress = onTracks)
                Rule()
                Line(title = "folder they live in", under = folderName, onPress = onChooseExportFolder)
                Rule()
                Line(
                    title = "recording",
                    under = if (recordingPaused) "paused" else "running",
                    onPress = onPause,
                )
            }

            Group("keys") {
                Line(
                    title = "Google Maps API key",
                    under = if (hasGoogleKey) "set — from a file you picked" else "not set",
                    onPress = onImportKeys,
                )
                Rule()
                Line(title = "test the key", under = "asks the service for one tile", onPress = onTestTiles)
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

@Composable
private fun Rule() {
    Box(Modifier.fillMaxWidth().padding(start = 16.dp).height(1.dp).background(Paint.Rule))
}

/**
 * A TICK THAT MOVES WHEN IT IS TAPPED. The old one asked the preferences whether it was ticked
 * every time it was drawn, so tapping it changed the disk and not the screen (17.9.2026). This one
 * is told, and the telling is what redraws it.
 */
@Composable
private fun Box2(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.size(48.dp).clickable { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (checked) Paint.Amber else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (checked) Paint.Amber else Paint.Dim,
                    shape = RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Words("✓", Paint.Ground, 14)
        }
    }
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
