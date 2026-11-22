package com.mabahrami.margin

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.RectF
import androidx.core.graphics.ColorUtils

/**
 * Dark view: display-only colours. Nothing here touches the document data, so saving and
 * exporting always use the original colours.
 *
 * Several styles (hold the dark/light button to choose): the paper colour is what white becomes,
 * the text colour what black becomes; everything in between is spread evenly, per colour channel.
 */
object Dark {

    /** One dark style: paper ([lo]) and text ([hi]) as RGB. */
    class Style(val name: String, val lo: IntArray, val hi: IntArray) {
        /** Maps 0..255 into lo..hi for each channel. */
        val squeeze: Array<IntArray> = Array(3) { c -> IntArray(256) { lo[c] + (it * (hi[c] - lo[c]) + 127) / 255 } }
        val paper: Int = rgb(lo[0], lo[1], lo[2])
        val rule: Int = rgb(lo[0] + 0x24, lo[1] + 0x24, lo[2] + 0x2A)
        val placeholder: Int = rgb(lo[0] + 0x14, lo[1] + 0x14, lo[2] + 0x16)

        /** Rough version of [process] as a colour matrix (invert, turn the hue back, squeeze into lo..hi). */
        val quickMatrix: ColorMatrix = ColorMatrix().apply {
            postConcat(ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )))
            // hue rotation by 180 degrees (luminance-preserving)
            postConcat(ColorMatrix(floatArrayOf(
                -0.574f, 1.430f, 0.144f, 0f, 0f,
                0.426f, 0.430f, 0.144f, 0f, 0f,
                0.426f, 1.430f, -0.856f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            )))
            postConcat(ColorMatrix(floatArrayOf(
                (hi[0] - lo[0]) / 255f, 0f, 0f, 0f, lo[0].toFloat(),
                0f, (hi[1] - lo[1]) / 255f, 0f, 0f, lo[1].toFloat(),
                0f, 0f, (hi[2] - lo[2]) / 255f, 0f, lo[2].toFloat(),
                0f, 0f, 0f, 1f, 0f,
            )))
        }
    }

    private fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** The styles, in menu order. 0 is the original dark grey. */
    val STYLES = listOf(
        Style("Dark grey", intArrayOf(0x26, 0x26, 0x26), intArrayOf(0xE6, 0xE6, 0xE6)),
        Style("Black", intArrayOf(0x00, 0x00, 0x00), intArrayOf(0xDC, 0xDC, 0xDC)),
        Style("Dim", intArrayOf(0x3A, 0x3A, 0x3C), intArrayOf(0xEC, 0xEC, 0xEC)),
        Style("Night blue", intArrayOf(0x14, 0x1B, 0x2A), intArrayOf(0xDD, 0xE3, 0xEE)),
        Style("Warm dark", intArrayOf(0x2A, 0x24, 0x1E), intArrayOf(0xEE, 0xE2, 0xCC)),
    )

    @Volatile
    private var style: Style = STYLES[0]

    /** Index of the current style. */
    @Volatile
    var styleIndex = 0
        private set

    /** Changes the style (display only). Pages must be re-rendered afterwards. */
    fun setStyle(i: Int) {
        val k = i.coerceIn(0, STYLES.size - 1)
        styleIndex = k
        style = STYLES[k]
    }

    /** Paper colour in dark view (= what white becomes). */
    val PAPER: Int get() = style.paper
    val RULE: Int get() = style.rule
    const val EDGE = 0x40FFFFFF
    val PLACEHOLDER: Int get() = style.placeholder

    /** Highlighter strength in dark view (screen blending, the dark-paper twin of multiply). */
    const val HIGHLIGHT_ALPHA = 0.45f

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

    /** Colour matrix for a quick dark preview in the current style. */
    val quickMatrix: ColorMatrix get() = style.quickMatrix

    /**
     * Turns a rendered (opaque) page into its dark version, in place: lightness is inverted while hue and
     * saturation stay (white paper -> the style's paper, black text -> its text colour, coloured lines keep
     * their colour). Pixels inside [keep] (bitmap pixel coordinates, e.g. photos) are left untouched.
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
        val st = style // one style for the whole page, even if it changes meanwhile
        val sr = st.squeeze[0]
        val sg = st.squeeze[1]
        val sb = st.squeeze[2]
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
                    buf[i] = (p and 0xFF000000.toInt()) or (sr[r + off] shl 16) or (sg[g + off] shl 8) or sb[b + off]
                    x++
                }
            }
            bmp.setPixels(buf, 0, w, 0, y0, w, rows)
            y0 += rows
        }
    }
}
