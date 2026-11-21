package com.mabahrami.margin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.SystemClock
import android.util.LruCache
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import java.io.File
import java.util.concurrent.ExecutorService
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Shows one document as a vertical list of pages with blank side margins.
 * Pen (S Pen) draws or erases; fingers pan and pinch-zoom.
 * Images: tap an unlocked image to select it (move/resize/bar); hold any image for its bar.
 */
@SuppressLint("ViewConstructor")
class PdfView(context: Context, private val renderExec: ExecutorService) : View(context) {

    companion object {
        private const val GAP = 16f                 // points between pages
        private const val MAX_SCALE = 8f
        private const val CACHE_BYTES = 256 * 1024 * 1024
        private const val IMG_CACHE_BYTES = 96 * 1024 * 1024
        private const val MAX_BITMAP_SIDE = 4096
        private const val IMG_SCREEN_SIDE = 2048
        private val LEVELS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f, 4f, 5f, 6f)

        private const val MODE_NONE = 0
        private const val MODE_INK = 1
        private const val MODE_ERASE = 2
        private const val MODE_NAV = 3
        private const val MODE_IMG_MOVE = 4
        private const val MODE_IMG_RESIZE = 5
        /** Straight highlighter snaps to horizontal/vertical within this many degrees. */
        private const val MAGNET_DEG = 1.5
    }

    var onChange: ((Doc) -> Unit)? = null
    /** Called when the selected image changes (null = nothing selected). */
    var onSelection: ((ImageItem?) -> Unit)? = null
    /** The page in the middle of the screen changed (for the page overview). */
    var onCurrentPage: ((Int) -> Unit)? = null
    private var lastReportedPage = -1

    /** Where the selected image is on screen (left, top, right, bottom), reported after every redraw. */
    var onSelectionRect: ((Float, Float, Float, Float) -> Unit)? = null
    /** Long-press on a page where there's no image: screen x/y and the point on the page. */
    var onLongPressPage: ((Float, Float, PlacePoint) -> Unit)? = null
    var penColor = Color.BLACK
    var penWidth = 1.5f
    var penStyle = LINE_SOLID
    /** Current tool is the highlighter (penColor/penWidth then hold the highlighter's). */
    var highlighter = false
    /** Straight-line highlighter: the stroke is always a straight line from start to the pen. */
    var hlStraight = false
    var eraserMode = false

    var doc: Doc? = null
        private set

    private val density = resources.displayMetrics.density
    private val edgePx = 16f * density
    /** Space kept free at the top for the floating tool palette. */
    private val topPx = 64f * density
    private val eraserPx = 14f * density
    private val handlePx = 9f * density
    private val grabPx = 26f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    // ---- layout (document coordinates, in points) ----
    private var tops = FloatArray(0)
    private var lefts = FloatArray(0)
    private var contentW = 0f
    private var contentH = 0f

    // ---- page bitmap cache, keyed by (source file, page) ----
    private class PageBmp(val bmp: Bitmap, val scale: Float)

    private val cache = object : LruCache<Int, PageBmp>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: PageBmp): Int = value.bmp.byteCount
    }
    private val pending = HashSet<Int>()
    private var generation = 0

    private fun pageKey(p: Page) = p.file * 100_000 + p.src

    // ---- image bitmap cache, keyed by absolute path ----
    private val imgCache = object : LruCache<String, Bitmap>(IMG_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val imgPending = HashSet<String>()

    // ---- paints ----
    private val pagePaint = Paint().apply { color = Color.WHITE }
    /** Soft shadow under each page, so it reads as paper on the dark background. */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private var shadowForScale = -1f
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33000000            // very dim
        strokeWidth = 0.6f            // points
        pathEffect = DashPathEffect(floatArrayOf(5f, 4f), 0f)
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val placeholderPaint = Paint().apply { color = 0xFFE6E6E6.toInt() }
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val selPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF0A84FF.toInt()
    }
    private val lockedSelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFF9F0A.toInt()
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A84FF.toInt() }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A8A8A.toInt()
        textSize = 18f * density
        textAlign = Paint.Align.CENTER
    }
    private val dst = RectF()
    private val srcRect = Rect()
    private val edgePath = Path()

    // ---- input state ----
    private var mode = MODE_NONE
    private var penPointerId = -1
    private var livePage: Page? = null
    private var livePageIndex = -1
    private var livePts = FloatArray(512)
    private var liveSize = 0
    private var livePath: Path? = null
    private var liveColor = Color.BLACK
    private var liveWidth = 1.5f
    private var liveStyle = LINE_SOLID
    // hold-to-snap: if the pen stays still for a moment, the stroke becomes a clean line/circle/rectangle
    private var livePoly = false
    private var snapped = 0              // 0 = no, 1 = straight line (end follows the pen), 2 = closed shape
    private var holdX = 0f
    private var holdY = 0f
    private val holdMs = 550L
    private val holdSlopPx = 5f * resources.displayMetrics.density
    private val holdRunnable = Runnable { trySnap() }
    private var liveHighlighter = false
    // Highlights are drawn opaque into one layer per page (so overlaps don't get darker),
    // then the whole layer is multiplied onto the page once.
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val hlLayerPaint = Paint().apply {
        blendMode = android.graphics.BlendMode.MULTIPLY
        alpha = (HIGHLIGHT_ALPHA * 255).toInt()
    }
    private val hlBounds = RectF()

    private fun hlStyle(color: Int, width: Float) {
        hlPaint.color = color
        hlPaint.strokeWidth = width
    }
    private val dashCache = HashMap<Long, DashPathEffect>()

    private fun applyStyle(color: Int, width: Float, style: Int) {
        inkPaint.color = color
        inkPaint.strokeWidth = width
        val pattern = linePattern(style, width)
        inkPaint.pathEffect = if (pattern == null) null
        else dashCache.getOrPut(style.toLong() shl 32 or width.toRawBits().toLong().and(0xFFFFFFFFL)) { DashPathEffect(pattern, 0f) }
    }
    private var liveStartTime = 0L
    private val erased = ArrayList<EraseItem>()

    // image selection / manipulation
    private var selPage: Page? = null
    private var selItem: ImageItem? = null
    private var dragBefore: ImgState? = null
    private var dragPointerId = -1
    private var dragLastX = 0f
    private var dragLastY = 0f
    private var resizeCorner = 0      // 0 TL, 1 TR, 2 BL, 3 BR
    private var anchorX = 0f
    private var anchorY = 0f
    private var dragAspect = 1f

    // finger tap / long-press detection
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var movedPastSlop = false
    private var longPressFired = false
    private val longPress = Runnable { onLongPress() }

    private var interacting = false
    private var flinging = false
    private var multiTouch = false
    private var lastFx = 0f
    private var lastFy = 0f
    private var focusX = 0f
    private var focusY = 0f
    private var velocity: VelocityTracker? = null
    private val scroller = OverScroller(context)

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val d = doc ?: return false
                val ns = (d.scale * det.scaleFactor).coerceIn(minScale(), MAX_SCALE)
                val f = ns / d.scale
                d.tx = det.focusX - (det.focusX - d.tx) * f
                d.ty = det.focusY - (det.focusY - d.ty) * f
                d.scale = ns
                return true
            }
        }).apply { isQuickScaleEnabled = false }

    // ======================= public API =======================

    fun setDoc(d: Doc?) {
        cancelLive()
        erased.clear()
        removeCallbacks(longPress)
        scroller.forceFinished(true)
        flinging = false
        interacting = false
        mode = MODE_NONE
        velocity?.recycle()
        velocity = null
        cache.evictAll()
        pending.clear()
        imgCache.evictAll()
        imgPending.clear()
        generation++
        doc = d
        clearSelection()
        relayout()
        if (d != null && d.scale > 0f && width > 0) clamp(d)
        invalidate()
    }

    private val penBusy: Boolean get() = mode != MODE_NONE && mode != MODE_NAV

    val selectedImage: ImageItem? get() = selItem

    fun clearSelection() {
        if (selItem == null && selPage == null) return
        selItem = null
        selPage = null
        onSelection?.invoke(null)
        invalidate()
    }

    private fun select(page: Page, item: ImageItem) {
        selPage = page
        selItem = item
        onSelection?.invoke(item)
        invalidate()
    }

    fun setMargins(l: Float, r: Float) {
        val d = doc ?: return
        val overrides = d.pages.filter { it.mL != null || it.mR != null }.map { PageOverride(it, it.mL, it.mR) }
        if (l == d.marginL && r == d.marginR && overrides.isEmpty()) return
        val a = Action.Margins(d.marginL, d.marginR, l, r, overrides)
        d.marginL = l
        d.marginR = r
        for (o in overrides) {
            o.page.mL = null; o.page.mR = null
        }
        d.push(a)
        afterLayoutChange(d)
        onChange?.invoke(d)
    }

    /** Margins for one page only. */
    fun setPageMargins(index: Int, l: Float, r: Float) {
        val d = doc ?: return
        val page = d.pages.getOrNull(index) ?: return
        if (page.mL == l && page.mR == r) return
        val a = Action.PageMargins(page, page.mL, page.mR, l, r)
        page.mL = l
        page.mR = r
        d.push(a)
        afterLayoutChange(d)
        onChange?.invoke(d)
    }

    /** Index of the page in the middle of the screen. */
    fun currentPageIndex(): Int {
        val d = doc ?: return -1
        if (d.pageCount == 0 || d.scale <= 0f || tops.size != d.pageCount) return 0
        val y = (height / 2f - d.ty) / d.scale
        var best = 0
        for (i in 0 until d.pageCount) if (tops[i] <= y) best = i
        return best
    }

    /** Scrolls so page [index] starts just below the toolbar. */
    fun scrollToPage(index: Int) {
        val d = doc ?: return
        if (index !in 0 until d.pageCount || tops.size != d.pageCount || d.scale <= 0f) return
        scroller.forceFinished(true)
        flinging = false
        d.ty = topPx - tops[index] * d.scale
        clamp(d)
        invalidate()
    }

    /** Inserts a blank page (same size as its neighbour) at [index]. */
    fun insertBlankPage(index: Int) {
        val d = doc ?: return
        val at = index.coerceIn(0, d.pageCount)
        val ref = d.pages.getOrNull(if (at > 0) at - 1 else 0)
        insertPages(at, listOf(Page(0, -1, ref?.w ?: 595, ref?.h ?: 842)))
    }

    fun insertPages(index: Int, pages: List<Page>) {
        val d = doc ?: return
        if (penBusy || pages.isEmpty()) return
        val at = index.coerceIn(0, d.pageCount)
        d.pages.addAll(at, pages)
        d.push(Action.InsertPages(at, pages))
        afterLayoutChange(d)
        onChange?.invoke(d)
    }

    /** The page overview already moved [page] from [from] to [to] in the list: record it and re-layout. */
    fun pageMoved(from: Int, to: Int, page: Page) {
        val d = doc ?: return
        d.push(Action.MovePage(from, to, page))
        afterLayoutChange(d)
        onChange?.invoke(d)
    }

    /** Deletes the page at [index]. Returns false if it's the last remaining page. */
    fun deletePage(index: Int): Boolean {
        val d = doc ?: return false
        if (penBusy || d.pageCount <= 1 || index !in 0 until d.pageCount) return false
        val page = d.pages.removeAt(index)
        if (page === selPage) clearSelection()
        d.push(Action.DeletePage(index, page))
        afterLayoutChange(d)
        onChange?.invoke(d)
        return true
    }

    /**
     * Places a new image on the page in the middle of the screen, centred on screen.
     * If [w]/[h] are > 0 they are used (paste), otherwise it gets 60 % of the page width.
     */
    fun placeImage(item: ImageItem, w: Float = 0f, h: Float = 0f, at: PlacePoint? = null) {
        val d = doc ?: return
        if (penBusy || d.pageCount == 0) return
        val target = if (at != null && d.pages.contains(at.page)) at else null
        val i = if (target != null) d.pages.indexOf(target.page) else currentPageIndex().coerceIn(0, d.pageCount - 1)
        val page = d.pages[i]
        var iw = w
        var ih = h
        if (iw <= 0f || ih <= 0f) {
            iw = page.w * 0.6f
            ih = iw / item.cropAspect
            if (ih > page.h * 0.8f) {
                ih = page.h * 0.8f; iw = ih * item.cropAspect
            }
        }
        item.w = iw
        item.h = ih
        if (target != null) {
            item.x = (target.x - iw / 2f).coerceIn(-d.leftOf(page), max(-d.leftOf(page), page.w + d.rightOf(page) - iw))
            item.y = (target.y - ih / 2f).coerceIn(0f, max(0f, page.h - ih))
        } else {
            val cy = if (d.scale > 0f && tops.size == d.pageCount) (height / 2f - d.ty) / d.scale - tops[i] else page.h / 2f
            item.x = (page.w - iw) / 2f
            item.y = (cy - ih / 2f).coerceIn(0f, max(0f, page.h - ih))
        }
        val idx = page.images.size
        page.images.add(item)
        d.push(Action.AddImage(page, item, idx))
        select(page, item)
        onChange?.invoke(d)
    }

    /** Applies a change to the selected image as one undo step. */
    fun changeSelected(transform: (ImageItem) -> Unit) {
        val d = doc ?: return
        val item = selItem ?: return
        if (penBusy) return
        val before = item.state()
        transform(item)
        val after = item.state()
        if (before != after) {
            d.push(Action.ImageChange(item, before, after))
            onChange?.invoke(d)
        }
        onSelection?.invoke(item)
        invalidate()
    }

    fun deleteSelected() {
        val d = doc ?: return
        val page = selPage ?: return
        val item = selItem ?: return
        if (penBusy) return
        val idx = page.images.indexOf(item)
        if (idx < 0) return
        page.images.removeAt(idx)
        d.push(Action.RemoveImage(page, item, idx))
        clearSelection()
        onChange?.invoke(d)
    }

    fun undo() {
        val d = doc ?: return
        if (penBusy) return
        val a = d.undo() ?: return
        afterAction(d, a)
    }

    fun redo() {
        val d = doc ?: return
        if (penBusy) return
        val a = d.redo() ?: return
        afterAction(d, a)
    }

    private fun afterAction(d: Doc, a: Action) {
        val si = selItem
        if (a is Action.MoveImage && si === a.item) selPage = if (a.from.images.contains(si)) a.from else a.to
        val sp2 = selPage
        if (sp2 != null && si != null && (!d.pages.contains(sp2) || !sp2.images.contains(si))) clearSelection()
        else if (si != null) onSelection?.invoke(si)
        if (a is Action.Margins || a is Action.PageMargins || a is Action.InsertPages || a is Action.DeletePage || a is Action.MovePage) afterLayoutChange(d) else invalidate()
        onChange?.invoke(d)
    }

    fun trimCache() {
        cache.evictAll()
        imgCache.evictAll()
    }

    // ======================= layout =======================

    private fun pageFullW(d: Doc, i: Int) = d.leftOf(d.pages[i]) + d.pages[i].w + d.rightOf(d.pages[i])

    /** Left margin of page [i] (its own or the document's). */
    private fun mlOf(d: Doc, i: Int) = d.leftOf(d.pages[i])

    private fun relayout() {
        val d = doc
        if (d == null) {
            tops = FloatArray(0); lefts = FloatArray(0); contentW = 0f; contentH = 0f
            return
        }
        val n = d.pageCount
        var maxW = 0f
        for (i in 0 until n) maxW = max(maxW, pageFullW(d, i))
        val t = FloatArray(n)
        val l = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            l[i] = (maxW - pageFullW(d, i)) / 2f
            t[i] = y
            y += d.pages[i].h + GAP
        }
        tops = t; lefts = l
        contentW = maxW
        contentH = max(0f, y - GAP)
    }

    private fun afterLayoutChange(d: Doc) {
        // keep the view roughly centred on the same content
        val oldW = contentW
        relayout()
        d.tx -= (contentW - oldW) / 2f * d.scale
        clamp(d)
        invalidate()
    }

    private fun minScale(): Float =
        if (width == 0 || contentW <= 0f) 0.05f else max(0.05f, width / contentW * 0.5f)

    private fun ensureViewport(d: Doc) {
        if (d.scale > 0f || width == 0 || contentW <= 0f) return
        d.scale = ((width - 2 * edgePx) / contentW).coerceIn(0.05f, MAX_SCALE)
        d.tx = edgePx
        d.ty = topPx
        clamp(d)
    }

    private fun clamp(d: Doc) {
        val cw = contentW * d.scale
        val ch = contentH * d.scale
        d.tx = if (cw + 2 * edgePx <= width) (width - cw) / 2f else d.tx.coerceIn(width - cw - edgePx, edgePx)
        d.ty = if (ch + topPx + edgePx <= height) topPx else d.ty.coerceIn(height - ch - edgePx, topPx)
    }

    /** Page index under a document-space point, or -1. */
    private fun pageAt(d: Doc, x: Float, y: Float): Int {
        var lo = 0
        var hi = tops.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (tops[mid] <= y) {
                found = mid; lo = mid + 1
            } else hi = mid - 1
        }
        if (found < 0 || found >= d.pageCount) return -1
        if (y > tops[found] + d.pages[found].h) return -1
        if (x < lefts[found] || x > lefts[found] + pageFullW(d, found)) return -1
        return found
    }

    // screen -> page-local helpers
    private fun localX(d: Doc, i: Int, sx: Float) = (sx - d.tx) / d.scale - (lefts[i] + mlOf(d, i))
    private fun localY(d: Doc, i: Int, sy: Float) = (sy - d.ty) / d.scale - tops[i]

    /** Top-most image under a screen point. */
    private fun imageAt(d: Doc, sx: Float, sy: Float): Pair<Page, ImageItem>? {
        val i = pageAt(d, (sx - d.tx) / d.scale, (sy - d.ty) / d.scale)
        if (i < 0) return null
        val page = d.pages[i]
        val lx = localX(d, i, sx)
        val ly = localY(d, i, sy)
        for (k in page.images.indices.reversed()) {
            val im = page.images[k]
            if (im.contains(lx, ly)) return page to im
        }
        return null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        doc?.let { if (it.scale > 0f) clamp(it) }
        invalidate()
    }

    // ======================= drawing =======================

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF121214.toInt())
        val d = doc
        if (d == null) {
            canvas.drawText("Tap the pencil for a new note, or the folder to open a PDF", width / 2f, height / 2f, hintPaint)
            return
        }
        if (tops.size != d.pageCount) relayout()
        ensureViewport(d)
        if (d.scale <= 0f) return

        val s = d.scale
        val visTop = -d.ty / s
        val visBottom = (height - d.ty) / s

        canvas.save()
        canvas.translate(d.tx, d.ty)
        canvas.scale(s, s)
        for (i in 0 until d.pageCount) {
            val page = d.pages[i]
            val top = tops[i]
            val bottom = top + page.h
            if (bottom < visTop || top > visBottom) continue
            val left = lefts[i]
            val right = left + pageFullW(d, i)
            if (shadowForScale != s) {
                shadowForScale = s
                shadowPaint.setShadowLayer(14f * density / s, 0f, 3f * density / s, 0x66000000)
            }
            canvas.drawRect(left, top, right, bottom, shadowPaint)

            val pl = d.leftOf(page)
            val pr = d.rightOf(page)
            val hasMargins = pl > 0f || pr > 0f
            val ox = left + pl
            if (!page.isBlank) {
                dst.set(ox, top, ox + page.w, bottom)
                val pb = cache.get(pageKey(page))
                if (pb != null) canvas.drawBitmap(pb.bmp, null, dst, bmpPaint)
                val want = bucketFor(page, s)
                if (pb == null || (!interacting && abs(pb.scale - want) > 0.01f)) requestRender(d, page, want)
                if (hasMargins) {
                    // dim dashed outline of the original page
                    edgePath.reset()
                    edgePath.addRect(dst, Path.Direction.CW)
                    canvas.drawPath(edgePath, edgePaint)
                }
            }

            canvas.save()
            canvas.clipRect(left, top, right, bottom)
            canvas.translate(ox, top)
            for (im in page.images) {
                if (mode == MODE_IMG_MOVE && im === selItem) continue
                drawImage(d, canvas, im)
            }
            // highlights first (one multiplied layer: text stays readable, overlaps don't darken), then ink
            val lp = livePath
            val liveHere = mode == MODE_INK && livePage === page && lp != null
            if ((liveHere && liveHighlighter) || page.strokes.any { it.highlighter }) {
                hlBounds.set(-pl, 0f, page.w + pr, page.h.toFloat())
                canvas.saveLayer(hlBounds, hlLayerPaint)
                for (st in page.strokes) {
                    if (!st.highlighter) continue
                    hlStyle(st.color, st.width)
                    canvas.drawPath(st.path, hlPaint)
                }
                if (liveHere && liveHighlighter) {
                    hlStyle(liveColor, liveWidth)
                    canvas.drawPath(lp!!, hlPaint)
                }
                canvas.restore()
            }
            for (st in page.strokes) {
                if (st.highlighter) continue
                applyStyle(st.color, st.width, st.style)
                canvas.drawPath(st.path, inkPaint)
            }
            if (liveHere && !liveHighlighter) {
                applyStyle(liveColor, liveWidth, liveStyle)
                canvas.drawPath(lp!!, inkPaint)
            }
            canvas.restore()
        }
        // selected image frame (and the image itself while dragging) on top of everything, unclipped,
        // so it can be carried across page boundaries
        val sel = selItem
        val sp = selPage
        if (sel != null && sp != null) {
            val si = d.pages.indexOf(sp)
            if (si >= 0 && si < tops.size) {
                canvas.save()
                canvas.translate(lefts[si] + mlOf(d, si), tops[si])
                if (mode == MODE_IMG_MOVE) drawImage(d, canvas, sel)
                drawSelection(canvas, sel, s)
                canvas.restore()
                val ox = d.tx + (lefts[si] + mlOf(d, si) + sel.x) * s
                val oy = d.ty + (tops[si] + sel.y) * s
                onSelectionRect?.invoke(ox, oy, ox + sel.w * s, oy + sel.h * s)
            }
        }
        canvas.restore()
        val cur = currentPageIndex()
        if (cur != lastReportedPage) {
            lastReportedPage = cur
            onCurrentPage?.invoke(cur)
        }
    }

    private fun drawImage(d: Doc, canvas: Canvas, im: ImageItem) {
        dst.set(im.x, im.y, im.x + im.w, im.y + im.h)
        val bmp = imageBitmap(d, im)
        if (bmp == null) {
            canvas.drawRect(dst, placeholderPaint)
            return
        }
        srcRect.set(
            (im.cl * bmp.width).toInt(), (im.ct * bmp.height).toInt(),
            (im.cr * bmp.width).toInt(), (im.cb * bmp.height).toInt(),
        )
        canvas.drawBitmap(bmp, srcRect, dst, bmpPaint)
    }

    private fun drawSelection(canvas: Canvas, im: ImageItem, s: Float) {
        dst.set(im.x, im.y, im.x + im.w, im.y + im.h)
        if (im.locked) {
            lockedSelPaint.strokeWidth = 2f * density / s
            lockedSelPaint.pathEffect = DashPathEffect(floatArrayOf(8f * density / s, 6f * density / s), 0f)
            canvas.drawRect(dst, lockedSelPaint)
            return
        }
        selPaint.strokeWidth = 2f * density / s
        canvas.drawRect(dst, selPaint)
        val r = handlePx / s
        handleRing.strokeWidth = 2f * density / s
        for (k in 0..3) {
            val hx = if (k == 0 || k == 2) im.x else im.x + im.w
            val hy = if (k < 2) im.y else im.y + im.h
            canvas.drawCircle(hx, hy, r, handlePaint)
            canvas.drawCircle(hx, hy, r, handleRing)
        }
    }

    private fun imageBitmap(d: Doc, im: ImageItem): Bitmap? {
        val f = File(d.dir, im.file)
        val key = f.path
        imgCache.get(key)?.let { return it }
        if (imgPending.add(key)) {
            val gen = generation
            try {
                renderExec.execute {
                    val bmp = Pics.decodeForScreen(f, IMG_SCREEN_SIDE)
                    post {
                        if (gen != generation) return@post
                        imgPending.remove(key)
                        if (bmp != null) {
                            imgCache.put(key, bmp)
                            invalidate()
                        }
                    }
                }
            } catch (e: Exception) {
                imgPending.remove(key)
            }
        }
        return null
    }

    private fun bucketFor(page: Page, s: Float): Float {
        var b = LEVELS[LEVELS.size - 1]
        for (l in LEVELS) if (l >= s * 0.98f) {
            b = l; break
        }
        val cap = MAX_BITMAP_SIDE.toFloat() / max(page.w, page.h)
        return min(b, cap)
    }

    private fun requestRender(d: Doc, page: Page, scale: Float) {
        if (page.src < 0) return
        val key = pageKey(page)
        if (!pending.add(key)) return
        val gen = generation
        val w = max(1, (page.w * scale).toInt())
        val h = max(1, (page.h * scale).toInt())
        val file = page.file
        val src = page.src
        try {
            renderExec.execute {
                val bmp = renderPage(d, file, src, w, h)
                post {
                    if (gen != generation) return@post
                    pending.remove(key)
                    if (bmp != null) {
                        cache.put(key, PageBmp(bmp, scale))
                        invalidate()
                    }
                }
            }
        } catch (e: Exception) {
            pending.remove(key) // executor already shut down (activity closing)
        }
    }

    /** Runs on the render thread. */
    private fun renderPage(d: Doc, file: Int, src: Int, w: Int, h: Int): Bitmap? {
        if (d.closed) return null
        val r = d.sources.getOrNull(file)?.renderer ?: return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val pg = r.openPage(src)
            try {
                val m = Matrix()
                m.setScale(w.toFloat() / pg.width, h.toFloat() / pg.height)
                pg.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } finally {
                pg.close()
            }
            bmp
        } catch (t: Throwable) {
            null // includes OutOfMemoryError: the page simply stays blank until retried
        }
    }

    // ======================= input =======================

    private fun computeFocus(e: MotionEvent, skipIndex: Int) {
        var sx = 0f
        var sy = 0f
        var n = 0
        for (k in 0 until e.pointerCount) {
            if (k == skipIndex) continue
            sx += e.getX(k); sy += e.getY(k); n++
        }
        if (n > 0) {
            focusX = sx / n; focusY = sy / n
        }
    }

    private fun isPen(e: MotionEvent, idx: Int): Boolean {
        val t = e.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    /** If the pointer is on the selected (unlocked) image or one of its handles, starts moving/resizing it. */
    private fun tryGrabSelection(d: Doc, e: MotionEvent, idx: Int): Boolean {
        val item = selItem ?: return false
        val page = selPage ?: return false
        if (item.locked) return false
        val i = d.pages.indexOf(page)
        if (i < 0 || tops.size != d.pageCount) return false
        val lx = localX(d, i, e.getX(idx))
        val ly = localY(d, i, e.getY(idx))
        val g = grabPx / d.scale
        var corner = -1
        for (k in 0..3) {
            val hx = if (k == 0 || k == 2) item.x else item.x + item.w
            val hy = if (k < 2) item.y else item.y + item.h
            if (abs(lx - hx) < g && abs(ly - hy) < g) {
                corner = k; break
            }
        }
        if (corner < 0 && !item.contains(lx, ly)) return false
        dragBefore = item.state()
        dragPointerId = e.getPointerId(idx)
        dragLastX = e.getX(idx)
        dragLastY = e.getY(idx)
        if (corner >= 0) {
            mode = MODE_IMG_RESIZE
            resizeCorner = corner
            anchorX = if (corner == 0 || corner == 2) item.x + item.w else item.x
            anchorY = if (corner < 2) item.y + item.h else item.y
            dragAspect = item.w / item.h
        } else {
            mode = MODE_IMG_MOVE
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun dragSelection(d: Doc, e: MotionEvent) {
        val item = selItem ?: return
        val page = selPage ?: return
        val idx = e.findPointerIndex(dragPointerId)
        if (idx < 0) return
        val x = e.getX(idx)
        val y = e.getY(idx)
        if (mode == MODE_IMG_MOVE) {
            item.x += (x - dragLastX) / d.scale
            item.y += (y - dragLastY) / d.scale
        } else {
            val i = d.pages.indexOf(page)
            if (i < 0) return
            val lx = localX(d, i, x)
            val minW = 12f
            var w = max(minW, abs(lx - anchorX))
            var h = w / dragAspect
            if (h < minW) {
                h = minW; w = h * dragAspect
            }
            item.w = w
            item.h = h
            item.x = if (resizeCorner == 0 || resizeCorner == 2) anchorX - w else anchorX
            item.y = if (resizeCorner < 2) anchorY - h else anchorY
        }
        dragLastX = x
        dragLastY = y
        invalidate()
    }

    private fun finishDrag(d: Doc) {
        val item = selItem
        val before = dragBefore
        val from = selPage
        if (mode == MODE_IMG_MOVE && item != null && before != null && from != null && moveToPageUnderCentre(d, item, from, before)) {
            dragBefore = null
            mode = MODE_NONE
            invalidate()
            return
        }
        if (item != null && before != null) {
            val after = item.state()
            if (after != before) {
                d.push(Action.ImageChange(item, before, after))
                onChange?.invoke(d)
            }
        }
        dragBefore = null
        mode = MODE_NONE
        invalidate()
    }

    /** If the dragged image's centre is now over another page, moves it there. Returns true if it moved pages. */
    private fun moveToPageUnderCentre(d: Doc, item: ImageItem, from: Page, before: ImgState): Boolean {
        val i = d.pages.indexOf(from)
        if (i < 0 || tops.size != d.pageCount) return false
        val cx = lefts[i] + mlOf(d, i) + item.x + item.w / 2f
        val cy = tops[i] + item.y + item.h / 2f
        var j = pageAt(d, cx, cy)
        if (j < 0) {
            // centre is in a gap or past the ends: take the nearest page vertically
            var best = i
            var bestDist = Float.MAX_VALUE
            for (k in 0 until d.pageCount) {
                val top = tops[k]
                val bottom = top + d.pages[k].h
                val dist = if (cy < top) top - cy else if (cy > bottom) cy - bottom else 0f
                if (dist < bestDist) {
                    bestDist = dist; best = k
                }
            }
            j = best
        }
        val to = d.pages[j]
        if (j != i) {
            item.x += (lefts[i] + mlOf(d, i)) - (lefts[j] + mlOf(d, j))
            item.y += tops[i] - tops[j]
        }
        // keep the centre on the target page
        item.y = item.y.coerceIn(-item.h / 2f, to.h - item.h / 2f)
        if (j == i) return false
        val fromIndex = from.images.indexOf(item)
        from.images.remove(item)
        to.images.add(item)
        d.push(Action.MoveImage(item, from, fromIndex, before, to, to.images.size - 1, item.state()))
        selPage = to
        onChange?.invoke(d)
        return true
    }

    private fun onLongPress() {
        val d = doc ?: return
        if (mode != MODE_NAV || multiTouch || movedPastSlop) return
        val hit = imageAt(d, downX, downY)
        if (hit != null) {
            longPressFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            // stop panning; the bar appears for this image (locked or not)
            velocity?.recycle(); velocity = null
            interacting = false
            select(hit.first, hit.second)
            return
        }
        val i = pageAt(d, (downX - d.tx) / d.scale, (downY - d.ty) / d.scale)
        if (i < 0) return
        longPressFired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        velocity?.recycle(); velocity = null
        interacting = false
        clearSelection()
        onLongPressPage?.invoke(downX, downY, PlacePoint(d.pages[i], localX(d, i, downX), localY(d, i, downY)))
    }

    private fun onTap(d: Doc, x: Float, y: Float) {
        val hit = imageAt(d, x, y)
        if (hit != null && !hit.second.locked) select(hit.first, hit.second) else clearSelection()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val d = doc ?: return true
        if (d.scale <= 0f) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                flinging = false
                multiTouch = false
                longPressFired = false
                movedPastSlop = false
                downX = e.x; downY = e.y
                downTime = SystemClock.uptimeMillis()
                if (tryGrabSelection(d, e, 0)) {
                    // moving / resizing the selected image
                } else if (isPen(e, 0)) {
                    if (selItem != null) clearSelection()
                    beginPen(d, e, 0)
                } else {
                    mode = MODE_NAV
                    interacting = true
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                    scaleDetector.onTouchEvent(e)
                    computeFocus(e, -1)
                    lastFx = focusX; lastFy = focusY
                    postDelayed(longPress, longPressMs)
                }
                invalidate()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                if (mode == MODE_NAV) {
                    removeCallbacks(longPress)
                    if (isPen(e, idx)) {
                        // pen landed while a palm/finger was panning: switch to the pen
                        velocity?.recycle(); velocity = null
                        interacting = false
                        if (selItem != null) clearSelection()
                        beginPen(d, e, idx)
                        invalidate()
                    } else {
                        multiTouch = true
                        velocity?.addMovement(e)
                        scaleDetector.onTouchEvent(e)
                        computeFocus(e, -1)
                        lastFx = focusX; lastFy = focusY
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> when (mode) {
                MODE_INK, MODE_ERASE -> {
                    val idx = e.findPointerIndex(penPointerId)
                    if (idx >= 0) {
                        val ex = e.getX(idx)
                        val ey = e.getY(idx)
                        if (mode == MODE_INK && snapped != 0) {
                            if (snapped == 1) moveSnappedLineEnd(d, ex, ey)
                        } else {
                            for (h in 0 until e.historySize) {
                                penPoint(d, e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                            }
                            penPoint(d, ex, ey)
                            if (mode == MODE_INK && hypot(ex - holdX, ey - holdY) > holdSlopPx) {
                                // still moving: restart the hold timer from here
                                holdX = ex; holdY = ey
                                removeCallbacks(holdRunnable)
                                postDelayed(holdRunnable, holdMs)
                            }
                        }
                        if (mode == MODE_INK) livePath = buildPath(livePts, liveSize, livePoly)
                        invalidate()
                    }
                }

                MODE_IMG_MOVE, MODE_IMG_RESIZE -> dragSelection(d, e)

                MODE_NAV -> {
                    if (!movedPastSlop && hypot(e.x - downX, e.y - downY) > touchSlop) {
                        movedPastSlop = true
                        removeCallbacks(longPress)
                    }
                    if (!longPressFired) {
                        velocity?.addMovement(e)
                        scaleDetector.onTouchEvent(e)
                        computeFocus(e, -1)
                        d.tx += focusX - lastFx
                        d.ty += focusY - lastFy
                        lastFx = focusX; lastFy = focusY
                        clamp(d)
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val idx = e.actionIndex
                if (mode == MODE_NAV) {
                    velocity?.addMovement(e)
                    scaleDetector.onTouchEvent(e)
                    computeFocus(e, idx)
                    lastFx = focusX; lastFy = focusY
                } else if ((mode == MODE_INK || mode == MODE_ERASE) && e.getPointerId(idx) == penPointerId) {
                    finishPen(d)
                } else if ((mode == MODE_IMG_MOVE || mode == MODE_IMG_RESIZE) && e.getPointerId(idx) == dragPointerId) {
                    finishDrag(d)
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when (mode) {
                    MODE_INK, MODE_ERASE -> finishPen(d)
                    MODE_IMG_MOVE, MODE_IMG_RESIZE -> finishDrag(d)
                    MODE_NAV -> {
                        scaleDetector.onTouchEvent(e)
                        val vt = velocity
                        var flung = false
                        if (vt != null) {
                            vt.addMovement(e)
                            vt.computeCurrentVelocity(1000)
                            val vx = vt.xVelocity
                            val vy = vt.yVelocity
                            if (!multiTouch && !longPressFired && hypot(vx, vy) > 400f * density) {
                                startFling(d, vx, vy); flung = true
                            }
                            vt.recycle()
                        }
                        velocity = null
                        if (!flung) interacting = false
                        val quick = SystemClock.uptimeMillis() - downTime < longPressMs
                        if (!multiTouch && !movedPastSlop && !longPressFired && quick) onTap(d, e.x, e.y)
                        invalidate()
                    }
                }
                mode = MODE_NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                when (mode) {
                    MODE_INK -> cancelLive()
                    MODE_ERASE -> finishPen(d)
                    MODE_IMG_MOVE, MODE_IMG_RESIZE -> finishDrag(d)
                    MODE_NAV -> {
                        velocity?.recycle(); velocity = null
                        interacting = false
                    }
                }
                mode = MODE_NONE
                invalidate()
            }
        }
        return true
    }

    private fun beginPen(d: Doc, e: MotionEvent, idx: Int) {
        penPointerId = e.getPointerId(idx)
        requestUnbufferedDispatch(e) // deliver pen events as soon as they arrive (lower latency)
        parent?.requestDisallowInterceptTouchEvent(true)
        val erase = eraserMode ||
            e.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val x = e.getX(idx)
        val y = e.getY(idx)
        if (erase) {
            mode = MODE_ERASE
            erased.clear()
            eraseAt(d, x, y)
        } else {
            mode = if (startStroke(d, x, y)) MODE_INK else MODE_NONE
        }
    }

    private fun penPoint(d: Doc, x: Float, y: Float) {
        if (mode == MODE_INK) addLivePoint(d, x, y) else eraseAt(d, x, y)
    }

    private fun startStroke(d: Doc, x: Float, y: Float): Boolean {
        val i = pageAt(d, (x - d.tx) / d.scale, (y - d.ty) / d.scale)
        if (i < 0) return false
        livePage = d.pages[i]
        livePageIndex = i
        liveSize = 0
        liveColor = penColor
        liveWidth = penWidth
        liveHighlighter = highlighter
        livePoly = false
        snapped = 0
        holdX = x
        holdY = y
        removeCallbacks(holdRunnable)
        postDelayed(holdRunnable, holdMs)
        liveStyle = if (highlighter) LINE_SOLID else penStyle
        liveStartTime = SystemClock.uptimeMillis()
        addLivePoint(d, x, y)
        if (highlighter && hlStraight && liveSize >= 2) {
            // straight highlighter: a 2-point line whose end follows the pen
            livePts[2] = livePts[0]
            livePts[3] = livePts[1]
            liveSize = 4
            livePoly = true
            snapped = 1
            removeCallbacks(holdRunnable)
            lastMagnet = 0
        }
        livePath = buildPath(livePts, liveSize, livePoly)
        return true
    }

    private fun addLivePoint(d: Doc, x: Float, y: Float) {
        val i = livePageIndex
        if (i < 0 || i >= d.pageCount) return
        val px = (x - d.tx) / d.scale - (lefts[i] + mlOf(d, i))
        val py = (y - d.ty) / d.scale - tops[i]
        if (!px.isFinite() || !py.isFinite()) return
        if (liveSize >= 2) {
            val dx = px - livePts[liveSize - 2]
            val dy = py - livePts[liveSize - 1]
            val minD = 0.6f / d.scale
            if (dx * dx + dy * dy < minD * minD) return
        }
        if (liveSize + 2 > livePts.size) livePts = livePts.copyOf(livePts.size * 2)
        livePts[liveSize++] = px
        livePts[liveSize++] = py
    }

    private fun eraseAt(d: Doc, x: Float, y: Float) {
        val dx = (x - d.tx) / d.scale
        val dy = (y - d.ty) / d.scale
        val i = pageAt(d, dx, dy)
        if (i < 0) return
        val page = d.pages[i]
        val lx = dx - (lefts[i] + mlOf(d, i))
        val ly = dy - tops[i]
        val r = eraserPx / d.scale
        val list = page.strokes
        for (k in list.indices.reversed()) {
            val s = list[k]
            if (s.hits(lx, ly, r)) {
                erased.add(EraseItem(page, k, s))
                list.removeAt(k)
            }
        }
    }

    /** A pen "tap": very short and tiny. Used to select unlocked images with the pen. */
    private fun liveIsTap(d: Doc): Boolean {
        if (SystemClock.uptimeMillis() - liveStartTime > 300) return false
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var k = 0
        while (k + 1 < liveSize) {
            minX = min(minX, livePts[k]); maxX = max(maxX, livePts[k])
            minY = min(minY, livePts[k + 1]); maxY = max(maxY, livePts[k + 1])
            k += 2
        }
        val lim = touchSlop / d.scale
        return maxX - minX < lim && maxY - minY < lim
    }

    private fun finishPen(d: Doc) {
        if (mode == MODE_INK) {
            val page = livePage
            if (page != null && liveSize >= 2 && liveIsTap(d)) {
                // a pen tap on an unlocked image selects it instead of leaving a dot
                val lx = livePts[0]
                val ly = livePts[1]
                val hit = page.images.lastOrNull { !it.locked && it.contains(lx, ly) }
                if (hit != null) {
                    cancelLive()
                    mode = MODE_NONE
                    select(page, hit)
                    return
                }
            }
            if (liveSize >= 2 && page != null && d.pages.contains(page)) {
                val s = Stroke(liveColor, liveWidth, livePts.copyOf(liveSize), liveStyle, liveHighlighter, livePoly)
                page.strokes.add(s)
                d.push(Action.Add(page, s))
                onChange?.invoke(d)
            }
            cancelLive()
        } else if (mode == MODE_ERASE) {
            if (erased.isNotEmpty()) {
                d.push(Action.Erase(ArrayList(erased)))
                erased.clear()
                onChange?.invoke(d)
            }
        }
        mode = MODE_NONE
        invalidate()
    }

    /** Pen held still: replace the rough stroke with the recognised shape. */
    private fun trySnap() {
        val d = doc ?: return
        if (mode != MODE_INK || snapped != 0 || liveSize < 8) return
        val r = Shapes.recognize(livePts, liveSize, 12f * density / d.scale) ?: return
        if (r.pts.size > livePts.size) livePts = FloatArray(r.pts.size * 2)
        System.arraycopy(r.pts, 0, livePts, 0, r.pts.size)
        liveSize = r.pts.size
        livePoly = r.poly
        snapped = if (r.isLine) 1 else 2
        livePath = buildPath(livePts, liveSize, livePoly)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    /** After a line has snapped, its end follows the pen until it's lifted. */
    private fun moveSnappedLineEnd(d: Doc, x: Float, y: Float) {
        val i = livePageIndex
        if (i < 0 || i >= d.pageCount || liveSize < 4) return
        var ex = (x - d.tx) / d.scale - (lefts[i] + mlOf(d, i))
        var ey = (y - d.ty) / d.scale - tops[i]
        if (liveHighlighter && hlStraight) {
            // magnetic: snap to exactly horizontal (0°) or vertical (90°) when close
            val dx = ex - livePts[0]
            val dy = ey - livePts[1]
            val ang = Math.toDegrees(kotlin.math.atan2(abs(dy).toDouble(), abs(dx).toDouble()))
            val magnet = when {
                hypot(dx, dy) < 6f * density / d.scale -> 0
                ang < MAGNET_DEG -> 1
                ang > 90.0 - MAGNET_DEG -> 2
                else -> 0
            }
            if (magnet == 1) ey = livePts[1]
            if (magnet == 2) ex = livePts[0]
            if (magnet != 0 && magnet != lastMagnet) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastMagnet = magnet
        }
        livePts[2] = ex
        livePts[3] = ey
    }

    private var lastMagnet = 0

    private fun cancelLive() {
        removeCallbacks(holdRunnable)
        snapped = 0
        livePoly = false
        liveSize = 0
        livePage = null
        livePageIndex = -1
        livePath = null
    }

    private fun startFling(d: Doc, vx: Float, vy: Float) {
        val cw = contentW * d.scale
        val ch = contentH * d.scale
        val minX: Int
        val maxX: Int
        if (cw + 2 * edgePx <= width) {
            minX = ((width - cw) / 2f).toInt(); maxX = minX
        } else {
            minX = (width - cw - edgePx).toInt(); maxX = edgePx.toInt()
        }
        val minY: Int
        val maxY: Int
        if (ch + topPx + edgePx <= height) {
            minY = topPx.toInt(); maxY = minY
        } else {
            minY = (height - ch - edgePx).toInt(); maxY = topPx.toInt()
        }
        scroller.fling(d.tx.toInt(), d.ty.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
        flinging = true
        postInvalidateOnAnimation()
    }

    override fun computeScroll() {
        val d = doc ?: return
        if (!flinging) return
        if (scroller.computeScrollOffset()) {
            d.tx = scroller.currX.toFloat()
            d.ty = scroller.currY.toFloat()
            postInvalidateOnAnimation()
        } else {
            flinging = false
            interacting = false
            invalidate()
        }
    }
}
