package com.mantra.trail

import androidx.compose.ui.graphics.Color

/**
 * THE AGY LOOK (design-language.md 3), over a map.
 *
 * Baba, 14.9.2026: *"all these buttons, they cover the map because they have black behind them.
 * Make transparent background. Let's say 50%."* So every panel and every key is the near-black
 * ground at half strength: the map reads through it, and the text still reads against it because
 * sand on half-black clears the contrast floor with room to spare.
 *
 * Colour is still the only state channel. Amber is the lit thing, sand is ink, red is recording
 * and real faults, and nothing else carries a hue.
 */
object Paint {
    val Ground = Color(0xFF0B0D10)
    val Sand = Color(0xFFF2DDB4)
    val Amber = Color(0xFFE8A64B)

    /** The position colour, and now the centre mark and the lit keys with it. */
    val AmberBright = Color(0xFFFBBF24)
    val Red = Color(0xFFEF4444)
    val Green = Color(0xFF34D399)

    /** Half-strength ground: the background of everything that sits over the map. */
    val Veil = Color(0x800B0D10)

    /** Ink for something present but not usable yet. The same ink, quieter. */
    val Dim = Color(0x8CF2DDB4)
}
