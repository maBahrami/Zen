package com.mabahrami.margin

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Gentle pressure-sensitive line width (like a fountain pen, but subtle): a light touch draws the line a
 * little thinner, a firm press a little thicker, within [MIN_FACTOR]..[MAX_FACTOR] of the chosen width.
 * Such a stroke is drawn as a filled outline, the same shape on screen and in the exported PDF.
 */
object Pressure {

    const val MIN_FACTOR = 0.70f
    const val MAX_FACTOR = 1.35f

    /** Width factor for an S Pen pressure (0..1). About 1.0 at a normal writing pressure. */
    fun factor(p: Float): Float {
        val q = if (p.isFinite()) p.coerceIn(0f, 1f) else 0.4f
        return MIN_FACTOR + (MAX_FACTOR - MIN_FACTOR) * q.pow(0.7f)
    }

    /** How quickly the width follows the pressure while writing (0..1): smooths out pen pressure noise. */
    const val FOLLOW = 0.3f

    /**
     * Width factors for the finished stroke's points [out], from the factors the live stroke already showed
     * ([fact], one per raw point): each output point takes the factor at the same distance along the stroke,
     * so the line keeps exactly the thickness it had while writing. Returns null if there's nothing usable.
     */
    fun widths(raw: FloatArray, fact: FloatArray, rawSize: Int, out: FloatArray): FloatArray? {
        val rn = rawSize / 2
        val on = out.size / 2
        if (rn < 2 || on < 2 || fact.size < rn) return null
        val rl = cumulative(raw, rn)
        val ol = cumulative(out, on)
        val rTotal = rl[rn - 1]
        val oTotal = ol[on - 1]
        val f = FloatArray(on)
        var j = 0
        for (i in 0 until on) {
            val target = if (oTotal > 1e-6f) ol[i] / oTotal * rTotal else 0f
            while (j < rn - 2 && rl[j + 1] < target) j++
            val seg = rl[j + 1] - rl[j]
            val t = if (seg > 1e-6f) ((target - rl[j]) / seg).coerceIn(0f, 1f) else 0f
            f[i] = (fact[j] + (fact[j + 1] - fact[j]) * t).coerceIn(MIN_FACTOR, MAX_FACTOR)
        }
        return f
    }

    private fun cumulative(p: FloatArray, n: Int): FloatArray {
        val l = FloatArray(n)
        for (k in 1 until n) l[k] = l[k - 1] + hypot(p[2 * k] - p[2 * k - 2], p[2 * k + 1] - p[2 * k - 1])
        return l
    }

    /**
     * Filled outline of a pressure stroke, as closed polygons (x0, y0, x1, y1, ...): the body with round
     * ends, plus a round dot at every sharp turn so corners are always fully filled. All polygons turn the
     * same way, so non-zero filling (Android and PDF) fills their union.
     */
    fun outline(s: Stroke): List<FloatArray>? {
        val pw = s.pw ?: return null
        val n = s.pts.size / 2
        if (n < 2 || pw.size != n) return null
        val pm = PathMeasure(s.path, false)
        val len = pm.length
        val w = s.width
        if (!(len > 1e-3f)) {
            return listOf(circle(s.pts[0], s.pts[1], w * pw[0] / 2f, true))
        }
        // where each stored point lies along the stroke, to look up its width at any distance
        val cl = cumulative(s.pts, n)
        val cTotal = cl[n - 1]
        fun widthAt(d: Float): Float {
            if (cTotal <= 1e-6f) return w * pw[0]
            // by distance along the stroke (not as a share of its length): while writing, the part
            // already drawn keeps exactly the same widths as the stroke grows, so it doesn't shimmer
            val target = d.coerceIn(0f, cTotal)
            var lo = 0
            var hi = n - 1
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (cl[mid] <= target) lo = mid else hi = mid
            }
            val seg = cl[hi] - cl[lo]
            val t = if (seg > 1e-6f) ((target - cl[lo]) / seg).coerceIn(0f, 1f) else 0f
            return w * (pw[lo] + (pw[hi] - pw[lo]) * t)
        }
        // fine steps (smooth edges even zoomed in), at fixed distances from the start: earlier samples
        // never move as the stroke grows; only the last one (the tip) follows the pen
        var step = (w * 0.15f).coerceIn(0.08f, 0.25f)
        if (len / step > 5998f) step = len / 5998f
        val whole = (len / step).toInt()
        val m = (whole + 2).coerceAtLeast(2)
        val px = FloatArray(m); val py = FloatArray(m)
        val tx = FloatArray(m); val ty = FloatArray(m)
        val hw = FloatArray(m)
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        for (i in 0 until m) {
            val d = if (i == m - 1) len else min(len, i * step)
            pm.getPosTan(d, pos, tan)
            var a = tan[0]; var b = tan[1]
            val tl = hypot(a, b)
            if (tl > 1e-6f) { a /= tl; b /= tl } else if (i > 0) { a = tx[i - 1]; b = ty[i - 1] } else { a = 1f; b = 0f }
            px[i] = pos[0]; py[i] = pos[1]; tx[i] = a; ty[i] = b
            hw[i] = widthAt(d) / 2f
        }
        val capSeg = 14
        val body = FloatArray((2 * m + 2 * (capSeg - 1)) * 2)
        var k = 0
        fun put(x: Float, y: Float) { body[k++] = x; body[k++] = y }
        // Each side's edge never runs backwards: on the inside of a tight turn the offset edge would loop
        // back on itself and poke out little spikes, so those points are skipped (the edge cuts across).
        fun side(sign: Float): FloatArray {
            val out = FloatArray(m * 2)
            var c = 0
            for (i in 0 until m) {
                val x = px[i] - sign * ty[i] * hw[i]
                val y = py[i] + sign * tx[i] * hw[i]
                if (c >= 2) {
                    val dx = x - out[c - 2]
                    val dy = y - out[c - 1]
                    if (dx * tx[i] + dy * ty[i] <= 0f) continue // would go backwards
                }
                out[c++] = x; out[c++] = y
            }
            return out.copyOf(c)
        }
        val left = side(1f)   // normal = (-ty, tx)
        val right = side(-1f)
        // left side, forward
        var q = 0
        while (q + 1 < left.size) { put(left[q], left[q + 1]); q += 2 }
        // round end: from left to right around the tip
        run {
            val i = m - 1
            for (c in 1 until capSeg) {
                val a = PI.toFloat() * c / capSeg
                val ca = cos(a); val sa = sin(a)
                put(px[i] + hw[i] * (-ty[i] * ca + tx[i] * sa), py[i] + hw[i] * (tx[i] * ca + ty[i] * sa))
            }
        }
        // right side, backward
        q = right.size - 2
        while (q >= 0) { put(right[q], right[q + 1]); q -= 2 }
        // round start: from right back to left around the start
        for (c in 1 until capSeg) {
            val a = PI.toFloat() * c / capSeg
            val ca = cos(a); val sa = sin(a)
            put(px[0] + hw[0] * (ty[0] * ca - tx[0] * sa), py[0] + hw[0] * (-tx[0] * ca - ty[0] * sa))
        }
        val polys = ArrayList<FloatArray>()
        val body2 = if (k == body.size) body else body.copyOf(k)
        polys.add(body2)
        val ccw = signedArea(body2) > 0f
        // dots at sharp turns (the inner edge folds there)
        // (the turn is measured over about half a line width, so fine sampling doesn't hide a corner)
        val win = max(1, (w * 0.5f / step).toInt())
        var lastDot = -10 * win
        for (i in win until m) {
            val turn = abs(angle(tx[i - win], ty[i - win], tx[i], ty[i]))
            if (turn > 0.35f && i - lastDot > win) {
                val c = i - win / 2 // the middle of the turn
                polys.add(circle(px[c], py[c], hw[c], ccw))
                lastDot = i
            }
        }
        return polys
    }

    private fun angle(ax: Float, ay: Float, bx: Float, by: Float): Float =
        atan2(ax * by - ay * bx, ax * bx + ay * by)

    private fun signedArea(p: FloatArray): Float {
        var s = 0.0
        val n = p.size / 2
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += p[2 * i].toDouble() * p[2 * j + 1] - p[2 * j].toDouble() * p[2 * i + 1]
        }
        return (s / 2).toFloat()
    }

    private fun circle(cx: Float, cy: Float, r: Float, ccw: Boolean): FloatArray {
        val seg = 24
        val out = FloatArray(seg * 2)
        for (i in 0 until seg) {
            val a = 2 * PI.toFloat() * i / seg * (if (ccw) 1f else -1f)
            out[2 * i] = cx + r * cos(a)
            out[2 * i + 1] = cy + r * sin(a)
        }
        return out
    }

    /** The polygons as one Android path (non-zero fill). */
    fun toPath(polys: List<FloatArray>): Path {
        val p = Path()
        p.fillType = Path.FillType.WINDING
        for (poly in polys) {
            if (poly.size < 6) continue
            p.moveTo(poly[0], poly[1])
            var i = 2
            while (i + 1 < poly.size) {
                p.lineTo(poly[i], poly[i + 1]); i += 2
            }
            p.close()
        }
        return p
    }

    /**
     * Draws an ink stroke: a pressure stroke as its filled outline in [stroke]'s colour (using [fill]),
     * any other stroke as before with [stroke].
     */
    fun draw(c: Canvas, s: Stroke, stroke: Paint, fill: Paint) {
        val fp = s.fillPath
        if (fp == null) {
            c.drawPath(s.path, stroke)
            return
        }
        fill.color = stroke.color
        c.drawPath(fp, fill)
    }
}
