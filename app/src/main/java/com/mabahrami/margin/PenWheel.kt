package com.mabahrami.margin

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Round pen menu that opens around the S Pen tip: the tools on an inner ring, the colours on an outer
 * ring. It grows and turns out of the tip, and shrinks back into it when it closes. Fills the whole
 * screen (in a popup) so a tap anywhere else just closes it.
 */
@SuppressLint("ViewConstructor")
class PenWheel(context: Context) : View(context) {

    /** [kind]: TOOL or COLOUR; [value]: tool number or colour; [tint]: icon tint or swatch colour. */
    class Item(val kind: Int, val value: Int, val icon: Drawable?, val tint: Int, val selected: Boolean)

    companion object {
        const val TOOL = 0
        const val COLOUR = 1
        private const val ACCENT = 0xFF0A84FF.toInt()
    }

    var onPick: ((Item) -> Unit)? = null
    /** Called once the closing animation has finished. */
    var onClosed: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val toolR = dp(50f)     // ring the tools sit on
    private val toolSize = dp(21f)  // radius of a tool button
    private val colourR = dp(98f)   // ring the colours sit on
    private val colourSize = dp(15f)
    private val ringIn = dp(27f)
    private val ringOut = dp(119f)

    private var tools: List<Item> = emptyList()
    private var colours: List<Item> = emptyList()
    private var screenX = 0f
    private var screenY = 0f
    private var cx = 0f
    private var cy = 0f
    private var progress = 0f
    private var pressed: Item? = null
    private var closing = false
    private var anim: ValueAnimator? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xEB1C1C1E.toInt()
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x26FFFFFF
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Where the menu goes (screen pixels: the pen tip) and what it shows. */
    fun setup(tipScreenX: Float, tipScreenY: Float, tools: List<Item>, colours: List<Item>) {
        screenX = tipScreenX
        screenY = tipScreenY
        this.tools = tools
        this.colours = colours
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        // centred on the tip; moved in only as much as needed to stay on screen
        val m = ringOut + dp(4f)
        cx = (screenX - loc[0]).coerceIn(min(m, width / 2f), max(width - m, width / 2f))
        cy = (screenY - loc[1]).coerceIn(min(m, height / 2f), max(height - m, height / 2f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animateTo(1f, 260, OvershootInterpolator(1.1f)) {}
    }

    /** Shrinks back into the tip, then [onClosed]. */
    fun close() {
        if (closing) return
        closing = true
        animateTo(0f, 170, DecelerateInterpolator()) { onClosed?.invoke() }
    }

    private fun animateTo(target: Float, ms: Long, interp: android.animation.TimeInterpolator, done: () -> Unit) {
        anim?.cancel()
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = ms
            interpolator = interp
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) done() }
            })
            start()
        }
    }

    /** Centre of item [i] of [n] on a ring of radius [r]: evenly around, starting at the top. */
    private fun pos(i: Int, n: Int, r: Float, out: FloatArray) {
        val turn = (1f - min(1f, progress)) * -0.6f // turns in as it opens
        val a = (-Math.PI / 2 + 2 * Math.PI * i / n + turn).toFloat()
        val rr = r * progress
        out[0] = cx + rr * cos(a)
        out[1] = cy + rr * sin(a)
    }

    private val tmp = FloatArray(2)

    override fun onDraw(canvas: Canvas) {
        val p = progress.coerceAtLeast(0f)
        if (p <= 0.001f) return
        val alpha = (min(1f, p * 1.6f) * 255).toInt()
        // the dark ring behind everything
        val rIn = ringIn * p
        val rOut = ringOut * p
        ringPaint.strokeWidth = rOut - rIn
        ringPaint.alpha = (alpha * 0xEB / 255)
        canvas.drawCircle(cx, cy, (rIn + rOut) / 2f, ringPaint)
        edgePaint.strokeWidth = 1f
        edgePaint.alpha = alpha * 0x26 / 255
        canvas.drawCircle(cx, cy, rOut, edgePaint)
        canvas.drawCircle(cx, cy, rIn, edgePaint)
        val s = min(1f, p)
        // tools
        for ((i, it) in tools.withIndex()) {
            pos(i, tools.size, toolR, tmp)
            val r = toolSize * s * (if (it === pressed) 0.9f else 1f)
            if (it.selected) {
                fill.color = 0x330A84FF
                fill.alpha = alpha * 0x33 / 255
                canvas.drawCircle(tmp[0], tmp[1], r, fill)
                ring.color = ACCENT
                ring.alpha = alpha
                ring.strokeWidth = dp(1.5f)
                canvas.drawCircle(tmp[0], tmp[1], r, ring)
            }
            val ic = it.icon ?: continue
            val h = (r * 0.62f).toInt()
            ic.setBounds((tmp[0] - h).toInt(), (tmp[1] - h).toInt(), (tmp[0] + h).toInt(), (tmp[1] + h).toInt())
            ic.setTint(it.tint)
            ic.alpha = alpha
            ic.draw(canvas)
        }
        // colours
        for ((i, it) in colours.withIndex()) {
            pos(i, colours.size, colourR, tmp)
            val big = if (it.selected) 1.2f else 1f
            val r = colourSize * s * big * (if (it === pressed) 0.88f else 1f)
            fill.color = it.tint
            fill.alpha = alpha
            canvas.drawCircle(tmp[0], tmp[1], r, fill)
            ring.color = if (it.selected) Color.WHITE else 0x40FFFFFF
            ring.alpha = if (it.selected) alpha else alpha / 4
            ring.strokeWidth = if (it.selected) dp(2.5f) else 1f
            canvas.drawCircle(tmp[0], tmp[1], r + if (it.selected) dp(2.5f) else 0f, ring)
        }
    }

    private fun hit(x: Float, y: Float): Item? {
        if (progress < 0.5f) return null
        for ((i, it) in tools.withIndex()) {
            pos(i, tools.size, toolR, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) <= toolSize + dp(6f)) return it
        }
        for ((i, it) in colours.withIndex()) {
            pos(i, colours.size, colourR, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) <= colourSize + dp(7f)) return it
        }
        return null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (closing) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = hit(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val h = hit(e.x, e.y)
                if (h !== pressed) {
                    pressed = h; invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val h = hit(e.x, e.y)
                pressed = null
                if (h != null) onPick?.invoke(h)
                close() // a pick, or a tap anywhere else, closes it
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                close()
            }
        }
        return true
    }
}
