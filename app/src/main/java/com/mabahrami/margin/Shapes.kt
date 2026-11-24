package com.mabahrami.margin

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Turns a rough hand-drawn line, circle/ellipse or rectangle into a clean one ("hold to snap"). */
object Shapes {

    /** [poly] = draw with straight segments (sharp corners) instead of smoothing. */
    class Result(val pts: FloatArray, val poly: Boolean, val isLine: Boolean)

    /**
     * [p]/[size]: stroke points (x,y pairs). [minSize]: strokes smaller than this (in the same units)
     * are left alone. Returns null if the stroke doesn't look like a simple shape.
     */
    fun recognize(p: FloatArray, size: Int, minSize: Float): Result? {
        val n = size / 2
        if (n < 4) return null
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var length = 0f
        for (k in 0 until n) {
            val x = p[2 * k]
            val y = p[2 * k + 1]
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
            if (k > 0) length += hypot(x - p[2 * k - 2], y - p[2 * k - 1])
        }
        val w = maxX - minX
        val h = maxY - minY
        if (hypot(w, h) < minSize) return null

        val x0 = p[0]
        val y0 = p[1]
        val x1 = p[size - 2]
        val y1 = p[size - 1]
        val ends = hypot(x1 - x0, y1 - y0)

        // ---- straight line: endpoints far apart, nothing strays far from the chord ----
        if (ends > 0.8f * length && ends >= minSize) {
            var maxDev = 0f
            for (k in 0 until n) {
                val dev = abs((p[2 * k] - x0) * (y1 - y0) - (p[2 * k + 1] - y0) * (x1 - x0)) / ends
                maxDev = max(maxDev, dev)
            }
            if (maxDev < max(0.07f * ends, minSize * 0.15f)) {
                return Result(floatArrayOf(x0, y0, x1, y1), poly = true, isLine = true)
            }
        }

        // ---- closed shapes: the stroke ends near where it started and goes all the way round ----
        val big = max(w, h)
        if (ends > 0.3f * big || length < 1.6f * big) return null

        // rectangle first: works for long, thin, tilted and badly drawn ones
        rectangle(p, n, length, minSize)?.let { return Result(it, poly = true, isLine = false) }

        if (min(w, h) < minSize * 0.3f) return null
        // an ellipse has to go all the way round (its perimeter, roughly)
        val perim = (Math.PI * (3 * (w + h) / 2f - sqrt((3 * w + h) * (w + 3 * h) / 4f))).toFloat()
        if (length < 0.75f * perim) return null
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val rx = w / 2f
        val ry = h / 2f
        var ellipseErr = 0f
        for (k in 0 until n) {
            val ex = (p[2 * k] - cx) / rx
            val ey = (p[2 * k + 1] - cy) / ry
            ellipseErr += abs(sqrt(ex * ex + ey * ey) - 1f)
        }
        ellipseErr /= n

        if (ellipseErr < 0.12f) {
            var ax = rx
            var ay = ry
            if (abs(rx - ry) / max(rx, ry) < 0.2f) {
                // close to round: make it a perfect circle
                val r = (rx + ry) / 2f
                ax = r; ay = r
            }
            val steps = 72
            val out = FloatArray((steps + 1) * 2)
            for (k in 0..steps) {
                val a = (2.0 * Math.PI * k / steps).toFloat()
                out[2 * k] = cx + ax * cos(a)
                out[2 * k + 1] = cy + ay * sin(a)
            }
            return Result(out, poly = false, isLine = false)
        }
        return null
    }

    /**
     * A rough closed stroke as a rectangle, or null. Uses the smallest rectangle around the stroke
     * (any angle; nearly level ones are made level): the stroke must fill it well (an ellipse fills only
     * about 79%), run along all four sides, and go round about once. Each side is then placed at the
     * middle (median) of the points drawn along it, so wobbles and overshoots at the corners don't count.
     */
    private fun rectangle(p: FloatArray, n: Int, length: Float, minSize: Float): FloatArray? {
        val hull = hull(p, n)
        if (hull.size < 4) return null
        var hullArea = 0.0
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            hullArea += p[2 * a].toDouble() * p[2 * b + 1] - p[2 * b].toDouble() * p[2 * a + 1]
        }
        hullArea = abs(hullArea) / 2.0
        // smallest-area rectangle: one of its sides lies along an edge of the hull
        var bestArea = Double.MAX_VALUE
        var ux = 1f; var uy = 0f
        for (i in hull.indices) {
            val a = hull[i]; val b = hull[(i + 1) % hull.size]
            val dx = p[2 * b] - p[2 * a]; val dy = p[2 * b + 1] - p[2 * a + 1]
            val len = hypot(dx, dy)
            if (len < 1e-4f) continue
            val cx = dx / len; val cy = dy / len
            var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
            for (k in hull) {
                val u = p[2 * k] * cx + p[2 * k + 1] * cy
                val v = -p[2 * k] * cy + p[2 * k + 1] * cx
                u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
            }
            val area = (u1 - u0).toDouble() * (v1 - v0)
            if (area < bestArea) { bestArea = area; ux = cx; uy = cy }
        }
        if (bestArea <= 0.0 || hullArea / bestArea < 0.86) return null
        // nearly level (within 10°): make it level
        var ang = Math.toDegrees(kotlin.math.atan2(uy.toDouble(), ux.toDouble()))
        ang = ((ang % 90.0) + 90.0) % 90.0
        if (ang < 10.0 || ang > 80.0) { ux = 1f; uy = 0f }
        // frame: u along (ux, uy), v along (-uy, ux)
        val us = FloatArray(n); val vs = FloatArray(n)
        var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
        for (k in 0 until n) {
            val x = p[2 * k]; val y = p[2 * k + 1]
            us[k] = x * ux + y * uy
            vs[k] = -x * uy + y * ux
            u0 = min(u0, us[k]); u1 = max(u1, us[k]); v0 = min(v0, vs[k]); v1 = max(v1, vs[k])
        }
        if (min(u1 - u0, v1 - v0) < minSize * 0.3f) return null
        // each point belongs to its nearest side
        val sides = Array(4) { ArrayList<Float>() }   // 0 = u0, 1 = u1 (offsets along u), 2 = v0, 3 = v1 (along v)
        val spans = Array(4) { floatArrayOf(Float.MAX_VALUE, -Float.MAX_VALUE) } // how far along the side it runs
        for (k in 0 until n) {
            val d = floatArrayOf(us[k] - u0, u1 - us[k], vs[k] - v0, v1 - vs[k])
            var s = 0
            for (j in 1..3) if (d[j] < d[s]) s = j
            sides[s].add(if (s < 2) us[k] else vs[k])
            val along = if (s < 2) vs[k] else us[k]
            spans[s][0] = min(spans[s][0], along); spans[s][1] = max(spans[s][1], along)
        }
        // the stroke runs along the sides; one (usually a short side where the stroke stopped early) may be
        // missing or only partly drawn: it's then placed at the edge of the box
        val good = BooleanArray(4)
        for (j in 0..3) {
            val full = if (j < 2) v1 - v0 else u1 - u0
            good[j] = sides[j].size >= 2 && spans[j][1] - spans[j][0] >= 0.4f * full
        }
        if (good.count { it } < 3) return null
        fun median(l: ArrayList<Float>): Float { l.sort(); return l[l.size / 2] }
        var a0 = if (good[0]) median(sides[0]) else u0
        var a1 = if (good[1]) median(sides[1]) else u1
        var b0 = if (good[2]) median(sides[2]) else v0
        var b1 = if (good[3]) median(sides[3]) else v1
        if (a1 - a0 < minSize * 0.3f) { a0 = u0; a1 = u1 }
        if (b1 - b0 < minSize * 0.3f) { b0 = v0; b1 = v1 }
        // goes round about once (not a scribble filling the box)
        val perim = 2f * ((a1 - a0) + (b1 - b0))
        if (length < 0.7f * perim || length > 1.9f * perim) return null
        // on average the stroke stays close to the rectangle's outline
        var err = 0f
        for (k in 0 until n) {
            val du = min(abs(us[k] - a0), abs(us[k] - a1))
            val dv = min(abs(vs[k] - b0), abs(vs[k] - b1))
            val inU = us[k] in a0..a1
            val inV = vs[k] in b0..b1
            err += when {
                inU && inV -> min(du, dv)
                inU -> dv
                inV -> du
                else -> hypot(du, dv)
            }
        }
        err /= n
        if (err > 0.12f * ((a1 - a0) + (b1 - b0)) / 2f) return null
        // back to page coordinates: x = u*ux - v*uy, y = u*uy + v*ux
        val out = FloatArray(10)
        val cs = arrayOf(floatArrayOf(a0, b0), floatArrayOf(a1, b0), floatArrayOf(a1, b1), floatArrayOf(a0, b1), floatArrayOf(a0, b0))
        for ((k, c) in cs.withIndex()) {
            out[2 * k] = c[0] * ux - c[1] * uy
            out[2 * k + 1] = c[0] * uy + c[1] * ux
        }
        return out
    }

    /** Convex hull (indices of points, anticlockwise), monotone chain. */
    private fun hull(p: FloatArray, n: Int): IntArray {
        val idx = (0 until n).sortedWith(compareBy<Int>({ p[2 * it] }, { p[2 * it + 1] }))
        fun cross(o: Int, a: Int, b: Int): Float =
            (p[2 * a] - p[2 * o]) * (p[2 * b + 1] - p[2 * o + 1]) - (p[2 * a + 1] - p[2 * o + 1]) * (p[2 * b] - p[2 * o])
        val h = IntArray(2 * n + 2)
        var k = 0
        for (i in idx) {
            while (k >= 2 && cross(h[k - 2], h[k - 1], i) <= 0f) k--
            h[k++] = i
        }
        val lower = k + 1
        for (j in idx.indices.reversed()) {
            val i = idx[j]
            while (k >= lower && cross(h[k - 2], h[k - 1], i) <= 0f) k--
            h[k++] = i
        }
        return h.copyOf(max(0, k - 1))
    }
}
