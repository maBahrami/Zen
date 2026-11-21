package com.mabahrami.margin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.view.Choreographer
import android.view.SurfaceView
import android.view.View
import androidx.annotation.RequiresApi
import androidx.graphics.lowlatency.CanvasFrontBufferedRenderer

/**
 * Front-buffered ink: the newest bits of the line are drawn straight to the screen (no waiting in the
 * display queue), on a transparent layer above the whole window. The page view keeps drawing the line
 * too, underneath, so nothing ever depends on this layer: when the pen lifts, the page view gets the
 * finished stroke first, and only a few frames later is this layer emptied (never a gap, so no flicker).
 *
 * All calls are on the main thread. Any failure switches it off for good; the normal drawing carries on.
 */
@RequiresApi(29)
class FrontInk(context: Context) {

    /** Segments to draw: x0, y0, x1, y1, width (overlay pixels) per segment, for stroke [id]. */
    private class Batch(val id: Int, val segs: FloatArray, val n: Int, val color: Int, val clip: RectF)

    val view: SurfaceView = SurfaceView(context).apply {
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        isClickable = false
        isFocusable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    // render thread only
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** The stroke being written (or -1); read on the render thread. */
    @Volatile
    private var activeId = -1
    private var nextId = 0
    @Volatile
    private var broken = false

    private fun drawBatch(c: Canvas, b: Batch) {
        c.save()
        c.clipRect(b.clip)
        paint.color = b.color
        val s = b.segs
        for (i in 0 until minOf(b.n, s.size / 5)) {
            val k = i * 5
            paint.strokeWidth = s[k + 4]
            c.drawLine(s[k], s[k + 1], s[k + 2], s[k + 3], paint)
        }
        c.restore()
    }

    private val callback = object : CanvasFrontBufferedRenderer.Callback<Batch> {
        override fun onDrawFrontBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, param: Batch) {
            try { drawBatch(canvas, param) } catch (t: Throwable) { broken = true }
        }

        override fun onDrawMultiBufferedLayer(canvas: Canvas, bufferWidth: Int, bufferHeight: Int, params: Collection<Batch>) {
            try {
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                // finished strokes are drawn by the page view; only a stroke still being written stays here
                val id = activeId
                if (id >= 0) for (b in params) if (b.id == id) drawBatch(canvas, b)
            } catch (t: Throwable) {
                broken = true
            }
        }
    }

    private val renderer: CanvasFrontBufferedRenderer<Batch>? = try {
        CanvasFrontBufferedRenderer(view, callback)
    } catch (t: Throwable) {
        broken = true
        null
    }

    // the stroke being written (main thread)
    private var color = 0
    private val clip = RectF()
    private var pending = FloatArray(5 * 64)
    private var pendingN = 0
    private var active = false

    /** Starts a stroke drawn in [color], kept inside [clipRect] (overlay pixels). False = not available. */
    fun begin(color: Int, clipRect: RectF): Boolean {
        if (broken || renderer == null) return false
        this.color = color
        clip.set(clipRect)
        pendingN = 0
        activeId = nextId++
        active = true
        return true
    }

    /** One more piece of line (overlay pixels); shown on the next [flush]. */
    fun add(x0: Float, y0: Float, x1: Float, y1: Float, width: Float) {
        if (!active) return
        if ((pendingN + 1) * 5 > pending.size) pending = pending.copyOf(pending.size * 2)
        val k = pendingN * 5
        pending[k] = x0; pending[k + 1] = y0; pending[k + 2] = x1; pending[k + 3] = y1; pending[k + 4] = width
        pendingN++
    }

    /** Draws the pieces added since the last flush, straight to the screen. */
    fun flush() {
        if (!active || pendingN == 0) return
        val r = renderer ?: return
        val b = Batch(activeId, pending.copyOf(pendingN * 5), pendingN, color, RectF(clip))
        pendingN = 0
        try {
            r.renderFrontBufferedLayer(b)
        } catch (t: Throwable) {
            broken = true
            active = false
        }
    }

    /**
     * The pen lifted and the page view now has the finished stroke: empty this layer a few frames later,
     * once the page view has certainly shown it.
     */
    fun end() {
        if (!active) return
        flush()
        active = false
        activeId = -1
        scheduleHandoff()
    }

    /** The stroke was turned into a shape or cancelled: drop it from this layer right away. */
    fun abandon() {
        if (!active) return
        active = false
        activeId = -1
        pendingN = 0
        try { renderer?.cancel() } catch (t: Throwable) { broken = true }
    }

    private var framesLeft = 0
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (--framesLeft > 0) {
                Choreographer.getInstance().postFrameCallback(this)
                return
            }
            // a stroke being written right now stays (it's redrawn into the kept layer)
            try { renderer?.commit() } catch (t: Throwable) { broken = true }
        }
    }

    private fun scheduleHandoff() {
        val waiting = framesLeft > 0
        framesLeft = HANDOFF_FRAMES
        if (!waiting) Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun release() {
        active = false
        activeId = -1
        if (framesLeft > 0) {
            Choreographer.getInstance().removeFrameCallback(frameCb)
            framesLeft = 0
        }
        try { renderer?.release(true) {} } catch (t: Throwable) {}
        broken = true
    }

    companion object {
        /** Frames to wait after a lift before emptying this layer (the page view shows the stroke by then). */
        const val HANDOFF_FRAMES = 3
    }
}
