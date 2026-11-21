package com.mabahrami.margin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * Full-image crop editor. The crop is a normalised rectangle (0..1) of the full image,
 * so re-cropping later can always bring back what was cut away.
 */
@SuppressLint("ViewConstructor")
class CropView(context: Context, private val bmp: Bitmap, l: Float, t: Float, r: Float, b: Float) : View(context) {

    private var cl = l
    private var ct = t
    private var cr = r
    private var cb = b

    private val density = resources.displayMetrics.density
    private val pad = 24f * density
    private val grab = 28f * density
    private val minSize = 0.05f

    private val img = RectF()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 2f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    // drag state: which edges move (left, top, right, bottom); all four = move whole rect
    private var dl = false
    private var dt = false
    private var dr = false
    private var db = false
    private var lastX = 0f
    private var lastY = 0f

    fun result(): FloatArray = floatArrayOf(cl, ct, cr, cb)

    fun reset() {
        cl = 0f; ct = 0f; cr = 1f; cb = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF000000.toInt())
        val aw = width - 2 * pad
        val ah = height - 2 * pad
        if (aw <= 0 || ah <= 0) return
        val s = min(aw / bmp.width, ah / bmp.height)
        val w = bmp.width * s
        val h = bmp.height * s
        img.set((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f)
        canvas.drawBitmap(bmp, null, img, bmpPaint)

        val x0 = img.left + cl * img.width()
        val y0 = img.top + ct * img.height()
        val x1 = img.left + cr * img.width()
        val y1 = img.top + cb * img.height()
        canvas.drawRect(img.left, img.top, img.right, y0, dimPaint)
        canvas.drawRect(img.left, y1, img.right, img.bottom, dimPaint)
        canvas.drawRect(img.left, y0, x0, y1, dimPaint)
        canvas.drawRect(x1, y0, img.right, y1, dimPaint)
        canvas.drawRect(x0, y0, x1, y1, framePaint)
        val hr = 7f * density
        canvas.drawCircle(x0, y0, hr, handlePaint)
        canvas.drawCircle(x1, y0, hr, handlePaint)
        canvas.drawCircle(x0, y1, hr, handlePaint)
        canvas.drawCircle(x1, y1, hr, handlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (img.width() <= 0f) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val x0 = img.left + cl * img.width()
                val y0 = img.top + ct * img.height()
                val x1 = img.left + cr * img.width()
                val y1 = img.top + cb * img.height()
                val inX = e.x > x0 - grab && e.x < x1 + grab
                val inY = e.y > y0 - grab && e.y < y1 + grab
                dl = inY && abs(e.x - x0) < grab
                dr = inY && abs(e.x - x1) < grab && !dl
                dt = inX && abs(e.y - y0) < grab
                db = inX && abs(e.y - y1) < grab && !dt
                if (!dl && !dr && !dt && !db && e.x in x0..x1 && e.y in y0..y1) {
                    dl = true; dr = true; dt = true; db = true
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.x - lastX) / img.width()
                val dy = (e.y - lastY) / img.height()
                lastX = e.x; lastY = e.y
                if (dl && dr && dt && db) {
                    val mx = dx.coerceIn(-cl, 1f - cr)
                    val my = dy.coerceIn(-ct, 1f - cb)
                    cl += mx; cr += mx; ct += my; cb += my
                } else {
                    if (dl) cl = (cl + dx).coerceIn(0f, cr - minSize)
                    if (dr) cr = (cr + dx).coerceIn(cl + minSize, 1f)
                    if (dt) ct = (ct + dy).coerceIn(0f, cb - minSize)
                    if (db) cb = (cb + dy).coerceIn(ct + minSize, 1f)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dl = false; dr = false; dt = false; db = false
            }
        }
        return true
    }
}
