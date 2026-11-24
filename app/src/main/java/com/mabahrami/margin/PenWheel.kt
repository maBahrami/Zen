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
 * The S Pen menu, after the Apple Pencil Pro squeeze palette: a ring of round frosted buttons around the pen
 * tip (pen, highlighter, eraser, lasso, camera, redo, undo, and the current colour). Tapping the colour button
 * turns the ring into colour dots (with a back button where the colour button was); picking one closes the
 * menu. The line size is a thin arc over the ring, with − and + at its ends. Buttons fly out of the tip one
 * after another and back into it on closing. Fills the screen (in a popup), so a tap anywhere else closes it.
 */
@SuppressLint("ViewConstructor")
class PenWheel(context: Context) : View(context) {

    /** [kind]: TOOL, ACTION or COLOUR; [value]: tool number, action or colour; [tint]: icon tint or colour. */
    class Item(val kind: Int, val value: Int, val icon: Drawable?, val tint: Int, val selected: Boolean)

    companion object {
        const val TOOL = 0
        const val COLOUR = 1
        /** Undo (0) and redo (1) keep the menu open; camera (2) closes it. */
        const val ACTION = 2
        /** The colour button in the ring (opens the colours) and the back button among the colours. */
        private const val COLOUR_BUTTON = 10
        private const val BACK = 11
        private const val ACCENT = 0xFF0A84FF.toInt()
        /** The size slider's arc over the top (degrees; 0 = right, clockwise on screen). */
        private const val ARC_START = -145.0
        private const val ARC_SPAN = 110.0
        /** − and + just past each end of the slider (degrees). */
        private const val STEP_GAP = 13.0
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
    private val ringR = dp(76f)        // centres of the buttons
    private val btnR = dp(22f)         // button radius
    private val dotR = dp(17f)         // colour dot radius
    private val arcR = dp(122f)        // size slider
    private val stepR = dp(122f)
    private val stepSize = dp(15f)
    private val arcOut = dp(134f)

    /** The ring: tools, then camera, redo, undo, then the colour button (clockwise from the top). */
    private var slots: List<Item> = emptyList()
    /** The colours, with a back button in the colour button's place. */
    private var colourSlots: List<Item> = emptyList()
    private var lineColour = Color.WHITE
    private var steps = 40
    private var stop = 0
    private var label: (Int) -> String = { "" }
    private var previewPx: (Int) -> Float = { dp(2f) }

    private var screenX = 0f
    private var screenY = 0f
    private var cx = 0f
    private var cy = 0f
    private var progress = 0f
    /** 0 = the tools ring, 1 = the colours ring (animated in between). */
    private var colourMode = 0f
    private var colourTarget = 0f
    private var pressed: Item? = null
    private var dragging = false
    private var closing = false
    private var anim: ValueAnimator? = null
    private var modeAnim: ValueAnimator? = null

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
    private val tmp = FloatArray(2)

    /** Where the menu goes (screen pixels: the pen tip) and what it shows. */
    fun setup(
        tipScreenX: Float, tipScreenY: Float, tools: List<Item>, colours: List<Item>,
        lineColour: Int, steps: Int, stop: Int, label: (Int) -> String, previewPx: (Int) -> Float,
    ) {
        screenX = tipScreenX
        screenY = tipScreenY
        val t = tools.filter { it.kind == TOOL }
        val a = tools.filter { it.kind == ACTION }
        val camera = a.filter { it.value == 2 }
        val redo = a.filter { it.value == 1 }
        val undo = a.filter { it.value == 0 }
        val current = colours.firstOrNull { it.selected }
        val colourBtn = Item(COLOUR_BUTTON, 0, null, current?.tint ?: lineColour, colours.any { it.selected })
        slots = t + camera + redo + undo + colourBtn
        // the colours take the same places; the back button sits where the colour button was
        val back = Item(BACK, 0, null, Color.WHITE, false)
        val n = slots.size
        val cs = ArrayList<Item>()
        var k = 0
        for (i in 0 until n) {
            if (i == n - 1) cs.add(back) else if (k < colours.size) cs.add(colours[k++])
        }
        while (k < colours.size) cs.add(cs.size - 1, colours[k++]) // more colours than places: squeeze in
        colourSlots = cs
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
        val m = arcOut + dp(20f)
        cx = (screenX - loc[0]).coerceIn(min(m, width / 2f), max(width - m, width / 2f))
        cy = (screenY - loc[1]).coerceIn(min(m, height / 2f), max(height - m, height / 2f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animateTo(1f, 360, OvershootInterpolator(1.2f)) {}
    }

    /** Shrinks back into the tip, then [onClosed]. */
    fun close() {
        if (closing) return
        closing = true
        dragging = false
        animateTo(0f, 200, DecelerateInterpolator()) { onClosed?.invoke() }
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

    /** Tools ring ⇄ colours ring. */
    private fun showColours(on: Boolean) {
        colourTarget = if (on) 1f else 0f
        modeAnim?.cancel()
        modeAnim = ValueAnimator.ofFloat(colourMode, colourTarget).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                colourMode = it.animatedValue as Float
                invalidate()
            }
            start()
        }
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** How far button [i] of [n] has come out of the tip: each starts a moment after the one before. */
    private fun outOf(i: Int, n: Int): Float {
        val delay = 0.35f * i / max(1, n)
        return ((progress - delay) / (1f - delay)).coerceAtLeast(0f)
    }

    /** Centre of place [i] of [n] on the ring (clockwise from the top), [p] of the way out of the tip. */
    private fun slotPos(i: Int, n: Int, p: Float, out: FloatArray) {
        val turn = (1f - min(1f, p)) * -0.5f // turns in as it comes out
        val a = (-PI / 2 + 2 * PI * i / n + turn).toFloat()
        val rr = ringR * p
        out[0] = cx + rr * cos(a)
        out[1] = cy + rr * sin(a)
    }

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
        if (hypot(x - tmp[0], y - tmp[1]) <= stepSize + dp(7f)) return -1
        stepPos(true, 1f, tmp)
        if (hypot(x - tmp[0], y - tmp[1]) <= stepSize + dp(7f)) return 1
        return 0
    }

    private var pressedStep = 0
    private var showLabel = false
    private val hideLabel = Runnable { showLabel = false; invalidate() }

    private fun arcAngle(t: Float): Double = Math.toRadians(ARC_START + ARC_SPAN * t)

    /** The size slider as a thin wedge along the arc, from [t0] to [t1] (0..1), thin to thick, scaled by [p]. */
    private fun wedge(path: Path, t0: Float, t1: Float, p: Float) {
        path.rewind()
        val n = 40
        val r = arcR * p
        for (k in 0..n) {
            val t = t0 + (t1 - t0) * k / n
            val a = arcAngle(t)
            val h = (dp(1f) + dp(3.2f) * t) * p
            val x = cx + (r + h) * cos(a).toFloat()
            val y = cy + (r + h) * sin(a).toFloat()
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (k in n downTo 0) {
            val t = t0 + (t1 - t0) * k / n
            val a = arcAngle(t)
            val h = (dp(1f) + dp(3.2f) * t) * p
            path.lineTo(cx + (r - h) * cos(a).toFloat(), cy + (r - h) * sin(a).toFloat())
        }
        path.close()
    }

    /** A round frosted button with a hairline light edge and a soft shadow. */
    private fun glassButton(canvas: Canvas, x: Float, y: Float, r: Float, alpha: Int, selected: Boolean) {
        fill.shader = null
        fill.color = 0xFF2C2C2E.toInt()
        fill.alpha = (alpha * 0.93f).toInt()
        fill.setShadowLayer(dp(8f), 0f, dp(3f), ((0x66 * alpha / 255) shl 24))
        canvas.drawCircle(x, y, r, fill)
        fill.clearShadowLayer()
        // light from above, like frosted glass
        shine.shader = RadialGradient(x, y - r * 0.9f, r * 1.6f, intArrayOf(0x26FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        shine.alpha = alpha
        canvas.drawCircle(x, y, r, shine)
        ring.color = if (selected) ACCENT else 0x33FFFFFF
        ring.alpha = if (selected) alpha else alpha * 0x33 / 255
        ring.strokeWidth = if (selected) dp(2f) else dp(1f)
        canvas.drawCircle(x, y, r - ring.strokeWidth / 2f, ring)
        if (selected) {
            fill.color = ACCENT
            fill.alpha = (alpha * 0.22f).toInt()
            canvas.drawCircle(x, y, r, fill)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val p = progress.coerceAtLeast(0f)
        if (p <= 0.001f) return
        val a = min(1f, p * 1.6f)
        val alpha = (a * 255).toInt()
        val cm = colourMode

        // ---- the tools ring (fades and shrinks away while the colours come in) ----
        if (cm < 1f) {
            val n = slots.size
            for ((i, it) in slots.withIndex()) {
                val op = outOf(i, n) * (1f - cm * 0.3f)
                if (op <= 0f) continue
                slotPos(i, n, op, tmp)
                val k = (1f - cm)
                val r = btnR * min(1f, op) * (0.6f + 0.4f * k) * (if (it === pressed) 0.9f else 1f)
                val al = (alpha * k).toInt()
                if (al <= 0) continue
                when (it.kind) {
                    COLOUR_BUTTON -> {
                        glassButton(canvas, tmp[0], tmp[1], r, al, false)
                        // the current colour as a dot, with a thin ring of all the colours' idea: a white edge
                        fill.shader = null
                        fill.color = it.tint
                        fill.alpha = al
                        canvas.drawCircle(tmp[0], tmp[1], r * 0.58f, fill)
                        ring.color = Color.WHITE
                        ring.alpha = al * 0xCC / 255
                        ring.strokeWidth = dp(1.5f)
                        canvas.drawCircle(tmp[0], tmp[1], r * 0.58f + dp(2.5f), ring)
                    }
                    else -> {
                        glassButton(canvas, tmp[0], tmp[1], r, al, it.selected)
                        val ic = it.icon ?: continue
                        val h = (r * 0.62f).toInt()
                        ic.setBounds((tmp[0] - h).toInt(), (tmp[1] - h).toInt(), (tmp[0] + h).toInt(), (tmp[1] + h).toInt())
                        ic.setTint(it.tint)
                        ic.alpha = if (it.kind == ACTION && it.value < 2 && !canDo(it.value)) al / 3 else al
                        ic.draw(canvas)
                    }
                }
            }
        }

        // ---- the colours ring ----
        if (cm > 0f) {
            val n = colourSlots.size
            for ((i, it) in colourSlots.withIndex()) {
                val op = min(1f, p) * cm
                slotPos(i, n, 1f, tmp)
                // each dot grows a moment after the one before
                val g = ((cm * 1.4f) - 0.4f * i / n).coerceIn(0f, 1f)
                if (g <= 0f) continue
                val al = (alpha * g).toInt()
                if (it.kind == BACK) {
                    val r = btnR * g * (if (it === pressed) 0.9f else 1f)
                    glassButton(canvas, tmp[0], tmp[1], r, al, false)
                    // a chevron pointing back
                    ring.color = Color.WHITE
                    ring.alpha = al
                    ring.strokeWidth = dp(2f)
                    ring.strokeCap = Paint.Cap.ROUND
                    val c = r * 0.28f
                    canvas.drawLine(tmp[0] + c * 0.5f, tmp[1] - c, tmp[0] - c * 0.5f, tmp[1], ring)
                    canvas.drawLine(tmp[0] - c * 0.5f, tmp[1], tmp[0] + c * 0.5f, tmp[1] + c, ring)
                    ring.strokeCap = Paint.Cap.BUTT
                    continue
                }
                val r = dotR * g * (if (it === pressed) 0.88f else 1f) * (if (it.selected) 1.08f else 1f)
                fill.shader = null
                fill.color = it.tint
                fill.alpha = al
                fill.setShadowLayer(dp(6f), 0f, dp(2f), ((0x66 * al / 255) shl 24))
                canvas.drawCircle(tmp[0], tmp[1], r, fill)
                fill.clearShadowLayer()
                // a soft sheen, and a white ring around the current colour
                shine.shader = RadialGradient(tmp[0], tmp[1] - r * 0.8f, r * 1.5f, intArrayOf(0x30FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
                shine.alpha = al
                canvas.drawCircle(tmp[0], tmp[1], r, shine)
                ring.color = if (it.selected) Color.WHITE else 0x40FFFFFF
                ring.alpha = if (it.selected) al else al / 4
                ring.strokeWidth = dp(if (it.selected) 2.5f else 1f)
                canvas.drawCircle(tmp[0], tmp[1], r + dp(if (it.selected) 4f else 0f), ring)
                if (op <= 0f) continue
            }
        }

        // ---- the size slider: a thin arc over the ring, filled in the line colour up to the thumb ----
        val sliderP = min(1f, ((progress - 0.25f) / 0.75f).coerceAtLeast(0f))
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
            // − and + at the ends: small frosted buttons, one step thinner / thicker
            for (plus in booleanArrayOf(false, true)) {
                stepPos(plus, sliderP, tmp)
                val r = stepSize * sliderP * (if (pressedStep == (if (plus) 1 else -1)) 0.88f else 1f)
                glassButton(canvas, tmp[0], tmp[1], r, (alpha * sliderP).toInt(), false)
                ring.color = Color.WHITE
                ring.alpha = alpha
                ring.strokeWidth = dp(1.8f)
                ring.strokeCap = Paint.Cap.ROUND
                val arm = r * 0.4f
                canvas.drawLine(tmp[0] - arm, tmp[1], tmp[0] + arm, tmp[1], ring)
                if (plus) canvas.drawLine(tmp[0], tmp[1] - arm, tmp[0], tmp[1] + arm, ring)
                ring.strokeCap = Paint.Cap.BUTT
            }
            // the size, just outside the thumb, while dragging (or just after a step)
            if (dragging || showLabel) {
                text.textSize = dp(11f)
                text.alpha = alpha
                val lr = arcR + dp(20f)
                canvas.drawText(label(stop), cx + lr * cos(ang).toFloat(), cy + lr * sin(ang).toFloat() + dp(4f), text)
            }
        }

        // ---- the centre: the current line, as it will be drawn ----
        if (cm < 1f) {
            val w = previewPx(stop).coerceIn(dp(1f), dp(20f)) * min(1f, p) * (1f - cm)
            if (w > 0f) {
                fill.shader = null
                fill.color = lineColour
                fill.alpha = (alpha * (1f - cm)).toInt()
                canvas.drawCircle(cx, cy, w / 2f, fill)
            }
        }
    }

    /** Stop of the size slider under (x, y), or -1 if not on the slider ([generous]: anywhere, while dragging). */
    private fun sliderAt(x: Float, y: Float, generous: Boolean): Int {
        val d = hypot(x - cx, y - cy)
        if (!generous && abs(d - arcR) > dp(16f)) return -1
        val ang = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble()))
        var rel = ang - ARC_START
        while (rel < -110) rel += 360.0
        while (rel > 250) rel -= 360.0
        if (!generous && (rel < -6 || rel > ARC_SPAN + 6)) return -1
        val t = (rel / ARC_SPAN).coerceIn(0.0, 1.0)
        return (t * (steps - 1)).roundToInt()
    }

    private fun hit(x: Float, y: Float): Item? {
        if (progress < 0.5f) return null
        val list = if (colourTarget >= 1f) colourSlots else slots
        val n = list.size
        for ((i, it) in list.withIndex()) {
            slotPos(i, n, 1f, tmp)
            if (hypot(x - tmp[0], y - tmp[1]) <= btnR + dp(6f)) return it
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
                else if (pressedStep == 0) {
                    val h = hit(e.x, e.y)
                    if (h !== pressed) {
                        pressed = h; invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (pressedStep != 0) {
                    // one step thinner / thicker; the menu stays open
                    val step = pressedStep
                    pressedStep = 0
                    if (stepAt(e.x, e.y) == step) {
                        showLabel = true
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
                when {
                    h == null -> close() // a tap anywhere else closes it
                    h.kind == COLOUR_BUTTON -> showColours(true)
                    h.kind == BACK -> showColours(false)
                    h.kind == ACTION && h.value != 2 -> {
                        // undo / redo: done, and the menu stays open for more
                        if (canDo(h.value)) onPick?.invoke(h)
                        invalidate()
                    }
                    else -> {
                        onPick?.invoke(h)
                        close()
                    }
                }
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
