package com.mabahrami.margin

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Holds a small popup and animates it like the macOS "genie" effect: the popup flows out of a source
 * rectangle (the tab it belongs to) through a narrowing funnel, and back into it when closing.
 * While animating, a snapshot of the child is drawn warped with a bitmap mesh; otherwise it draws normally.
 */
class GenieLayout(context: Context) : FrameLayout(context) {

    private companion object {
        const val MESH_W = 10
        const val MESH_H = 24
    }

    private var snap: Bitmap? = null
    private var progress = 1f
    private var animating = false
    private val src = RectF()
    private val dst = RectF()
    private var anim: ValueAnimator? = null
    private val verts = FloatArray((MESH_W + 1) * (MESH_H + 1) * 2)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** Flows [child] out of [source] (this layout's coordinates). [child] may be INVISIBLE until now. */
    fun open(source: RectF, child: View, onEnd: (() -> Unit)? = null) =
        run(source, child, 0f, 1f, 280L, onEnd)

    /** Flows [child] back into [source], then calls [onEnd] (e.g. to dismiss the popup). */
    fun close(source: RectF, child: View, onEnd: (() -> Unit)? = null) =
        run(source, child, progress.coerceIn(0f, 1f).let { if (animating) it else 1f }, 0f, 120L, onEnd)

    private fun run(source: RectF, child: View, from: Float, to: Float, ms: Long, onEnd: (() -> Unit)?) {
        anim?.let { it.removeAllListeners(); it.cancel() }
        anim = null
        val b = if (child.width > 0 && child.height > 0) try {
            Bitmap.createBitmap(child.width, child.height, Bitmap.Config.ARGB_8888).also { child.draw(Canvas(it)) }
        } catch (t: Throwable) {
            null // out of memory: no animation
        } else null
        child.visibility = View.VISIBLE
        if (b == null) {
            finish()
            onEnd?.invoke()
            return
        }
        snap?.recycle()
        snap = b
        src.set(source)
        dst.set(child.left.toFloat(), child.top.toFloat(), child.right.toFloat(), child.bottom.toFloat())
        animating = true
        progress = from
        anim = ValueAnimator.ofFloat(from, to).apply {
            duration = ms
            interpolator = if (to > from) DecelerateInterpolator(1.3f) else AccelerateInterpolator(1.3f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish()
                    onEnd?.invoke()
                }
            })
            start()
        }
        invalidate()
    }

    private fun finish() {
        animating = false
        anim = null
        snap?.recycle()
        snap = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim?.let { it.removeAllListeners(); it.cancel() }
        anim = null
        animating = false
        snap?.recycle()
        snap = null
        super.onDetachedFromWindow()
    }

    private fun smooth(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val b = snap
        if (!animating || b == null || b.isRecycled) {
            super.dispatchDraw(canvas)
            return
        }
        val t = progress
        // the funnel starts at the source's bottom edge, kept inside this layout (the popup's own window)
        val sy = src.bottom.coerceIn(0f, dst.top)
        val down = smooth(t / 0.7f)                       // how far the content has poured down
        var k = 0
        for (j in 0..MESH_H) {
            val v = j.toFloat() / MESH_H                   // 0 = edge near the tab, 1 = far edge
            // rows far from the tab widen first; rows near it stay narrow (the funnel) until the end
            val wide = smooth((t - (1f - v) * 0.45f) / 0.55f)
            val y = sy + (dst.top + v * dst.height() - sy) * down
            val l = src.left + (dst.left - src.left) * wide
            val r = src.right + (dst.right - src.right) * wide
            for (i in 0..MESH_W) {
                verts[k++] = l + (r - l) * i / MESH_W
                verts[k++] = y
            }
        }
        paint.alpha = (255 * (t / 0.15f).coerceIn(0f, 1f)).toInt()
        canvas.drawBitmapMesh(b, MESH_W, MESH_H, verts, 0, null, 0, paint)
    }
}
