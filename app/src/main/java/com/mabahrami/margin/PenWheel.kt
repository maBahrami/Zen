package com.mabahrami.margin

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Round pen menu that opens around the S Pen tip: the tools on an inner ring, the colours on a middle
 * ring, and the line size as a curved slider across the top (thin on the left, thick on the right).
 * The centre shows the current line: its colour and its size (and its width in points while dragging).
 * It grows and turns out of the tip and shrinks back into it. Fills the screen (in a popup), so a tap
 * anywhere else closes it. Picking a tool or colour closes it; dragging the size keeps it open.
 */
@SuppressLint("ViewConstructor")
class PenWheel(context: Context) : View(context) {

    /** [kind]: TOOL or COLOUR; [value]: tool number or colour; [tint]: icon tint or swatch colour. */
    class Item(val kind: Int, val value: Int, val icon: Drawable?, val tint: Int, val selected: Boolean)

    companion object {
        const val TOOL = 0
        const val COLOUR = 1
        private const val ACCENT = 0xFF0A84FF.toInt()
        /** The size slider's arc over the top (degrees; 0 = right, clockwise on screen). */
        private const val ARC_START = -155.0
        private const val ARC_SPAN = 130.0
        private const val TOOL_START = -PI * 3 / 4
    }

    var onPick: ((Item) -> Unit)? = null
    /** Size slider moved: the new stop (0 until steps). */
    var onSize: ((Int) -> Unit)? = null
    /** Called once the closing animation has finished. */
    var onClosed: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // geometry
    private val toolR = dp(24f)        // the tools sit in the middle, inside the colour ring
    private val toolSize = dp(15.5f)
    private val segIn = dp(47f)        // the colour ring: segments from here...
    private val segOut = dp(80f)       // ...to here
    private val segGap = 3.2f          // degrees between segments
    private val arcR = dp(97f)
    private val ringOut = dp(86f)
    private val arcOut = dp(110f)
    private val segPath = Path()

    private var tools: List<Item> = emptyList()
    private var colours: List<Item> = emptyList()
    private var lineColour = Color.WHITE
    private var steps = 40
    private var stop = 0
    private var label: (Int) -> String = { "" }
    /** Line width on screen (px) at each stop, for the preview in the middle. */
    private var previewPx: (Int) -> Float = { dp(2f) }

    private var screenX = 0f
    private var screenY = 0f
    private var cx = 0f
    private var cy = 0f
    private var progress = 0f
    private var pressed: Item? = null
    private var dragging = false
    private var closing = false
    private var anim: ValueAnimator? = null

    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val arcPath = Path()
    private val arcDone = Path()

    /** Where the menu goes (screen pixels: the pen tip) and what it shows. */
    fun setup(
        tipScreenX: Float, tipScreenY: Float, tools: List<Item>, colours: List<Item>,
        lineColour: Int, steps: Int, stop: Int, label: (Int) -> String, previewPx: (Int) -> Float,
    ) {
        screenX = tipScreenX
        screenY = tipScreenY
        this.tools = tools
        this.colours = colours
        this.lineColour = lineColour
        this.steps = max(2, steps)
        this.stop = stop.coerceIn(0, this.steps - 1)
        this.label = label
        this.previewPx = previewPx
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        // centred on the tip; moved in only as much as needed to stay on screen
        val m = arcOut + dp(26f)
        cx = (screenX - loc[0]).coerceIn(min(m, width / 2f), max(width - m, width / 2f))
        cy = (screenY - loc[1]).coerceIn(min(m, height / 2f), max(height - m, height / 2f))
        glass.shader = RadialGradient(cx, cy, ringOut, intArrayOf(0xF22A2A2E.toInt(), 0xF2161618.toInt()),
            null, Shader.TileMode.CLAMP)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animateTo(1f, 320, OvershootInterpolator(1.15f)) {}
    }

    /** Shrinks back into the tip, then [onClosed]. */
    fun close() {
        if (closing) return
        closing = true
        dragging = false
        animateTo(0f, 180, DecelerateInterpolator()) { onClosed?.invoke() }
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

    /** How far a part that starts at [delay] (0..1 of the opening) has come in: 0..1 (a bit more on overshoot). */
    private fun part(delay: Float): Float = ((progress - delay) / (1f - delay)).coerceAtLeast(0f)

    /** Centre of item [i] of [n] on a ring of radius [r], scaled by [p]. */
    private fun pos(i: Int, n: Int, r: Float, p: Float, start: Double, out: FloatArray) {
        val turn = (1f - min(1f, p)) * -0.7f // turns in as it opens
        val a = (start + 2 * PI * i / n + turn).toFloat()
        val rr = r * p
        out[0] = cx + rr * cos(a)
        out[1] = cy + rr * sin(a)
    }

    private val tmp = FloatArray(2)

    /** An annulus segment from [startDeg] sweeping [sweepDeg], between radius [rIn] and [rOut]. */
    private fun sector(path: Path, rIn: Float, rOut: Float, startDeg: Float, sweepDeg: Float) {
        path.rewind()
        path.arcTo(cx - rOut, cy - rOut, cx + rOut, cy + rOut, startDeg, sweepDeg, true)
        path.arcTo(cx - rIn, cy - rIn, cx + rIn, cy + rIn, startDeg + sweepDeg, -sweepDeg, false)
        path.close()
    }

    private fun arcAngle(t: Float): Double = Math.toRadians(ARC_START + ARC_SPAN * t)

    /** The size slider as a wedge along the arc, from [t0] to [t1] (0..1), thin to thick, scaled by [p]. */
    private fun wedge(path: Path, t0: Float, t1: Float, p: Float) {
        path.rewind()
        val n = 48
        val r = arcR * p
        for (k in 0..n) {
            val t = t0 + (t1 - t0) * k / n
            val a = arcAngle(t)
            val h = (dp(1.2f) + dp(4.3f) * t) * p
            val x = cx + (r + h) * cos(a).toFloat()
            val y = cy + (r + h) * sin(a).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (k in n downTo 0) {
            val t = t0 + (t1 - t0) * k / n
            val a = arcAngle(t)
            val h = (dp(1.2f) + dp(4.3f) * t) * p
            path.lineTo(cx + (r - h) * cos(a).toFloat(), cy + (r - h) * sin(a).toFloat())
        }
        path.close()
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress.coerceAtLeast(0f)
        if (p <= 0.001f) return
        val a = min(1f, p * 1.6f)
        val alpha = (a * 255).toInt()
        val s = min(1f, p)
        val sliderP = min(1f, part(0.2f))

        // soft shadow under the disc
        shadow.color = 0xFF161618.toInt()
        shadow.alpha = alpha
        shadow.setShadowLayer(dp(16f) * s, 0f, dp(4f) * s, ((0x70 * a).toInt() shl 24))
        canvas.drawCircle(cx, cy, ringOut * p, shadow)
        shadow.clearShadowLayer()

        // the glass disc (the size slider above it has no background: just its wedge and thumb)
        glass.alpha = alpha
        canvas.drawCircle(cx, cy, ringOut * p, glass)
        // fine light rims
        edge.color = 0x33FFFFFF
        edge.alpha = (0x33 * a).toInt()
        canvas.drawCircle(cx, cy, ringOut * p, edge)
        edge.color = 0x14FFFFFF
        edge.alpha = (0x14 * a).toInt()
        canvas.drawCircle(cx, cy, (segIn - dp(2f)) * p, edge)

        // tools (inner ring)
        val tp = part(0f)
        for ((i, it) in tools.withIndex()) {
            pos(i, tools.size, toolR, tp, TOOL_START, tmp)
            val r = toolSize * min(1f, tp) * (if (it === pressed) 0.88f else 1f)
            fill.shader = null
            if (it.selected) {
                fill.color = ACCENT
                fill.alpha = (alpha * 0.18f).toInt()
                canvas.drawCircle(tmp[0], tmp[1], r + dp(3.5f), fill) // glow
                fill.alpha = (alpha * 0.22f).toInt()
                canvas.drawCircle(tmp[0], tmp[1], r, fill)
                ring.color = ACCENT
                ring.alpha = alpha
                ring.strokeWidth = dp(1.5f)
                canvas.drawCircle(tmp[0], tmp[1], r, ring)
            } else {
                fill.color = 0x16FFFFFF
                fill.alpha = (0x16 * a).toInt()
                canvas.drawCircle(tmp[0], tmp[1], r, fill)
            }
            val ic = it.icon ?: continue
            val h = (r * 0.7f).toInt()
            ic.setBounds((tmp[0] - h).toInt(), (tmp[1] - h).toInt(), (tmp[0] + h).toInt(), (tmp[1] + h).toInt())
            ic.setTint(it.tint)
            ic.alpha = alpha
            ic.draw(canvas)
        }

        // colours: a ring of segments, the first at the top, going clockwise
        val cp = part(0.1f)
        if (cp > 0f) {
            val n = colours.size
            val step = 360f / n
            // the current colour last, so its lift shadow falls over its neighbours
            for (i in colours.indices.sortedBy { if (colours[it].selected) 1 else 0 }) {
                val it = colours[i]
                // each segment sweeps in a moment after the one before
                val sp = ((cp * 1.25f) - i * 0.25f / n).coerceIn(0f, 1f)
                if (sp <= 0f) continue
                val mid = -90f + step * i + (1f - sp) * -25f
                val sweep = (step - segGap) * sp
                // the current colour stands out like in the top palette: taller (out and in) and lifted
                val growOut = if (it.selected) dp(7f) else 0f
                val growIn = if (it.selected) dp(3f) else 0f
                val press = if (it === pressed) dp(3f) else 0f
                val rIn = (segIn - growIn + press) * min(1f, cp)
                val rOut = (segOut + growOut - press) * min(1f, cp)
                sector(segPath, rIn, rOut, mid - sweep / 2f, sweep)
                fill.shader = null
                fill.color = it.tint
                fill.alpha = alpha
                if (it.selected) fill.setShadowLayer(dp(6f), 0f, dp(2f), ((0x80 * a).toInt() shl 24))
                canvas.drawPath(segPath, fill)
                fill.clearShadowLayer()
                // a soft sheen from the top-left
                shine.shader = RadialGradient(cx - rOut * 0.5f, cy - rOut * 0.6f, rOut * 1.6f,
                    intArrayOf(0x30FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                shine.alpha = alpha
                canvas.drawPath(segPath, shine)
            }
        }

        // size slider (arc over the top): grey wedge, filled in the line colour up to the thumb
        if (sliderP > 0f) {
            val t = stop / (steps - 1f)
            wedge(arcPath, 0f, 1f, sliderP)
            fill.shader = null
            fill.color = 0xFF48484A.toInt()
            fill.alpha = (alpha * sliderP).toInt()
            canvas.drawPath(arcPath, fill)
            if (t > 0f) {
                wedge(arcDone, 0f, t, sliderP)
                fill.color = lineColour
                fill.alpha = (alpha * sliderP).toInt()
                canvas.drawPath(arcDone, fill)
            }
            val ang = arcAngle(t)
            val tx = cx + arcR * sliderP * cos(ang).toFloat()
            val ty = cy + arcR * sliderP * sin(ang).toFloat()
            val tr = dp(if (dragging) 9.5f else 8f) * sliderP
            shadow.color = Color.WHITE
            shadow.alpha = (alpha * sliderP).toInt()
            shadow.setShadowLayer(dp(4f), 0f, dp(1f), 0x66000000)
            canvas.drawCircle(tx, ty, tr, shadow)
            shadow.clearShadowLayer()
            // the width in points, just outside the thumb, while dragging
            if (dragging) {
                text.textSize = dp(11f)
                text.alpha = alpha
                val lr = arcR + dp(23f)
                canvas.drawText(label(stop), cx + lr * cos(ang).toFloat(), cy + lr * sin(ang).toFloat() + dp(4f), text)
            }
        }
    }

    /** Stop of the size slider under (x, y), or -1 if not on the slider ([generous]: anywhere, while dragging). */
    private fun sliderAt(x: Float, y: Float, generous: Boolean): Int {
        val d = hypot(x - cx, y - cy)
        if (!generous && abs(d - arcR) > dp(17f)) return -1
        val ang = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        var rel = ang - ARC_START
        while (rel < -110) rel += 360.0
        while (rel > 250) rel -= 360.0
        if (!generous && (rel < -10 || rel > ARC_SPAN + 10)) return -1
        val t = (rel / ARC_SPAN).coerceIn(0.0, 1.0)
        return (t * (steps - 1)).roundToInt()
    }

    private fun hit(x: Float, y: Float): Item? {
        if (progress < 0.5f) return null
        for ((i, it) in tools.withIndex()) {
            pos(i, tools.size, toolR, 1f, TOOL_START, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) <= toolSize + dp(5f)) return it
        }
        // colour segments: by distance and angle
        val d = hypot(x - cx, y - cy)
        if (colours.isNotEmpty() && d >= segIn - dp(4f) && d <= segOut + dp(10f)) {
            val n = colours.size
            var deg = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())) + 90.0 + 180.0 / n
            while (deg < 0) deg += 360.0
            val i = ((deg % 360.0) / (360.0 / n)).toInt().coerceIn(0, n - 1)
            return colours[i]
        }
        return null
    }

    private fun setStop(v: Int) {
        if (v < 0 || v == stop) return
        stop = v
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onSize?.invoke(v)
        invalidate()
    }

    /** The S Pen button again while it's open: close. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_BUTTON_PRESS && e.actionButton == MotionEvent.BUTTON_STYLUS_PRIMARY) {
            close()
            return true
        }
        return super.onGenericMotionEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (closing) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val st = if (progress >= 0.5f) sliderAt(e.x, e.y, false) else -1
                if (st >= 0) {
                    dragging = true
                    setStop(st)
                } else pressed = hit(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) setStop(sliderAt(e.x, e.y, true))
                else {
                    val h = hit(e.x, e.y)
                    if (h !== pressed) {
                        pressed = h; invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false // the size is set; the menu stays open
                    invalidate()
                    return true
                }
                val h = hit(e.x, e.y)
                pressed = null
                if (h != null) onPick?.invoke(h)
                close() // a pick, or a tap anywhere else, closes it
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                dragging = false
                close()
            }
        }
        return true
    }
}
