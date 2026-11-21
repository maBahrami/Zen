package com.mabahrami.margin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.min

/** Live preview in the New page window: page shape (size + orientation) and its paper. */
class PaperPreview(context: Context) : View(context) {

    private var pw = 595f
    private var ph = 842f
    private var paper = PAPER_BLANK
    private var dark = false

    fun set(w: Int, h: Int, p: Int, dark: Boolean = false) {
        pw = w.toFloat(); ph = h.toFloat(); paper = p
        this.dark = dark
        invalidate()
    }

    private val density = resources.displayMetrics.density
    private val sheet = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(10f * density, 0f, 2f * density, 0x66000000)
    }
    private val rule = Paint().apply { color = 0xFFC8C8CC.toInt() }
    /** Hairline edge so a dark sheet stands out from the dark window. */
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x40FFFFFF
        strokeWidth = 1f
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 14f * density
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        if (aw <= 0 || ah <= 0) return
        val s = min(aw / pw, ah / ph)
        val w = pw * s
        val h = ph * s
        val x0 = (width - w) / 2f
        val y0 = (height - h) / 2f
        sheet.color = if (dark) Dark.PAPER else Color.WHITE
        rule.color = if (dark) Dark.RULE else 0xFFC8C8CC.toInt()
        canvas.drawRect(x0, y0, x0 + w, y0 + h, sheet)
        if (dark) canvas.drawRect(x0, y0, x0 + w, y0 + h, outline)
        if (paper != PAPER_BLANK) {
            canvas.save()
            canvas.translate(x0, y0)
            canvas.scale(s, s)
            rule.strokeWidth = maxOf(1f / s, 0.5f)
            canvas.drawLines(paperLines(paper, pw, ph), rule)
            canvas.restore()
        }
    }
}
