package com.mabahrami.margin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Live preview in the Margins dialog: a sample page with the margins that would be added. The page stays put
 * at a fixed size (room for the largest margins on both sides); a handle on the outer edge of each margin is
 * dragged sideways to set it (10 pt steps, 0 .. [MAX] pt). Dragging anywhere on a side moves that side's handle.
 */
@SuppressLint("ViewConstructor")
class MarginPreview(context: Context, private val pageW: Float, private val pageH: Float) : View(context) {

    var pageBitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    private var mL = 0f
    private var mR = 0f
    private var ruling = PAPER_BLANK

    /** The margins changed by dragging (points). */
    var onChange: ((Float, Float) -> Unit)? = null

    val left: Float get() = mL
    val right: Float get() = mR

    fun setMargins(l: Float, r: Float) {
        mL = snap(l); mR = snap(r)
        invalidate()
    }

    private fun snap(v: Float) = ((v / 10f).roundToInt() * 10f).coerceIn(0f, MAX)

    companion object {
        const val MAX = 600f
    }

    fun setPaper(p: Int) {
        ruling = p
        invalidate()
    }

    private val rule = Paint().apply { color = 0xFFC8C8CC.toInt() }

    private val density = resources.displayMetrics.density
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(10f * density, 0f, 2f * density, 0x66000000)
    }
    private val marginTint = Paint().apply { color = 0x1A0A84FF }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x55000000
        strokeWidth = 1f * density
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 3f * density), 0f)
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt() }
    private val handleGrip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD1D1D6.toInt()
        textSize = 12f * density
        textAlign = Paint.Align.CENTER
    }

    // fixed layout: the scale leaves room for the largest margin on both sides, so the page doesn't move
    private val labelH get() = 20f * density
    private val pad get() = 8f * density
    private fun scale(): Float {
        val aw = width - 2 * pad
        val ah = height - 2 * pad - labelH
        if (aw <= 0 || ah <= 0 || pageW <= 0f || pageH <= 0f) return 0f
        return min(aw / (pageW + 2 * MAX), ah / pageH)
    }

    /** As tall as the page needs at the width given (plus the values below it): no empty bands above and below. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val s = if (pageW > 0f) (w - 2 * pad) / (pageW + 2 * MAX) else 0f
        val want = (pageH * s + 2 * pad + labelH).toInt()
        setMeasuredDimension(w, resolveSize(want, heightMeasureSpec))
    }
    /** Screen x of the original page's left edge. */
    private fun pageX(s: Float) = width / 2f - pageW * s / 2f

    private var dragSide = 0 // -1 left, 1 right
    private var dragStartX = 0f
    private var dragStartM = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val s = scale()
        if (s <= 0f) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                // the nearer handle (each side's handle sits on the outer edge of its margin)
                val lx = pageX(s) - mL * s
                val rx = pageX(s) + (pageW + mR) * s
                dragSide = if (abs(e.x - lx) <= abs(e.x - rx)) -1 else 1
                dragStartX = e.x
                dragStartM = if (dragSide < 0) mL else mR
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - dragStartX) / s
                val m = snap(dragStartM + if (dragSide < 0) -dx else dx)
                if (dragSide < 0 && m != mL) { mL = m; invalidate(); onChange?.invoke(mL, mR) }
                if (dragSide > 0 && m != mR) { mR = m; invalidate(); onChange?.invoke(mL, mR) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragSide = 0
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val s = scale()
        if (s <= 0f) return
        val w = (mL + pageW + mR) * s
        val h = pageH * s
        val x0 = pageX(s) - mL * s
        val y0 = (height - labelH - h) / 2f
        // whole widened page
        r.set(x0, y0, x0 + w, y0 + h)
        canvas.drawRect(r, paper)
        // the added margins, faintly tinted
        if (mL > 0f) canvas.drawRect(x0, y0, x0 + mL * s, y0 + h, marginTint)
        if (mR > 0f) canvas.drawRect(x0 + (mL + pageW) * s, y0, x0 + w, y0 + h, marginTint)
        if (ruling != PAPER_BLANK) {
            rule.strokeWidth = maxOf(1f, 0.6f * s)
            canvas.save()
            canvas.translate(x0, y0)
            canvas.scale(s, s)
            rule.strokeWidth = maxOf(1f / s, 0.5f)
            if (mL > 0f) canvas.drawLines(paperLines(ruling, mL, pageH), rule)
            if (mR > 0f) {
                canvas.save(); canvas.translate(mL + pageW, 0f)
                canvas.drawLines(paperLines(ruling, mR, pageH), rule)
                canvas.restore()
            }
            canvas.restore()
        }
        // the original page
        r.set(x0 + mL * s, y0, x0 + (mL + pageW) * s, y0 + h)
        pageBitmap?.let { canvas.drawBitmap(it, null, r, bmpPaint) }
        if (mL > 0f || mR > 0f) canvas.drawRect(r, outline)
        // the drag handles: a blue bar on the outer edge of each margin, with a white grip in the middle
        val bw = 5f * density
        val cy = y0 + h / 2f
        for ((side, hx) in listOf(-1 to x0, 1 to x0 + w)) {
            val active = dragSide == side
            val half = if (active) bw * 0.75f else bw / 2f
            r.set(hx - half, y0 - 4f * density, hx + half, y0 + h + 4f * density)
            canvas.drawRoundRect(r, half, half, handleFill)
            r.set(hx - 1f * density, cy - 10f * density, hx + 1f * density, cy + 10f * density)
            canvas.drawRoundRect(r, density, density, handleGrip)
            // its value just below the page
            val v = if (side < 0) mL else mR
            val tx = (if (side < 0) hx - 0f else hx).coerceIn(label.textSize * 2f, width - label.textSize * 2f)
            canvas.drawText("${v.toInt()} pt", tx, y0 + h + 4f * density + labelH * 0.8f, label)
        }
    }
}
