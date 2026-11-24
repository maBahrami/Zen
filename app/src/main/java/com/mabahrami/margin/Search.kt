package com.mabahrami.margin

import android.graphics.RectF
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Finding words in a document's PDF text (not in handwriting). */
object PdfSearch {

    /** The text of one PDF page, and where each of its characters sits (page-local points; null = an added space). */
    class PageText(val text: String, val boxes: Array<RectF?>)

    /** One place a word was found: its page, and a box per line it covers (page-local points). */
    class Hit(val page: Page, val rects: List<RectF>)

    /**
     * Reads the text of every page of [file] with where each character is. [pageW]/[pageH]: the size of each
     * page as the app shows it (the boxes are scaled to those). Pages without text (scans) come back empty.
     */
    fun extract(file: File, pageW: IntArray, pageH: IntArray): Array<PageText?> {
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val n = doc.numberOfPages
            val out = arrayOfNulls<PageText>(n)
            val stripper = Collector()
            stripper.sortByPosition = true
            for (i in 0 until n) {
                try {
                    val page = doc.getPage(i)
                    val crop = page.cropBox
                    val rot = ((page.rotation % 360) + 360) % 360
                    val sideways = rot == 90 || rot == 270
                    val visW = if (sideways) crop.height else crop.width
                    val visH = if (sideways) crop.width else crop.height
                    val w = if (i < pageW.size && pageW[i] > 0) pageW[i].toFloat() else visW
                    val h = if (i < pageH.size && pageH[i] > 0) pageH[i].toFloat() else visH
                    stripper.reset(w / visW, h / visH)
                    stripper.startPage = i + 1
                    stripper.endPage = i + 1
                    stripper.getText(doc)
                    out[i] = PageText(stripper.sb.toString(), stripper.boxes.toTypedArray())
                } catch (t: Throwable) {
                    out[i] = null
                }
            }
            return out
        }
    }

    /** Every place [query] appears on [page] (case and accents of letters as typed, ignoring case). */
    fun find(page: Page, text: PageText, query: String): List<Hit> {
        val q = normalise(query)
        if (q.isEmpty()) return emptyList()
        val hay = normalise(text.text)
        val hits = ArrayList<Hit>()
        var from = 0
        while (true) {
            val at = hay.indexOf(q, from)
            if (at < 0) break
            val rects = ArrayList<RectF>()
            var cur: RectF? = null
            for (k in at until min(at + q.length, text.boxes.size)) {
                val b = text.boxes[k] ?: continue
                val c = cur
                // same line (vertical overlap): grow the box; a new line starts a new box
                if (c != null && abs(b.centerY() - c.centerY()) < max(b.height(), c.height()) * 0.6f && b.left >= c.left - b.height()) {
                    c.union(b)
                } else {
                    cur = RectF(b).also { rects.add(it) }
                }
            }
            if (rects.isNotEmpty()) hits.add(Hit(page, rects))
            from = at + max(1, q.length)
        }
        return hits
    }

    /** Lower case, with runs of spaces as one, so "Fourier  transform" across a line break still matches. */
    private fun normalise(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) sb.append(if (ch.isWhitespace()) ' ' else ch.lowercaseChar())
        return sb.toString()
    }

    /** Collects the characters and their boxes instead of plain text. */
    private class Collector : PDFTextStripper() {
        val sb = StringBuilder()
        val boxes = ArrayList<RectF?>()
        private var sx = 1f
        private var sy = 1f

        fun reset(scaleX: Float, scaleY: Float) {
            sb.setLength(0)
            boxes.clear()
            sx = scaleX
            sy = scaleY
        }

        override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
            if (textPositions == null) return
            for (tp in textPositions) {
                val u = tp.unicode ?: continue
                // box from the baseline up by the glyph's height (a little taller than PDF fonts report)
                val hgt = max(tp.height, tp.fontSizeInPt * 0.7f) * 1.25f
                val x = tp.x
                val y = tp.y
                val r = RectF(x * sx, (y - hgt) * sy, (x + tp.width) * sx, (y + hgt * 0.22f) * sy)
                for (ch in u) {
                    sb.append(ch)
                    boxes.add(r)
                }
            }
        }

        override fun writeWordSeparator() {
            sb.append(' '); boxes.add(null)
        }

        override fun writeLineSeparator() {
            sb.append(' '); boxes.add(null)
        }
    }
}
