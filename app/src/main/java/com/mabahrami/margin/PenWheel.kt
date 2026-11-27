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
        /** Undo (value 0) on the left and redo (value 1) on the right (they keep the wheel open); camera (2) in the centre. */
        const val ACTION = 2
        /** A line style (value: LINE_SOLID…), shown in an arc beside the pen after a second tap on it. */
        const val STYLE = 3
        /** A highlighter mode (value 0 = freehand, 1 = straight), shown beside the highlighter after a second tap. */
        const val HL_STYLE = 4
        private const val ACCENT = 0xFF0A84FF.toInt()
        /** The size slider's arc over the top (degrees; 0 = right, clockwise on screen). */
        private const val ARC_START = -155.0
        private const val ARC_SPAN = 130.0
        /** Size steps (− and +) just past each end of the slider (degrees). */
        private const val STEP_GAP = 18.0
        /** The lower arc of buttons: the pen at the left end (degrees, clockwise on screen), 6 places to the right end. */
        private const val RING_LEFT = 164.0
        private const val RING_STEP = (164.0 - 16.0) / 5
        private const val TOOL_START = -PI * 3 / 4
    }

    var onPick: ((Item) -> Unit)? = null
    /** Whether undo (0) / redo (1) can be done right now (asked on every frame). */
    var canDo: (Int) -> Boolean = { true }
    /** Size slider moved: the new stop (0 until steps). */
    var onSize: ((Int) -> Unit)? = null
    /** Called once the closing animation has finished. */
    var onClosed: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // geometry
    // an outer band round the colour ring holds everything else: the tools along its lower half,
    // the size slider along its upper half
    private val bandIn = dp(68f)       // a small gap outside the colour ring
    private val bandOut = dp(106f)
    private val toolR = dp(88f)        // the middle of the band
    private val toolSize = dp(16f)     // touch size of a tool (its icon is drawn smaller)
    private val stepSize = dp(14f)     // the − / + at the ends of the size slider
    private val stepR = dp(88f)
    private val bandPath = Path()

    /** A blurred picture of the screen behind (small), drawn through the band so it looks like frosted glass. */
    private var backdrop: android.graphics.Bitmap? = null
    private val backdropDst = android.graphics.RectF()
    private val backdropPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var backdropScreenL = 0f
    private var backdropScreenT = 0f
    private var backdropScale = 1f

    /** [bmp]: the screen behind, already blurred, at [scale] of the real size, its top-left at screen ([l], [t]). */
    fun setBackdrop(bmp: android.graphics.Bitmap?, l: Float, t: Float, scale: Float) {
        backdrop = bmp
        backdropScreenL = l
        backdropScreenT = t
        backdropScale = scale
        invalidate()
    }
    private val segIn = dp(30f)        // the colour ring, empty in the middle: segments from here...
    private val segOut = dp(64f)       // ...out to here
    private val segGap = -0.4f         // degrees between segments (a hair of overlap: no seams)
    private val arcR = dp(88f)
    private val ringOut = dp(68f)
    private val arcOut = dp(106f)
    private val segPath = Path()

    private var tools: List<Item> = emptyList()
    /** Line styles: an arc just outside the pen button, opened by tapping the pen when it's already the tool. */
    private var styles: List<Item> = emptyList()
    private var hlStyles: List<Item> = emptyList()
    private var styleOpen = false
    /** Which tool's options are out: 0 = the pen's line styles, 1 = the highlighter's modes. */
    private var styleTool = 0
    private val shownStyles: List<Item> get() = if (styleTool == 1) hlStyles else styles
    private var styleP = 0f
    private var styleAnim: ValueAnimator? = null
    private var actions: List<Item> = emptyList()
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
        this.tools = tools.filter { it.kind == TOOL }
        this.actions = tools.filter { it.kind == ACTION }
        this.styles = tools.filter { it.kind == STYLE }
        this.hlStyles = tools.filter { it.kind == HL_STYLE }
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
        // room for everything, the line styles beside the pen included
        val m = max(arcOut + dp(26f), toolR + dp(36f) + toolSize + dp(4f))
        cx = (screenX - loc[0]).coerceIn(min(m, width / 2f), max(width - m, width / 2f))
        cy = (screenY - loc[1]).coerceIn(min(m, height / 2f), max(height - m, height / 2f))
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
    /** Centre of tool [i] of [n]: spread around the lower half of the ring (left to right), clear of the slider. */
    private fun toolPos(i: Int, n: Int, p: Float, out: FloatArray) {
        val turn = (1f - min(1f, p)) * -0.7f
        // one arc round the lower half, clockwise from the right: redo, undo, lasso, eraser, highlighter, pen
        val deg = RING_LEFT - RING_STEP * i
        val a = (Math.toRadians(deg) + turn).toFloat()
        val rr = toolR * p
        out[0] = cx + rr * cos(a)
        out[1] = cy + rr * sin(a)
    }

    /** Centre of line style [k] of [n]: an arc just outside the pen button (the first tool), [p] of the way out. */
    private fun stylePos(k: Int, n: Int, p: Float, out: FloatArray) {
        toolPos(styleTool, tools.size, 1f, out)
        val base = Math.atan2((out[1] - cy).toDouble(), (out[0] - cx).toDouble())
        val step = Math.toRadians(19.0)
        // the first style (solid) highest on screen, the rest going down
        val a = base + ((n - 1 - k) - (n - 1) / 2.0) * step
        val rr = toolR + (dp(36f) * p)
        out[0] = cx + rr * cos(a).toFloat()
        out[1] = cy + rr * sin(a).toFloat()
    }

    private fun setStyleOpen(open: Boolean) {
        styleOpen = open
        styleAnim?.cancel()
        styleAnim = ValueAnimator.ofFloat(styleP, if (open) 1f else 0f).apply {
            duration = 200
            interpolator = if (open) OvershootInterpolator(1.2f) else DecelerateInterpolator()
            addUpdateListener { styleP = it.animatedValue as Float; invalidate() }
            start()
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Undo and redo at the right end of the lower arc (redo last); the camera in the middle. */
    private fun actionPos(item: Item, p: Float, out: FloatArray) {
        val turn = (1f - min(1f, p)) * -0.7f
        if (item.value == 2) { // camera: right in the middle
            out[0] = cx
            out[1] = cy
            return
        }
        val a = Math.toRadians(if (item.value == 0) RING_LEFT - RING_STEP * 4 else RING_LEFT - RING_STEP * 5).toFloat() + turn
        val rr = toolR * p
        out[0] = cx + rr * cos(a)
        out[1] = cy + rr * sin(a)
    }

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
        if (rIn <= 0.5f) {
            // a pie slice from the centre
            path.moveTo(cx, cy)
            path.arcTo(cx - rOut, cy - rOut, cx + rOut, cy + rOut, startDeg, sweepDeg, false)
            path.close()
            return
        }
        path.arcTo(cx - rOut, cy - rOut, cx + rOut, cy + rOut, startDeg, sweepDeg, true)
        path.arcTo(cx - rIn, cy - rIn, cx + rIn, cy + rIn, startDeg + sweepDeg, -sweepDeg, false)
        path.close()
    }

    /** Centre of the − (left end) or + (right end) button of the size slider. */
    private fun stepPos(plus: Boolean, p: Float, out: FloatArray) {
        val deg = if (plus) ARC_START + ARC_SPAN + STEP_GAP else ARC_START - STEP_GAP
        val a = Math.toRadians(deg)
        out[0] = cx + stepR * p * cos(a).toFloat()
        out[1] = cy + stepR * p * sin(a).toFloat()
    }

    /** Which step button (−1 = minus, +1 = plus) is under (x, y), or 0. */
    private fun stepAt(x: Float, y: Float): Int {
        if (progress < 0.5f) return 0
        stepPos(false, 1f, tmp)
        if (hypot(x - tmp[0], y - tmp[1]) <= stepSize + dp(5f)) return -1
        stepPos(true, 1f, tmp)
        if (hypot(x - tmp[0], y - tmp[1]) <= stepSize + dp(5f)) return 1
        return 0
    }

    private var pressedStep = 0
    /** Show the width label after a − / + step (for a moment). */
    private var showLabel = false
    private val hideLabel = Runnable { showLabel = false; invalidate() }

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

        // the outer band: one dark glass ring behind all the tools and the size slider
        val tp = part(0f)
        run {
            val bp = min(1f, tp)
            bandPath.rewind()
            bandPath.fillType = Path.FillType.EVEN_ODD
            bandPath.addCircle(cx, cy, bandOut * bp, Path.Direction.CW)
            bandPath.addCircle(cx, cy, bandIn * bp, Path.Direction.CW)
            // the band with a soft shadow under it (the blurred picture then covers it)
            fill.shader = null
            fill.color = 0xFF1C1C1E.toInt()
            fill.alpha = alpha
            fill.setShadowLayer(dp(10f), 0f, dp(3f), ((0x66 * a).toInt() shl 24))
            canvas.drawPath(bandPath, fill)
            fill.clearShadowLayer()
            // frosted glass: the blurred screen behind, seen through the band, under a dark tint
            val bd = backdrop
            if (bd != null && !bd.isRecycled) {
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                backdropDst.set(backdropScreenL - loc[0], backdropScreenT - loc[1],
                    backdropScreenL - loc[0] + bd.width / backdropScale, backdropScreenT - loc[1] + bd.height / backdropScale)
                canvas.save()
                canvas.clipPath(bandPath)
                backdropPaint.alpha = alpha
                canvas.drawBitmap(bd, null, backdropDst, backdropPaint)
                canvas.restore()
            }
            fill.color = 0xFF1C1C1E.toInt()
            fill.alpha = (alpha * (if (bd != null) 0.62f else 0.95f)).toInt()
            canvas.drawPath(bandPath, fill)
            ring.color = Color.WHITE
            ring.alpha = (alpha * 0.12f).toInt()
            ring.strokeWidth = dp(1f)
            canvas.drawCircle(cx, cy, bandOut * bp - dp(0.5f), ring)
            canvas.drawCircle(cx, cy, bandIn * bp + dp(0.5f), ring)
        }
        for ((i, it) in tools.withIndex()) {
            toolPos(i, tools.size, tp, tmp)
            val r = toolSize * min(1f, tp)
            // on the band: just the icon; the tool in use sits in a soft blue circle, a pressed one in a light one
            if (it.selected || it === pressed) {
                fill.shader = null
                fill.color = if (it.selected) ACCENT else Color.WHITE
                fill.alpha = (alpha * (if (it.selected) 0.28f else 0.14f)).toInt()
                canvas.drawCircle(tmp[0], tmp[1], r * 0.95f, fill)
            }
            val ic = it.icon ?: continue
            val h = (r * 0.62f).toInt()
            ic.setBounds((tmp[0] - h).toInt(), (tmp[1] - h).toInt(), (tmp[0] + h).toInt(), (tmp[1] + h).toInt())
            ic.setTint(it.tint)
            ic.alpha = alpha
            ic.draw(canvas)
        }

        // line styles beside the pen, or the highlighter's modes beside it (after a second tap on that tool)
        if (styleP > 0.01f) {
            val sp = min(1f, styleP)
            val list = shownStyles
            for ((k, it) in list.withIndex()) {
                stylePos(k, list.size, styleP, tmp)
                val r = toolSize * 0.9f * sp * (if (it === pressed) 0.88f else 1f)
                fill.shader = null
                fill.color = 0xFF1C1C1E.toInt()
                fill.alpha = (alpha * 0.94f * sp).toInt()
                fill.setShadowLayer(dp(5f), 0f, dp(1.5f), ((0x55 * a * sp).toInt() shl 24))
                canvas.drawCircle(tmp[0], tmp[1], r, fill)
                fill.clearShadowLayer()
                if (it.selected) {
                    fill.color = ACCENT
                    fill.alpha = (alpha * 0.25f * sp).toInt()
                    canvas.drawCircle(tmp[0], tmp[1], r, fill)
                    ring.color = ACCENT
                    ring.alpha = (alpha * sp).toInt()
                    ring.strokeWidth = dp(1.6f)
                    canvas.drawCircle(tmp[0], tmp[1], r, ring)
                }
                val ic = it.icon ?: continue
                val h = (r * 0.7f).toInt()
                ic.setBounds((tmp[0] - h).toInt(), (tmp[1] - h).toInt(), (tmp[0] + h).toInt(), (tmp[1] + h).toInt())
                ic.setTint(it.tint)
                ic.alpha = (alpha * sp).toInt()
                ic.draw(canvas)
            }
        }

        // undo / redo on the sides (dimmed when there's nothing to undo / redo)
        for (it in actions) {
            actionPos(it, tp, tmp)
            val r = toolSize * min(1f, tp)
            val on = canDo(it.value)
            fill.shader = null
            if (it.value == 2) {
                // the camera in the middle keeps its own small button (it's not on the band)
                fill.color = 0xFF1C1C1E.toInt()
                fill.alpha = (alpha * 0.94f).toInt()
                fill.setShadowLayer(dp(5f), 0f, dp(1.5f), ((0x55 * a).toInt() shl 24))
                canvas.drawCircle(tmp[0], tmp[1], r * (if (it === pressed) 0.88f else 1f), fill)
                fill.clearShadowLayer()
            } else if (it === pressed && on) {
                fill.color = Color.WHITE
                fill.alpha = (alpha * 0.14f).toInt()
                canvas.drawCircle(tmp[0], tmp[1], r * 0.95f, fill)
            }
            val ic = it.icon ?: continue
            val h = (r * (if (it.value == 2) 0.7f else 0.62f)).toInt()
            ic.setBounds((tmp[0] - h).toInt(), (tmp[1] - h).toInt(), (tmp[0] + h).toInt(), (tmp[1] + h).toInt())
            ic.setTint(it.tint)
            ic.alpha = if (on) alpha else alpha / 3
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
                val growOut = if (it.selected) dp(5f) else 0f
                val growIn = 0f
                val press = if (it === pressed) dp(3f) else 0f
                val rIn = (segIn - growIn) * min(1f, cp)
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
            // − and + at the ends: one step thinner / thicker
            for (plus in booleanArrayOf(false, true)) {
                stepPos(plus, sliderP, tmp)
                val r = stepSize * sliderP
                // on the band: just the sign (a light circle while pressed)
                if (pressedStep == (if (plus) 1 else -1)) {
                    fill.shader = null
                    fill.color = Color.WHITE
                    fill.alpha = (alpha * 0.14f).toInt()
                    canvas.drawCircle(tmp[0], tmp[1], r, fill)
                }
                ring.color = Color.WHITE
                ring.alpha = alpha
                ring.strokeWidth = dp(2f)
                ring.strokeCap = Paint.Cap.ROUND
                val arm = r * 0.4f
                canvas.drawLine(tmp[0] - arm, tmp[1], tmp[0] + arm, tmp[1], ring)
                if (plus) canvas.drawLine(tmp[0], tmp[1] - arm, tmp[0], tmp[1] + arm, ring)
                ring.strokeCap = Paint.Cap.BUTT
            }
            // the width in points, just outside the thumb, while dragging (or just after a step)
            if (dragging || showLabel) {
                text.textSize = dp(11f)
                text.alpha = alpha
                val lr = bandOut + dp(12f) // just outside the band
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
        if (styleOpen) for ((k, it) in shownStyles.withIndex()) {
            stylePos(k, shownStyles.size, 1f, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) <= toolSize + dp(3f)) return it
        }
        for ((i, it) in tools.withIndex()) {
            toolPos(i, tools.size, 1f, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) <= toolSize + dp(5f)) return it
        }
        for (it in actions) {
            actionPos(it, 1f, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) <= toolSize + dp(5f)) return it
        }
        // colour segments: by distance and angle
        val d = hypot(x - cx, y - cy)
        if (colours.isNotEmpty() && d >= segIn - dp(4f) && d <= segOut + dp(8f)) {
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
                pressedStep = stepAt(e.x, e.y)
                if (pressedStep != 0) {
                    invalidate()
                    return true
                }
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
                if (pressedStep != 0) {
                    // one step thinner / thicker; the wheel stays open
                    val step = pressedStep
                    pressedStep = 0
                    if (stepAt(e.x, e.y) == step) {
                        showLabel = true // the width shows next to the thumb for a moment
                        setStop((stop + step).coerceIn(0, steps - 1))
                        removeCallbacks(hideLabel)
                        postDelayed(hideLabel, 900)
                    }
                    invalidate()
                    return true
                }
                if (dragging) {
                    dragging = false // the size is set; the menu stays open
                    invalidate()
                    return true
                }
                val h = hit(e.x, e.y)
                pressed = null
                if (h != null && h.kind == TOOL && h.selected && (h.value == 0 && styles.isNotEmpty() || h.value == 1 && hlStyles.isNotEmpty())) {
                    // the pen (or highlighter) again: its options open (or close) beside it; the wheel stays open
                    val t = h.value
                    if (styleOpen && styleTool == t) setStyleOpen(false)
                    else {
                        styleTool = t
                        styleP = 0f // grows out of this tool
                        setStyleOpen(true)
                    }
                    return true
                }
                if (h != null && h.kind == ACTION && h.value != 2) {
                    // undo / redo: done, and the wheel stays open for more
                    if (canDo(h.value)) onPick?.invoke(h)
                    invalidate()
                    return true
                }
                if (h != null) onPick?.invoke(h)
                close() // a pick, or a tap anywhere else, closes it
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedStep = 0
                pressed = null
                dragging = false
                close()
            }
        }
        return true
    }
}
