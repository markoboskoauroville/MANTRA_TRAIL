package com.mantra.trail

import androidx.compose.ui.graphics.Color

/**
 * THE AGY LOOK (design-language.md 3). Dark, warm, low contrast between surfaces and high
 * contrast for the one thing that matters. The same amber as every other app in the account.
 *
 * COLOUR CARRIES STATE AND IT IS THE ONLY CHANNEL THAT DOES. Amber is the lit thing, sand is ink,
 * slate is present but not available, red is recording and real faults and nothing else.
 */
object Paint {
    val Ground = Color(0xFF0B0D10)
    val Surface = Color(0xFF141A21)
    val Slate = Color(0xFF23303D)
    val Sand = Color(0xFFF2DDB4)
    val Amber = Color(0xFFE8A64B)
    val AmberBright = Color(0xFFFBBF24)
    val Red = Color(0xFFEF4444)
    val Green = Color(0xFF34D399)

    /** Ink for something present but not usable yet. Never a different hue: the same ink, quieter. */
    val Dim = Color(0x59F2DDB4)
}
