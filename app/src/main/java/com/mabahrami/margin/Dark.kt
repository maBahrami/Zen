package com.mabahrami.margin

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.RectF
import androidx.core.graphics.ColorUtils

/**
 * Dark view: display-only colours. Nothing here touches the document data, so saving and
 * exporting always use the original colours.
 */
object Dark {

    /** Grey that white paper becomes. */
    private const val LO = 0x26
    /** Grey that black text becomes (a soft white, easier on the eyes than pure white). */
    private const val HI = 0xE6

    /** Paper colour in dark view (= what white becomes, LO grey). */
    const val PAPER = 0xFF262626.toInt()
    const val RULE = 0xFF4A4A50.toInt()
    const val EDGE = 0x40FFFFFF
    const val PLACEHOLDER = 0xFF3A3A3C.toInt()

    /** Highlighter strength in dark view (screen blending, the dark-paper twin of multiply). */
    const val HIGHLIGHT_ALPHA = 0.45f

    /** Maps 0..255 into LO..HI, so the darkest result is the paper grey and the brightest a soft white. */
    private val squeeze = IntArray(256) { LO + (it * (HI - LO) + 127) / 255 }

    /** Ink colours for dark view, cached (only a handful of colours are ever used). */
    private val inkCache = HashMap<Int, Int>()

    /**
     * How an ink colour looks on dark paper: black (and near-black greys) turns white; every other
     * colour stays exactly as it is in light view.
     */
    fun ink(c: Int): Int = synchronized(inkCache) {
        inkCache.getOrPut(c) {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(c, hsl)
            if (hsl[2] < 0.25f && hsl[1] < 0.25f) {
                hsl[2] = 1f - hsl[2]
                (ColorUtils.HSLToColor(hsl) and 0x00FFFFFF) or (c and 0xFF000000.toInt())
            } else c
        }
    }

    /**
     * Rough version of [process] as a colour matrix (invert, then turn the hue back), used only to
     * show an already-rendered page for the moment until its proper version is ready.
     */
    val quickMatrix: ColorMatrix by lazy {
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        // hue rotation by 180 degrees (luminance-preserving)
        val hue = ColorMatrix(floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val k = (HI - LO) / 255f
        val range = ColorMatrix(floatArrayOf(
            k, 0f, 0f, 0f, LO.toFloat(),
            0f, k, 0f, 0f, LO.toFloat(),
            0f, 0f, k, 0f, LO.toFloat(),
            0f, 0f, 0f, 1f, 0f,
        ))
        ColorMatrix().apply {
            postConcat(invert)
            postConcat(hue)
            postConcat(range)
        }
    }

    /**
     * Turns a rendered (opaque) page into its dark version, in place: lightness is inverted while hue and
     * saturation stay (white paper -> dark grey, black text -> soft white, coloured lines keep their colour).
     * Pixels inside [keep] (bitmap pixel coordinates, e.g. photos) are left untouched.
     * Works in bands of rows, so it needs little extra memory even for large pages.
     */
    fun process(bmp: Bitmap, keep: List<RectF>?) {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return
        val bandH = maxOf(1, minOf(h, 262_144 / w))
        val buf = IntArray(w * bandH)
        // keep-rects as integer pixel ranges
        val kr = keep?.mapNotNull { r ->
            val l = r.left.toInt().coerceIn(0, w)
            val t = r.top.toInt().coerceIn(0, h)
            val rr = kotlin.math.ceil(r.right.toDouble()).toInt().coerceIn(0, w)
            val b = kotlin.math.ceil(r.bottom.toDouble()).toInt().coerceIn(0, h)
            if (rr > l && b > t) intArrayOf(l, t, rr, b) else null
        } ?: emptyList()
        val sq = squeeze
        var y0 = 0
        while (y0 < h) {
            val rows = minOf(bandH, h - y0)
            bmp.getPixels(buf, 0, w, 0, y0, w, rows)
            for (ry in 0 until rows) {
                val y = y0 + ry
                val base = ry * w
                var x = 0
                while (x < w) {
                    // skip pixels covered by a keep-rect
                    var skipTo = -1
                    for (k in kr) if (y >= k[1] && y < k[3] && x >= k[0] && x < k[2] && k[2] > skipTo) skipTo = k[2]
                    if (skipTo > x) {
                        x = skipTo; continue
                    }
                    val i = base + x
                    val p = buf[i]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val mx = if (r > g) (if (r > b) r else b) else (if (g > b) g else b)
                    val mn = if (r < g) (if (r < b) r else b) else (if (g < b) g else b)
                    val off = 255 - mx - mn
                    buf[i] = (p and 0xFF000000.toInt()) or (sq[r + off] shl 16) or (sq[g + off] shl 8) or sq[b + off]
                    x++
                }
            }
            bmp.setPixels(buf, 0, w, 0, y0, w, rows)
            y0 += rows
        }
    }
}
