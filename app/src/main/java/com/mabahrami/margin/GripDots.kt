package com.mabahrami.margin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * The grip between the two sides of side by side: a tall rounded bar with two short vertical grooves
 * along its middle (the scroll handle has horizontal ones).
 */
class GripDots(context: Context) : View(context) {

    companion object {
        const val IDLE = 0xFF636366.toInt()
    }

    private val density = resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = IDLE }
    private val groove = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x8C000000.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    var color: Int
        get() = bar.color
        set(v) {
            if (bar.color != v) {
                bar.color = v
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRoundRect(0f, 0f, w, h, w / 2f, w / 2f, bar)
        // two short vertical lines along the middle
        groove.strokeWidth = maxOf(1f, 1.1f * density)
        val half = h * 0.18f
        val cy = h / 2f
        val gap = 1.3f * density
        for (x in floatArrayOf(w / 2f - gap, w / 2f + gap)) canvas.drawLine(x, cy - half, x, cy + half, groove)
    }
}
