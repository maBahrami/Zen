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
        /** Closest zoom (px per point): about 7x the page width on the Tab S9+. */
        private const val MAX_SCALE = 32f
        /** Memory for the sharp close-up renders of the visible part of zoomed-in pages. */
        private const val DETAIL_BYTES = 80 * 1024 * 1024
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
        private const val MODE_LASSO = 6
        private const val MODE_SEL_MOVE = 7
        private const val MODE_SEL_SCALE = 8
        /** Dragging the fast-scroll handle on the right edge. */
        private const val MODE_FAST = 9

        const val FAST_RIGHT = 0
        const val FAST_LEFT = 1
        const val FAST_BOTH = 2
        /** Straight highlighter snaps to horizontal/vertical within this many degrees. */
        /** Straightened lines within this many degrees of level (0°) or upright (90°) snap to it exactly. */
        private const val MAGNET_DEG = 1.0
        /** Once levelled, a line stays levelled until it leans this far away. */
        private const val MAGNET_RELEASE_DEG = 3.0
    }

    var onChange: ((Doc) -> Unit)? = null
    /** Someone pinched while the zoom is fixed. */
    var onZoomLocked: (() -> Unit)? = null
    private var zoomLockHinted = false
    /** A finger or pen just touched this view (side by side: makes it the active side before anything else). */
    var onTouchStart: ((PdfView) -> Unit)? = null
    /** Side by side: draw a thin blue outline to mark this as the active side. */
    var activeMark = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    // ---- fast scroll: a handle on the right edge, shown while scrolling; drag it to move through pages fast ----
    /** Where the fast-scroll handle is: [FAST_RIGHT], [FAST_LEFT] or [FAST_BOTH] (one on each edge). */
    var fastSide = FAST_RIGHT
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    /** The handle was tapped (not dragged): view x / y of the tap, e.g. to offer moving it to the other side. */
    var onFastScrollTap: ((Float, Float) -> Unit)? = null
    private var fastDownX = 0f
    private var fastDownY = 0f
    private var fastDownAt = 0L
    private var fastMoved = false
    /** The handle being dragged is the left one. */
    private var fastDragLeft = false

    /** Shows the handle for a moment (e.g. after moving it to the other side). */
    fun flashFastScroll() {
        lastScrollAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private var lastDrawTy = Float.NaN
    private var lastScrollAt = 0L
    private var fastDragOffset = 0f
    private val fastShowMs = 1300L
    private val fastFadeMs = 250L
    // (getters: these fields come before `density` is set)
    private val fastThumbH: Float get() = 46f * density
    private val fastThumbW: Float get() = 4f * density
    private val fastGrabW: Float get() = 40f * density
    private val fastRect = RectF()
    private val fastPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fastTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fastGroovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.BUTT }
    private val fastBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEB1C1C1E.toInt() }
    private val fastTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }

    /** The document is long enough to need it. */
    private fun fastScrollUseful(d: Doc): Boolean = contentH * d.scale > height * 1.6f

    private fun fastTrackTop() = topPx + 4f * density
    private fun fastTrackBottom() = height - 12f * density

    /** Scroll position as 0 (top) .. 1 (bottom). */
    private fun scrollFraction(d: Doc): Float {
        val maxTy = topPx
        val minTy = height - contentH * d.scale - edgePx
        if (minTy >= maxTy) return 0f
        return ((maxTy - d.ty) / (maxTy - minTy)).coerceIn(0f, 1f)
    }

    /** Handle position (its top, in px) for the current scroll. */
    private fun fastThumbTop(d: Doc): Float =
        fastTrackTop() + (fastTrackBottom() - fastTrackTop() - fastThumbH) * scrollFraction(d)

    /** How visible the handle is now (0..1): fully while dragging, then it fades a moment after scrolling stops. */
    private fun fastAlpha(): Float {
        if (mode == MODE_FAST) return 1f
        val idle = SystemClock.uptimeMillis() - lastScrollAt
        return when {
            idle < fastShowMs -> 1f
            idle < fastShowMs + fastFadeMs -> 1f - (idle - fastShowMs).toFloat() / fastFadeMs
            else -> 0f
        }
    }

    /** A touch on the visible handle (generously wide) starts dragging it. */
    private fun tryGrabFast(d: Doc, x: Float, y: Float): Boolean {
        if (!fastScrollUseful(d) || fastAlpha() <= 0.1f) return false
        val onLeft = fastSide != FAST_RIGHT && x <= fastGrabW
        val onRight = fastSide != FAST_LEFT && x >= width - fastGrabW
        if (!onLeft && !onRight) return false
        fastDragLeft = onLeft
        val top = fastThumbTop(d)
        if (y < top - 16f * density || y > top + fastThumbH + 16f * density) return false
        fastDragOffset = y - top
        fastDownX = x
        fastDownY = y
        fastDownAt = SystemClock.uptimeMillis()
        fastMoved = false
        mode = MODE_FAST
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
        return true
    }

    private fun dragFast(d: Doc, y: Float) {
        val span = fastTrackBottom() - fastTrackTop() - fastThumbH
        if (span <= 0f) return
        val f = ((y - fastDragOffset - fastTrackTop()) / span).coerceIn(0f, 1f)
        val maxTy = topPx
        val minTy = height - contentH * d.scale - edgePx
        d.ty = maxTy + (minTy - maxTy) * f
        clamp(d)
        lastScrollAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun drawFastScroll(d: Doc, canvas: Canvas) {
        if (!d.ty.isNaN() && d.ty != lastDrawTy) {
            if (!lastDrawTy.isNaN()) lastScrollAt = SystemClock.uptimeMillis()
            lastDrawTy = d.ty
        }
        if (!fastScrollUseful(d)) return
        val a = fastAlpha()
        if (a <= 0f) return
        val dragging = mode == MODE_FAST
        val dark = d.dark
        val top = fastThumbTop(d)
        for (left in booleanArrayOf(true, false)) {
            if (left && fastSide == FAST_RIGHT || !left && fastSide == FAST_LEFT) continue
            val active = dragging && fastDragLeft == left
            val w = if (active) fastThumbW * 1.6f else fastThumbW
            val x = if (left) 8f * density else width - 8f * density - w
            // faint track while dragging, so the whole document's length is visible
            if (active) {
                fastTrackPaint.color = if (dark) 0x1FFFFFFF else 0x14000000
                fastRect.set(x, fastTrackTop(), x + w, fastTrackBottom())
                canvas.drawRoundRect(fastRect, w / 2f, w / 2f, fastTrackPaint)
            }
            fastPaint.color = if (active) 0xFF0A84FF.toInt() else if (dark) 0xB3FFFFFF.toInt() else 0x8C000000.toInt()
            fastPaint.alpha = (Color.alpha(fastPaint.color) * a).toInt()
            fastRect.set(x, top, x + w, top + fastThumbH)
            canvas.drawRoundRect(fastRect, w / 2f, w / 2f, fastPaint)
            // three short horizontal grooves across the handle's middle
            fastGroovePaint.color = if (active || !dark) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
            fastGroovePaint.alpha = (Color.alpha(fastGroovePaint.color) * a).toInt()
            fastGroovePaint.strokeWidth = 1f * density
            val gcy = top + fastThumbH / 2f
            for (k in -1..1) {
                val gy = gcy + k * 3.5f * density
                canvas.drawLine(x + w * 0.2f, gy, x + w * 0.8f, gy, fastGroovePaint)
            }
            if (active) {
                // page number beside the handle being dragged, towards the middle of the view
                val n = currentPageIndex() + 1
                val label = "$n / ${d.pageCount}"
                fastTextPaint.textSize = 14f * density
                val tw = fastTextPaint.measureText(label)
                val bw = tw + 24f * density
                val bh = 30f * density
                val cy = top + fastThumbH / 2f
                if (left) fastRect.set(x + w + 14f * density, cy - bh / 2f, x + w + 14f * density + bw, cy + bh / 2f)
                else fastRect.set(x - 14f * density - bw, cy - bh / 2f, x - 14f * density, cy + bh / 2f)
                canvas.drawRoundRect(fastRect, bh / 2f, bh / 2f, fastBubblePaint)
                canvas.drawText(label, fastRect.centerX(), cy + fastTextPaint.textSize * 0.35f, fastTextPaint)
            }
        }
        if (dragging) {
            // fully shown while dragging
        } else if (a < 1f || SystemClock.uptimeMillis() - lastScrollAt < fastShowMs + fastFadeMs) {
            // keep redrawing until it has faded away
            postInvalidateDelayed(if (a < 1f) 16L else fastShowMs + 5 - (SystemClock.uptimeMillis() - lastScrollAt).coerceAtMost(fastShowMs))
        }
    }

    // ---- eraser circle: shows what the eraser covers, only while the tip is down and erasing ----
    private var eraserVisible = false
    private var eraserX = 0f
    private var eraserY = 0f
    private val eraserFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val eraserRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private fun showEraser(x: Float, y: Float) {
        eraserX = x
        eraserY = y
        eraserVisible = true
        invalidate()
    }

    private fun hideEraser() {
        if (!eraserVisible) return
        eraserVisible = false
        invalidate()
    }

    /** S Pen button clicked while the pen hovers (not touching): view x, y of the tip. */
    var onPenButtonClick: ((Float, Float) -> Unit)? = null

    // S Pen button: a quick click while hovering opens the pen wheel; holding it while writing still erases
    private var penBtnDown = false
    private var penBtnDownAt = 0L
    /** The pen touched the screen during this button press (then it's erasing, not a click). */
    private var penBtnTouched = false

    private fun penButton(down: Boolean, time: Long, x: Float, y: Float) {
        if (down == penBtnDown) return
        penBtnDown = down
        if (down) {
            penBtnDownAt = time
            penBtnTouched = mode != MODE_NONE && mode != MODE_NAV
        } else {
            val click = !penBtnTouched && mode == MODE_NONE && time - penBtnDownAt < 500
            penBtnTouched = false
            if (click) onPenButtonClick?.invoke(x, y)
        }
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        val t = e.getToolType(0)
        if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
            when (e.actionMasked) {
                MotionEvent.ACTION_BUTTON_PRESS -> if (e.actionButton == MotionEvent.BUTTON_STYLUS_PRIMARY) {
                    penButton(true, e.eventTime, e.x, e.y); return true
                }
                MotionEvent.ACTION_BUTTON_RELEASE -> if (e.actionButton == MotionEvent.BUTTON_STYLUS_PRIMARY) {
                    penButton(false, e.eventTime, e.x, e.y); return true
                }
            }
        }
        return super.onGenericMotionEvent(e)
    }

    /** S Pen hovering: the eraser circle shows only while the tip touches the screen, so hovering hides it. */
    override fun onHoverEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT ->
                if (mode != MODE_ERASE) hideEraser()
        }
        // the button state also comes with hover moves (some devices send no separate press/release)
        if (e.actionMasked == MotionEvent.ACTION_HOVER_MOVE && e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) {
            penButton((e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0, e.eventTime, e.x, e.y)
        }
        return super.onHoverEvent(e)
    }

    /** Rounded corners for the whole view (side by side), in px; 0 = square. */
    var cornerRadiusPx = 0f
        set(v) {
            if (field == v) return
            field = v
            if (v > 0f) {
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx)
                    }
                }
                clipToOutline = true
            } else {
                clipToOutline = false
                outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            }
            invalidateOutline()
            invalidate()
        }

    private val activeMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x660A84FF // soft blue: marks the active side without shouting
    }
    /**
     * Pulled past the end and held: the user wants a new page. [atStart] = pulled down at the first page
     * (new page before it); false = pulled up at the last page (new page at the end).
     */
    var onPullAddPage: ((atStart: Boolean, plusX: Float, plusY: Float) -> Unit)? = null
    /** The dim + between two pages was tapped: add a page at this index. */
    var onAddPageBetween: ((Int) -> Unit)? = null

    // ---- dim "+" in the gap between pages (tap: add a page there) ----
    private val gapPlusArm = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x40FFFFFF; strokeCap = Paint.Cap.ROUND
    }

    /** The gap between pages is big enough on screen to show the + (not when zoomed far out). */
    private fun gapPlusShown(d: Doc): Boolean = GAP * d.scale >= 14f * density

    /** Index to insert at if (sx, sy) on screen is on the + after page i (so i + 1), else -1. */
    private fun gapPlusAt(d: Doc, sx: Float, sy: Float): Int {
        if (d.scale <= 0f || !gapPlusShown(d) || tops.size != d.pageCount) return -1
        val s = d.scale
        val cx = d.tx + contentW / 2f * s
        val hit = 20f * density
        if (abs(sx - cx) > hit) return -1
        for (i in 0 until d.pageCount - 1) {
            val cy = d.ty + (tops[i] + d.pages[i].h + GAP / 2f) * s
            if (abs(sy - cy) <= max(hit * 0.8f, GAP * s / 2f) && hypot(sx - cx, sy - cy) <= hit) return i + 1
        }
        return -1
    }

    /** Draws the + in each visible gap (document coordinates). */
    private fun drawGapPluses(d: Doc, canvas: Canvas, s: Float, visTop: Float, visBottom: Float) {
        if (!gapPlusShown(d)) return
        // just a small, thin, faint plus: there when you look for it, not catching the eye
        val arm = 4f * density / s
        val r = 8.5f * density / s
        gapPlusArm.strokeWidth = 1.1f * density / s
        val cx = contentW / 2f
        for (i in 0 until d.pageCount - 1) {
            val cy = tops[i] + d.pages[i].h + GAP / 2f
            if (cy < visTop - r || cy > visBottom + r) continue
            canvas.drawLine(cx - arm, cy, cx + arm, cy, gapPlusArm)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, gapPlusArm)
        }
    }
    /** Where the "+" of the pull indicator was last drawn (view px), so the page window can grow out of it. */
    private var pullPlusX = 0f
    private var pullPlusY = 0f
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
    /** Pen smoothing against hand tremor, applied when a stroke is finished (fixed at PEN_SMOOTH_DP; no setting). */
    private val smoothTolDp = PEN_SMOOTH_DP
    /** Current tool is the highlighter (penColor/penWidth then hold the highlighter's). */
    var highlighter = false
    /** Straight-line highlighter: the stroke is always a straight line from start to the pen. */
    var hlStraight = false
    var eraserMode = false
    /** Lasso tool: the pen draws a selection loop instead of ink. */
    var lassoMode = false
        set(v) {
            field = v
            if (!v) clearLasso()
        }
    /** Where the lasso selection is on screen (l, t, r, b), or null when nothing is selected. */
    var onLassoRect: ((RectF?) -> Unit)? = null

    // ---- lasso selection state (page-local coordinates) ----
    private var lassoPage: Page? = null
    private var lassoPts = FloatArray(256)
    private var lassoSize = 0
    private val lassoPath = Path()
    private var selStrokes: List<Stroke> = emptyList()
    private var selImages: List<ImageItem> = emptyList()
    private val selBox = RectF()
    private var selDx = 0f
    private var selDy = 0f
    private var selScale = 1f
    /** Stretch while dragging a side handle: x (left/right side) or y (top/bottom side). */
    private var selSx = 1f
    private var selSy = 1f
    /** 0 = corner (even scale), 1 = side handle (stretch x), 2 = top/bottom handle (stretch y). */
    private var selAxis = 0
    private var selStartLen = 1f
    private var selAx = 0f
    private var selAy = 0f
    private var selStartDist = 1f
    private var selLastX = 0f
    private var selLastY = 0f
    private var selPointer = -1
    /** The lasso loop and the selection box: thin dashes, black on light paper and light on dark paper. */
    private val lassoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
    }
    private val selMatrix = android.graphics.Matrix()
    private val tmpRect = RectF()
    private val screenRect = RectF()

    val hasLasso: Boolean get() = lassoPage != null && (selStrokes.isNotEmpty() || selImages.isNotEmpty())

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
    /**
     * Each page's own sideways slide (document units). Only pages with margins slide, and only when not
     * every page has margins; a slid page springs back to the centre once it's scrolled off screen.
     */
    private var shifts = FloatArray(0)
    /** Layout follows the original pages (margins hang out to the sides), so margin pages can slide. */
    private var floatLayout = false
    /** Page being slid sideways by the current finger drag, or -1. */
    private var slidePage = -1
    private var slidMoved = false
    private val slideRange = FloatArray(2)

    /** Left edge of page [i] (with its margins), including its slide. */
    private fun pl(i: Int): Float = lefts[i] + (if (i < shifts.size) shifts[i] else 0f)
    private var contentW = 0f
    private var contentH = 0f

    // ---- page bitmap cache, keyed by (source file, page) ----
    private class PageBmp(val bmp: Bitmap, val scale: Float)

    private val cache = object : LruCache<Int, PageBmp>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: PageBmp): Int = value.bmp.byteCount
    }
    private val pending = HashSet<Int>()
    private var generation = 0

    // ---- close-ups: a whole page can only be rendered up to MAX_BITMAP_SIDE px, so when zoomed in further
    // the visible part of each page is rendered separately at the exact zoom and drawn over it ----
    private class Detail(val bmp: Bitmap, val scale: Float, val l: Float, val t: Float, val r: Float, val b: Float)

    private val detailCache = object : LruCache<Int, Detail>(DETAIL_BYTES) {
        override fun sizeOf(key: Int, value: Detail): Int = value.bmp.byteCount
    }
    private val detailPending = HashSet<Int>()
    private val detailDst = RectF()

    /** Cache key of a page's bitmap; light and dark versions are cached separately. */
    private fun pageKey(p: Page, dark: Boolean) = (p.file * 100_000 + p.src) * 2 + (if (dark) 1 else 0)

    // ---- dark view ----
    /** Dark view of the current document (set at the start of every draw). */
    private var darkView = false
    /** Dark style the paints were last set for. */
    private var darkStyleSeen = -1

    /** The dark style changed: drop the pages rendered in the old style and redraw. */
    fun darkStyleChanged() {
        cache.evictAll()
        pending.clear()
        generation++
        flipPaint.colorFilter = android.graphics.ColorMatrixColorFilter(Dark.quickMatrix)
        invalidate()
    }
    /** Shows a page rendered for the other view, colour-flipped, until its proper version is ready. */
    private val flipPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(Dark.quickMatrix)
    }
    /** Where photos sit on each PDF page (page-local points), so dark view can leave them natural. Key: doc id, file, page. */
    private val photoRects = java.util.concurrent.ConcurrentHashMap<String, List<RectF>>()
    // Render thread only: the PDF last opened with PdfBox to find photos (kept open while scrolling).
    private var photoPdfPath: String? = null
    private var photoPdf: com.tom_roush.pdfbox.pdmodel.PDDocument? = null

    // ---- image bitmap cache, keyed by absolute path ----
    private val imgCache = object : LruCache<String, Bitmap>(IMG_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val imgPending = HashSet<String>()

    // ---- paints ----
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val rulePaint = Paint().apply {
        color = 0xFFD6D6DA.toInt()
        strokeWidth = 0.5f
    }
    private val ruleCache = HashMap<Long, FloatArray>()
    /** Cache key for margin ruling (bit 62 keeps it apart from whole-page ruling keys). */
    private fun ruleKey(paper: Int, w: Float, h: Int): Long =
        (1L shl 62) xor (paper.toLong() shl 56) xor (h.toLong() shl 32) xor w.toRawBits().toLong().and(0xFFFFFFFFL)
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
    private val barRect = RectF()
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val emptyLogo = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_launcher_fg)
        ?.mutate()?.apply { setTint(0xFF48484A.toInt()) }

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
    /** Width factor for each live point (liveFact[k] belongs to point k), from the smoothed pen pressure. */
    private var liveFact = FloatArray(256)
    private var liveEma = 0.4f
    /** The live stroke with its pressure widths, rebuilt only when the stroke changed. */
    private var liveInk: Stroke? = null
    /** Pressure of the pen sample being added. */
    private var curPressure = 0.4f
    /** The live stroke comes from the S Pen, so its pressure is real. */
    private var liveHasPressure = false
    private val inkFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    // ---- motion prediction: a short guessed tail drawn ahead of the live line; never stored ----
    private val predictor: PenPredictor? =
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            try { PenPredictor(context) } catch (t: Throwable) { null }
        } else null
    /** Prediction works for the pen of the current stroke. */
    private var predictOn = false
    /** Predicted points (page-local), drawn on from the live line's last point. Cleared on lift. */
    private val tail = FloatArray(16)
    private var tailSize = 0
    private val tailPath = Path()
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

    /** Highlights over pasted images: plain see-through colour (visible on dark and light pictures alike). */
    private val hlOverImagePaint = Paint().apply { alpha = (HIGHLIGHT_OVER_IMAGE_ALPHA * 255).toInt() }
    private val hlImgClip = Path().apply { fillType = Path.FillType.WINDING }

    /** Highlights multiply onto light paper; on dark paper they screen (the dark twin), so they still show. */
    private fun hlBlend(p: Paint) {
        p.blendMode = if (darkView) android.graphics.BlendMode.SCREEN else android.graphics.BlendMode.MULTIPLY
        p.alpha = ((if (darkView) Dark.HIGHLIGHT_ALPHA else HIGHLIGHT_ALPHA) * 255).toInt()
    }
    private val hlBounds = RectF()

    private fun hlStyle(color: Int, width: Float) {
        hlPaint.color = color
        hlPaint.strokeWidth = width
    }
    private val dashCache = HashMap<Long, DashPathEffect>()

    private fun applyStyle(color: Int, width: Float, style: Int) {
        inkPaint.color = if (darkView) Dark.ink(color) else color
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

    // ---- pull past the last page (up) or the first page (down) and hold to add a page ----
    /** 1 = pulling up at the last page, -1 = pulling down at the first page. */
    private var pullDir = 1
    /** How far the finger has pulled past the end (px); the content moves half of it (rubber band). */
    private var pullPx = 0f
    /** The pull has fired for this touch: ignore further pulling until the finger lifts. */
    private var pullDone = false
    /** When the pull first reached the threshold (uptime ms), or 0 while below it. */
    private var pullArmedAt = 0L
    private val pullThresholdPx = 90f * density
    private val pullMaxPx = 150f * density
    private val pullHoldMs = 400L
    private var pullAnim: android.animation.ValueAnimator? = null
    private val pullHold = Runnable { firePull() }
    private val pullCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C2C2E.toInt() }
    private val pullRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val pullPlusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val pullTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAEAEB2.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val pullArc = RectF()

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
                if (d.zoomLocked) {
                    // zoom is fixed: two fingers just move the pages (say so once per gesture)
                    if (!zoomLockHinted && abs(det.scaleFactor - 1f) > 0.04f) {
                        zoomLockHinted = true
                        onZoomLocked?.invoke()
                    }
                    return false // keep measuring from the start, so the hint needs a real pinch
                }
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
        resetPull(animate = false)
        lastDrawTy = Float.NaN
        lastScrollAt = 0L
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
        detailCache.evictAll()
        detailPending.clear()
        imgCache.evictAll()
        imgPending.clear()
        generation++
        // switching to another file: its pages fade in softly instead of popping
        if (doc != null && d != null && doc !== d && isShown) {
            animate().cancel()
            alpha = 0.25f
            animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
        if (doc !== d) {
            photoRects.clear()
            try {
                renderExec.execute { closePhotoPdf() }
            } catch (ignored: Exception) {
            }
        }
        doc = d
        clearSelection()
        clearLasso()
        relayout()
        if (d != null) adaptViewport(d)
        if (d != null && d.scale > 0f && width > 0) clamp(d)
        invalidate()
    }

    /**
     * Keeps the zoom relative to the view's width when the width changes (side by side, dragging the
     * divider, or a document moving to a narrower or wider side), so pages keep filling the view the same way.
     */
    private fun adaptViewport(d: Doc) {
        if (width <= 0) return
        if (d.scale > 0f && d.viewW > 0 && d.viewW != width && contentW > 0f) {
            val ns = (d.scale * width / d.viewW).coerceIn(minScale(), MAX_SCALE)
            val k = ns / d.scale
            d.tx *= k
            d.ty = topPx - (topPx - d.ty) * k // the content at the top stays at the top
            d.scale = ns
        }
        d.viewW = width
    }

    private val penBusy: Boolean get() = mode != MODE_NONE && mode != MODE_NAV

    private val lassoDragging: Boolean get() = mode == MODE_SEL_MOVE || mode == MODE_SEL_SCALE

    fun clearLasso() {
        val had = lassoPage != null
        lassoPage = null
        selStrokes = emptyList()
        selImages = emptyList()
        lassoSize = 0
        if (had) onLassoRect?.invoke(null)
        invalidate()
    }

    /** Box around the selected strokes and images (page-local). */
    private fun recomputeSelBox() {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (st in selStrokes) {
            val hw = st.width / 2f
            l = min(l, st.minX - hw); t = min(t, st.minY - hw)
            r = max(r, st.maxX + hw); b = max(b, st.maxY + hw)
        }
        for (im in selImages) {
            l = min(l, im.x); t = min(t, im.y)
            r = max(r, im.x + im.w); b = max(b, im.y + im.h)
        }
        selBox.set(l, t, r, b)
    }

    /** Ray-casting point-in-polygon test against the lasso loop. */
    private fun insideLasso(x: Float, y: Float): Boolean {
        var inside = false
        val n = lassoSize / 2
        var j = n - 1
        for (i in 0 until n) {
            val xi = lassoPts[2 * i]
            val yi = lassoPts[2 * i + 1]
            val xj = lassoPts[2 * j]
            val yj = lassoPts[2 * j + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi + 1e-6f) + xi) inside = !inside
            j = i
        }
        return inside
    }

    private fun addLassoPoint(d: Doc, sx: Float, sy: Float) {
        val page = lassoPage ?: return
        val i = d.pages.indexOf(page)
        if (i < 0 || tops.size != d.pageCount) return
        if (lassoSize + 2 > lassoPts.size) lassoPts = lassoPts.copyOf(lassoPts.size * 2)
        lassoPts[lassoSize++] = localX(d, i, sx)
        lassoPts[lassoSize++] = localY(d, i, sy)
    }

    private fun beginLasso(d: Doc, e: MotionEvent, idx: Int) {
        clearLasso()
        val x = e.getX(idx)
        val y = e.getY(idx)
        val i = pageAt(d, (x - d.tx) / d.scale, (y - d.ty) / d.scale)
        if (i < 0) {
            mode = MODE_NONE; return
        }
        penPointerId = e.getPointerId(idx)
        requestUnbufferedDispatch(e)
        parent?.requestDisallowInterceptTouchEvent(true)
        lassoPage = d.pages[i]
        lassoSize = 0
        addLassoPoint(d, x, y)
        mode = MODE_LASSO
    }

    /** Lasso closed: select strokes mostly inside the loop and images whose centre is inside. */
    private fun finishLasso() {
        val page = lassoPage
        mode = MODE_NONE
        if (page != null && lassoSize >= 2 && lassoIsTap()) {
            // a tap (no loop drawn): select the one stroke or image under the pen tip
            if (selectAtTap(page, lassoPts[0], lassoPts[1])) return
            clearLasso(); return
        }
        if (page == null || lassoSize < 6) {
            clearLasso(); return
        }
        val strokes = ArrayList<Stroke>()
        for (st in page.strokes) {
            val n = st.pts.size / 2
            var inside = 0
            for (k in 0 until n) if (insideLasso(st.pts[2 * k], st.pts[2 * k + 1])) inside++
            if (n > 0 && inside * 2 >= n) strokes.add(st)
        }
        val images = page.images.filter { insideLasso(it.x + it.w / 2f, it.y + it.h / 2f) }
        lassoSize = 0
        if (strokes.isEmpty() && images.isEmpty()) {
            clearLasso(); return
        }
        selStrokes = strokes
        selImages = images
        recomputeSelBox()
        invalidate()
    }

    /** The lasso stayed within a few dp of where it started: it was a tap, not a loop. */
    private fun lassoIsTap(): Boolean {
        val d = doc ?: return false
        val lim = 10f * density / d.scale
        var k = 0
        while (k + 1 < lassoSize) {
            if (hypot(lassoPts[k] - lassoPts[0], lassoPts[k + 1] - lassoPts[1]) > lim) return false
            k += 2
        }
        return true
    }

    /**
     * Lasso tap at (x, y) (page-local): selects the topmost stroke within reach of the tip (the whole
     * stroke: a line, a box, a word written in one go), or else the image under it. False if nothing's there.
     */
    private fun selectAtTap(page: Page, x: Float, y: Float): Boolean {
        val d = doc ?: return false
        val r = 8f * density / d.scale
        val st = page.strokes.lastOrNull { it.hits(x, y, r) }
        val im = if (st == null) page.images.lastOrNull { !it.locked && it.contains(x, y) } else null
        if (st == null && im == null) return false
        lassoSize = 0
        selStrokes = if (st != null) listOf(st) else emptyList()
        selImages = if (im != null) listOf(im) else emptyList()
        recomputeSelBox()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
        return true
    }

    /** Pen or finger on the selection: drag inside to move, a corner to scale. */
    private fun tryGrabLasso(d: Doc, e: MotionEvent, idx: Int): Boolean {
        if (!hasLasso) return false
        val page = lassoPage ?: return false
        val i = d.pages.indexOf(page)
        if (i < 0 || tops.size != d.pageCount) return false
        val lx = localX(d, i, e.getX(idx))
        val ly = localY(d, i, e.getY(idx))
        val g = grabPx / d.scale
        var corner = -1
        for (k in 0..3) {
            val hx = if (k == 0 || k == 2) selBox.left else selBox.right
            val hy = if (k < 2) selBox.top else selBox.bottom
            if (abs(lx - hx) < g && abs(ly - hy) < g) {
                corner = k; break
            }
        }
        // the middle of each side: stretch one way (4 = left, 5 = right, 6 = top, 7 = bottom)
        var side = -1
        if (corner < 0) {
            val cx = selBox.centerX()
            val cy = selBox.centerY()
            val tall = selBox.height() > 2 * g
            val wide = selBox.width() > 2 * g
            side = when {
                tall && abs(lx - selBox.left) < g && abs(ly - cy) < g -> 4
                tall && abs(lx - selBox.right) < g && abs(ly - cy) < g -> 5
                wide && abs(ly - selBox.top) < g && abs(lx - cx) < g -> 6
                wide && abs(ly - selBox.bottom) < g && abs(lx - cx) < g -> 7
                else -> -1
            }
        }
        val inside = lx >= selBox.left - g / 2 && lx <= selBox.right + g / 2 && ly >= selBox.top - g / 2 && ly <= selBox.bottom + g / 2
        if (corner < 0 && side < 0 && !inside) return false
        selDx = 0f; selDy = 0f; selScale = 1f; selSx = 1f; selSy = 1f
        selPointer = e.getPointerId(idx)
        selLastX = e.getX(idx); selLastY = e.getY(idx)
        if (corner >= 0) {
            selAx = if (corner == 0 || corner == 2) selBox.right else selBox.left
            selAy = if (corner < 2) selBox.bottom else selBox.top
            val cx = if (corner == 0 || corner == 2) selBox.left else selBox.right
            val cy = if (corner < 2) selBox.top else selBox.bottom
            selStartDist = max(1f, hypot(cx - selAx, cy - selAy))
            selAxis = 0
            mode = MODE_SEL_SCALE
        } else if (side >= 0) {
            // anchored at the opposite side
            selAxis = if (side < 6) 1 else 2
            selAx = if (side == 4) selBox.right else selBox.left
            selAy = if (side == 6) selBox.bottom else selBox.top
            selStartLen = max(1f, if (selAxis == 1) selBox.width() else selBox.height())
            mode = MODE_SEL_SCALE
        } else {
            selAx = selBox.left; selAy = selBox.top
            mode = MODE_SEL_MOVE
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun dragLasso(d: Doc, e: MotionEvent) {
        val idx = e.findPointerIndex(selPointer)
        if (idx < 0) return
        val x = e.getX(idx)
        val y = e.getY(idx)
        if (mode == MODE_SEL_MOVE) {
            selDx += (x - selLastX) / d.scale
            selDy += (y - selLastY) / d.scale
            edgeScroll(y)
        } else {
            val i = d.pages.indexOf(lassoPage ?: return)
            if (i < 0) return
            val lx = localX(d, i, x)
            val ly = localY(d, i, y)
            when (selAxis) {
                1 -> { selSx = (abs(lx - selAx) / selStartLen).coerceIn(0.05f, 20f); selSy = 1f }
                2 -> { selSy = (abs(ly - selAy) / selStartLen).coerceIn(0.05f, 20f); selSx = 1f }
                else -> {
                    selScale = (hypot(lx - selAx, ly - selAy) / selStartDist).coerceIn(0.1f, 10f)
                    selSx = selScale; selSy = selScale
                }
            }
        }
        selLastX = x; selLastY = y
        invalidate()
    }

    /** Commits a move/scale as one undo step (strokes replaced by moved copies, images changed). */
    private fun finishLassoDrag(d: Doc) {
        val page = lassoPage
        val moved = selDx != 0f || selDy != 0f || selSx != 1f || selSy != 1f
        mode = MODE_NONE
        if (page == null || !moved) {
            invalidate(); return
        }
        val actions = ArrayList<Action>()
        val newStrokes = ArrayList<Stroke>()
        // where the middle of the selection ends up: if that's on another page, everything moves there
        val i = d.pages.indexOf(page)
        var j = i
        if (i >= 0 && tops.size == d.pageCount) {
            val cx = selAx + (selBox.centerX() - selAx) * selSx + selDx
            val cy = selAy + (selBox.centerY() - selAy) * selSy + selDy
            val k = pageAt(d, pl(i) + mlOf(d, i) + cx, tops[i] + cy)
            if (k >= 0) j = k
        }
        if (i >= 0 && j != i) {
            val dst = d.pages[j]
            // the same spot on screen, in the other page's coordinates
            val ox = (pl(i) + mlOf(d, i)) - (pl(j) + mlOf(d, j))
            val oy = tops[i] - tops[j]
            val erased = ArrayList<EraseItem>()
            for (st in selStrokes) {
                val k = page.strokes.indexOf(st)
                if (k < 0) continue
                page.strokes.removeAt(k)
                erased.add(EraseItem(page, k, st))
                val n = st.stretched(selDx + ox, selDy + oy, selSx, selSy, selAx, selAy)
                dst.strokes.add(n)
                newStrokes.add(n)
            }
            if (erased.isNotEmpty()) actions.add(Action.Erase(erased))
            for (n in newStrokes) actions.add(Action.Add(dst, n))
            for (im in selImages) {
                val fromIndex = page.images.indexOf(im)
                if (fromIndex < 0) continue
                val before = im.state()
                im.x = selAx + (im.x - selAx) * selSx + selDx + ox
                im.y = selAy + (im.y - selAy) * selSy + selDy + oy
                im.w *= selSx
                im.h *= selSy
                page.images.removeAt(fromIndex)
                dst.images.add(im)
                actions.add(Action.MoveImage(im, page, fromIndex, before, dst, dst.images.size - 1, im.state()))
            }
            lassoPage = dst
        } else {
            for (st in selStrokes) {
                val n = st.stretched(selDx, selDy, selSx, selSy, selAx, selAy)
                val k = page.strokes.indexOf(st)
                if (k >= 0) {
                    page.strokes[k] = n
                    actions.add(Action.ReplaceStroke(page, st, n))
                    newStrokes.add(n)
                }
            }
            for (im in selImages) {
                val before = im.state()
                im.x = selAx + (im.x - selAx) * selSx + selDx
                im.y = selAy + (im.y - selAy) * selSy + selDy
                im.w *= selSx
                im.h *= selSy
                actions.add(Action.ImageChange(im, before, im.state()))
            }
        }
        selStrokes = newStrokes
        selDx = 0f; selDy = 0f; selScale = 1f; selSx = 1f; selSy = 1f
        recomputeSelBox()
        if (actions.isNotEmpty()) {
            d.push(Action.Batch(actions))
            onChange?.invoke(d)
        }
        invalidate()
    }

    /** Deletes the selection (one undo step). */
    fun lassoDelete() {
        val d = doc ?: return
        val page = lassoPage ?: return
        if (penBusy) return
        val actions = ArrayList<Action>()
        val erased = ArrayList<EraseItem>()
        for (k in page.strokes.indices.reversed()) {
            val st = page.strokes[k]
            if (selStrokes.any { it === st }) {
                erased.add(EraseItem(page, k, st))
                page.strokes.removeAt(k)
            }
        }
        if (erased.isNotEmpty()) actions.add(Action.Erase(erased))
        for (im in selImages) {
            val k = page.images.indexOf(im)
            if (k >= 0) {
                page.images.removeAt(k)
                actions.add(Action.RemoveImage(page, im, k))
            }
        }
        clearLasso()
        if (actions.isNotEmpty()) {
            d.push(Action.Batch(actions))
            onChange?.invoke(d)
        }
    }

    /** New colour for the selected strokes (images stay as they are). */
    fun lassoRecolor(c: Int) {
        val d = doc ?: return
        val page = lassoPage ?: return
        if (penBusy) return
        val actions = ArrayList<Action>()
        val newStrokes = ArrayList<Stroke>()
        for (st in selStrokes) {
            val k = page.strokes.indexOf(st)
            if (k < 0) continue
            val n = st.recoloured(c)
            page.strokes[k] = n
            newStrokes.add(n)
            actions.add(Action.ReplaceStroke(page, st, n))
        }
        selStrokes = newStrokes
        if (actions.isNotEmpty()) {
            d.push(Action.Batch(actions))
            onChange?.invoke(d)
        }
        invalidate()
    }

    /** The selection as a clip, positions relative to its top-left. Image files are the tab's own. */
    fun lassoCopy(): LassoClip? {
        val d = doc ?: return null
        if (!hasLasso) return null
        val ox = selBox.left
        val oy = selBox.top
        val strokes = selStrokes.map { it.transformed(-ox, -oy, 1f, 0f, 0f) }
        val images = selImages.map {
            ClipImage(File(d.dir, it.file), it.imgW, it.imgH,
                it.state().copy(x = it.x - ox, y = it.y - oy, locked = false))
        }
        return LassoClip(strokes, images, selBox.width(), selBox.height())
    }

    /**
     * Pastes a clip centred at [at] (or the screen centre). [imageFiles] are the clip's images already
     * copied into this tab's folder (relative names, same order). The pasted items become the selection.
     */
    fun lassoPaste(clip: LassoClip, imageFiles: List<String>, at: PlacePoint?) {
        val d = doc ?: return
        if (penBusy || d.pageCount == 0) return
        val target = if (at != null && d.pages.contains(at.page)) at else null
        val i = if (target != null) d.pages.indexOf(target.page) else currentPageIndex().coerceIn(0, d.pageCount - 1)
        val page = d.pages[i]
        val cx = target?.x ?: (page.w / 2f)
        val cy = target?.y ?: if (d.scale > 0f && tops.size == d.pageCount) (height / 2f - d.ty) / d.scale - tops[i] else page.h / 2f
        val ox = cx - clip.w / 2f
        val oy = (cy - clip.h / 2f).coerceIn(0f, max(0f, page.h - clip.h))
        val actions = ArrayList<Action>()
        val strokes = ArrayList<Stroke>()
        for (st in clip.strokes) {
            val n = st.transformed(ox, oy, 1f, 0f, 0f)
            page.strokes.add(n)
            strokes.add(n)
            actions.add(Action.Add(page, n))
        }
        val images = ArrayList<ImageItem>()
        for ((k, ci) in clip.images.withIndex()) {
            val rel = imageFiles.getOrNull(k) ?: continue
            val im = ImageItem(rel, ci.imgW, ci.imgH)
            im.set(ci.st.copy(x = ci.st.x + ox, y = ci.st.y + oy))
            val idx = page.images.size
            page.images.add(im)
            images.add(im)
            actions.add(Action.AddImage(page, im, idx))
        }
        if (actions.isEmpty()) return
        d.push(Action.Batch(actions))
        clearSelection()
        lassoPage = page
        selStrokes = strokes
        selImages = images
        recomputeSelBox()
        onChange?.invoke(d)
        invalidate()
    }

    private fun isSelected(st: Stroke) = selStrokes.any { it === st }
    private fun isSelected(im: ImageItem) = selImages.any { it === im }

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

    fun setMargins(l: Float, r: Float, paper: Int) {
        val d = doc ?: return
        val overrides = d.pages.filter { it.mL != null || it.mR != null || it.mPaper != null }
            .map { PageOverride(it, it.mL, it.mR, it.mPaper) }
        if (l == d.marginL && r == d.marginR && paper == d.marginPaper && overrides.isEmpty()) return
        val a = Action.Margins(d.marginL, d.marginR, l, r, overrides, d.marginPaper, paper)
        d.marginL = l
        d.marginR = r
        d.marginPaper = paper
        for (o in overrides) {
            o.page.mL = null; o.page.mR = null; o.page.mPaper = null
        }
        d.push(a)
        afterLayoutChange(d)
        onChange?.invoke(d)
    }

    /** Margins for one page only. */
    fun setPageMargins(index: Int, l: Float, r: Float, paper: Int) {
        val d = doc ?: return
        val page = d.pages.getOrNull(index) ?: return
        if (page.mL == l && page.mR == r && page.mPaper == paper) return
        val a = Action.PageMargins(page, page.mL, page.mR, l, r, page.mPaper, paper)
        page.mL = l
        page.mR = r
        page.mPaper = paper
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

    /** Inserts a new page of [w] x [h] points with the given paper at [index]. */
    fun insertBlankPage(index: Int, w: Int, h: Int, paper: Int) {
        val d = doc ?: return
        val at = index.coerceIn(0, d.pageCount)
        insertPages(at, listOf(Page(0, -1, w, h, paper)))
    }

    fun insertPages(index: Int, pages: List<Page>) {
        val d = doc ?: return
        if (penBusy || pages.isEmpty()) return
        val at = index.coerceIn(0, d.pageCount)
        d.pages.addAll(at, pages)
        d.push(Action.InsertPages(at, pages))
        pages.lastOrNull { it.isBlank }?.let { d.lastAdded = intArrayOf(it.w, it.h, it.paper) }
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

    /** Deletes several pages as one undo step. Returns false if that would leave no page. */
    fun deletePages(pages: Collection<Page>): Boolean {
        val d = doc ?: return false
        if (penBusy) return false
        val idx = pages.map { d.pages.indexOf(it) }.filter { it >= 0 }.distinct().sortedDescending()
        if (idx.isEmpty() || idx.size >= d.pageCount) return false
        val acts = ArrayList<Action>()
        // from the end, so each recorded index is right when undo puts the pages back in reverse
        for (i in idx) {
            val page = d.pages.removeAt(i)
            if (page === selPage) clearSelection()
            acts.add(Action.DeletePage(i, page))
        }
        d.push(if (acts.size == 1) acts[0] else Action.Batch(acts))
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
        revealAction(d, a, undone = true)
    }

    fun redo() {
        val d = doc ?: return
        if (penBusy) return
        val a = d.redo() ?: return
        afterAction(d, a)
        revealAction(d, a, undone = false)
    }

    private fun afterAction(d: Doc, a: Action) {
        clearLasso()
        val si = selItem
        if (a is Action.MoveImage && si === a.item) selPage = if (a.from.images.contains(si)) a.from else a.to
        val sp2 = selPage
        if (sp2 != null && si != null && (!d.pages.contains(sp2) || !sp2.images.contains(si))) clearSelection()
        else if (si != null) onSelection?.invoke(si)
        if (changesLayout(a)) afterLayoutChange(d) else invalidate()
        onChange?.invoke(d)
    }

    /** Pages added, removed, moved or re-margined (also inside a batch, e.g. several pages deleted at once). */
    private fun changesLayout(a: Action): Boolean = when (a) {
        is Action.Margins, is Action.PageMargins, is Action.InsertPages, is Action.DeletePage, is Action.MovePage -> true
        is Action.Batch -> a.actions.any { changesLayout(it) }
        else -> false
    }

    /** Where an undo / redo happened: the page and the part of it (page-local y range; NaN = whole page). */
    private class Spot(val page: Page, val y0: Float = Float.NaN, val y1: Float = Float.NaN)

    private fun spotOf(d: Doc, a: Action, undone: Boolean): Spot? = when (a) {
        is Action.Add -> Spot(a.page, a.stroke.minY, a.stroke.maxY)
        is Action.ReplaceStroke -> (if (undone) a.old else a.new).let { Spot(a.page, it.minY, it.maxY) }
        is Action.Erase -> a.items.firstOrNull()?.let { Spot(it.page, it.stroke.minY, it.stroke.maxY) }
        is Action.AddImage -> Spot(a.page, a.item.y, a.item.y + a.item.h)
        is Action.RemoveImage -> Spot(a.page, a.item.y, a.item.y + a.item.h)
        is Action.ImageChange -> d.pages.firstOrNull { it.images.contains(a.item) }?.let { Spot(it, a.item.y, a.item.y + a.item.h) }
        is Action.MoveImage -> (if (a.to.images.contains(a.item)) a.to else a.from).let { Spot(it, a.item.y, a.item.y + a.item.h) }
        is Action.PageMargins -> Spot(a.page)
        is Action.MovePage -> Spot(a.page)
        is Action.InsertPages -> d.pages.getOrNull(a.index.coerceIn(0, max(0, d.pageCount - 1)))?.let { Spot(it) }
        is Action.DeletePage -> d.pages.getOrNull(a.index.coerceIn(0, max(0, d.pageCount - 1)))?.let { Spot(it) }
        is Action.Batch -> a.actions.asSequence().mapNotNull { spotOf(d, it, undone) }.firstOrNull()
        is Action.Margins -> null
    }

    /** Undo / redo: if what changed isn't on screen, scroll so it's in the middle. */
    private fun revealAction(d: Doc, a: Action, undone: Boolean) {
        val spot = try { spotOf(d, a, undone) } catch (t: Throwable) { null } ?: return
        if (tops.size != d.pageCount) relayout()
        val i = d.pages.indexOf(spot.page)
        if (i < 0 || d.scale <= 0f || height <= 0) return
        val y0 = if (spot.y0.isFinite()) spot.y0 else 0f
        val y1 = if (spot.y1.isFinite()) spot.y1 else spot.page.h.toFloat()
        val s = d.scale
        val sy0 = d.ty + (tops[i] + y0) * s
        val sy1 = d.ty + (tops[i] + y1) * s
        if (sy1 >= topPx && sy0 <= height - edgePx) return // already (at least partly) on screen
        scroller.forceFinished(true)
        flinging = false
        d.ty = height / 2f - (tops[i] + (y0 + y1) / 2f) * s
        clamp(d)
        invalidate()
    }

    fun trimCache() {
        cache.evictAll()
        detailCache.evictAll()
        imgCache.evictAll()
    }

    /** Side by side: each view gets a share (0..1] of the normal bitmap memory, so two views together never use more. */
    fun setMemoryShare(share: Float) {
        val f = share.coerceIn(0.1f, 1f)
        cache.resize(max(32 * 1024 * 1024, (CACHE_BYTES * f).toInt()))
        imgCache.resize(max(16 * 1024 * 1024, (IMG_CACHE_BYTES * f).toInt()))
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
        var maxOrig = 0f
        var allMargins = n > 0
        for (i in 0 until n) {
            maxW = max(maxW, pageFullW(d, i))
            maxOrig = max(maxOrig, d.pages[i].w.toFloat())
            if (pageFullW(d, i) <= d.pages[i].w + 0.01f) allMargins = false
        }
        floatLayout = !allMargins
        val t = FloatArray(n)
        val l = FloatArray(n)
        var y = 0f
        for (i in 0 until n) {
            // as if there were no margins: the original pages are centred under each other
            l[i] = if (floatLayout) (maxOrig - d.pages[i].w) / 2f - mlOf(d, i) else (maxW - pageFullW(d, i)) / 2f
            t[i] = y
            y += d.pages[i].h + GAP
        }
        tops = t; lefts = l
        shifts = FloatArray(n)
        slidePage = -1
        contentW = if (floatLayout) maxOrig else maxW
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

    /** Farthest zoom: the widest page fills 15% of the view (many pages at once). */
    private fun minScale(): Float =
        if (width == 0 || contentW <= 0f) 0.05f else max(0.05f, width / contentW * 0.15f)

    private fun ensureViewport(d: Doc) {
        if (d.scale > 0f || width == 0 || contentW <= 0f) return
        var fitW = contentW
        if (!floatLayout && d.pageCount > 0) fitW = d.pages.maxOf { it.w }.toFloat()
        d.scale = ((width - 2 * edgePx) / fitW).coerceIn(0.05f, MAX_SCALE)
        d.tx = edgePx
        if (!floatLayout && d.pageCount > 0) {
            // every page has margins: start with the first original page in the middle
            val cx = pl(0) + mlOf(d, 0) + d.pages[0].w / 2f
            d.tx = width / 2f - cx * d.scale
        }
        d.ty = topPx
        d.viewW = width
        clamp(d)
    }

    private fun clamp(d: Doc) {
        val cw = contentW * d.scale
        val ch = contentH * d.scale
        d.tx = if (cw + 2 * edgePx <= width) (width - cw) / 2f else d.tx.coerceIn(width - cw - edgePx, edgePx)
        d.ty = if (ch + topPx + edgePx <= height) topPx else d.ty.coerceIn(height - ch - edgePx, topPx)
    }

    /** How far page [i] may slide (document units) so its margins can be reached; [0, 0] = no sliding. */
    private fun slideLimits(d: Doc, i: Int, out: FloatArray) {
        out[0] = 0f; out[1] = 0f
        if (!floatLayout || i !in 0 until d.pageCount || d.scale <= 0f) return
        if (pageFullW(d, i) <= d.pages[i].w + 0.01f) return // no margins: stays centred
        val viewL = (edgePx - d.tx) / d.scale
        val viewR = (width - edgePx - d.tx) / d.scale
        val l0 = lefts[i]
        val r0 = lefts[i] + pageFullW(d, i)
        out[0] = min(0f, viewR - r0) // slide left until the right margin's edge is in view
        out[1] = max(0f, viewL - l0) // slide right until the left margin's edge is in view
        // and further, until the page's outer edge lines up with the page next to it (below, or above for the last)
        val j = if (i + 1 < d.pageCount) i + 1 else i - 1
        if (j in 0 until d.pageCount) {
            val nl = lefts[j] + mlOf(d, j)            // the neighbour's own page, without its margins
            val nr = nl + d.pages[j].w
            out[1] = max(out[1], nl - l0)
            out[0] = min(out[0], nr - r0)
        }
    }

    /** Slides page [i] by [dxPx] screen pixels as far as it can; returns what's left over (pixels). */
    private fun slideBy(d: Doc, i: Int, dxPx: Float): Float {
        if (i !in shifts.indices) return dxPx
        slideLimits(d, i, slideRange)
        val old = shifts[i]
        val nw = (old + dxPx / d.scale).coerceIn(slideRange[0], slideRange[1])
        shifts[i] = nw
        if (nw != old) slidMoved = true
        return dxPx - (nw - old) * d.scale
    }

    /** Slid pages that are off screen go back to the centre; the others stay within their limits. */
    private fun settleShifts(d: Doc) {
        if (shifts.size != d.pageCount || d.scale <= 0f) return
        val visTop = -d.ty / d.scale
        val visBottom = (height - d.ty) / d.scale
        for (i in shifts.indices) {
            if (shifts[i] == 0f) continue
            val top = tops[i]
            val bottom = top + d.pages[i].h
            if ((bottom < visTop || top > visBottom) && i != slidePage) {
                shifts[i] = 0f
            } else {
                slideLimits(d, i, slideRange)
                shifts[i] = shifts[i].coerceIn(slideRange[0], slideRange[1])
            }
        }
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
        if (x < pl(found) || x > pl(found) + pageFullW(d, found)) return -1
        return found
    }

    // screen -> page-local helpers
    private fun localX(d: Doc, i: Int, sx: Float) = (sx - d.tx) / d.scale - (pl(i) + mlOf(d, i))
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
        doc?.let {
            adaptViewport(it)
            if (it.scale > 0f) clamp(it)
        }
        invalidate()
    }

    // ======================= drawing =======================

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF121214.toInt())
        val d = doc
        val dk = d?.dark == true
        if (dk != darkView || darkStyleSeen != Dark.styleIndex) {
            darkView = dk
            darkStyleSeen = Dark.styleIndex
            shadowPaint.color = if (dk) Dark.PAPER else Color.WHITE
            rulePaint.color = if (dk) Dark.RULE else 0xFFD6D6DA.toInt()
            edgePaint.color = if (dk) Dark.EDGE else 0x33000000
            placeholderPaint.color = if (dk) Dark.PLACEHOLDER else 0xFFE6E6E6.toInt()
            hlBlend(hlLayerPaint)
        }
        if (d == null) {
            // Nothing open: just the Zen ensō, softly, in the middle.
            emptyLogo?.let { logo ->
                val size = (min(width, height) * 0.84f).toInt().coerceAtMost((520 * resources.displayMetrics.density).toInt())
                val l = (width - size) / 2
                val t = (height - size) / 2
                logo.setBounds(l, t, l + size, t + size)
                logo.draw(canvas)
            }
            return
        }
        if (tops.size != d.pageCount) relayout()
        ensureViewport(d)
        if (d.scale <= 0f) return
        settleShifts(d)

        val s = d.scale
        val visTop = -d.ty / s
        val visBottom = (height - d.ty) / s
        // pulling past the last page lifts the content (rubber band) to make room for the + indicator
        val pullOff = pullOffset()
        if (pullOff > 0f) {
            canvas.save()
            canvas.translate(0f, -pullOff * pullDir)
        }

        canvas.save()
        canvas.translate(d.tx, d.ty)
        canvas.scale(s, s)
        for (i in 0 until d.pageCount) {
            val page = d.pages[i]
            val top = tops[i]
            val bottom = top + page.h
            if (bottom < visTop || top > visBottom) continue
            val left = pl(i)
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
                val pb = cache.get(pageKey(page, dk))
                if (pb != null) canvas.drawBitmap(pb.bmp, null, dst, bmpPaint)
                else cache.get(pageKey(page, !dk))?.let { canvas.drawBitmap(it.bmp, null, dst, flipPaint) }
                val want = bucketFor(page, s)
                if (pb == null || (!interacting && abs(pb.scale - want) > 0.01f)) requestRender(d, page, want)
                if (s > want * 1.05f) drawDetail(d, canvas, page, i, ox, top, s, dk)
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
            val mp = d.marginPaperOf(page)
            if (mp != PAPER_BLANK) {
                // dim ruling in the margin areas
                if (pl > 0f) {
                    canvas.save(); canvas.translate(-pl, 0f)
                    canvas.drawLines(ruleCache.getOrPut(ruleKey(mp, pl, page.h)) { paperLines(mp, pl, page.h.toFloat()) }, rulePaint)
                    canvas.restore()
                }
                if (pr > 0f) {
                    canvas.save(); canvas.translate(page.w.toFloat(), 0f)
                    canvas.drawLines(ruleCache.getOrPut(ruleKey(mp, pr, page.h)) { paperLines(mp, pr, page.h.toFloat()) }, rulePaint)
                    canvas.restore()
                }
            }
            if (page.isBlank && page.paper != PAPER_BLANK) {
                val key = (page.paper.toLong() shl 40) or (page.w.toLong() shl 20) or page.h.toLong()
                val lines = ruleCache.getOrPut(key) { paperLines(page.paper, page.w.toFloat(), page.h.toFloat()) }
                canvas.drawLines(lines, rulePaint)
            }
            val hideSel = lassoDragging && page === lassoPage
            for (im in page.images) {
                if (im.front) continue // drawn after the writing
                if (mode == MODE_IMG_MOVE && im === selItem) continue
                if (hideSel && isSelected(im)) continue
                drawImage(d, canvas, im)
            }
            // highlights first (one multiplied layer: text stays readable, overlaps don't darken), then ink
            val lp = livePath
            val liveHere = mode == MODE_INK && livePage === page && lp != null
            if ((liveHere && liveHighlighter) || page.strokes.any { it.highlighter }) {
                hlBounds.set(-pl, 0f, page.w + pr, page.h.toFloat())
                fun highlights() {
                    for (st in page.strokes) {
                        if (!st.highlighter) continue
                        if (hideSel && isSelected(st)) continue
                        hlStyle(st.color, st.width)
                        canvas.drawPath(st.path, hlPaint)
                    }
                    if (liveHere && liveHighlighter) {
                        hlStyle(liveColor, liveWidth)
                        canvas.drawPath(lp!!, hlPaint)
                    }
                }
                val imgs = page.images
                if (imgs.isEmpty()) {
                    canvas.saveLayer(hlBounds, hlLayerPaint)
                    highlights()
                    canvas.restore()
                } else {
                    // on the paper: multiply (text stays crisp); over pasted images: a plain see-through
                    // colour, so it also shows on dark pictures (multiply would vanish on black)
                    canvas.save()
                    for (im in imgs) canvas.clipOutRect(im.x, im.y, im.x + im.w, im.y + im.h)
                    canvas.saveLayer(hlBounds, hlLayerPaint)
                    highlights()
                    canvas.restore()
                    canvas.restore()
                    canvas.save()
                    hlImgClip.rewind()
                    for (im in imgs) hlImgClip.addRect(im.x, im.y, im.x + im.w, im.y + im.h, Path.Direction.CW)
                    canvas.clipPath(hlImgClip)
                    canvas.saveLayer(hlBounds, hlOverImagePaint)
                    highlights()
                    canvas.restore()
                    canvas.restore()
                }
            }
            for (st in page.strokes) {
                if (st.highlighter) continue
                if (hideSel && isSelected(st)) continue
                applyStyle(st.color, st.width, st.style)
                Pressure.draw(canvas, st, inkPaint, inkFill)
            }
            // images brought to the front cover the writing
            for (im in page.images) {
                if (!im.front) continue
                if (mode == MODE_IMG_MOVE && im === selItem) continue
                if (hideSel && isSelected(im)) continue
                drawImage(d, canvas, im)
            }
            if (liveHere && !liveHighlighter) {
                applyStyle(liveColor, liveWidth, liveStyle)
                // with the S Pen the width follows the pressure as you write (the same shape it keeps when lifted)
                val ink = livePressureInk()
                if (ink != null) Pressure.draw(canvas, ink, inkPaint, inkFill) else canvas.drawPath(lp!!, inkPaint)
                // predicted tail: same colour, the width the line has right now
                if (tailSize >= 2 && liveSize >= 2 && snapped == 0 && !livePoly && liveStyle == LINE_SOLID) {
                    tailPath.rewind()
                    tailPath.moveTo(livePts[liveSize - 2], livePts[liveSize - 1])
                    var k = 0
                    while (k + 1 < tailSize) {
                        tailPath.lineTo(tail[k], tail[k + 1]); k += 2
                    }
                    val f = if (ink != null) liveFact[liveSize / 2 - 1] else 1f
                    inkPaint.strokeWidth = liveWidth * f
                    canvas.drawPath(tailPath, inkPaint)
                }
            }
            canvas.restore()
        }
        drawGapPluses(d, canvas, s, visTop, visBottom)
        drawLasso(d, canvas, s)
        // selected image frame (and the image itself while dragging) on top of everything, unclipped,
        // so it can be carried across page boundaries
        val sel = selItem
        val sp = selPage
        if (sel != null && sp != null) {
            val si = d.pages.indexOf(sp)
            if (si >= 0 && si < tops.size) {
                canvas.save()
                canvas.translate(pl(si) + mlOf(d, si), tops[si])
                if (mode == MODE_IMG_MOVE) {
                    // dashed centre guides of the page (only while the image sits on them)
                    if (guideV || guideH) {
                        guidePaint.strokeWidth = 1.2f * density / s
                        guidePaint.pathEffect = DashPathEffect(floatArrayOf(6f * density / s, 4f * density / s), 0f)
                        if (guideV) canvas.drawLine(sp.w / 2f, 0f, sp.w / 2f, sp.h.toFloat(), guidePaint)
                        if (guideH) canvas.drawLine(0f, sp.h / 2f, sp.w.toFloat(), sp.h / 2f, guidePaint)
                    }
                    drawImage(d, canvas, sel)
                }
                drawSelection(canvas, sel, s)
                canvas.restore()
                val ox = d.tx + (pl(si) + mlOf(d, si) + sel.x) * s
                val oy = d.ty + (tops[si] + sel.y) * s
                onSelectionRect?.invoke(ox, oy, ox + sel.w * s, oy + sel.h * s)
            }
        }
        canvas.restore()
        if (pullOff > 0f) {
            canvas.restore()
            drawPullIndicator(canvas, pullOff)
        }
        val cur = currentPageIndex()
        if (cur != lastReportedPage) {
            lastReportedPage = cur
            onCurrentPage?.invoke(cur)
        }
        drawFastScroll(d, canvas)
        if (eraserVisible) {
            // soft disc with a thin ring, readable on light and dark paper
            val dark = doc?.dark == true
            eraserFill.color = if (dark) 0x26FFFFFF else 0x1A000000
            eraserRing.color = if (dark) 0xB3FFFFFF.toInt() else 0x99000000.toInt()
            eraserRing.strokeWidth = 1.2f * density
            canvas.drawCircle(eraserX, eraserY, eraserPx, eraserFill)
            canvas.drawCircle(eraserX, eraserY, eraserPx, eraserRing)
        }
        if (activeMark) {
            val w = 2f * density
            activeMarkPaint.strokeWidth = w
            // follows the rounded corners
            val rr = (cornerRadiusPx - w / 2f).coerceAtLeast(0f)
            canvas.drawRoundRect(w / 2f, w / 2f, width - w / 2f, height - w / 2f, rr, rr, activeMarkPaint)
        }
    }

    /** Lasso loop while drawing; dashed box + handles (and the moving content) once selected. */
    private fun drawLasso(d: Doc, canvas: Canvas, s: Float) {
        val page = lassoPage ?: run { onLassoRectIfChanged(null); return }
        val i = d.pages.indexOf(page)
        if (i < 0 || i >= tops.size) return
        canvas.save()
        canvas.translate(pl(i) + mlOf(d, i), tops[i])
        lassoPaint.strokeWidth = 1f * density / s
        lassoPaint.color = if (d.dark) 0xFFE6E6E6.toInt() else Color.BLACK
        lassoPaint.pathEffect = DashPathEffect(floatArrayOf(6f * density / s, 4f * density / s), 0f)
        if (mode == MODE_LASSO && lassoSize >= 4) {
            lassoPath.reset()
            lassoPath.moveTo(lassoPts[0], lassoPts[1])
            var k = 2
            while (k + 1 < lassoSize) {
                lassoPath.lineTo(lassoPts[k], lassoPts[k + 1]); k += 2
            }
            canvas.drawPath(lassoPath, lassoPaint)
        }
        if (hasLasso) {
            selMatrix.reset()
            selMatrix.postScale(selSx, selSy, selAx, selAy)
            selMatrix.postTranslate(selDx, selDy)
            if (lassoDragging) {
                // the selection moves as one piece while dragging
                canvas.save()
                canvas.concat(selMatrix)
                for (im in selImages) drawImage(d, canvas, im)
                for (st in selStrokes) {
                    if (st.highlighter) {
                        hlStyle(st.color, st.width); hlBlend(hlPaint)
                        canvas.drawPath(st.path, hlPaint)
                        hlPaint.blendMode = null; hlPaint.alpha = 255
                    } else {
                        applyStyle(st.color, st.width, st.style)
                        Pressure.draw(canvas, st, inkPaint, inkFill)
                    }
                }
                canvas.restore()
            }
            tmpRect.set(selBox)
            selMatrix.mapRect(tmpRect)
            canvas.drawRect(tmpRect, lassoPaint)
            val r = handlePx / s
            handleRing.strokeWidth = 2f * density / s
            for (k in 0..3) {
                val hx = if (k == 0 || k == 2) tmpRect.left else tmpRect.right
                val hy = if (k < 2) tmpRect.top else tmpRect.bottom
                canvas.drawCircle(hx, hy, r, handlePaint)
                canvas.drawCircle(hx, hy, r, handleRing)
            }
            // side handles (stretch one way): small bars in the middle of each side, when there's room
            val g2 = 2 * grabPx / s
            val bl = r * 1.3f
            val bt = r * 0.55f
            if (tmpRect.height() > g2) for (x0 in floatArrayOf(tmpRect.left, tmpRect.right)) {
                barRect.set(x0 - bt, tmpRect.centerY() - bl, x0 + bt, tmpRect.centerY() + bl)
                canvas.drawRoundRect(barRect, bt, bt, handlePaint)
                canvas.drawRoundRect(barRect, bt, bt, handleRing)
            }
            if (tmpRect.width() > g2) for (y0 in floatArrayOf(tmpRect.top, tmpRect.bottom)) {
                barRect.set(tmpRect.centerX() - bl, y0 - bt, tmpRect.centerX() + bl, y0 + bt)
                canvas.drawRoundRect(barRect, bt, bt, handlePaint)
                canvas.drawRoundRect(barRect, bt, bt, handleRing)
            }
            val ox = d.tx + (pl(i) + mlOf(d, i)) * s
            val oy = d.ty + tops[i] * s
            screenRect.set(ox + tmpRect.left * s, oy + tmpRect.top * s, ox + tmpRect.right * s, oy + tmpRect.bottom * s)
            onLassoRectIfChanged(screenRect)
        } else onLassoRectIfChanged(null)
        canvas.restore()
    }

    private fun onLassoRectIfChanged(r: RectF?) {
        onLassoRect?.invoke(r)
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
        val dark = d.dark
        val key = pageKey(page, dark)
        if (!pending.add(key)) return
        val gen = generation
        val w = max(1, (page.w * scale).toInt())
        val h = max(1, (page.h * scale).toInt())
        val file = page.file
        val src = page.src
        val pw = page.w
        val ph = page.h
        try {
            renderExec.execute {
                val bmp = renderPage(d, file, src, w, h)
                if (bmp != null && dark) {
                    try {
                        val sx = w.toFloat() / pw
                        val sy = h.toFloat() / ph
                        val keep = photoRectsOf(d, file, src, pw, ph).map { RectF(it.left * sx, it.top * sy, it.right * sx, it.bottom * sy) }
                        Dark.process(bmp, keep)
                    } catch (t: Throwable) {
                        // never fail a page because of dark view: worst case it stays light
                    }
                }
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

    /** Runs on the render thread: where photos are on a PDF page (cached; empty if it can't tell). */
    private fun photoRectsOf(d: Doc, file: Int, src: Int, pw: Int, ph: Int): List<RectF> {
        val key = "${d.id}:$file:$src"
        photoRects[key]?.let { return it }
        if (d.closed) return emptyList()
        val name = d.sources.getOrNull(file)?.name ?: return emptyList()
        val f = File(d.dir, name)
        val rects = try {
            if (photoPdfPath != f.path) {
                closePhotoPdf()
                photoPdfPath = f.path // set first: a file PdfBox can't open is tried only once
                photoPdf = com.tom_roush.pdfbox.pdmodel.PDDocument.load(f,
                    com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
            }
            photoPdf?.let { PdfImages.rects(it, src, pw, ph) } ?: emptyList()
        } catch (t: Throwable) {
            emptyList() // unreadable for PdfBox (or out of memory): darken the whole page
        }
        photoRects[key] = rects
        return rects
    }

    /** Render thread only. */
    private fun closePhotoPdf() {
        try { photoPdf?.close() } catch (ignored: Throwable) {}
        photoPdf = null
        photoPdfPath = null
    }

    /** Closes what the render thread keeps open for dark view (call before the render thread shuts down). */
    fun release() {
        try {
            renderExec.execute { closePhotoPdf() }
        } catch (ignored: Exception) {
            // executor already shut down
        }
    }

    /**
     * Zoomed in beyond what a whole-page render can show sharply: draws (and when the view is still,
     * requests) a render of just the visible part of the page at the current zoom.
     */
    private fun drawDetail(d: Doc, canvas: Canvas, page: Page, i: Int, ox: Float, top: Float, s: Float, dk: Boolean) {
        // visible part of the page, in page points (a little extra around it for small moves)
        val vl = ((-d.tx) / s - ox).coerceIn(0f, page.w.toFloat())
        val vt = ((-d.ty) / s - top).coerceIn(0f, page.h.toFloat())
        val vr = ((width - d.tx) / s - ox).coerceIn(0f, page.w.toFloat())
        val vb = ((height - d.ty) / s - top).coerceIn(0f, page.h.toFloat())
        if (vr <= vl || vb <= vt) return
        val key = pageKey(page, dk)
        val det = detailCache.get(key)
        if (det != null && !det.bmp.isRecycled) {
            detailDst.set(ox + det.l, top + det.t, ox + det.r, top + det.b)
            canvas.drawBitmap(det.bmp, null, detailDst, bmpPaint)
        }
        val fresh = det != null && abs(det.scale - s) < 0.01f &&
            det.l <= vl + 0.5f && det.t <= vt + 0.5f && det.r >= vr - 0.5f && det.b >= vb - 0.5f
        if (fresh || interacting || page.src < 0) return
        val padX = (vr - vl) * 0.15f
        val padY = (vb - vt) * 0.15f
        requestDetail(d, page, s, dk,
            (vl - padX).coerceAtLeast(0f), (vt - padY).coerceAtLeast(0f),
            (vr + padX).coerceAtMost(page.w.toFloat()), (vb + padY).coerceAtMost(page.h.toFloat()))
    }

    private fun requestDetail(d: Doc, page: Page, s: Float, dark: Boolean, l: Float, t: Float, r: Float, b: Float) {
        val key = pageKey(page, dark)
        if (!detailPending.add(key)) return
        val gen = generation
        val w = ((r - l) * s).toInt().coerceIn(1, 6000)
        val h = ((b - t) * s).toInt().coerceIn(1, 6000)
        val file = page.file
        val src = page.src
        val pw = page.w
        val ph = page.h
        try {
            renderExec.execute {
                val bmp: Bitmap? = if (d.closed) null else try {
                    val rnd = d.sources.getOrNull(file)?.renderer
                    if (rnd == null) null else {
                        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        out.eraseColor(Color.WHITE)
                        val pg = rnd.openPage(src)
                        try {
                            val m = Matrix()
                            m.setScale(s * pw / pg.width, s * ph / pg.height)
                            m.postTranslate(-l * s, -t * s)
                            pg.render(out, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } finally {
                            pg.close()
                        }
                        if (dark) {
                            try {
                                val keep = photoRectsOf(d, file, src, pw, ph).map {
                                    RectF((it.left - l) * s, (it.top - t) * s, (it.right - l) * s, (it.bottom - t) * s)
                                }
                                Dark.process(out, keep)
                            } catch (ignored: Throwable) {
                            }
                        }
                        out
                    }
                } catch (t2: Throwable) {
                    null // out of memory etc.: the page just stays at its normal sharpness
                }
                post {
                    detailPending.remove(key)
                    if (gen != generation) return@post
                    if (bmp != null) {
                        detailCache.put(key, Detail(bmp, s, l, t, r, b))
                        invalidate()
                    }
                }
            }
        } catch (e: Exception) {
            detailPending.remove(key)
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
            imgRawX = item.x
            imgRawY = item.y
            guideV = false
            guideH = false
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    // ---- centre guides while moving an image: it snaps to the page's middle, with a dashed line ----
    /** Where the image would be without snapping (page-local points). */
    private var imgRawX = 0f
    private var imgRawY = 0f
    /** Centred left-right (vertical guide) / top-bottom (horizontal guide) right now. */
    private var guideV = false
    private var guideH = false
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFF2D78.toInt()
    }

    /** The dragged image where the finger has it, snapped to the page's middle when close (with the guides). */
    private fun placeDraggedImage(d: Doc) {
        val item = selItem ?: return
        val page = selPage ?: return
        item.x = imgRawX
        item.y = imgRawY
        val snap = 8f * density / d.scale
        val wasV = guideV
        val wasH = guideH
        val midX = page.w / 2f - item.w / 2f
        val midY = page.h / 2f - item.h / 2f
        guideV = abs(imgRawX - midX) < snap
        guideH = abs(imgRawY - midY) < snap
        if (guideV) item.x = midX
        if (guideH) item.y = midY
        if ((guideV && !wasV) || (guideH && !wasH)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    // ---- scrolling while dragging near the top or bottom edge ----
    /** Pixels per frame the pages scroll (> 0: towards later pages), 0 = still. */
    private var edgeSpeed = 0f
    private var edgeRunning = false

    /** Finger at [y] while dragging: near the top or bottom edge, the pages scroll (faster closer to the edge). */
    private fun edgeScroll(y: Float) {
        val zone = 64f * density
        val maxV = 22f * density
        edgeSpeed = when {
            y > height - zone -> ((y - (height - zone)) / zone).coerceIn(0f, 1f) * maxV
            y < zone -> -((zone - y) / zone).coerceIn(0f, 1f) * maxV
            else -> 0f
        }
        if (edgeSpeed != 0f && !edgeRunning) {
            edgeRunning = true
            postOnAnimation(edgeTick)
        }
    }

    private val edgeTick: Runnable = object : Runnable {
        override fun run() {
            val d = doc
            if (d == null || edgeSpeed == 0f || (mode != MODE_IMG_MOVE && mode != MODE_SEL_MOVE)) {
                edgeRunning = false
                return
            }
            val before = d.ty
            d.ty -= edgeSpeed
            clamp(d)
            val moved = d.ty - before
            if (moved != 0f) {
                // what's being dragged stays under the finger while the pages move
                val docDy = -moved / d.scale
                if (mode == MODE_IMG_MOVE) {
                    imgRawY += docDy
                    placeDraggedImage(d)
                } else selDy += docDy
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    private fun dragSelection(d: Doc, e: MotionEvent) {
        val item = selItem ?: return
        val page = selPage ?: return
        val idx = e.findPointerIndex(dragPointerId)
        if (idx < 0) return
        val x = e.getX(idx)
        val y = e.getY(idx)
        if (mode == MODE_IMG_MOVE) {
            imgRawX += (x - dragLastX) / d.scale
            imgRawY += (y - dragLastY) / d.scale
            placeDraggedImage(d)
            edgeScroll(y)
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
        val cx = pl(i) + mlOf(d, i) + item.x + item.w / 2f
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
            item.x += (pl(i) + mlOf(d, i)) - (pl(j) + mlOf(d, j))
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
        if (hasLasso) {
            clearLasso(); return
        }
        val at = gapPlusAt(d, x, y)
        if (at >= 0) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onAddPageBetween?.invoke(at)
            return
        }
        val hit = imageAt(d, x, y)
        if (hit != null && !hit.second.locked) select(hit.first, hit.second) else clearSelection()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val handled = handleTouch(e)
        // the eraser circle stays only while erasing
        if (mode != MODE_ERASE && eraserVisible &&
            (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL || e.actionMasked == MotionEvent.ACTION_POINTER_UP)) hideEraser()
        return handled
    }

    private fun handleTouch(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) onTouchStart?.invoke(this)
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
                zoomLockHinted = false
                if (tryGrabFast(d, e.x, e.y)) {
                    // dragging the fast-scroll handle
                } else if (tryGrabLasso(d, e, 0)) {
                    // moving / scaling the lasso selection
                } else if (lassoMode && isPen(e, 0)) {
                    beginLasso(d, e, 0)
                } else if (tryGrabSelection(d, e, 0)) {
                    // moving / resizing the selected image
                } else if (isPen(e, 0)) {
                    if (selItem != null) clearSelection()
                    if (hasLasso) clearLasso()
                    beginPen(d, e, 0)
                } else {
                    mode = MODE_NAV
                    slidMoved = false
                    slidePage = if (floatLayout) pageAt(d, (e.x - d.tx) / d.scale, (e.y - d.ty) / d.scale) else -1
                    if (slidePage >= 0 && pageFullW(d, slidePage) <= d.pages[slidePage].w + 0.01f) slidePage = -1
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
                        resetPull(animate = true)
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
                                curPressure = e.getHistoricalPressure(idx, h)
                                penPoint(d, e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                            }
                            curPressure = e.getPressure(idx)
                            penPoint(d, ex, ey)
                            if (mode == MODE_INK) updateTail(d, e)
                            if (mode == MODE_INK && hypot(ex - holdX, ey - holdY) > holdSlopPx) {
                                // still moving: restart the hold timer from here
                                holdX = ex; holdY = ey
                                removeCallbacks(holdRunnable)
                                postDelayed(holdRunnable, holdMs)
                            }
                        }
                        if (mode == MODE_INK) livePath = strokePath(livePts, liveSize, livePoly, liveStyle, liveWidth); liveInk = null
                        if (mode == MODE_ERASE) showEraser(ex, ey)
                        invalidate()
                    }
                }

                MODE_IMG_MOVE, MODE_IMG_RESIZE -> dragSelection(d, e)

                MODE_LASSO -> {
                    val idx = e.findPointerIndex(penPointerId)
                    if (idx >= 0) {
                        for (h in 0 until e.historySize) addLassoPoint(d, e.getHistoricalX(idx, h), e.getHistoricalY(idx, h))
                        addLassoPoint(d, e.getX(idx), e.getY(idx))
                        invalidate()
                    }
                }

                MODE_SEL_MOVE, MODE_SEL_SCALE -> dragLasso(d, e)

                MODE_FAST -> {
                    // a tap stays a tap; only a real drag moves the document
                    if (!fastMoved && hypot(e.x - fastDownX, e.y - fastDownY) > touchSlop) fastMoved = true
                    if (fastMoved) dragFast(d, e.y)
                }

                MODE_NAV -> {
                    if (!movedPastSlop && hypot(e.x - downX, e.y - downY) > touchSlop) {
                        movedPastSlop = true
                        removeCallbacks(longPress)
                    }
                    if (!longPressFired) {
                        velocity?.addMovement(e)
                        scaleDetector.onTouchEvent(e)
                        computeFocus(e, -1)
                        var dxPx = focusX - lastFx
                        if (multiTouch) slidePage = -1 // pinching: no page slide
                        if (slidePage >= 0) dxPx = slideBy(d, slidePage, dxPx)
                        d.tx += dxPx
                        val dy = focusY - lastFy
                        if (pullPx > 0f && !multiTouch && !pullDone) {
                            // already pulling past the end: the finger changes the pull first
                            setPull(pullPx - dy * pullDir)
                        } else {
                            val wanted = d.ty + dy
                            d.ty = wanted
                            clamp(d)
                            // the end stopped the scroll while pulling up: start pulling
                            if (!multiTouch && !pullDone && d.pageCount > 0) {
                                if (dy < 0f && d.ty > wanted + 0.5f) {
                                    pullDir = 1; setPull(d.ty - wanted)          // past the last page
                                } else if (dy > 0f && d.ty < wanted - 0.5f) {
                                    pullDir = -1; setPull(wanted - d.ty)         // past the first page
                                }
                            }
                        }
                        if (multiTouch && pullPx > 0f) resetPull(animate = true)
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
                } else if (mode == MODE_LASSO && e.getPointerId(idx) == penPointerId) {
                    finishLasso()
                } else if (lassoDragging && e.getPointerId(idx) == selPointer) {
                    finishLassoDrag(d)
                }
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                when (mode) {
                    MODE_INK, MODE_ERASE -> finishPen(d)
                    MODE_IMG_MOVE, MODE_IMG_RESIZE -> finishDrag(d)
                    MODE_LASSO -> finishLasso()
                    MODE_SEL_MOVE, MODE_SEL_SCALE -> finishLassoDrag(d)
                    MODE_FAST -> {
                        lastScrollAt = SystemClock.uptimeMillis() // stays a moment, then fades
                        invalidate()
                        if (!fastMoved && SystemClock.uptimeMillis() - fastDownAt < longPressMs) onFastScrollTap?.invoke(e.x, e.y)
                    }
                    MODE_NAV -> {
                        scaleDetector.onTouchEvent(e)
                        val vt = velocity
                        var flung = false
                        if (vt != null) {
                            vt.addMovement(e)
                            vt.computeCurrentVelocity(1000)
                            val vx = vt.xVelocity
                            val vy = vt.yVelocity
                            val fx = if (slidMoved) 0f else vx
                            slidePage = -1 // the slid page stays put until it's scrolled off screen
                            if (!multiTouch && !longPressFired && pullPx <= 0f && hypot(fx, vy) > 400f * density) {
                                startFling(d, fx, vy); flung = true
                            }
                            vt.recycle()
                        }
                        velocity = null
                        if (!flung) interacting = false
                        val quick = SystemClock.uptimeMillis() - downTime < longPressMs
                        if (!multiTouch && !movedPastSlop && !longPressFired && quick) onTap(d, e.x, e.y)
                        resetPull(animate = true)
                        pullDone = false
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
                    MODE_LASSO -> clearLasso()
                    MODE_SEL_MOVE, MODE_SEL_SCALE -> finishLassoDrag(d)
                    MODE_FAST -> {
                        lastScrollAt = SystemClock.uptimeMillis() // stays a moment, then fades
                        invalidate()
                    }
                    MODE_NAV -> {
                        velocity?.recycle(); velocity = null
                        interacting = false
                        resetPull(animate = true)
                        pullDone = false
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
        if (penBtnDown || (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0) {
            penBtnDown = true
            penBtnTouched = true
        }
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
            showEraser(x, y)
        } else {
            curPressure = e.getPressure(idx)
            liveHasPressure = e.getToolType(idx) == MotionEvent.TOOL_TYPE_STYLUS
            tailSize = 0
            predictOn = false
            if (liveHasPressure) predictor?.let { pr ->
                pr.record(e)
                predictOn = pr.available(e)
            }
            mode = if (startStroke(d, x, y)) MODE_INK else MODE_NONE
            if (mode == MODE_NONE) {
                // pen down in the gap between pages: the + adds a page there
                val at = gapPlusAt(d, x, y)
                if (at >= 0) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onAddPageBetween?.invoke(at)
                }
            }
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
        livePath = strokePath(livePts, liveSize, livePoly, liveStyle, liveWidth); liveInk = null
        return true
    }

    /** True if [sm] (the smoothed stroke) starts and ends where [raw] does and isn't noticeably shorter. */
    private fun smoothedIntact(raw: FloatArray, rawSize: Int, sm: FloatArray, tol: Float): Boolean {
        if (sm.size < 4 || rawSize < 4) return false
        val e = max(tol * 2f, 0.05f)
        if (hypot(sm[0] - raw[0], sm[1] - raw[1]) > e) return false
        if (hypot(sm[sm.size - 2] - raw[rawSize - 2], sm[sm.size - 1] - raw[rawSize - 1]) > e) return false
        var rl = 0f
        var k = 2
        while (k + 1 < rawSize) { rl += hypot(raw[k] - raw[k - 2], raw[k + 1] - raw[k - 1]); k += 2 }
        var sl = 0f
        k = 2
        while (k + 1 < sm.size) { sl += hypot(sm[k] - sm[k - 2], sm[k + 1] - sm[k - 1]); k += 2 }
        // smoothing only takes out jitter, which shortens a stroke a little; never by much
        return sl >= rl * 0.85f - e
    }

    /**
     * Asks the predictor where the pen is heading and keeps a short tail (at most ~24 dp on screen) to draw
     * ahead of the line. No tail if the guess turns back against the writing direction or anything fails.
     */
    private fun updateTail(d: Doc, e: MotionEvent) {
        tailSize = 0
        val pr = predictor ?: return
        if (!predictOn) return
        pr.record(e)
        if (liveHighlighter || liveStyle != LINE_SOLID || livePoly || snapped != 0 || liveSize < 4) return
        val i = livePageIndex
        if (i < 0 || i >= d.pageCount) return
        val pe = pr.predict() ?: return
        try {
            val pi = pe.findPointerIndex(penPointerId).let { if (it >= 0) it else 0 }
            val ox = pl(i) + mlOf(d, i)
            val oy = tops[i]
            var px = livePts[liveSize - 2]
            var py = livePts[liveSize - 1]
            val dx0 = px - livePts[liveSize - 4]
            val dy0 = py - livePts[liveSize - 3]
            val maxLen = 24f * density / d.scale
            var len = 0f
            val n = pe.historySize + 1
            for (h in 0 until n) {
                val sx = if (h < pe.historySize) pe.getHistoricalX(pi, h) else pe.getX(pi)
                val sy = if (h < pe.historySize) pe.getHistoricalY(pi, h) else pe.getY(pi)
                val qx = (sx - d.tx) / d.scale - ox
                val qy = (sy - d.ty) / d.scale - oy
                if (!qx.isFinite() || !qy.isFinite()) break
                val vx = qx - px
                val vy = qy - py
                val sl = hypot(vx, vy)
                if (sl < 1e-4f) continue
                if (tailSize == 0 && dx0 * vx + dy0 * vy < 0f) break // heading back: no guess
                if (len + sl >= maxLen) {
                    val t = (maxLen - len) / sl
                    tail[tailSize++] = px + vx * t
                    tail[tailSize++] = py + vy * t
                    break
                }
                tail[tailSize++] = qx
                tail[tailSize++] = qy
                len += sl
                px = qx
                py = qy
                if (tailSize + 2 > tail.size) break
            }
        } catch (t: Throwable) {
            tailSize = 0
        } finally {
            pe.recycle()
        }
    }

    /** The live stroke with its pressure widths, or null when it's drawn evenly (highlighter, dashes, shapes, finger). */
    private fun livePressureInk(): Stroke? {
        if (!liveHasPressure || liveHighlighter || liveStyle != LINE_SOLID || livePoly || snapped != 0 || liveSize < 4) return null
        liveInk?.let { return it }
        val s = try {
            Stroke(liveColor, liveWidth, livePts.copyOf(liveSize), LINE_SOLID, false, false, liveFact.copyOf(liveSize / 2))
        } catch (t: Throwable) {
            return null
        }
        liveInk = s
        return s
    }

    private fun addLivePoint(d: Doc, x: Float, y: Float) {
        val i = livePageIndex
        if (i < 0 || i >= d.pageCount) return
        val px = (x - d.tx) / d.scale - (pl(i) + mlOf(d, i))
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
        val k = liveSize / 2 - 1
        if (k >= liveFact.size) liveFact = liveFact.copyOf(max(liveFact.size * 2, k + 1))
        val p = if (curPressure.isFinite()) curPressure.coerceIn(0f, 1f) else liveEma
        liveEma = if (k == 0) p else liveEma + (p - liveEma) * Pressure.FOLLOW
        liveFact[k] = Pressure.factor(liveEma)
    }

    private fun eraseAt(d: Doc, x: Float, y: Float) {
        val dx = (x - d.tx) / d.scale
        val dy = (y - d.ty) / d.scale
        val i = pageAt(d, dx, dy)
        if (i < 0) return
        val page = d.pages[i]
        val lx = dx - (pl(i) + mlOf(d, i))
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
        tailSize = 0 // the guess never outlives the pen on the screen
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
                // freehand strokes: tremor smoothed out once finished (snapped lines and shapes are already clean);
                // the tolerance is set on screen, so it's the same at every zoom
                val pts = if (smoothTolDp > 0f && !livePoly && snapped == 0 && liveSize >= 6) {
                    val tol = smoothTolDp * resources.displayMetrics.density / d.scale
                    try {
                        val sm = Smoothing.smooth(livePts, liveSize, tol)
                        // safety: the smoothed stroke must end where the pen lifted and be about as long as
                        // what was drawn; otherwise keep the stroke exactly as drawn
                        if (smoothedIntact(livePts, liveSize, sm, tol)) sm else livePts.copyOf(liveSize)
                    } catch (t: Throwable) {
                        livePts.copyOf(liveSize) // never lose a stroke to smoothing
                    }
                } else livePts.copyOf(liveSize)
                // gentle pressure width: S Pen freehand solid ink only (not highlighter, dashes, lines or shapes)
                val pw = if (liveHasPressure && !liveHighlighter && liveStyle == LINE_SOLID && !livePoly &&
                    snapped == 0 && liveSize >= 4) {
                    try {
                        Pressure.widths(livePts, liveFact, liveSize, pts)
                    } catch (t: Throwable) {
                        null // never lose a stroke: it's just drawn evenly
                    }
                } else null
                val s = Stroke(liveColor, liveWidth, pts, liveStyle, liveHighlighter, livePoly, pw)
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
        if (snapped == 1 && liveSize >= 4) {
            // magnetic levelling right away (the end then keeps following the pen, levelled too)
            lastMagnet = 0 // a new line: nothing locked yet
            val m = levelMagnet(d, livePts[2] - livePts[0], livePts[3] - livePts[1])
            if (m == 1) livePts[3] = livePts[1]
            if (m == 2) livePts[2] = livePts[0]
            lastMagnet = m
        }
        livePath = strokePath(livePts, liveSize, livePoly, liveStyle, liveWidth); liveInk = null
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    /** After a line has snapped, its end follows the pen until it's lifted. */
    private fun moveSnappedLineEnd(d: Doc, x: Float, y: Float) {
        val i = livePageIndex
        if (i < 0 || i >= d.pageCount || liveSize < 4) return
        var ex = (x - d.tx) / d.scale - (pl(i) + mlOf(d, i))
        var ey = (y - d.ty) / d.scale - tops[i]
        run {
            // magnetic (any straightened line, pen or highlighter): exactly level (0°) or upright (90°) when close
            val magnet = levelMagnet(d, ex - livePts[0], ey - livePts[1])
            if (magnet == 1) ey = livePts[1]
            if (magnet == 2) ex = livePts[0]
            if (magnet != 0 && magnet != lastMagnet) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            lastMagnet = magnet
        }
        livePts[2] = ex
        livePts[3] = ey
    }

    private var lastMagnet = 0

    /** 1 = close to level (0°), 2 = close to upright (90°), 0 = neither (or the line is still too short). */
    private fun levelMagnet(d: Doc, dx: Float, dy: Float): Int {
        if (hypot(dx, dy) < 6f * density / d.scale) return 0
        val ang = Math.toDegrees(kotlin.math.atan2(abs(dy).toDouble(), abs(dx).toDouble()))
        // sticky: it locks within MAGNET_DEG, but once locked it only lets go past MAGNET_RELEASE_DEG,
        // so the small sideways slip of the tip while lifting the pen doesn't undo it
        val hLimit = if (lastMagnet == 1) MAGNET_RELEASE_DEG else MAGNET_DEG
        val vLimit = if (lastMagnet == 2) MAGNET_RELEASE_DEG else MAGNET_DEG
        return when {
            ang <= hLimit -> 1
            ang >= 90.0 - vLimit -> 2
            else -> 0
        }
    }

    private fun cancelLive() {
        removeCallbacks(holdRunnable)
        tailSize = 0
        snapped = 0
        livePoly = false
        liveSize = 0
        livePage = null
        livePageIndex = -1
        livePath = null
        liveInk = null
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

    // ======================= pull to add a page =======================

    /** Visible lift of the content: half the pull (rubber band), never more than half the max pull. */
    private fun pullOffset(): Float = pullPx.coerceIn(0f, pullMaxPx) * 0.5f

    /** Sets the pull distance; holding at or past the threshold for [pullHoldMs] adds a page. */
    private fun setPull(px: Float) {
        pullAnim?.cancel()
        pullAnim = null
        pullPx = px.coerceIn(0f, pullMaxPx)
        if (pullPx >= pullThresholdPx && !pullDone) {
            if (pullArmedAt == 0L) {
                pullArmedAt = SystemClock.uptimeMillis()
                removeCallbacks(pullHold)
                postDelayed(pullHold, pullHoldMs)
                vibrate(18L, 90) // light tick: far enough, now hold
            }
        } else if (pullArmedAt != 0L) {
            pullArmedAt = 0L
            removeCallbacks(pullHold)
        }
        invalidate()
    }

    /** Lets the content slide back down; cancels a pending hold. */
    private fun resetPull(animate: Boolean) {
        removeCallbacks(pullHold)
        pullArmedAt = 0L
        pullAnim?.cancel()
        pullAnim = null
        if (pullPx <= 0f) return
        if (!animate || !isAttachedToWindow) {
            pullPx = 0f
            invalidate()
            return
        }
        pullAnim = android.animation.ValueAnimator.ofFloat(pullPx, 0f).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                pullPx = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun firePull() {
        pullArmedAt = 0L
        if (pullDone || pullPx < pullThresholdPx || mode != MODE_NAV || multiTouch || doc == null) return
        pullDone = true // one page per pull; lift the finger to pull again
        vibrate(40L, 220) // firmer buzz: the page window opens
        resetPull(animate = true)
        onPullAddPage?.invoke(pullDir < 0, pullPlusX, pullPlusY)
    }

    /** Short vibration ([amplitude] 1..255; ignored where the motor can't vary it). Never throws. */
    private fun vibrate(ms: Long, amplitude: Int) {
        try {
            val v = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (context.getSystemService(android.content.Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            }
            if (v == null || !v.hasVibrator()) return
            val amp = if (v.hasAmplitudeControl()) amplitude.coerceIn(1, 255) else android.os.VibrationEffect.DEFAULT_AMPLITUDE
            v.vibrate(android.os.VibrationEffect.createOneShot(ms, amp))
        } catch (t: Throwable) {
            // no vibration is fine
        }
    }

    /**
     * Round "+" in the space opened below the last page (or above the first); its ring fills while the
     * user holds the pull.
     */
    private fun drawPullIndicator(canvas: Canvas, off: Float) {
        val frac = (pullPx / pullThresholdPx).coerceIn(0f, 1f)
        val r = 20f * density
        val atTop = pullDir < 0
        val cx = if (atTop) width / 2f - 70f * density else width / 2f
        val cy = if (atTop) topPx + off / 2f + 4f * density else height - off / 2f - 8f * density
        if (cy + r < 0f || cy - r > height) return
        pullPlusX = cx
        pullPlusY = cy
        val alpha = (frac * 255).toInt()
        pullCirclePaint.alpha = alpha
        canvas.drawCircle(cx, cy, r, pullCirclePaint)
        // plus sign
        val a = r * 0.42f
        pullPlusPaint.strokeWidth = 2.2f * density
        pullPlusPaint.alpha = alpha
        canvas.drawLine(cx - a, cy, cx + a, cy, pullPlusPaint)
        canvas.drawLine(cx, cy - a, cx, cy + a, pullPlusPaint)
        // ring: dim while pulling, blue and filling while held at the threshold
        pullRingPaint.strokeWidth = 2.5f * density
        pullArc.set(cx - r, cy - r, cx + r, cy + r)
        val armed = pullArmedAt != 0L
        if (armed) {
            val t = ((SystemClock.uptimeMillis() - pullArmedAt).toFloat() / pullHoldMs).coerceIn(0f, 1f)
            pullRingPaint.color = 0xFF0A84FF.toInt()
            canvas.drawArc(pullArc, -90f, 360f * t, false, pullRingPaint)
            postInvalidateOnAnimation()
        } else {
            pullRingPaint.color = 0xFF636366.toInt()
            pullRingPaint.alpha = alpha
            canvas.drawArc(pullArc, -90f, 360f * frac, false, pullRingPaint)
        }
        pullTextPaint.textSize = 13f * density
        pullTextPaint.alpha = alpha
        val label = if (armed) "Keep holding to add a page" else if (pullDone) "" else "Pull and hold to add a page"
        if (label.isEmpty()) return
        if (atTop) {
            // beside the circle: above it is the toolbar, below it the first page
            pullTextPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(label, cx + r + 12f * density, cy + pullTextPaint.textSize * 0.35f, pullTextPaint)
            pullTextPaint.textAlign = Paint.Align.CENTER
        } else if (cy - r - 10f * density > 0f) {
            canvas.drawText(label, cx, cy - r - 10f * density, pullTextPaint)
        }
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
