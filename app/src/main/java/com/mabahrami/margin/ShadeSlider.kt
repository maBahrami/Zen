package com.mabahrami.margin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils

/**
 * A vertical slider through the shades of one colour: light at the top, dark at the bottom, same hue.
 * [main] is the palette's colour (its hue and strength); grey for black. [shown] turns a colour into how it
 * looks on the page (dark view), for drawing only. [onChange] gets the colour, and done = true on release.
 */
@SuppressLint("ViewConstructor")
class ShadeSlider(
    context: Context,
    private val main: Int,
    private val shown: (Int) -> Int,
) : View(context) {

    var onChange: ((color: Int, done: Boolean) -> Unit)? = null

    private val hsl = FloatArray(3).also { ColorUtils.colorToHSL(main, it) }
    private val grey = hsl[1] < 0.1f || hsl[2] < 0.08f
    private val top = if (grey) 0.95f else 0.92f
    private val bottom = if (grey) 0.0f else 0.12f

    /** 0 = top (lightest) .. 1 = bottom (darkest). */
    var value = valueOf(main)
        private set

    fun setColor(c: Int) {
        value = valueOf(c)
        invalidate()
    }

    /** The colour at [v] (0..1). The main colour itself sits exactly at its own lightness. */
    fun colorAt(v: Float): Int {
        val l = top + (bottom - top) * v.coerceIn(0f, 1f)
        if (grey) return ColorUtils.HSLToColor(floatArrayOf(0f, 0f, l))
        if (kotlin.math.abs(l - hsl[2]) < 0.004f) return main
        // lighter shades a little softer, darker ones keep their strength
        val sat = if (l > hsl[2]) hsl[1] * (1f - 0.25f * ((l - hsl[2]) / (top - hsl[2]).coerceAtLeast(0.01f))) else hsl[1]
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], sat, l))
    }

    private fun valueOf(c: Int): Float {
        if (c == main && !grey) return ((hsl[2] - top) / (bottom - top)).coerceIn(0f, 1f)
        val h = FloatArray(3)
        ColorUtils.colorToHSL(c, h)
        return ((h[2] - top) / (bottom - top)).coerceIn(0f, 1f)
    }

    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66000000 }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var shaderFor = -1

    /** Room at the ends: the whole thumb (its ring and shadow included) stays inside the slider. */
    private val pad get() = knobH / 2f + 3f * density
    /** The knob: a small rounded bar across the slider. */
    private val knobH get() = 4f * density
    private val knob = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (shaderFor != height + width * 100000) {
            shaderFor = height + width * 100000
            val n = 12
            val colors = IntArray(n) { shown(colorAt(it / (n - 1f))) }
            bar.shader = LinearGradient(0f, pad, 0f, h - pad, colors, null, Shader.TileMode.CLAMP)
        }
        r.set(w * 0.2f, pad, w * 0.8f, h - pad)
        canvas.drawRoundRect(r, w * 0.3f, w * 0.3f, bar)
        // the thumb: a ring in the chosen shade
        val y = pad + (h - 2 * pad) * value
        // the knob: a thin white rounded line across the slider, just past its edges, with a faint shadow
        val kw = w * 0.78f
        val kh = knobH
        knob.set((w - kw) / 2f, y - kh / 2f, (w + kw) / 2f, y + kh / 2f)
        val kr = kh / 2f
        thumbShadow.strokeWidth = 1.5f * density
        canvas.drawRoundRect(knob, kr, kr, thumbShadow)
        thumbFill.color = Color.WHITE
        canvas.drawRoundRect(knob, kr, kr, thumbFill)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                value = ((e.y - pad) / (height - 2 * pad)).coerceIn(0f, 1f)
                invalidate()
                onChange?.invoke(colorAt(value), e.actionMasked == MotionEvent.ACTION_UP)
            }
            MotionEvent.ACTION_CANCEL -> onChange?.invoke(colorAt(value), true)
        }
        return true
    }
}
