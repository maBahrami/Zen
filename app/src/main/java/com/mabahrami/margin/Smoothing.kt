package com.mabahrami.margin

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pen smoothing strengths (setting). */
const val SMOOTH_OFF = 0
const val SMOOTH_LIGHT = 1
const val SMOOTH_MEDIUM = 2
const val SMOOTH_STRONG = 3

/** Pen smoothing (dp on screen): the biggest wiggle treated as tremor. Fixed, very gentle (chosen on the tablet). */
const val PEN_SMOOTH_DP = 0.08f

/**
 * Cleans up a finished stroke without changing its shape:
 *  0. the stroke is resampled evenly (every half tolerance) and lightly averaged with its neighbours,
 *     which takes out the fine jitter of the pen samples;
 *  1. Douglas–Peucker simplification drops every wiggle smaller than [tol] (hand tremor) but keeps every
 *     real turn, so the line never moves more than [tol] away from where it was drawn;
 *  2. a centripetal Catmull–Rom curve through the points that are left (it passes exactly through them,
 *     without loops or overshoot) is sampled densely, so the stroke is drawn as one smooth curve.
 * Points are (x0, y0, x1, y1, ...); returns a new array.
 */
object Smoothing {

    /** Tolerance in dp on screen for each strength (how big a wiggle may be to count as tremor). */
    fun toleranceDp(strength: Int): Float = when (strength) {
        SMOOTH_LIGHT -> 0.35f // gentle: only the finest tremor is taken out (was 0.6)
        SMOOTH_STRONG -> 1.6f
        else -> 1.0f // medium
    }

    fun smooth(raw: FloatArray, rawSize: Int, tol: Float): FloatArray {
        if (rawSize / 2 < 3 || !(tol > 0f)) return raw.copyOf(rawSize)
        // even spacing: half the tolerance, but wider on long strokes so the point limit is never reached
        val rawLen = length(raw, rawSize)
        val h = max(tol * 0.5f, rawLen / (MAX_POINTS * 0.8f))
        val even = resample(raw, rawSize, h) ?: return raw.copyOf(rawSize) // too many points: keep the stroke as drawn
        val pts = preSmooth(even)
        val n = pts.size / 2
        if (n < 3) return raw.copyOf(rawSize)
        // 1. which points carry the shape
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Long>()
        stack.addLast(pack(0, n - 1))
        val tol2 = tol * tol
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            val a = (e ushr 32).toInt()
            val b = (e and 0xFFFFFFFFL).toInt()
            if (b - a < 2) continue
            var worst = -1
            var worstD = tol2
            for (k in a + 1 until b) {
                val d = segDist2(pts[2 * k], pts[2 * k + 1], pts[2 * a], pts[2 * a + 1], pts[2 * b], pts[2 * b + 1])
                if (d > worstD) {
                    worstD = d; worst = k
                }
            }
            if (worst >= 0) {
                keep[worst] = true
                stack.addLast(pack(a, worst))
                stack.addLast(pack(worst, b))
            }
        }
        var m = 0
        for (k in 0 until n) if (keep[k]) m++
        val kx = FloatArray(m)
        val ky = FloatArray(m)
        var j = 0
        for (k in 0 until n) if (keep[k]) {
            kx[j] = pts[2 * k]; ky[j] = pts[2 * k + 1]; j++
        }
        if (m == 2) return floatArrayOf(kx[0], ky[0], kx[1], ky[1]) // a shaky straight line becomes straight
        // 2. smooth curve through them, sampled about every half tolerance (at least 0.3 points apart)
        val step = max(0.3f, tol * 0.5f)
        val out = ArrayList<Float>(m * 8)
        out.add(kx[0]); out.add(ky[0])
        for (i in 0 until m - 1) {
            // neighbours, with mirrored points beyond the ends
            val x0 = if (i > 0) kx[i - 1] else 2 * kx[0] - kx[1]
            val y0 = if (i > 0) ky[i - 1] else 2 * ky[0] - ky[1]
            val x3 = if (i + 2 < m) kx[i + 2] else 2 * kx[m - 1] - kx[m - 2]
            val y3 = if (i + 2 < m) ky[i + 2] else 2 * ky[m - 1] - ky[m - 2]
            val x1 = kx[i]; val y1 = ky[i]; val x2 = kx[i + 1]; val y2 = ky[i + 1]
            val len = hypot(x2 - x1, y2 - y1)
            val steps = min(400, max(1, ceil(len / step).toInt()))
            // centripetal parametrisation (no cusps or loops)
            val t0 = 0f
            val t1 = t0 + knot(x0, y0, x1, y1)
            val t2 = t1 + knot(x1, y1, x2, y2)
            val t3 = t2 + knot(x2, y2, x3, y3)
            for (s in 1..steps) {
                val t = t1 + (t2 - t1) * s / steps
                val a1x = lerp(x0, x1, t0, t1, t); val a1y = lerp(y0, y1, t0, t1, t)
                val a2x = lerp(x1, x2, t1, t2, t); val a2y = lerp(y1, y2, t1, t2, t)
                val a3x = lerp(x2, x3, t2, t3, t); val a3y = lerp(y2, y3, t2, t3, t)
                val b1x = lerp(a1x, a2x, t0, t2, t); val b1y = lerp(a1y, a2y, t0, t2, t)
                val b2x = lerp(a2x, a3x, t1, t3, t); val b2y = lerp(a2y, a3y, t1, t3, t)
                var cx = lerp(b1x, b2x, t1, t2, t)
                var cy = lerp(b1y, b2y, t1, t2, t)
                if (!cx.isFinite() || !cy.isFinite()) {
                    cx = x1 + (x2 - x1) * s / steps; cy = y1 + (y2 - y1) * s / steps
                }
                out.add(cx); out.add(cy)
            }
        }
        return out.toFloatArray()
    }

    /** Most points a resampled stroke may have. */
    private const val MAX_POINTS = 20000

    private fun length(p: FloatArray, size: Int): Float {
        var l = 0f
        var k = 2
        while (k + 1 < size) {
            l += hypot(p[k] - p[k - 2], p[k + 1] - p[k - 1]); k += 2
        }
        return l
    }

    /**
     * Points every [h] along the stroke (the first and last point kept exactly), or null if that would take
     * more than [MAX_POINTS] (the stroke is then never cut short: the caller keeps it as drawn).
     */
    private fun resample(pts: FloatArray, size: Int, h: Float): FloatArray? {
        val n = size / 2
        val out = ArrayList<Float>(n * 2)
        out.add(pts[0]); out.add(pts[1])
        var need = h // distance still to go to the next output point
        for (k in 1 until n) {
            var ax = pts[2 * k - 2]
            var ay = pts[2 * k - 1]
            val bx = pts[2 * k]
            val by = pts[2 * k + 1]
            var len = hypot(bx - ax, by - ay)
            while (len >= need && len > 0f) {
                val t = need / len
                ax += (bx - ax) * t
                ay += (by - ay) * t
                out.add(ax); out.add(ay)
                if (out.size > MAX_POINTS * 2) return null
                len -= need
                need = h
            }
            need -= len
        }
        val lx = pts[2 * n - 2]
        val ly = pts[2 * n - 1]
        if (hypot(lx - out[out.size - 2], ly - out[out.size - 1]) > 1e-4f) {
            out.add(lx); out.add(ly)
        }
        return out.toFloatArray()
    }

    /** One light pass of a [1, 4, 1] / 6 average over neighbours (was [1, 2, 1] / 4); the ends stay where they are. */
    private fun preSmooth(p: FloatArray): FloatArray {
        var a = p
        val n = a.size / 2
        if (n < 3) return a
        repeat(1) {
            val b = a.copyOf()
            for (k in 1 until n - 1) {
                b[2 * k] = (a[2 * k - 2] + 4 * a[2 * k] + a[2 * k + 2]) / 6f
                b[2 * k + 1] = (a[2 * k - 1] + 4 * a[2 * k + 1] + a[2 * k + 3]) / 6f
            }
            a = b
        }
        return a
    }

    private fun pack(a: Int, b: Int): Long = (a.toLong() shl 32) or (b.toLong() and 0xFFFFFFFFL)

    private fun knot(ax: Float, ay: Float, bx: Float, by: Float): Float =
        max(1e-4f, sqrt(hypot(bx - ax, by - ay)))

    private fun lerp(a: Float, b: Float, ta: Float, tb: Float, t: Float): Float {
        val d = tb - ta
        return if (d <= 1e-6f) b else a + (b - a) * (t - ta) / d
    }

    private fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val l2 = vx * vx + vy * vy
        if (l2 <= 1e-9f) return (px - ax) * (px - ax) + (py - ay) * (py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0f, 1f)
        val dx = px - (ax + t * vx)
        val dy = py - (ay + t * vy)
        return dx * dx + dy * dy
    }
}
