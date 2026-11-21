package com.mabahrami.margin

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Samsung-Notes-style page overview: a two-column grid of thumbnails (with annotations),
 * the current page outlined in blue, and a ⋮ button on each page for page actions.
 */
@SuppressLint("ViewConstructor")
class PagesPanel(context: Context, private val renderExec: ExecutorService) : LinearLayout(context) {

    companion object {
        const val WIDTH_DP = 300
        private const val ACCENT = 0xFF0A84FF.toInt()
    }

    var onPageTap: ((Int) -> Unit)? = null
    var onPageMenu: ((View, Int) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    /** The + tile after the last page. */
    var onAddPage: (() -> Unit)? = null
    /** A page was dragged to a new place (the list is already reordered). */
    var onPageMoved: ((Int, Int, Page) -> Unit)? = null
    /** Select mode: the chosen pages, in document order. */
    var onCopyPages: ((List<Page>) -> Unit)? = null
    var onCutPages: ((List<Page>) -> Unit)? = null
    var onExportPages: ((List<Page>) -> Unit)? = null

    // ---- select mode: tap pages to choose several, then act on them together ----
    private var selecting = false
    /** Chosen pages (by identity, so undo/insert/delete never confuse it). */
    private val chosen = HashSet<Page>()
    private lateinit var selectBtn: TextView
    private lateinit var allBtn: TextView
    private lateinit var actionBar: LinearLayout
    private val actions = ArrayList<TextView>()

    private var doc: Doc? = null
    private var current = 0
    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private val header: TextView
    private val list: RecyclerView
    private val adapter = Adapter()

    // thumbnail cache, keyed by page identity + content signature
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pending = HashSet<String>()
    private var generation = 0

    /** Thumbnail image width in pixels (2 columns inside the panel). */
    private val thumbW = dp((WIDTH_DP - 3 * 16) / 2)

    init {
        orientation = VERTICAL
        setBackgroundColor(0xFF0B0B0C.toInt())

        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(8), dp(6))
        }
        header = TextView(context).apply {
            textSize = 17f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        top.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        allBtn = textButton("All") { toggleAll() }
        allBtn.visibility = View.GONE
        top.addView(allBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        selectBtn = textButton("Select") { if (selecting) endSelection() else startSelection() }
        top.addView(selectBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)))
        top.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            imageTintList = ColorStateList.valueOf(0xFF8E8E93.toInt())
            contentDescription = "Close pages"
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
            isClickable = true
            setOnClickListener { onClose?.invoke() }
        }, LinearLayout.LayoutParams(dp(40), dp(40)))
        addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        list = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = this@PagesPanel.adapter
            setPadding(dp(8), dp(4), dp(8), dp(16))
            clipToPadding = false
            itemAnimator = null
        }
        addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        actionBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(12))
            setBackgroundColor(0xFF1C1C1E.toInt())
            visibility = View.GONE
        }
        fun act(label: String, cb: () -> ((List<Page>) -> Unit)?) {
            val b = textButton(label) {
                val ps = chosenInOrder()
                if (ps.isNotEmpty()) cb()?.invoke(ps)
            }.apply { gravity = Gravity.CENTER }
            actions.add(b)
            actionBar.addView(b, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        act("Copy") { onCopyPages }
        act("Cut") { onCutPages }
        act("Export PDF") { onExportPages }
        addView(actionBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun textButton(label: String, onClick: () -> Unit): TextView = TextView(context).apply {
        text = label
        textSize = 15f
        setTextColor(ACCENT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(12), 0)
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
            GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Color.WHITE) })
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Enters select mode (dragging to reorder is off while selecting). */
    fun startSelection() {
        if (doc == null) return
        selecting = true
        chosen.clear()
        refresh()
    }

    /** Leaves select mode and forgets the choice. */
    fun endSelection() {
        if (!selecting && chosen.isEmpty()) return
        selecting = false
        chosen.clear()
        refresh()
    }

    private fun toggleAll() {
        val d = doc ?: return
        if (chosen.size == d.pageCount) chosen.clear() else chosen.addAll(d.pages)
        refresh()
    }

    private fun chosenInOrder(): List<Page> = doc?.pages?.filter { it in chosen } ?: emptyList()

    private fun updateSelectionUi() {
        selectBtn.text = if (selecting) "Done" else "Select"
        allBtn.visibility = if (selecting) View.VISIBLE else View.GONE
        val d = doc
        allBtn.text = if (d != null && d.pageCount > 0 && chosen.size == d.pageCount) "None" else "All"
        actionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        val any = chosen.isNotEmpty()
        for (b in actions) {
            b.isEnabled = any
            b.alpha = if (any) 1f else 0.35f
        }
    }

    // ---------------- hold a page and drag it to reorder ----------------

    private var dragPage: Page? = null
    private var dragFrom = -1

    private val reorder = ItemTouchHelper(object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            if (vh !is Holder) 0 else makeMovementFlags(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0,
            )

        override fun isLongPressDragEnabled(): Boolean = !selecting
        override fun isItemViewSwipeEnabled(): Boolean = false

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val d = doc ?: return false
            val from = vh.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (from !in 0 until d.pageCount || to !in 0 until d.pageCount) return false
            val p = d.pages.removeAt(from)
            d.pages.add(to, p)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && vh != null) {
                val d = doc ?: return
                dragFrom = vh.bindingAdapterPosition
                dragPage = d.pages.getOrNull(dragFrom)
                vh.itemView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                vh.itemView.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.9f).setDuration(120).start()
            }
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
            val d = doc
            val p = dragPage
            val from = dragFrom
            dragPage = null
            dragFrom = -1
            if (d == null || p == null) return
            val to = d.pages.indexOf(p)
            if (to >= 0 && to != from) {
                current = to
                onPageMoved?.invoke(from, to, p)
            }
            refresh() // renumber
        }
    })

    // runs after `reorder` exists (Kotlin initialises properties and init blocks in order)
    init {
        reorder.attachToRecyclerView(list)
    }

    fun setDoc(d: Doc?) {
        doc = d
        selecting = false
        chosen.clear()
        generation++
        cache.evictAll()
        pending.clear()
        current = 0
        refresh()
    }

    /** Re-binds visible thumbnails; only pages whose content changed are re-rendered. */
    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        // pages removed meanwhile (undo, delete, another tab) drop out of the choice
        val d = doc
        if (d == null) chosen.clear() else if (chosen.isNotEmpty()) chosen.retainAll(d.pages.toSet())
        adapter.notifyDataSetChanged()
        updateHeader()
        updateSelectionUi()
    }

    fun setCurrent(i: Int) {
        if (i == current) return
        val old = current
        current = i
        adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(i)
        updateHeader()
    }

    fun scrollToCurrent() {
        list.scrollToPosition(current)
    }

    private fun updateHeader() {
        val n = doc?.pageCount ?: 0
        header.text = when {
            n == 0 -> ""
            selecting -> if (chosen.isEmpty()) "Select pages" else "${chosen.size} selected"
            else -> "${current + 1} / $n"
        }
    }

    // ---------------- thumbnails ----------------

    private fun signature(d: Doc, p: Page): String {
        var h = 17
        for (s in p.strokes) h = h * 31 + System.identityHashCode(s)
        for (im in p.images) h = h * 31 + im.state().hashCode()
        return "${System.identityHashCode(p)}:${p.strokes.size}:${p.images.size}:$h:${d.leftOf(p)}:${d.rightOf(p)}:${d.marginPaperOf(p)}:${d.dark}"
    }

    private class Snap(
        val file: Int, val src: Int, val w: Int, val h: Int, val paper: Int, val marginPaper: Int,
        val mL: Float, val mR: Float,
        val strokes: List<Stroke>,
        val images: List<Pair<File, ImgState>>,
        val dark: Boolean,
    )

    private fun request(d: Doc, p: Page, key: String, tw: Int, th: Int) {
        if (!pending.add(key)) return
        val gen = generation
        val snap = Snap(p.file, p.src, p.w, p.h, if (p.isBlank) p.paper else PAPER_BLANK, d.marginPaperOf(p), d.leftOf(p), d.rightOf(p), ArrayList(p.strokes),
            p.images.map { File(d.dir, it.file) to it.state() }, d.dark)
        try {
            renderExec.execute {
                val bmp = render(d, snap, tw, th)
                post {
                    if (gen != generation) return@post
                    pending.remove(key)
                    if (bmp != null) {
                        cache.put(key, bmp)
                        refreshKey(key)
                    }
                }
            }
        } catch (e: Exception) {
            pending.remove(key)
        }
    }

    private fun refreshKey(key: String) {
        for (k in 0 until list.childCount) {
            val vh = list.getChildViewHolder(list.getChildAt(k)) as? Holder ?: continue
            if (vh.key == key) cache.get(key)?.let { vh.thumb.setImageBitmap(it) }
        }
    }

    /** Runs on the render thread: page + margins + images + strokes, scaled down. */
    private fun render(d: Doc, s: Snap, tw: Int, th: Int): Bitmap? {
        if (d.closed) return null
        return try {
            val bmp = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val fullW = s.mL + s.w + s.mR
            val sc = tw / fullW
            if (s.src >= 0) {
                val r = d.sources.getOrNull(s.file)?.renderer
                if (r != null && s.src < r.pageCount) {
                    val pg = r.openPage(s.src)
                    try {
                        val m = Matrix()
                        m.setScale(s.w * sc / pg.width, s.h * sc / pg.height)
                        m.postTranslate(s.mL * sc, 0f)
                        pg.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        pg.close()
                    }
                }
            }
            val c = Canvas(bmp)
            c.scale(sc, sc)
            c.translate(s.mL, 0f)
            val rule = Paint().apply { color = 0xFFD6D6DA.toInt(); strokeWidth = max(0.5f, 1f / sc) }
            if (s.paper != PAPER_BLANK) c.drawLines(paperLines(s.paper, s.w.toFloat(), s.h.toFloat()), rule)
            if (s.marginPaper != PAPER_BLANK) {
                if (s.mL > 0f) {
                    c.save(); c.translate(-s.mL, 0f); c.drawLines(paperLines(s.marginPaper, s.mL, s.h.toFloat()), rule); c.restore()
                }
                if (s.mR > 0f) {
                    c.save(); c.translate(s.w.toFloat(), 0f); c.drawLines(paperLines(s.marginPaper, s.mR, s.h.toFloat()), rule); c.restore()
                }
            }
            val bp = Paint(Paint.FILTER_BITMAP_FLAG)
            for ((f, st) in s.images) {
                val ib = Pics.decodeForScreen(f, 400) ?: continue
                val src = Rect((st.cl * ib.width).toInt(), (st.ct * ib.height).toInt(),
                    (st.cr * ib.width).toInt(), (st.cb * ib.height).toInt())
                c.drawBitmap(ib, src, RectF(st.x, st.y, st.x + st.w, st.y + st.h), bp)
            }
            val hl = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            val hlLayer = Paint().apply {
                blendMode = BlendMode.MULTIPLY
                alpha = (HIGHLIGHT_ALPHA * 255).toInt()
            }
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            }
            if (s.strokes.any { it.highlighter }) {
                c.saveLayer(RectF(-s.mL, 0f, s.w + s.mR, s.h.toFloat()), hlLayer)
                for (st in s.strokes) if (st.highlighter) {
                    hl.color = st.color
                    hl.strokeWidth = st.width
                    c.drawPath(st.path, hl)
                }
                c.restore()
            }
            for (st in s.strokes) if (!st.highlighter) {
                ink.color = st.color
                ink.strokeWidth = max(st.width, 0.8f / sc) // keep thin lines visible when tiny
                ink.pathEffect = linePattern(st.style, st.width)?.let { DashPathEffect(it, 0f) }
                c.drawPath(st.path, ink)
            }
            if (s.dark) {
                // photos keep their colours, like on the page itself
                val keep = s.images.map { (_, st) ->
                    RectF((st.x + s.mL) * sc, st.y * sc, (st.x + st.w + s.mL) * sc, (st.y + st.h) * sc)
                }
                Dark.process(bmp, keep)
            }
            bmp
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------- list ----------------

    private inner class Holder(root: View, val frame: FrameLayout, val thumb: ImageView, val number: TextView, val more: ImageView, val check: TextView) :
        RecyclerView.ViewHolder(root) {
        var key: String? = null
    }

    private inner class AddHolder(root: View) : RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        /** Pages plus a trailing "+" tile. */
        override fun getItemCount(): Int = doc?.let { it.pageCount + if (selecting) 0 else 1 } ?: 0

        override fun getItemViewType(position: Int): Int = if (position == (doc?.pageCount ?: 0)) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 1) {
                val root = FrameLayout(context).apply { setPadding(dp(8), dp(8), dp(8), dp(4)) }
                val tile = FrameLayout(context).apply {
                    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                        GradientDrawable().apply {
                            cornerRadius = dp(6).toFloat()
                            setStroke(dp(1), 0xFF48484A.toInt(), dp(5).toFloat(), dp(4).toFloat())
                        }, null)
                    isClickable = true
                    contentDescription = "Add page"
                    setOnClickListener { onAddPage?.invoke() }
                }
                tile.addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_plus)
                    imageTintList = ColorStateList.valueOf(0xFF8E8E93.toInt())
                }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
                root.addView(tile, FrameLayout.LayoutParams(thumbW, (thumbW * 1.414f).roundToInt(), Gravity.CENTER_HORIZONTAL))
                return AddHolder(root)
            }
            return createPageHolder()
        }

        private fun createPageHolder(): Holder {
            val root = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(8), dp(8), dp(4))
            }
            val frame = FrameLayout(context)
            val thumb = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Color.WHITE)
            }
            frame.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val more = ImageView(context).apply {
                setImageResource(R.drawable.ic_more)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                contentDescription = "Page options"
                setPadding(dp(6), dp(6), dp(6), dp(6))
                background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF),
                    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x99636366.toInt()) }, null)
                isClickable = true
            }
            frame.addView(more, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(6), dp(6))
            })
            // select mode: a round tick in the top corner
            val check = TextView(context).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                visibility = View.GONE
            }
            frame.addView(check, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(6), dp(6), 0)
            })
            root.addView(frame, LinearLayout.LayoutParams(thumbW, dp(100)))
            val number = TextView(context).apply {
                textSize = 13f
                setTextColor(0xFFD1D1D6.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(6), 0, dp(4))
            }
            root.addView(number, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            return Holder(root, frame, thumb, number, more, check)
        }

        override fun onBindViewHolder(vh: RecyclerView.ViewHolder, position: Int) {
            val h = vh as? Holder ?: return
            val d = doc ?: return
            val p = d.pages.getOrNull(position) ?: return
            val fullW = d.leftOf(p) + p.w + d.rightOf(p)
            val th = max(dp(40), (thumbW * p.h / fullW).roundToInt())
            val lp = h.frame.layoutParams
            if (lp.height != th) {
                lp.height = th
                h.frame.layoutParams = lp
            }
            h.number.text = "${position + 1}"
            h.thumb.setBackgroundColor(if (d.dark) Dark.PAPER else Color.WHITE)
            val isChosen = selecting && p in chosen
            val selected = if (selecting) isChosen else position == current
            h.frame.foreground = GradientDrawable().apply {
                setColor(if (isChosen) 0x330A84FF else Color.TRANSPARENT)
                cornerRadius = dp(3).toFloat()
                if (selected) setStroke(dp(3), ACCENT) else setStroke(1, 0xFF3A3A3C.toInt())
            }
            h.more.visibility = if (selecting) View.GONE else View.VISIBLE
            h.check.visibility = if (selecting) View.VISIBLE else View.GONE
            h.check.text = if (isChosen) "\u2713" else ""
            h.check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (isChosen) setColor(ACCENT) else {
                    setColor(0x66000000)
                    setStroke(dp(2), Color.WHITE)
                }
            }
            h.number.setTextColor(if (selected) ACCENT else 0xFFD1D1D6.toInt())
            val key = signature(d, p)
            h.key = key
            val bmp = cache.get(key)
            if (bmp != null) h.thumb.setImageBitmap(bmp) else {
                h.thumb.setImageDrawable(null)
                request(d, p, key, thumbW, th)
            }
            h.itemView.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                if (selecting) {
                    val page = doc?.pages?.getOrNull(pos) ?: return@setOnClickListener
                    if (!chosen.remove(page)) chosen.add(page)
                    adapter.notifyItemChanged(pos)
                    updateHeader()
                    updateSelectionUi()
                } else onPageTap?.invoke(pos)
            }
            h.more.setOnClickListener {
                val pos = h.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPageMenu?.invoke(h.more, pos)
            }
        }
    }
}
