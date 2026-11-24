package com.mabahrami.margin

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

const val MAX_HISTORY = 100

/**
 * One pen stroke. Points are stored in page-local PDF points (1/72 inch),
 * origin at the top-left corner of the ORIGINAL page area. x may be negative
 * (left margin) or larger than the page width (right margin).
 */
class Stroke(
    val color: Int,
    val width: Float,
    val pts: FloatArray,
    /** Line style: LINE_SOLID, LINE_DASHED, LINE_DOTTED or LINE_WAVY. */
    val style: Int = LINE_SOLID,
    /** Highlighter: drawn with multiply blending so text underneath stays readable. */
    val highlighter: Boolean = false,
    /** Straight segments between points (snapped rectangles/lines) instead of a smooth curve. */
    val poly: Boolean = false,
    /** Pen pressure: a width factor per point (see [Pressure]), or null for an even line. */
    widths: FloatArray? = null,
) {
    /** Per-point width factors, only kept when they match the points exactly. */
    val pw: FloatArray? = widths?.takeIf { f ->
        f.size >= 2 && f.size == pts.size / 2 && f.all { it.isFinite() && it > 0f }
    }

    /** Filled outline for a pressure stroke (null = draw [path] with an even width, also if it can't be built). */
    val fillPath: Path? by lazy {
        if (pw == null) null else try {
            Pressure.outline(this)?.let { Pressure.toPath(it) }
        } catch (t: Throwable) {
            null
        }
    }

    val minX: Float
    val minY: Float
    val maxX: Float
    val maxY: Float

    init {
        var a = Float.MAX_VALUE
        var b = Float.MAX_VALUE
        var c = -Float.MAX_VALUE
        var d = -Float.MAX_VALUE
        var i = 0
        while (i + 1 < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            if (x < a) a = x
            if (y < b) b = y
            if (x > c) c = x
            if (y > d) d = y
            i += 2
        }
        minX = a; minY = b; maxX = c; maxY = d
    }

    val path: Path by lazy { strokePath(pts, pts.size, poly, style, width) }

    /** True if the circle (x, y, r) touches this stroke. */
    fun hits(x: Float, y: Float, r: Float): Boolean {
        val rr = r + width / 2f
        if (x < minX - rr || x > maxX + rr || y < minY - rr || y > maxY + rr) return false
        val n = pts.size / 2
        if (n == 1) return dist2(x, y, pts[0], pts[1]) <= rr * rr
        for (k in 0 until n - 1) {
            if (segDist2(x, y, pts[2 * k], pts[2 * k + 1], pts[2 * k + 2], pts[2 * k + 3]) <= rr * rr) return true
        }
        return false
    }

    private fun dist2(x: Float, y: Float, ax: Float, ay: Float): Float {
        val dx = x - ax
        val dy = y - ay
        return dx * dx + dy * dy
    }

    private fun segDist2(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 <= 1e-9f) return dist2(x, y, ax, ay)
        val t = (((x - ax) * vx + (y - ay) * vy) / len2).coerceIn(0f, 1f)
        return dist2(x, y, ax + t * vx, ay + t * vy)
    }
}

/** Highlighter strength (0..1) for multiply blending, on screen and in the PDF. */
const val HIGHLIGHT_ALPHA = 0.55f

/** Highlighter strength over pasted images, drawn as a plain see-through colour (screen and PDF). */
const val HIGHLIGHT_OVER_IMAGE_ALPHA = 0.4f

const val LINE_SOLID = 0
const val LINE_DASHED = 1
const val LINE_DOTTED = 2
/** Corrugated: a regular wave running along the stroke. */
const val LINE_WAVY = 3

/**
 * A corrugated stroke of [width] points as a polyline (x0, y0, x1, y1, ...): the stroke's own smooth curve
 * with a regular wave across it. The wave grows with the line width. Same points on screen and in the PDF.
 */
fun wavyPoints(pts: FloatArray, size: Int, poly: Boolean, width: Float): FloatArray {
    val base = buildPath(pts, size, poly)
    val pm = PathMeasure(base, false)
    val len = pm.length
    if (!(len > 0.5f)) return pts.copyOf(size)
    val amp = maxOf(0.65f, width * 0.7f)           // height of the wave (points)
    val waveLen = maxOf(5f, width * 5f)            // length of one wave
    val step = maxOf(0.25f, waveLen / 16f)         // fine enough to look smooth
    val n = minOf(20000, kotlin.math.ceil(len / step).toInt() + 1).coerceAtLeast(2)
    val out = FloatArray(n * 2)
    val pos = FloatArray(2)
    val tan = FloatArray(2)
    for (i in 0 until n) {
        val s = len * i / (n - 1)
        pm.getPosTan(s, pos, tan)
        val off = amp * kotlin.math.sin(2.0 * Math.PI * s / waveLen).toFloat()
        out[2 * i] = pos[0] - tan[1] * off       // across the stroke (its normal)
        out[2 * i + 1] = pos[1] + tan[0] * off
    }
    return out
}

/** The path a stroke is drawn with: its smooth curve, or for corrugated strokes the wavy version of it. */
fun strokePath(pts: FloatArray, size: Int, poly: Boolean, style: Int, width: Float): Path {
    if (style != LINE_WAVY || size < 4) return buildPath(pts, size, poly)
    val w = wavyPoints(pts, size, poly, width)
    val p = Path()
    p.moveTo(w[0], w[1])
    var k = 2
    while (k + 1 < w.size) {
        p.lineTo(w[k], w[k + 1]); k += 2
    }
    return p
}

/** On/off pattern for a stroke style (null = solid). Dots are tiny dashes drawn with round caps. */
fun linePattern(style: Int, width: Float): FloatArray? = when (style) {
    LINE_DASHED -> dashPattern(width)
    LINE_DOTTED -> floatArrayOf(0.01f, maxOf(2.5f, width * 2.2f))
    else -> null
}

/** Dash pattern for a dashed stroke of [width] points: [dash, gap], scaled to the line width. */
fun dashPattern(width: Float): FloatArray = floatArrayOf(maxOf(3f, width * 3f), maxOf(2.5f, width * 2.2f))

/** Smooth path through points using quadratic segments between midpoints. */
fun buildPath(pts: FloatArray, size: Int, poly: Boolean = false): Path {
    val p = Path()
    val n = size / 2
    if (n == 0) return p
    p.moveTo(pts[0], pts[1])
    if (poly && n > 1) {
        for (k in 1 until n) p.lineTo(pts[2 * k], pts[2 * k + 1])
        return p
    }
    if (n == 1) {
        p.lineTo(pts[0] + 0.01f, pts[1])
        return p
    }
    if (n == 2) {
        p.lineTo(pts[2], pts[3])
        return p
    }
    for (k in 1 until n - 1) {
        val x = pts[2 * k]
        val y = pts[2 * k + 1]
        val nx = pts[2 * k + 2]
        val ny = pts[2 * k + 3]
        p.quadTo(x, y, (x + nx) / 2f, (y + ny) / 2f)
    }
    p.lineTo(pts[2 * n - 2], pts[2 * n - 1])
    return p
}

/** One PDF file the document draws pages from (index 0 = the original). Renderer: render thread only. */
class SourcePdf(val name: String, val w: IntArray, val h: IntArray) {
    var pfd: ParcelFileDescriptor? = null
    var renderer: PdfRenderer? = null

    fun close() {
        try { renderer?.close() } catch (ignored: Throwable) {}
        try { pfd?.close() } catch (ignored: Throwable) {}
        renderer = null
        pfd = null
    }
}

data class ImgState(
    val x: Float, val y: Float, val w: Float, val h: Float,
    val cl: Float, val ct: Float, val cr: Float, val cb: Float,
    val locked: Boolean,
    /** In front of the writing (true) or behind it (false, the default). */
    val front: Boolean = false,
)

/**
 * An image placed on a page. [file] is relative to the doc folder (images/<id>.jpg).
 * Position/size in page-local points (same space as strokes). Crop is a normalised
 * rectangle of the full image, so cropping never throws pixels away.
 */
class ImageItem(val file: String, val imgW: Int, val imgH: Int) {
    var x = 0f
    var y = 0f
    var w = 100f
    var h = 100f
    var cl = 0f
    var ct = 0f
    var cr = 1f
    var cb = 1f
    var locked = false
    /** Drawn in front of the writing instead of behind it. */
    var front = false

    fun state() = ImgState(x, y, w, h, cl, ct, cr, cb, locked, front)

    fun set(s: ImgState) {
        x = s.x; y = s.y; w = s.w; h = s.h
        cl = s.cl; ct = s.ct; cr = s.cr; cb = s.cb
        locked = s.locked
        front = s.front
    }

    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h

    /** Width/height ratio of the visible (cropped) part. */
    val cropAspect: Float
        get() {
            val cw = (cr - cl).coerceAtLeast(0.01f) * imgW
            val ch = (cb - ct).coerceAtLeast(0.01f) * imgH
            return cw / ch
        }
}

/**
 * A page as the user sees it. [file] indexes Doc.sources, [src] is the page in that file,
 * or src = -1 for a blank page. [w]/[h] are in points.
 */
const val PAPER_BLANK = 0
const val PAPER_LINED = 1
const val PAPER_GRID = 2

/** Spacing (points) of the dim ruling on lined / grid pages. */
const val RULE_SPACING = 24f
const val GRID_SPACING = 18f

/**
 * Dim ruling for an inserted page of [w] x [h] points, as line segments (x0,y0,x1,y1, ...)
 * in page coordinates (origin top-left).
 */
fun paperLines(paper: Int, w: Float, h: Float): FloatArray {
    val out = ArrayList<Float>()
    when (paper) {
        PAPER_LINED -> {
            var y = RULE_SPACING * 2
            while (y < h - RULE_SPACING / 2) {
                out.add(0f); out.add(y); out.add(w); out.add(y)
                y += RULE_SPACING
            }
        }
        PAPER_GRID -> {
            var x = GRID_SPACING
            while (x < w) {
                out.add(x); out.add(0f); out.add(x); out.add(h); x += GRID_SPACING
            }
            var y = GRID_SPACING
            while (y < h) {
                out.add(0f); out.add(y); out.add(w); out.add(y); y += GRID_SPACING
            }
        }
    }
    return out.toFloatArray()
}

class Page(val file: Int, val src: Int, val w: Int, val h: Int, val paper: Int = PAPER_BLANK) {
    /** This page's own margins (points) and margin paper, or null to use the document's. */
    var mL: Float? = null
    var mR: Float? = null
    var mPaper: Int? = null
    val strokes: MutableList<Stroke> = ArrayList()
    val images: MutableList<ImageItem> = ArrayList()
    val isBlank: Boolean get() = src < 0
}

class EraseItem(val page: Page, val index: Int, val stroke: Stroke)

/** Copied lasso selection. Stroke points and image positions are relative to the selection's top-left. */
class LassoClip(val strokes: List<Stroke>, val images: List<ClipImage>, val w: Float, val h: Float)
class ClipImage(val file: File, val imgW: Int, val imgH: Int, val st: ImgState)

/** A stroke moved by (dx, dy) and/or scaled by [s] around (ax, ay). */
fun Stroke.transformed(dx: Float, dy: Float, s: Float, ax: Float, ay: Float): Stroke {
    val p = FloatArray(pts.size)
    var i = 0
    while (i + 1 < pts.size) {
        p[i] = ax + (pts[i] - ax) * s + dx
        p[i + 1] = ay + (pts[i + 1] - ay) * s + dy
        i += 2
    }
    return Stroke(color, width * s, p, style, highlighter, poly, pw)
}

/**
 * A stroke stretched by [sx] / [sy] around (ax, ay) and moved by (dx, dy). Stretching one way only keeps
 * the line width (a widened box keeps its line thickness); an even scale scales it like [transformed].
 */
fun Stroke.stretched(dx: Float, dy: Float, sx: Float, sy: Float, ax: Float, ay: Float): Stroke {
    if (sx == sy) return transformed(dx, dy, sx, ax, ay)
    val p = FloatArray(pts.size)
    var i = 0
    while (i + 1 < pts.size) {
        p[i] = ax + (pts[i] - ax) * sx + dx
        p[i + 1] = ay + (pts[i + 1] - ay) * sy + dy
        i += 2
    }
    return Stroke(color, width, p, style, highlighter, poly, pw)
}

fun Stroke.recoloured(c: Int): Stroke = Stroke(c, width, pts, style, highlighter, poly, pw)

/** A page's own margins, remembered so "all pages" can be undone. */
class PageOverride(val page: Page, val l: Float?, val r: Float?, val paper: Int?)

/** A point on a page (page-local points), e.g. where the user long-pressed. */
class PlacePoint(val page: Page, val x: Float, val y: Float)

/** Actions reference Page/Image objects (not indices), so inserting/deleting pages never confuses undo. */
sealed class Action {
    class Add(val page: Page, val stroke: Stroke) : Action()
    class Erase(val items: List<EraseItem>) : Action()
    /** Margins for all pages; pages that had their own margins are reset ([cleared], for undo). */
    class Margins(
        val oldL: Float, val oldR: Float, val newL: Float, val newR: Float,
        val cleared: List<PageOverride> = emptyList(),
        val oldPaper: Int = PAPER_BLANK, val newPaper: Int = PAPER_BLANK,
    ) : Action()
    /** Margins for one page only (null = back to the document's margins). */
    class PageMargins(
        val page: Page, val oldL: Float?, val oldR: Float?, val newL: Float?, val newR: Float?,
        val oldPaper: Int?, val newPaper: Int?,
    ) : Action()
    class InsertPages(val index: Int, val pages: List<Page>) : Action()
    class DeletePage(val index: Int, val page: Page) : Action()
    class AddImage(val page: Page, val item: ImageItem, val index: Int) : Action()
    class RemoveImage(val page: Page, val item: ImageItem, val index: Int) : Action()
    class ImageChange(val item: ImageItem, val before: ImgState, val after: ImgState) : Action()
    /** A stroke swapped for a changed copy (moved, scaled, recoloured). */
    class ReplaceStroke(val page: Page, val old: Stroke, val new: Stroke) : Action()
    /** Several actions undone/redone as one step. */
    class Batch(val actions: List<Action>) : Action()
    /** Page dragged to a new position in the page overview. */
    class MovePage(val from: Int, val to: Int, val page: Page) : Action()
    /** Image dragged from one page to another. */
    class MoveImage(
        val item: ImageItem,
        val from: Page, val fromIndex: Int, val before: ImgState,
        val to: Page, val toIndex: Int, val after: ImgState,
    ) : Action()
}

/** An open PDF tab: its working files, pages, annotations, history and viewport. */
class Doc(
    val id: String,
    val dir: File,
    var name: String,
    main: SourcePdf,
) {
    /** PDF files pages come from. Index 0 is the original. Read on the render thread too. */
    val sources: MutableList<SourcePdf> = java.util.concurrent.CopyOnWriteArrayList(listOf(main))

    val sourceFile: File get() = File(dir, "source.pdf")

    val pages: MutableList<Page> = MutableList(main.w.size) { Page(0, it, main.w[it], main.h[it]) }
    val pageCount: Int get() = pages.size

    var marginL = 0f
    var marginR = 0f

    /** Effective margins of a page: its own if set, otherwise the document's. */
    fun leftOf(p: Page): Float = p.mL ?: marginL
    fun rightOf(p: Page): Float = p.mR ?: marginR

    /** Paper in the margin areas (blank / lined / grid). */
    var marginPaper = PAPER_BLANK
    fun marginPaperOf(p: Page): Int = p.mPaper ?: marginPaper

    /** Where "Save" writes (a content Uri string), or null if never saved. */
    var saveUri: String? = null
    /** The file this tab was opened from (for "Save in place"), if known. */
    var originUri: String? = null
    /**
     * Size and last change ("size;time") of [originUri] when Zen last read or wrote it, to notice when the file
     * was changed somewhere else (e.g. on a laptop through Drive). Null if its storage doesn't tell.
     */
    var originStamp: String? = null
    /** A change elsewhere the user was already asked about (not saved). */
    var stampAsked: String? = null

    /** Size and paper of the blank page added most recently (w, h, paper), or null if none was added yet. */
    var lastAdded: IntArray? = null

    /** Zoom fixed: pinching only moves the pages, it doesn't zoom (display only, like dark view). */
    var zoomLocked = false

    /** Dark view (display only: never changes what Save or Export writes). */
    var dark = false

    /** Width (px) of the view the viewport was last fitted to; 0 = unknown. Not saved. */
    var viewW = 0

    // Viewport (scale 0 = not initialised yet)
    var scale = 0f
    var tx = 0f
    var ty = 0f

    /** True when there are changes not yet saved with Save. */
    var unsaved = false
    /** Increments on every edit; used to tell whether a save captured the latest state. */
    var version = 0L

    @Volatile
    var closed = false
    var busy = false

    private val undoStack = ArrayDeque<Action>()
    private val redoStack = ArrayDeque<Action>()
    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    val hasContent: Boolean
        get() = marginL > 0f || marginR > 0f || pages.size != sources[0].w.size ||
            pages.any { it.isBlank || it.file != 0 || it.strokes.isNotEmpty() || it.images.isNotEmpty() || it.mL != null || it.mR != null || it.mPaper != null }

    /** Records an action that has ALREADY been applied. */
    fun push(a: Action) {
        undoStack.addLast(a)
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        touched()
    }

    fun undo(): Action? {
        val a = undoStack.removeLastOrNull() ?: return null
        revert(a)
        redoStack.addLast(a)
        touched()
        return a
    }

    fun redo(): Action? {
        val a = redoStack.removeLastOrNull() ?: return null
        forward(a)
        undoStack.addLast(a)
        touched()
        return a
    }

    private fun touched() {
        unsaved = true
        version++
    }

    private fun forward(a: Action) {
        when (a) {
            is Action.Add -> a.page.strokes.add(a.stroke)
            is Action.Erase -> for (item in a.items) item.page.strokes.remove(item.stroke)
            is Action.Margins -> {
                marginL = a.newL; marginR = a.newR; marginPaper = a.newPaper
                for (c in a.cleared) {
                    c.page.mL = null; c.page.mR = null; c.page.mPaper = null
                }
            }
            is Action.PageMargins -> {
                a.page.mL = a.newL; a.page.mR = a.newR; a.page.mPaper = a.newPaper
            }
            is Action.InsertPages -> pages.addAll(a.index.coerceIn(0, pages.size), a.pages)
            is Action.DeletePage -> pages.remove(a.page)
            is Action.AddImage -> a.page.images.add(a.index.coerceIn(0, a.page.images.size), a.item)
            is Action.RemoveImage -> a.page.images.remove(a.item)
            is Action.ImageChange -> a.item.set(a.after)
            is Action.MoveImage -> {
                a.from.images.remove(a.item)
                a.to.images.add(a.toIndex.coerceIn(0, a.to.images.size), a.item)
                a.item.set(a.after)
            }
            is Action.MovePage -> {
                pages.remove(a.page)
                pages.add(a.to.coerceIn(0, pages.size), a.page)
            }
            is Action.ReplaceStroke -> {
                val i = a.page.strokes.indexOf(a.old)
                if (i >= 0) a.page.strokes[i] = a.new
            }
            is Action.Batch -> for (x in a.actions) forward(x)
        }
    }

    private fun revert(a: Action) {
        when (a) {
            is Action.Add -> a.page.strokes.remove(a.stroke)
            is Action.Erase -> for (k in a.items.indices.reversed()) {
                val item = a.items[k]
                val list = item.page.strokes
                list.add(item.index.coerceIn(0, list.size), item.stroke)
            }
            is Action.Margins -> {
                marginL = a.oldL; marginR = a.oldR; marginPaper = a.oldPaper
                for (c in a.cleared) {
                    c.page.mL = c.l; c.page.mR = c.r; c.page.mPaper = c.paper
                }
            }
            is Action.PageMargins -> {
                a.page.mL = a.oldL; a.page.mR = a.oldR; a.page.mPaper = a.oldPaper
            }
            is Action.InsertPages -> for (p in a.pages) pages.remove(p)
            is Action.DeletePage -> pages.add(a.index.coerceIn(0, pages.size), a.page)
            is Action.AddImage -> a.page.images.remove(a.item)
            is Action.RemoveImage -> a.page.images.add(a.index.coerceIn(0, a.page.images.size), a.item)
            is Action.ImageChange -> a.item.set(a.before)
            is Action.MoveImage -> {
                a.to.images.remove(a.item)
                a.from.images.add(a.fromIndex.coerceIn(0, a.from.images.size), a.item)
                a.item.set(a.before)
            }
            is Action.MovePage -> {
                pages.remove(a.page)
                pages.add(a.from.coerceIn(0, pages.size), a.page)
            }
            is Action.ReplaceStroke -> {
                val i = a.page.strokes.indexOf(a.new)
                if (i >= 0) a.page.strokes[i] = a.old
            }
            is Action.Batch -> for (k in a.actions.indices.reversed()) revert(a.actions[k])
        }
    }

    /** Must run on the render thread. */
    fun closeRenderer() {
        for (s in sources) s.close()
    }
}
