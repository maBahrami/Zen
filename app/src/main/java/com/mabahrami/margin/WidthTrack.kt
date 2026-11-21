package com.mabahrami.margin

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/**
 * Track for the pen / highlighter size slider: a wedge that grows from thin on the left to thick on
 * the right, so the slider itself shows the size. The part up to the thumb is filled with the current
 * colour, the rest is grey. The SeekBar drives it through the drawable's level (0..10000).
 */
class WidthTrack(density: Float) : Drawable() {

    private val thin = 1f * density      // half-height at the left end
    private val thick = 4.5f * density   // half-height at the right end
    private val height = (2 * thick + 2 * density).toInt()

    private val rest = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF48484A.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt() }
    private val path = Path()

    /** Colour of the filled part (the current pen or highlighter colour). */
    var color: Int
        get() = fill.color
        set(c) {
            if (fill.color != c) {
                fill.color = c
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val cy = b.exactCenterY()
        val l = b.left + thin
        val r = b.right - thick
        if (r <= l) return
        path.rewind()
        path.moveTo(l, cy - thin)
        path.lineTo(r, cy - thick)
        path.lineTo(r, cy + thick)
        path.lineTo(l, cy + thin)
        path.close()
        // rounded ends
        path.addCircle(l, cy, thin, Path.Direction.CW)
        path.addCircle(r, cy, thick, Path.Direction.CW)

        canvas.drawPath(path, rest)
        val x = b.left + b.width() * (level / 10000f)
        if (x > b.left) {
            val save = canvas.save()
            canvas.clipRect(b.left.toFloat(), b.top.toFloat(), x, b.bottom.toFloat())
            canvas.drawPath(path, fill)
            canvas.restoreToCount(save)
        }
    }

    override fun onLevelChange(level: Int): Boolean {
        invalidateSelf()
        return true
    }

    override fun getIntrinsicHeight(): Int = height
    override fun setAlpha(alpha: Int) {
        rest.alpha = alpha
        fill.alpha = alpha
        invalidateSelf()
    }
    override fun setColorFilter(colorFilter: ColorFilter?) {
        rest.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        invalidateSelf()
    }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
