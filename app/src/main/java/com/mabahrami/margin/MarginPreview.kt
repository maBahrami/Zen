package com.mabahrami.margin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Live preview in the Margins dialog: a sample page with the margins that would be added. */
@SuppressLint("ViewConstructor")
class MarginPreview(context: Context, private val pageW: Float, private val pageH: Float) : View(context) {

    var pageBitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    private var mL = 0f
    private var mR = 0f

    fun setMargins(l: Float, r: Float) {
        mL = l; mR = r
        invalidate()
    }

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

    override fun onDraw(canvas: Canvas) {
        val pad = 14f * density
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        if (aw <= 0 || ah <= 0 || pageW <= 0f || pageH <= 0f) return
        val total = mL + pageW + mR
        val s = min(aw / total, ah / pageH)
        val w = total * s
        val h = pageH * s
        val x0 = (width - w) / 2f
        val y0 = (height - h) / 2f
        // whole widened page
        r.set(x0, y0, x0 + w, y0 + h)
        canvas.drawRect(r, paper)
        // the added margins, faintly tinted
        if (mL > 0f) canvas.drawRect(x0, y0, x0 + mL * s, y0 + h, marginTint)
        if (mR > 0f) canvas.drawRect(x0 + (mL + pageW) * s, y0, x0 + w, y0 + h, marginTint)
        // the original page
        r.set(x0 + mL * s, y0, x0 + (mL + pageW) * s, y0 + h)
        pageBitmap?.let { canvas.drawBitmap(it, null, r, bmpPaint) }
        if (mL > 0f || mR > 0f) canvas.drawRect(r, outline)
    }
}
