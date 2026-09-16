package com.mantra.trail

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface

/**
 * THE MARKS THEMSELVES, drawn once and used by both engines.
 *
 * When the second renderer arrived (16.9.2026) the position dot and the route letters existed
 * only inside the mapsforge canvas, and the quickest thing would have been to copy them. Two
 * copies of a drawing is two drawings that drift, and the day they drift is the day one map shows
 * a different mark from the other for the same place. So they live here, in Android's own
 * graphics, and each engine wraps the result in whatever bitmap it wants.
 */
object Marks {

    /** Where he is, and the cone of light showing where the phone is pointed. */
    fun position(context: Context, heading: Double, mapTurn: Double): Bitmap {
        val scale = context.resources.displayMetrics.density
        val side = (72 * scale).toInt()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = side / 2f
        val dot = 7f * scale

        if (!heading.isNaN()) {
            val reach = c - 1f
            val sweep = 62f
            val start = (heading + mapTurn - 90.0 - sweep / 2).toFloat()
            val cone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    c,
                    c,
                    reach,
                    intArrayOf(
                        Color.argb(150, 59, 130, 246),
                        Color.argb(70, 59, 130, 246),
                        Color.argb(0, 59, 130, 246),
                    ),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawArc(RectF(c - reach, c - reach, c + reach, c + reach), start, sweep, true, cone)
        }

        canvas.drawCircle(
            c,
            c + 0.5f * scale,
            dot + 2.5f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0) },
        )
        canvas.drawCircle(
            c,
            c,
            dot + 2f * scale,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE },
        )
        canvas.drawCircle(
            c,
            c,
            dot,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(255, 59, 130, 246) },
        )
        return bitmap
    }

    /** A route point: the hairline cross of the screen's own centre, with its letter beside it. */
    fun routePoint(context: Context, letter: String): Bitmap {
        val scale = context.resources.displayMetrics.density
        val side = (44 * scale).toInt()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val c = side / 2f
        val arm = side / 2f - 2 * scale
        val gap = arm * 0.36f

        fun cross(colour: Int, width: Float) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = colour
                strokeWidth = width
                style = Paint.Style.STROKE
            }
            canvas.drawLine(c - arm, c, c - gap, c, p)
            canvas.drawLine(c + gap, c, c + arm, c, p)
            canvas.drawLine(c, c - arm, c, c - gap, p)
            canvas.drawLine(c, c + gap, c, c + arm, p)
            canvas.drawCircle(c, c, gap, p)
        }
        cross(Color.argb(200, 11, 13, 16), 3f * scale)
        cross(Color.argb(255, 96, 165, 250), 1.4f * scale)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 96, 165, 250)
            textSize = 13f * scale
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setShadowLayer(3f * scale, 0f, 0f, Color.argb(220, 11, 13, 16))
        }
        canvas.drawText(letter, c + gap + 2 * scale, c - gap, text)
        return bitmap
    }
}
