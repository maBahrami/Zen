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
        if (ends > 0.3f * big || length < 2.2f * big || min(w, h) < minSize * 0.3f) return null
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val rx = w / 2f
        val ry = h / 2f
        var ellipseErr = 0f
        var rectErr = 0f
        val half = (w + h) / 2f
        for (k in 0 until n) {
            val x = p[2 * k]
            val y = p[2 * k + 1]
            val ex = (x - cx) / rx
            val ey = (y - cy) / ry
            ellipseErr += abs(sqrt(ex * ex + ey * ey) - 1f)
            val edge = min(min(abs(x - minX), abs(x - maxX)), min(abs(y - minY), abs(y - maxY)))
            rectErr += edge / half
        }
        ellipseErr /= n
        rectErr /= n

        if (rectErr < 0.05f && rectErr * 2f < ellipseErr) {
            return Result(
                floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY, minX, minY),
                poly = true, isLine = false,
            )
        }
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
}
