package com.mabahrami.margin

import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "Margin"
        private val COLORS = intArrayOf(
            0xFF000000.toInt(), // black
            0xFF1E5BD8.toInt(), // blue
            0xFFD32F2F.toInt(), // red
            0xFF2E7D32.toInt(), // green
            0xFFEF6C00.toInt(), // orange
            0xFF7B1FA2.toInt(), // purple
            0xFFFFD60A.toInt(), // yellow
        )
        private const val YELLOW = 0xFFFFD60A.toInt()

        // Apple-style dark palette
        private const val BG = 0xFF000000.toInt()           // window / title bar
        private const val SURFACE = 0xFF1C1C1E.toInt()      // floating bars
        private const val SURFACE2 = 0xFF2C2C2E.toInt()     // active tab, pressed
        private const val SEPARATOR = 0xFF38383A.toInt()
        private const val TEXT = 0xFFFFFFFF.toInt()
        private const val TEXT_DIM = 0xFF8E8E93.toInt()
        private const val ACCENT = 0xFF0A84FF.toInt()       // systemBlue
    }

    private val docs = ArrayList<Doc>()
    private var active: Doc? = null

    private lateinit var pdfView: PdfView
    private lateinit var tabBar: LinearLayout
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabCount: TextView
    private lateinit var penBtn: ImageView
    private lateinit var eraserBtn: ImageView
    private lateinit var hlBtn: ImageView
    /** Current tool is the highlighter (pen and highlighter each remember their own colour and width). */
    private var hlMode = false
    private lateinit var undoBtn: ImageView
    private lateinit var redoBtn: ImageView
    private lateinit var pagesBtn: ImageView
    private lateinit var insertBtn: ImageView
    private lateinit var selBar: LinearLayout
    private lateinit var lockBtn: TextView
    private lateinit var pressAnchor: View
    private lateinit var pagesPanel: PagesPanel
    private lateinit var pageBadge: TextView
    private lateinit var pagesPanelDivider: View
    /** Where the next inserted image goes (set by the long-press menu), or null for screen centre. */
    private var pendingPlace: PlacePoint? = null
    /** Page index to insert a PDF after (long-press menu), or -1 for the current page. */
    private var insertAfterIndex = -1
    private lateinit var widthLabel: TextView
    private lateinit var widthSeek: SeekBar
    private lateinit var titleBar: View
    private lateinit var titleDivider: View
    private val focusOnlyViews = ArrayList<View>()
    private var focusMode = false
    private val swatches = ArrayList<Pair<Int, View>>()
    private lateinit var prefs: SharedPreferences
    private val shownUnsaved = HashMap<String, Boolean>()

    // One thread owns all PdfRenderer work; one thread does all state writes (keeps them ordered).
    private val renderExec = Executors.newSingleThreadExecutor()
    private val renderDispatcher = renderExec.asCoroutineDispatcher()
    private val ioExec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val dirtyDocs = LinkedHashSet<Doc>()
    private val flushRunnable = Runnable { flushSaves() }
    private var pendingSaveId: String? = null
    private var pendingExportId: String? = null
    private var pendingInsertId: String? = null
    private var pendingCameraPath: String? = null

    private val openLauncher =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) importAll(uris, fromPicker = true)
        }

    private val saveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val d = docs.firstOrNull { it.id == pendingSaveId }
            pendingSaveId = null
            if (uri != null && d != null) {
                keepWriteAccess(uri)
                writeDoc(d, uri, isSave = true, fromPicker = true)
            }
        }

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val d = docs.firstOrNull { it.id == pendingInsertId }
            pendingInsertId = null
            if (uri != null && d != null) insertImage(d, { contentResolver.openInputStream(uri) }, cropAfter = false)
        }

    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val d = docs.firstOrNull { it.id == pendingInsertId }
            val path = pendingCameraPath
            pendingInsertId = null
            pendingCameraPath = null
            if (ok && d != null && path != null) {
                val f = File(path)
                insertImage(d, { if (f.exists()) f.inputStream() else null }, cropAfter = true, cleanup = f)
            } else if (path != null) {
                File(path).delete()
            }
        }

    private val pickPdfLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val d = docs.firstOrNull { it.id == pendingInsertId }
            pendingInsertId = null
            if (uri != null && d != null) insertPdf(d, uri)
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val d = docs.firstOrNull { it.id == pendingExportId }
            pendingExportId = null
            if (uri != null && d != null) writeDoc(d, uri, isSave = false, fromPicker = true)
        }

    // ======================= lifecycle =======================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.statusBarColor = BG
        @Suppress("DEPRECATION")
        window.navigationBarColor = BG
        prefs = getSharedPreferences("settings", MODE_PRIVATE)
        pendingSaveId = savedInstanceState?.getString("pendingSave")
        pendingExportId = savedInstanceState?.getString("pendingExport")
        pendingInsertId = savedInstanceState?.getString("pendingInsert")
        pendingCameraPath = savedInstanceState?.getString("pendingCamera")
        pdfView = PdfView(this, renderExec)
        pdfView.onChange = { d ->
            markDirty(d)
            updateButtons()
            if (pagesPanel.visibility == View.VISIBLE) pagesPanel.refresh()
            updatePageBadge()
        }
        pdfView.onSelection = { item -> updateSelBar(item) }
        pdfView.onLongPressPage = { x, y, pt -> showPageMenu(x, y, pt) }
        pdfView.onSelectionRect = { l, t, r, b -> placeSelBar(l, t, r, b) }
        pdfView.onCurrentPage = { i ->
            if (pagesPanel.visibility == View.VISIBLE) pagesPanel.setCurrent(i)
            updatePageBadge(i)
        }
        setContentView(buildUi())
        loadTool()
        if (prefs.getBoolean("focus", false)) setFocusMode(true)
        restoreSession(if (savedInstanceState == null) intent else null)
        ioExec.execute {
            try {
                Store.purgeTrash(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "purge", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("pendingSave", pendingSaveId)
        outState.putString("pendingExport", pendingExportId)
        outState.putString("pendingInsert", pendingInsertId)
        outState.putString("pendingCamera", pendingCameraPath)
    }

    override fun onPause() {
        super.onPause()
        // Save working state (incl. viewport) and wait briefly so nothing is lost if the app is killed.
        dirtyDocs.addAll(docs)
        main.removeCallbacks(flushRunnable)
        val f = flushSaves()
        saveSession()
        try {
            f?.get(2, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "save wait", e)
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) pdfView.trimCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(flushRunnable)
        val all = ArrayList(docs)
        renderExec.execute { for (d in all) d.closeRenderer() }
        renderExec.shutdown()
        ioExec.shutdown()
    }

    // ======================= UI =======================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(fill: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
    }

    private fun oval(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    /** Soft round touch highlight, like iOS. */
    private fun ripple(content: Drawable? = null): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, oval(Color.WHITE))

    /** Text pill (used in dialogs and the crop screen). */
    private fun button(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(TEXT)
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(8), dp(16), dp(8))
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(SURFACE2, 18), null)
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Line-icon button, 40dp touch target. */
    private fun iconButton(icon: Int, desc: String, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(TEXT)
        contentDescription = desc
        tooltipText = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = ripple()
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** Glass-like floating surface: translucent dark with a hairline light edge. */
    private fun glass(radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(0xEB1C1C1E.toInt())
        setStroke(1, 0x26FFFFFF)
    }

    private fun setToolSelected(v: ImageView, on: Boolean) {
        v.background = if (on) ripple(oval(0x330A84FF)) else ripple()
        v.imageTintList = ColorStateList.valueOf(if (on) ACCENT else TEXT)
    }

    private fun iconLp(startDp: Int = 0, size: Int = 38) = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginStart = dp(startDp) }

    /** Title-bar icon: 36dp target, 18dp glyph, soft white. */
    private fun barIcon(icon: Int, desc: String, onClick: () -> Unit): ImageView =
        iconButton(icon, desc, onClick).apply {
            setPadding(dp(9), dp(9), dp(9), dp(9))
            imageTintList = ColorStateList.valueOf(0xFFD1D1D6.toInt())
        }

    private fun barLp(startDp: Int = 0) = iconLp(startDp, 36)

    /** Compact buttons for the floating pen bar. */
    private fun palLp(startDp: Int = 0) = iconLp(startDp, 34)

    private fun palDivider(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(1, dp(16)).apply { marginStart = dp(8); marginEnd = dp(8) }

    private fun lp(startDp: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginStart = dp(startDp) }

    private fun divider(): View = View(this).apply { setBackgroundColor(SEPARATOR) }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // ---- title bar: open · tabs · document actions ----
        val titleBar = LinearLayout(this).apply {
            this@MainActivity.titleBar = this
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        titleBar.addView(barIcon(R.drawable.ic_new, "New note") { newNote() }, barLp(0))
        lateinit var openBtn: ImageView
        openBtn = barIcon(R.drawable.ic_open, "Open") { showOpenMenu(openBtn) }
        titleBar.addView(openBtn, barLp())
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            // fade the edges so it's clear there are more tabs to scroll to
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dp(28))
            addView(tabBar)
        }
        titleBar.addView(tabScroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(6); marginEnd = dp(4)
        })
        // count of open tabs; tap for a list with full names
        tabCount = TextView(this).apply {
            textSize = 12f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            minWidth = dp(26)
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                GradientDrawable().apply { cornerRadius = dp(7).toFloat(); setStroke(dp(1), 0xFF8E8E93.toInt()) }, null)
            contentDescription = "All open tabs"
            isClickable = true
            visibility = View.GONE
            setOnClickListener { v -> showTabList(v) }
        }
        titleBar.addView(tabCount, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24)).apply {
            marginEnd = dp(6)
        })
        insertBtn = barIcon(R.drawable.ic_plus, "Insert") { showInsertMenu(insertBtn) }
        pagesBtn = barIcon(R.drawable.ic_pages, "Pages") { togglePages() }
        // page tools
        titleBar.addView(pagesBtn, barLp())
        titleBar.addView(barIcon(R.drawable.ic_margins, "Margins") { showMargins() }, barLp())
        titleBar.addView(insertBtn, barLp())
        lateinit var saveBtn: ImageView
        saveBtn = barIcon(R.drawable.ic_save, "Save or export") { showSaveMenu(saveBtn) }
        // file & view (a wider gap separates the groups)
        titleBar.addView(saveBtn, barLp(10))
        titleBar.addView(barIcon(R.drawable.ic_share, "Share") { active?.let { share(it) } }, barLp())
        titleBar.addView(barIcon(R.drawable.ic_focus, "Focus mode") { setFocusMode(true) }, barLp())
        root.addView(titleBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        titleDivider = divider()
        root.addView(titleDivider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))

        // ---- canvas with floating tool palette and image bar ----
        val stage = FrameLayout(this)
        stage.addView(pdfView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pressAnchor = View(this)
        stage.addView(pressAnchor, FrameLayout.LayoutParams(dp(1), dp(1), Gravity.TOP or Gravity.START))

        val palette = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
            background = glass(20)
            elevation = dp(6).toFloat()
        }
        // shown only in focus mode, when the top bar is hidden
        lateinit var fInsert: ImageView
        lateinit var fPages: ImageView
        fInsert = iconButton(R.drawable.ic_plus, "Insert") { showInsertMenu(fInsert) }
        fPages = iconButton(R.drawable.ic_pages, "Pages") { togglePages() }
        val fMargins = iconButton(R.drawable.ic_margins, "Margins") { showMargins() }

        penBtn = iconButton(R.drawable.ic_pen, "Pen (tap again for line style)") {
            if (pdfView.eraserMode || hlMode) setTool(false) else showPenStyleMenu()
        }
        hlBtn = iconButton(R.drawable.ic_highlighter, "Highlighter (tap again for straight / free)") {
            if (hlMode && !pdfView.eraserMode) showHighlighterMenu() else setTool(true)
        }
        eraserBtn = iconButton(R.drawable.ic_eraser, "Eraser (or hold the S Pen button)") { setEraser(true) }
        palette.addView(penBtn, palLp())
        palette.addView(hlBtn, palLp(1))
        palette.addView(eraserBtn, palLp(1))
        palette.addView(divider(), palDivider())
        // colours: one continuous strip of rectangles, rounded at the ends
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(Color.TRANSPARENT, 7)
            clipToOutline = true
        }
        for (c in COLORS) {
            val v = View(this)
            v.setOnClickListener { setColor(c) }
            swatches.add(c to v)
            strip.addView(v, LinearLayout.LayoutParams(dp(24), dp(28)))
        }
        palette.addView(strip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        palette.addView(divider(), palDivider())
        palette.addView(iconButton(R.drawable.ic_minus, "Thinner") { stepWidth(-1) }, LinearLayout.LayoutParams(dp(28), dp(34)))
        val seek = SeekBar(this).apply {
            max = 39 // width = (progress + 1) * 0.25 pt  ->  0.25 .. 10 pt
            setPadding(dp(8), dp(10), dp(8), dp(10))
            // keep the sideways-scrolling toolbar from stealing the drag
            setOnTouchListener { v, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF48484A.toInt())
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) setWidth((p + 1) * widthUnit())
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        seek.progress = ((prefs.getFloat("width", 1.5f) / 0.25f).roundToInt() - 1).coerceIn(0, 39)
        widthSeek = seek
        palette.addView(seek, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        palette.addView(iconButton(R.drawable.ic_plus, "Thicker") { stepWidth(1) }, LinearLayout.LayoutParams(dp(28), dp(34)))
        widthLabel = TextView(this).apply {
            textSize = 11f
            setTextColor(TEXT_DIM)
            minWidth = dp(26)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        palette.addView(widthLabel, lp(0))
        palette.addView(divider(), palDivider())
        undoBtn = iconButton(R.drawable.ic_undo, "Undo") { pdfView.undo() }
        redoBtn = iconButton(R.drawable.ic_redo, "Redo") { pdfView.redo() }
        palette.addView(undoBtn, palLp())
        palette.addView(redoBtn, palLp())
        // focus mode only: Insert · Pages · Margins, then Exit at the very end
        val fExit = iconButton(R.drawable.ic_unfocus, "Exit focus mode") { setFocusMode(false) }
        val fDiv = divider()
        palette.addView(fDiv, palDivider())
        palette.addView(fInsert, palLp())
        palette.addView(fPages, palLp(1))
        palette.addView(fMargins, palLp(1))
        palette.addView(fExit, palLp(1))
        focusOnlyViews.addAll(listOf(fDiv, fInsert, fPages, fMargins, fExit))
        for (v in focusOnlyViews) v.visibility = View.GONE
        val paletteScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(12), dp(8), dp(12), dp(12)) // room for the shadow
            addView(palette)
        }
        stage.addView(paletteScroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ))

        // iOS-style edit menu for the selected image: text items with thin separators
        selBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glass(12)
            elevation = dp(10).toFloat()
            clipToOutline = true
            visibility = View.GONE
        }
        fun menuItem(label: String, color: Int = TEXT, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, rounded(Color.WHITE, 0))
            isClickable = true
            setOnClickListener { onClick() }
        }
        fun sep() = divider()
        lockBtn = menuItem("Lock") { toggleLock() }
        selBar.addView(lockBtn)
        selBar.addView(sep(), LinearLayout.LayoutParams(1, dp(22)))
        selBar.addView(menuItem("Crop") { cropSelected() })
        selBar.addView(sep(), LinearLayout.LayoutParams(1, dp(22)))
        selBar.addView(menuItem("Copy") { copySelected(cut = false) })
        selBar.addView(sep(), LinearLayout.LayoutParams(1, dp(22)))
        selBar.addView(menuItem("Cut") { copySelected(cut = true) })
        selBar.addView(sep(), LinearLayout.LayoutParams(1, dp(22)))
        selBar.addView(menuItem("Delete", 0xFFFF453A.toInt()) { pdfView.deleteSelected() })
        // dim "current / total" page counter, bottom right
        pageBadge = TextView(this).apply {
            textSize = 12f
            setTextColor(0x99FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(0x661C1C1E, 12)
            visibility = View.GONE
        }
        stage.addView(pageBadge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply { setMargins(0, 0, dp(14), dp(14)) })
        stage.addView(selBar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START,
        ))
        // page overview on the left, canvas on the right
        val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pagesPanel = PagesPanel(this, renderExec).apply { visibility = View.GONE }
        pagesPanel.onPageTap = { i -> pdfView.scrollToPage(i) }
        pagesPanel.onPageMenu = { anchor, i -> showThumbMenu(anchor, i) }
        pagesPanel.onClose = { setPagesVisible(false) }
        pagesPanel.onPageMoved = { from, to, page -> pdfView.pageMoved(from, to, page) }
        pagesPanel.onAddPage = { addPageLikeLast() }
        body.addView(pagesPanel, LinearLayout.LayoutParams(dp(PagesPanel.WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        pagesPanelDivider = divider().apply { visibility = View.GONE }
        body.addView(pagesPanelDivider, LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(stage, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun loadTool() {
        pdfView.hlStraight = prefs.getBoolean("hl_straight", false)
        pdfView.penStyle = prefs.getInt("line_style", if (prefs.getBoolean("dashed", false)) LINE_DASHED else LINE_SOLID)
        setTool(prefs.getBoolean("tool_hl", false))
        refreshTool()
        updateButtons()
    }

    private fun refreshTool() {
        penBtn.setImageResource(when (pdfView.penStyle) {
            LINE_DASHED -> R.drawable.ic_pen_dashed
            LINE_DOTTED -> R.drawable.ic_pen_dotted
            else -> R.drawable.ic_pen
        })
        setToolSelected(penBtn, !pdfView.eraserMode && !hlMode)
        hlBtn.setImageResource(if (pdfView.hlStraight) R.drawable.ic_highlighter_straight else R.drawable.ic_highlighter)
        setToolSelected(hlBtn, !pdfView.eraserMode && hlMode)
        setToolSelected(eraserBtn, pdfView.eraserMode)
        for ((c, v) in swatches) {
            val selected = c == pdfView.penColor && !pdfView.eraserMode
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(c)
                // white inner frame marks the current colour
                if (selected) setStroke(dp(2), Color.WHITE)
            }
        }
        val w = pdfView.penWidth
        widthLabel.text = if (w % 1f == 0f) "${w.toInt()}" else String.format("%.2f", w).trimEnd('0').trimEnd('.')
    }

    // pen: 0.25 .. 10 pt in 0.25 steps; highlighter: 0.75 .. 30 pt in 0.75 steps (slider has 40 stops)
    private fun widthUnit() = if (hlMode) 0.75f else 0.25f
    private fun colorKey() = if (hlMode) "hl_color" else "color"
    private fun widthKey() = if (hlMode) "hl_width" else "width"

    /** Switches between pen and highlighter; each keeps its own colour and width. */
    private fun setTool(highlighter: Boolean) {
        hlMode = highlighter
        prefs.edit().putBoolean("tool_hl", highlighter).apply()
        pdfView.highlighter = highlighter
        pdfView.eraserMode = false
        val unit = widthUnit()
        pdfView.penColor = prefs.getInt(colorKey(), if (highlighter) YELLOW else COLORS[0])
        pdfView.penWidth = prefs.getFloat(widthKey(), if (highlighter) 12f else 1.5f).coerceIn(unit, unit * 40)
        widthSeek.progress = ((pdfView.penWidth / unit).roundToInt() - 1).coerceIn(0, 39)
        refreshTool()
    }

    /** − / + buttons. */
    private fun stepWidth(dir: Int) {
        val unit = widthUnit()
        val w = pdfView.penWidth
        val step = if (!hlMode && (w > 3f || (dir < 0 && w > 3.25f))) 0.5f else unit
        val nw = ((w + dir * step) / unit).roundToInt() * unit
        if (pdfView.eraserMode) setEraser(false)
        setWidth(nw.coerceIn(unit, unit * 40))
    }

    /** Focus mode: hides the status bar and the top bar; the floating toolbar gets Insert/Pages/Margins. */
    private fun setFocusMode(on: Boolean) {
        focusMode = on
        prefs.edit().putBoolean("focus", on).apply()
        titleBar.visibility = if (on) View.GONE else View.VISIBLE
        titleDivider.visibility = titleBar.visibility
        for (v in focusOnlyViews) v.visibility = if (on) View.VISIBLE else View.GONE
        window.insetsController?.let { c ->
            if (on) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            } else {
                c.show(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // bars can come back after dialogs or app switches: hide them again
        if (hasFocus && focusMode) setFocusMode(true)
    }

    /** Second tap on the highlighter: freehand (curvy line) or straight lines only. */
    private fun showHighlighterMenu() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = glass(14)
            elevation = dp(10).toFloat()
        }
        val popup = android.widget.PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = dp(10).toFloat()
        fun choice(icon: Int, desc: String, straight: Boolean) {
            val v = iconButton(icon, desc) {
                pdfView.hlStraight = straight
                prefs.edit().putBoolean("hl_straight", straight).apply()
                refreshTool()
                popup.dismiss()
            }
            setToolSelected(v, pdfView.hlStraight == straight)
            box.addView(v, LinearLayout.LayoutParams(dp(48), dp(40)).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        choice(R.drawable.ic_line_curve, "Freehand highlighter", false)
        choice(R.drawable.ic_line_solid, "Straight highlighter", true)
        popup.showAsDropDown(hlBtn, 0, dp(6))
    }

    /** Second tap on the pen: a small bubble with a solid and a dashed line to pick from. */
    private fun showPenStyleMenu() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = glass(14)
            elevation = dp(10).toFloat()
        }
        val popup = android.widget.PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = dp(10).toFloat()
        fun choice(icon: Int, desc: String, style: Int) {
            val v = iconButton(icon, desc) {
                pdfView.penStyle = style
                prefs.edit().putInt("line_style", style).apply()
                refreshTool()
                popup.dismiss()
            }
            setToolSelected(v, pdfView.penStyle == style)
            box.addView(v, LinearLayout.LayoutParams(dp(48), dp(40)).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        choice(R.drawable.ic_line_solid, "Solid line", LINE_SOLID)
        choice(R.drawable.ic_line_dashed, "Dashed line", LINE_DASHED)
        choice(R.drawable.ic_line_dotted, "Dotted line", LINE_DOTTED)
        popup.showAsDropDown(penBtn, 0, dp(6))
    }

    private fun setEraser(on: Boolean) {
        pdfView.eraserMode = on
        refreshTool()
    }

    private fun setColor(c: Int) {
        pdfView.penColor = c
        pdfView.eraserMode = false
        prefs.edit().putInt(colorKey(), c).apply()
        refreshTool()
    }

    private fun setWidth(w: Float) {
        pdfView.penWidth = w
        prefs.edit().putFloat(widthKey(), w).apply()
        val p = ((w / widthUnit()).roundToInt() - 1).coerceIn(0, 39)
        if (widthSeek.progress != p) widthSeek.progress = p
        refreshTool()
    }

    private fun updateButtons() {
        val d = active
        undoBtn.isEnabled = d?.canUndo == true
        undoBtn.alpha = if (undoBtn.isEnabled) 1f else 0.3f
        redoBtn.isEnabled = d?.canRedo == true
        redoBtn.alpha = if (redoBtn.isEnabled) 1f else 0.3f
        // refresh the "unsaved" dot on tabs only when it actually changes
        if (docs.any { shownUnsaved[it.id] != it.unsaved }) rebuildTabs()
    }

    /** Safari-style tabs: the active one is a soft pill, others are quiet text. */
    private fun rebuildTabs() {
        tabBar.removeAllViews()
        shownUnsaved.clear()
        for (d in docs) {
            shownUnsaved[d.id] = d.unsaved
            val isActive = d === active
            val tab = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(4), 0)
                background = RippleDrawable(
                    ColorStateList.valueOf(0x22FFFFFF),
                    if (isActive) rounded(SURFACE2, 10) else null,
                    rounded(Color.WHITE, 10),
                )
                isClickable = true
                setOnClickListener { activate(d) }
                // hold to pick the tab up, then drag sideways to reorder
                setOnLongClickListener { v -> startTabDrag(v, d); true }
                setOnTouchListener { v, e -> handleTabDrag(v, d, e) }
            }
            if (d.unsaved) {
                tab.addView(View(this).apply { background = oval(if (isActive) ACCENT else TEXT_DIM) },
                    LinearLayout.LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(7) })
            }
            tab.addView(TextView(this).apply {
                text = baseName(d.name)
                textSize = 13f
                setTextColor(if (isActive) TEXT else TEXT_DIM)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                // long names are shortened in the middle; the active tab gets a bit more room
                maxWidth = dp(if (isActive) 200 else if (docs.size > 5) 110 else 150)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            if (isActive) {
                // only the active tab can be closed, so a stray tap on another tab just switches to it
                tab.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_close)
                    imageTintList = ColorStateList.valueOf(TEXT_DIM)
                    contentDescription = "Close tab"
                    setPadding(dp(7), dp(7), dp(7), dp(7))
                    background = ripple()
                    isClickable = true
                    setOnClickListener { confirmClose(d) }
                }, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(12) })
            } else {
                tab.setPadding(dp(14), 0, dp(14), 0)
            }
            tabBar.addView(tab, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)
            ).apply { marginEnd = dp(4) })
        }
        afterTabsBuilt()
    }

    /** Hold a tab and let go without dragging: Rename / Close. */
    private fun showTabMenu(anchor: View, d: Doc) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Rename…")
        pm.menu.add(0, 2, 1, "Close tab")
        pm.setOnMenuItemClickListener { item ->
            if (docs.contains(d)) {
                when (item.itemId) {
                    1 -> showRenameDialog(d)
                    2 -> confirmClose(d)
                }
            }
            true
        }
        pm.show()
    }

    private fun showRenameDialog(d: Doc) {
        val current = baseName(d.name)
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            setSelectAllOnFocus(true)
            setTextColor(TEXT)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
            addView(TextView(this@MainActivity).apply {
                text = if (d.saveUri != null) "The saved file will be renamed too." else "Used as the file name when you save."
                textSize = 12f
                setTextColor(TEXT_DIM)
                setPadding(0, dp(6), 0, 0)
            })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Rename")
            .setView(box)
            .setPositiveButton("Rename") { _, _ ->
                val n = input.text.toString().trim().replace('/', '_')
                if (n.isNotEmpty() && n != current) renameDoc(d, n)
            }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        compact(dialog, 360)
        input.requestFocus()
    }

    /** Renames the tab and, if it has been saved, the file on the tablet. */
    private fun renameDoc(d: Doc, base: String) {
        val newName = "$base.pdf"
        val target = d.saveUri
        if (target == null) {
            d.name = newName
            markDirty(d)
            rebuildTabs()
            return
        }
        lifecycleScope.launch {
            val renamed = withContext(Dispatchers.IO) {
                try {
                    DocumentsContract.renameDocument(contentResolver, Uri.parse(target), newName)
                } catch (e: Exception) {
                    null
                }
            }
            d.name = newName
            if (renamed != null) {
                keepWriteAccess(renamed)
                d.saveUri = renamed.toString()
                if (d.originUri == target) d.originUri = renamed.toString()
                toast("Renamed")
            } else {
                toast("Tab renamed; this location doesn't allow renaming the saved file")
            }
            markDirty(d)
            rebuildTabs()
        }
    }

    /** Keeps the active tab visible and updates the tab counter. */
    private fun afterTabsBuilt() {
        tabCount.text = docs.size.toString()
        tabCount.visibility = if (docs.size >= 2) View.VISIBLE else View.GONE
        val i = docs.indexOf(active)
        if (i < 0) return
        tabBar.post {
            val v = tabBar.getChildAt(i) ?: return@post
            val viewW = tabScroll.width
            val l = v.left - dp(24)
            val r = v.right + dp(24)
            val x = tabScroll.scrollX
            if (l < x) tabScroll.smoothScrollTo(maxOf(0, l), 0)
            else if (r > x + viewW) tabScroll.smoothScrollTo(r - viewW, 0)
        }
    }

    /** Full list of open tabs (with their whole names); tap one to switch. */
    private fun showTabList(anchor: View) {
        val pm = PopupMenu(this, anchor)
        for ((k, d) in docs.withIndex()) {
            val title = (if (d.unsaved) "● " else "") + baseName(d.name)
            pm.menu.add(1, k, k, title).setCheckable(true).setChecked(d === active)
        }
        pm.menu.setGroupCheckable(1, true, true)
        docs.indexOf(active).takeIf { it >= 0 }?.let { pm.menu.findItem(it)?.setChecked(true) }
        pm.setOnMenuItemClickListener { item ->
            docs.getOrNull(item.itemId)?.let { activate(it) }
            true
        }
        pm.show()
    }

    // ---------------- tab reordering ----------------

    private var draggingTab: Doc? = null
    private var tabDragLastRawX = 0f
    private var tabDragStartRawX = 0f
    private var tabDragMoved = false

    private fun startTabDrag(v: View, d: Doc) {
        draggingTab = d
        tabDragMoved = false
        tabDragStartRawX = tabDragLastRawX
        v.parent?.requestDisallowInterceptTouchEvent(true)
        v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        v.elevation = dp(6).toFloat()
        v.animate().scaleX(1.06f).scaleY(1.06f).alpha(0.92f).setDuration(120).start()
    }

    /** Moves the held tab with the finger and swaps it with a neighbour once it passes the neighbour's middle. */
    private fun handleTabDrag(v: View, d: Doc, e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) tabDragLastRawX = e.rawX
        if (draggingTab !== d) return false // normal tap / long-press handling
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!tabDragMoved && kotlin.math.abs(e.rawX - tabDragStartRawX) > dp(8)) tabDragMoved = true
                v.translationX += e.rawX - tabDragLastRawX
                tabDragLastRawX = e.rawX
                val i = tabBar.indexOfChild(v)
                val centre = v.left + v.translationX + v.width / 2f
                if (i + 1 < tabBar.childCount) {
                    val next = tabBar.getChildAt(i + 1)
                    if (centre > next.left + next.width / 2f) {
                        val shift = next.width + ((next.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0)
                        tabBar.removeViewAt(i + 1)
                        tabBar.addView(next, i)
                        val di = docs.indexOf(d)
                        if (di >= 0 && di + 1 < docs.size) java.util.Collections.swap(docs, di, di + 1)
                        v.translationX -= shift
                    }
                }
                if (i - 1 >= 0) {
                    val prev = tabBar.getChildAt(i - 1)
                    if (centre < prev.left + prev.width / 2f) {
                        val shift = prev.width + ((prev.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0)
                        tabBar.removeViewAt(i - 1)
                        tabBar.addView(prev, i)
                        val di = docs.indexOf(d)
                        if (di > 0) java.util.Collections.swap(docs, di, di - 1)
                        v.translationX += shift
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingTab = null
                v.elevation = 0f
                val justHeld = !tabDragMoved && e.actionMasked == MotionEvent.ACTION_UP
                v.animate().translationX(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(150)
                    .withEndAction { if (!justHeld) rebuildTabs() }.start()
                if (justHeld) showTabMenu(v, d) else saveSession()
            }
        }
        return true
    }

    private fun updatePageBadge(current: Int = pdfView.currentPageIndex()) {
        val d = active
        if (d == null || d.pageCount == 0) {
            pageBadge.visibility = View.GONE
            return
        }
        pageBadge.text = "${current.coerceIn(0, d.pageCount - 1) + 1} / ${d.pageCount}"
        pageBadge.visibility = View.VISIBLE
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ======================= tabs =======================

    private fun activate(d: Doc) {
        if (active !== d) {
            active = d
            pdfView.setDoc(d)
            if (pagesPanel.visibility == View.VISIBLE) {
                pagesPanel.setDoc(d)
                pagesPanel.setCurrent(pdfView.currentPageIndex())
            }
        }
        updatePageBadge()
        rebuildTabs()
        updateButtons()
        saveSession()
    }

    private fun addDoc(d: Doc, makeActive: Boolean) {
        docs.add(d)
        if (makeActive || active == null) activate(d) else rebuildTabs()
    }

    private fun confirmClose(d: Doc) {
        if (d.busy) {
            toast("Wait until saving finishes")
            return
        }
        if (!(d.unsaved && d.hasContent)) {
            closeDoc(d)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Close “${d.name}”?")
            .setMessage("This tab has changes that haven't been saved to a file. " +
                "If you close it, you can still reopen it from the folder icon (Recently closed) for 7 days.")
            .setPositiveButton("Save first") { _, _ ->
                activate(d)
                save(d)
            }
            .setNeutralButton("Close anyway") { _, _ -> closeDoc(d) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun closeDoc(d: Doc) {
        d.closed = true
        val idx = docs.indexOf(d)
        docs.remove(d)
        dirtyDocs.remove(d)
        if (active === d) {
            active = null
            val next = if (docs.isEmpty()) null else docs[idx.coerceIn(0, docs.size - 1)]
            if (next != null) {
                activate(next)
            } else {
                pdfView.setDoc(null)
                pagesPanel.setDoc(null)
                updatePageBadge()
                rebuildTabs()
                updateButtons()
                saveSession()
            }
        } else {
            rebuildTabs()
            saveSession()
        }
        renderExec.execute { d.closeRenderer() }
        // write the final state, then keep the tab in "Recently closed" for 7 days instead of deleting it
        val finalState = Store.serialize(d)
        val ctx = applicationContext
        ioExec.execute {
            try {
                Store.writeAtomic(File(d.dir, "state.json"), finalState)
            } catch (e: Exception) {
                Log.e(TAG, "final save", e)
            }
            try {
                Store.moveToTrash(ctx, d.dir)
            } catch (e: Exception) {
                Log.e(TAG, "trash", e)
            }
        }
    }

    /** Open icon: open a PDF, or bring back a recently closed tab. */
    private fun showOpenMenu(anchor: View) {
        lifecycleScope.launch {
            val closed = withContext(Dispatchers.IO) {
                try { Store.listTrash(this@MainActivity) } catch (e: Exception) { emptyList() }
            }.take(10)
            val pm = PopupMenu(this@MainActivity, anchor)
            pm.menu.add(0, 0, 0, "Open PDF…")
            val fmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
            if (closed.isEmpty()) {
                pm.menu.add(1, -1, 1, "No recently closed tabs").setEnabled(false)
            } else {
                for ((k, c) in closed.withIndex()) {
                    pm.menu.add(1, k + 1, k + 1, "Reopen “${baseName(c.name)}” · closed ${fmt.format(Date(c.closedAt))}")
                }
            }
            pm.setOnMenuItemClickListener { item ->
                if (item.itemId == 0) openLauncher.launch(arrayOf("application/pdf"))
                else closed.getOrNull(item.itemId - 1)?.let { reopenClosed(it) }
                true
            }
            pm.show()
        }
    }

    private fun reopenClosed(c: Store.Closed) {
        lifecycleScope.launch {
            try {
                val dir = withContext(Dispatchers.IO) { Store.restoreFromTrash(this@MainActivity, c) }
                val d = withContext(renderDispatcher) { openDoc(dir.name, dir, c.name) }
                addDoc(d, true)
                markDirty(d)
                saveSession()
                toast("Reopened ${baseName(d.name)}")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "reopen", t)
                toast("Couldn't reopen: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    // ======================= pages =======================

    private fun togglePages() = setPagesVisible(pagesPanel.visibility != View.VISIBLE)

    /** The page overview on the left (thumbnails with ⋮ page actions). */
    private fun setPagesVisible(on: Boolean) {
        if (on && active == null) {
            toast("Open a PDF first")
            return
        }
        pagesPanel.visibility = if (on) View.VISIBLE else View.GONE
        pagesPanelDivider.visibility = pagesPanel.visibility
        if (on) {
            pagesPanel.setDoc(active)
            pagesPanel.setCurrent(pdfView.currentPageIndex())
            pagesPanel.scrollToCurrent()
        } else {
            pagesPanel.setDoc(null)
        }
        setToolSelected(pagesBtn, on)
        if (!on) pagesBtn.imageTintList = ColorStateList.valueOf(0xFFD1D1D6.toInt())
    }

    /**
     * Tablets make dialogs very wide by default; keep them compact.
     * [widthDp] is the visible card width (the window adds 24dp inset on each side).
     */
    private fun compact(dialog: AlertDialog, widthDp: Int): AlertDialog {
        dialog.window?.setLayout(dp(widthDp + 48), ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    /** Small iOS-style segmented control. */
    private fun segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = rounded(0xFF2C2C2E.toInt(), 10)
        }
        val items = ArrayList<TextView>()
        fun style(sel: Int) {
            for ((k, v) in items.withIndex()) {
                v.background = if (k == sel) rounded(0xFF636366.toInt(), 8) else null
                v.setTextColor(if (k == sel) TEXT else TEXT_DIM)
            }
        }
        for ((k, label) in options.withIndex()) {
            val v = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = dp(64)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                isClickable = true
                setOnClickListener { style(k); onSelect(k) }
            }
            items.add(v)
            bar.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        style(selected)
        return bar
    }

    /** New page: size (same as document / A4 / A5) and paper (blank / lined / grid). */
    private fun askNewPage(index: Int) {
        val d = active ?: return
        var size = prefs.getInt("newpage_size", 0)
        var paper = prefs.getInt("newpage_paper", PAPER_BLANK)
        var landscape = prefs.getBoolean("newpage_landscape", false)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }
        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 12f
            setTextColor(TEXT_DIM)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(5))
        }
        fun wrapLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // page size in points for the current choices
        fun dims(): Pair<Int, Int> {
            val (w0, h0) = when (size) {
                1 -> 595 to 842                 // A4
                2 -> 420 to 595                 // A5
                else -> {
                    val src = d.sources.firstOrNull()
                    if (src != null && src.w.isNotEmpty()) src.w[0] to src.h[0] else 595 to 842
                }
            }
            return if (landscape) maxOf(w0, h0) to minOf(w0, h0) else minOf(w0, h0) to maxOf(w0, h0)
        }
        val preview = PaperPreview(this)
        fun refresh() {
            val (w, h) = dims()
            preview.set(w, h, paper)
        }
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(200)))
        box.addView(label("Size"))
        box.addView(segmented(listOf("Document", "A4", "A5"), size) { size = it; refresh() }, wrapLp())
        box.addView(label("Orientation"))
        box.addView(segmented(listOf("Portrait", "Landscape"), if (landscape) 1 else 0) { landscape = it == 1; refresh() }, wrapLp())
        box.addView(label("Paper"))
        box.addView(segmented(listOf("Blank", "Lined", "Grid"), paper) { paper = it; refresh() }, wrapLp())
        refresh()
        AlertDialog.Builder(this)
            .setTitle("New page")
            .setView(box)
            .setPositiveButton("Add") { _, _ ->
                if (active !== d) return@setPositiveButton
                prefs.edit().putInt("newpage_size", size).putInt("newpage_paper", paper)
                    .putBoolean("newpage_landscape", landscape).apply()
                val (w, h) = dims()
                pdfView.insertBlankPage(index, w, h, paper)
            }
            .setNegativeButton("Cancel", null)
            .show()
            .let { compact(it, 340) }
    }

    /** "+" in the page overview: a new page at the end with the last page's size, paper and margins. */
    private fun addPageLikeLast() {
        val d = active ?: return
        val last = d.pages.lastOrNull()
        val page = Page(0, -1, last?.w ?: 595, last?.h ?: 842, if (last != null && last.isBlank) last.paper else PAPER_BLANK)
        if (last != null) {
            page.mL = last.mL
            page.mR = last.mR
            page.mPaper = last.mPaper
        }
        val at = d.pageCount
        pdfView.insertPages(at, listOf(page))
        pdfView.post { pdfView.scrollToPage(at) }
        pagesPanel.setCurrent(at)
        pagesPanel.scrollToCurrent()
    }

    /** ⋮ on a thumbnail. */
    private fun showThumbMenu(anchor: View, i: Int) {
        val d = active ?: return
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 2, 1, "Insert blank page after")
        pm.menu.add(0, 3, 2, "Insert PDF after this page")
        pm.menu.add(0, 4, 3, "Delete page")
        pm.setOnMenuItemClickListener { item ->
            if (active === d && i in 0 until d.pageCount) {
                when (item.itemId) {
                    2 -> askNewPage(i + 1)
                    3 -> { insertAfterIndex = i; pickPdf(d) }
                    4 -> if (!pdfView.deletePage(i)) toast("Can't delete the only page")
                }
            }
            true
        }
        pm.show()
    }

    private fun showPagesMenu(anchor: View) {
        val d = active ?: return
        val cur = pdfView.currentPageIndex().coerceIn(0, maxOf(0, d.pageCount - 1))
        val n = cur + 1
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Insert blank page after page $n")
        pm.menu.add(0, 2, 1, "Insert blank page before page $n")
        pm.menu.add(0, 3, 2, "Add blank page at the end")
        pm.menu.add(0, 4, 3, "Delete page $n")
        pm.setOnMenuItemClickListener { item ->
            if (active === d) {
                when (item.itemId) {
                    1 -> askNewPage(cur + 1)
                    2 -> askNewPage(cur)
                    3 -> askNewPage(d.pageCount)
                    4 -> {
                        if (pdfView.deletePage(cur)) toast("Page $n deleted (Undo brings it back)")
                        else toast("Can't delete the only page")
                    }
                }
            }
            true
        }
        pm.show()
    }

    // ======================= opening =======================

    private fun handleIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            else -> null
        }
        if (uri != null) importAll(listOf(uri), fromPicker = false)
    }

    private fun restoreSession(startIntent: Intent?) {
        lifecycleScope.launch {
            val (ids, activeId) = withContext(Dispatchers.IO) { Store.loadSession(this@MainActivity) }
            for (id in ids) {
                val dir = File(Store.root(this@MainActivity), id)
                try {
                    val d = withContext(renderDispatcher) { openDoc(id, dir, null) }
                    addDoc(d, false)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e(TAG, "restore $id", t)
                    toast("Couldn't restore a tab: ${t.message}")
                }
            }
            (docs.firstOrNull { it.id == activeId } ?: docs.firstOrNull())?.let { activate(it) }
            startIntent?.let { handleIntent(it) }
        }
    }

    /** A new tab with one blank A4 page; saved like any other document. */
    private fun newNote() {
        lifecycleScope.launch {
            val id = UUID.randomUUID().toString()
            val dir = File(Store.root(this@MainActivity), id)
            try {
                withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    val tmp = File(dir, "source.pdf.tmp")
                    com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
                        doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage(com.tom_roush.pdfbox.pdmodel.common.PDRectangle.A4))
                        doc.save(tmp)
                    }
                    if (!tmp.renameTo(File(dir, "source.pdf"))) throw IOException("can't create the note")
                }
                val name = "Note " + SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.getDefault()).format(Date()) + ".pdf"
                val d = withContext(renderDispatcher) { openDoc(id, dir, name) }
                d.unsaved = false
                addDoc(d, true)
                markDirty(d)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "new note", t)
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
                toast("Couldn't create a note: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun importAll(uris: List<Uri>, fromPicker: Boolean) {
        lifecycleScope.launch { for (u in uris) importOne(u, fromPicker) }
    }

    private suspend fun importOne(uri: Uri, fromPicker: Boolean) {
        val id = UUID.randomUUID().toString()
        val dir = File(Store.root(this), id)
        var name = "document.pdf"
        try {
            name = withContext(Dispatchers.IO) { queryName(uri) } ?: name
            val editable = withContext(Dispatchers.IO) {
                dir.mkdirs()
                val tmp = File(dir, "source.pdf.tmp")
                val input = contentResolver.openInputStream(uri) ?: throw IOException("can't read the file")
                input.use { inp -> FileOutputStream(tmp).use { out -> inp.copyTo(out); out.fd.sync() } }
                val src = File(dir, "source.pdf")
                if (!tmp.renameTo(src)) throw IOException("can't store the file")
                // A file saved by Margin carries the original + editable strokes inside it.
                Exporter.unpack(src, dir)
            }
            val d = withContext(renderDispatcher) { openDoc(id, dir, name) }
            d.name = name
            d.unsaved = false
            d.scale = 0f // fit to screen on first view
            // remember where the file came from, so "Save in place" can write back to it
            if (fromPicker) keepWriteAccess(uri)
            d.originUri = uri.toString()
            d.saveUri = if (editable && fromPicker) uri.toString() else null
            if (editable) toast("Opened with editable annotations")
            addDoc(d, true)
            markDirty(d)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.e(TAG, "import", t)
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            toast("Couldn't open $name: ${t.message ?: "unknown error"}")
        }
    }

    /** Tries to keep long-term read/write access to a document Uri. */
    private fun keepWriteAccess(uri: Uri): Boolean = try {
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        true
    } catch (e: Exception) {
        false
    }

    private fun queryName(uri: Uri): String? {
        var name: String? = null
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (ignored: Exception) {
        }
        if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
        return name
    }

    /** Opens one PDF file with PdfRenderer and reads its page sizes. Runs on the render thread. */
    private fun openSource(file: File, name: String): SourcePdf {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try {
            PdfRenderer(pfd)
        } catch (t: Throwable) {
            try { pfd.close() } catch (ignored: Throwable) {}
            throw IOException(if (t is SecurityException) "password-protected PDFs aren't supported" else "not a valid PDF")
        }
        try {
            val n = renderer.pageCount
            if (n <= 0) throw IOException("the PDF has no pages")
            val w = IntArray(n)
            val h = IntArray(n)
            for (i in 0 until n) {
                val p = renderer.openPage(i)
                try {
                    w[i] = maxOf(1, p.width)
                    h[i] = maxOf(1, p.height)
                } finally {
                    p.close()
                }
            }
            val sp = SourcePdf(name, w, h)
            sp.pfd = pfd
            sp.renderer = renderer
            return sp
        } catch (t: Throwable) {
            try { renderer.close() } catch (ignored: Throwable) {}
            try { pfd.close() } catch (ignored: Throwable) {}
            throw t
        }
    }

    /** Runs on the render thread. */
    private fun openDoc(id: String, dir: File, fallbackName: String?): Doc {
        val stateFile = File(dir, "state.json")
        // the working state, or its previous version if the latest copy is damaged
        val stateText: String? = Store.readJsonText(stateFile)
        val names = try {
            stateText?.let { Store.sourceNames(it) } ?: listOf("source.pdf")
        } catch (e: Exception) {
            listOf("source.pdf")
        }
        val sources = ArrayList<SourcePdf>()
        try {
            sources.add(openSource(File(dir, "source.pdf"), "source.pdf"))
            for (k in 1 until names.size) {
                val nm = names[k]
                sources.add(
                    try {
                        openSource(File(dir, nm), nm)
                    } catch (e: Exception) {
                        SourcePdf(nm, IntArray(0), IntArray(0)) // missing file: its pages show as blank
                    }
                )
            }
            var state: Store.State? = null
            if (stateText != null) {
                try {
                    state = Store.parse(stateText, sources)
                } catch (e: Exception) {
                    // keep the unreadable file aside instead of overwriting it
                    stateFile.renameTo(File(dir, "state.corrupt.${System.currentTimeMillis()}.json"))
                }
            }
            val d = Doc(id, dir, state?.name ?: fallbackName ?: "document.pdf", sources[0])
            for (k in 1 until sources.size) d.sources.add(sources[k])
            state?.applyTo(d)
            return d
        } catch (t: Throwable) {
            for (sp in sources) sp.close()
            throw t
        }
    }

    // ======================= working-state autosave =======================

    private fun markDirty(d: Doc) {
        dirtyDocs.add(d)
        main.removeCallbacks(flushRunnable)
        main.postDelayed(flushRunnable, 200)
    }

    private fun flushSaves(): Future<*>? {
        if (dirtyDocs.isEmpty()) return null
        val jobs = dirtyDocs.filter { !it.closed }.map { File(it.dir, "state.json") to Store.serialize(it) }
        dirtyDocs.clear()
        if (jobs.isEmpty()) return null
        return try {
            ioExec.submit {
                for ((f, text) in jobs) {
                    try {
                        if (f.parentFile?.exists() == true) Store.writeAtomic(f, text)
                    } catch (e: Exception) {
                        Log.e(TAG, "save failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun saveSession() {
        val text = Store.sessionText(docs.map { it.id }, active?.id)
        val f = Store.sessionFile(this)
        try {
            ioExec.execute {
                try {
                    Store.writeAtomic(f, text)
                } catch (e: Exception) {
                    Log.e(TAG, "session save failed", e)
                }
            }
        } catch (ignored: Exception) {
        }
    }

    // ======================= margins =======================

    private fun showMargins() {
        val d = active ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }

        // live preview of the page in the middle of the screen
        val pi = pdfView.currentPageIndex().coerceIn(0, maxOf(0, d.pageCount - 1))
        val page = d.pages.getOrNull(pi)
        val preview = MarginPreview(this, (page?.w ?: 595).toFloat(), (page?.h ?: 842).toFloat())

        // scope: all pages, or only the page on screen
        var thisPageOnly = page != null && (page.mL != null || page.mR != null)
        val segment = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = rounded(0xFF2C2C2E.toInt(), 10)
        }
        fun segItem(label: String): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            isClickable = true
        }
        val segAll = segItem("All pages")
        val segOne = segItem("This page (${pi + 1})")
        segment.addView(segAll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        segment.addView(segOne, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(segment, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(6)
        })
        preview.setMargins(
            if (thisPageOnly && page != null) d.leftOf(page) else d.marginL,
            if (thisPageOnly && page != null) d.rightOf(page) else d.marginR,
        )
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)).apply {
            bottomMargin = dp(10)
        })
        if (page != null && !page.isBlank) {
            lifecycleScope.launch {
                val bmp = withContext(renderDispatcher) {
                    try {
                        val r = d.sources.getOrNull(page.file)?.renderer
                        if (r == null || d.closed || page.src >= r.pageCount) null
                        else {
                            val pg = r.openPage(page.src)
                            try {
                                val w = 480
                                val h = maxOf(1, (w * page.h.toFloat() / page.w).toInt())
                                val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                                b.eraseColor(Color.WHITE)
                                pg.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                b
                            } finally {
                                pg.close()
                            }
                        }
                    } catch (t: Throwable) {
                        null
                    }
                }
                preview.pageBitmap = bmp
            }
        }

        lateinit var left: SeekBar
        lateinit var right: SeekBar
        var marginPaper = if (thisPageOnly && page != null) d.marginPaperOf(page) else d.marginPaper
        preview.setPaper(marginPaper)
        val paperBox = FrameLayout(this)
        fun buildPaperSegment() {
            paperBox.removeAllViews()
            paperBox.addView(segmented(listOf("Blank", "Lined", "Grid"), marginPaper) {
                marginPaper = it
                preview.setPaper(it)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        buildPaperSegment()
        fun row(title: String, init: Float): SeekBar {
            val label = TextView(this).apply { textSize = 14f; setTextColor(TEXT) }
            val sb = SeekBar(this).apply {
                max = 60 // 0..600 pt in 10 pt steps
                progressTintList = ColorStateList.valueOf(ACCENT)
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
            }
            sb.progress = (init / 10f).roundToInt().coerceIn(0, 60)
            label.text = "$title  ${sb.progress * 10} pt"
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    label.text = "$title  ${p * 10} pt"
                    preview.setMargins(left.progress * 10f, right.progress * 10f)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            box.addView(label)
            box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
            return sb
        }

        left = row("Left", if (thisPageOnly && page != null) d.leftOf(page) else d.marginL)
        right = row("Right", if (thisPageOnly && page != null) d.rightOf(page) else d.marginR)

        fun styleSegment() {
            for ((v, on) in listOf(segAll to !thisPageOnly, segOne to thisPageOnly)) {
                v.background = if (on) rounded(0xFF636366.toInt(), 8) else null
                v.setTextColor(if (on) TEXT else TEXT_DIM)
            }
        }
        fun switchScope(one: Boolean) {
            if (one && page == null) return
            thisPageOnly = one
            // show the values that apply to the chosen scope
            val l = if (one && page != null) d.leftOf(page) else d.marginL
            val r = if (one && page != null) d.rightOf(page) else d.marginR
            left.progress = (l / 10f).roundToInt().coerceIn(0, 60)
            right.progress = (r / 10f).roundToInt().coerceIn(0, 60)
            preview.setMargins(left.progress * 10f, right.progress * 10f)
            marginPaper = if (one && page != null) d.marginPaperOf(page) else d.marginPaper
            preview.setPaper(marginPaper)
            buildPaperSegment()
            styleSegment()
        }
        segAll.setOnClickListener { switchScope(false) }
        segOne.setOnClickListener { switchScope(true) }
        styleSegment()
        box.addView(TextView(this).apply {
            text = "Margin paper"
            textSize = 13f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(2), 0, dp(6))
        })
        box.addView(paperBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        box.addView(TextView(this).apply {
            text = "72 pt = 1 inch. The original page is never scaled."
            textSize = 12f
            setTextColor(TEXT_DIM)
            setPadding(0, 0, 0, dp(4))
        })
        AlertDialog.Builder(this)
            .setTitle("Margins")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                if (docs.contains(d) && active === d) {
                    if (thisPageOnly && page != null) pdfView.setPageMargins(d.pages.indexOf(page), left.progress * 10f, right.progress * 10f, marginPaper)
                    else pdfView.setMargins(left.progress * 10f, right.progress * 10f, marginPaper)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
            .let { compact(it, 400) }
    }

    // ======================= save / export / share =======================

    private fun baseName(name: String): String =
        if (name.endsWith(".pdf", ignoreCase = true)) name.dropLast(4) else name

    /** Save = PDF that also carries the original + editable strokes. Re-saves go to the same file. */
    private fun save(d: Doc) {
        if (d.busy) {
            toast("Busy, try again in a moment")
            return
        }
        val target = d.saveUri
        if (target != null) writeDoc(d, Uri.parse(target), isSave = true, fromPicker = false)
        else showSaveDialog(d)
    }

    /** One icon for saving: Save (editable in Margin), Save as / rename, or Export a plain PDF. */
    private fun showSaveMenu(anchor: View) {
        val d = active ?: run { toast("Open a PDF first"); return }
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, if (d.saveUri != null) "Save" else "Save…")
        pm.menu.add(0, 2, 1, "Save as / Rename…")
        pm.menu.add(0, 3, 2, "Export as plain PDF…")
        pm.setOnMenuItemClickListener { item ->
            if (active === d) {
                when (item.itemId) {
                    1 -> save(d)
                    2 -> showSaveDialog(d)
                    3 -> startExport(d)
                }
            }
            true
        }
        pm.show()
    }

    /** First save (or Save as): save in place, optionally renaming, or save as a new file. */
    private fun showSaveDialog(d: Doc) {
        if (d.busy) {
            toast("Busy, try again in a moment")
            return
        }
        val current = baseName(d.name)
        val input = EditText(this).apply {
            setText(current)
            setSingleLine()
            setSelectAllOnFocus(true)
            setTextColor(TEXT)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        box.addView(TextView(this).apply {
            text = "File name"
            textSize = 13f
            setTextColor(TEXT_DIM)
        })
        box.addView(input)
        val inPlaceTarget = d.saveUri ?: d.originUri
        box.addView(TextView(this).apply {
            text = if (inPlaceTarget != null)
                "Save in place overwrites the file you opened (renaming it if you changed the name). " +
                    "The untouched original stays inside it, so nothing is lost and Margin can still edit everything."
            else "This tab has no file to save back to, so choose a location."
            textSize = 12f
            setTextColor(TEXT_DIM)
            setPadding(0, dp(8), 0, 0)
        })
        val b = AlertDialog.Builder(this).setTitle("Save").setView(box)
        fun chosenName(): String {
            val n = input.text.toString().trim().replace('/', '_')
            return if (n.isEmpty()) current else n
        }
        if (inPlaceTarget != null) {
            b.setPositiveButton("Save in place") { _, _ ->
                val n = chosenName()
                writeDoc(d, Uri.parse(inPlaceTarget), isSave = true, fromPicker = false,
                    renameTo = if (n != current) "$n.pdf" else null)
            }
        }
        b.setNeutralButton("Save as new file…") { _, _ -> pickSaveLocation(d, chosenName()) }
        b.setNegativeButton("Cancel", null)
        compact(b.show(), 380)
    }

    private fun pickSaveLocation(d: Doc, base: String = baseName(d.name)) {
        pendingSaveId = d.id
        try {
            saveLauncher.launch("$base.pdf")
        } catch (e: Exception) {
            pendingSaveId = null
            toast("No app available to save files")
        }
    }

    private fun startExport(d: Doc) {
        if (d.busy) {
            toast("Busy, try again in a moment")
            return
        }
        pendingExportId = d.id
        try {
            exportLauncher.launch(baseName(d.name) + "_export.pdf")
        } catch (e: Exception) {
            pendingExportId = null
            toast("No app available to save files")
        }
    }

    private fun writeDoc(d: Doc, uri: Uri, isSave: Boolean, fromPicker: Boolean, renameTo: String? = null) {
        if (d.busy || d.closed) return
        val job = Exporter.snapshot(d, withEditData = isSave)
        val versionAtStart = d.version
        d.busy = true
        toast(if (isSave) "Saving…" else "Exporting…")
        lifecycleScope.launch {
            var retryWithPicker = false
            try {
                withContext(Dispatchers.IO) { Exporter.writeTo(applicationContext, job, uri) }
                if (isSave) {
                    var finalUri = uri
                    var renameFailed = false
                    if (renameTo != null) {
                        val renamed = withContext(Dispatchers.IO) {
                            try {
                                DocumentsContract.renameDocument(contentResolver, uri, renameTo)
                            } catch (e: Exception) {
                                null
                            }
                        }
                        if (renamed != null) {
                            finalUri = renamed
                            keepWriteAccess(renamed)
                            d.name = renameTo
                        } else renameFailed = true
                    } else if (fromPicker) {
                        // picked a new file: show its real name on the tab
                        withContext(Dispatchers.IO) { queryName(uri) }?.let { d.name = it }
                    }
                    d.saveUri = finalUri.toString()
                    d.originUri = finalUri.toString()
                    if (d.version == versionAtStart) d.unsaved = false
                    markDirty(d)
                    rebuildTabs()
                    updateButtons()
                    toast(if (renameFailed) "Saved, but this location doesn't allow renaming" else "Saved")
                } else {
                    toast("Exported")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "write", t)
                if (isSave && !fromPicker) {
                    // the old file is gone or no longer writable: ask where to save instead
                    d.saveUri = null
                    if (d.originUri == uri.toString()) d.originUri = null
                    markDirty(d)
                    retryWithPicker = true
                } else {
                    toast((if (isSave) "Save" else "Export") + " failed: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                d.busy = false
            }
            if (retryWithPicker && !d.closed) {
                toast("Choose where to save")
                pickSaveLocation(d)
            }
        }
    }

    private fun share(d: Doc) {
        if (d.busy) {
            toast("Busy, try again in a moment")
            return
        }
        val job = Exporter.snapshot(d, withEditData = false)
        val fileName = baseName(d.name).replace('/', '_') + ".pdf"
        d.busy = true
        toast("Preparing…")
        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "share")
                    dir.mkdirs()
                    dir.listFiles()?.forEach { it.delete() }
                    val f = File(dir, fileName)
                    Exporter.build(job, f)
                    f
                }
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, "Share PDF"))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "share", t)
                toast("Share failed: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                d.busy = false
            }
        }
    }

    // ======================= images & inserted PDFs =======================

    private fun pickImage(d: Doc) {
        pendingInsertId = d.id
        try {
            pickImageLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: Exception) {
            pendingInsertId = null
            toast("No gallery app available")
        }
    }

    private fun pickPdf(d: Doc) {
        pendingInsertId = d.id
        try {
            pickPdfLauncher.launch(arrayOf("application/pdf"))
        } catch (e: Exception) {
            pendingInsertId = null
            toast("No file picker available")
        }
    }

    /** Long-press on empty page space: menu at the finger, inserts go where you pressed. */
    private fun showPageMenu(x: Float, y: Float, pt: PlacePoint) {
        val d = active ?: return
        val pageIndex = d.pages.indexOf(pt.page)
        if (pageIndex < 0) return
        pressAnchor.translationX = x
        pressAnchor.translationY = y
        val page = pt.page
        lifecycleScope.launch {
            // a picture that's part of the PDF itself, under the finger?
            val found: PdfImages.Found? = if (page.isBlank) null else withContext(Dispatchers.IO) {
                try {
                    val sp = d.sources.getOrNull(page.file)
                    if (sp == null) null
                    else PdfImages.findAt(File(d.dir, sp.name), page.src, page.w, page.h, pt.x, pt.y)
                } catch (t: Throwable) {
                    Log.w(TAG, "find image", t)
                    null
                }
            }
            if (active !== d) return@launch
            val pm = PopupMenu(this@MainActivity, pressAnchor)
            if (found != null) pm.menu.add(0, 7, 0, "Copy this image")
            val canPaste = canPaste()
            pm.menu.add(0, 1, 0, if (canPaste) "Paste image here" else "Paste image (no image on clipboard)").setEnabled(canPaste)
            pm.menu.add(0, 5, 4, "Blank page after this page")
            pm.menu.add(0, 6, 5, "Delete this page")
            pm.setOnMenuItemClickListener { item ->
                if (active === d) {
                    val idx = d.pages.indexOf(pt.page)
                    when (item.itemId) {
                        1 -> { pendingPlace = pt; paste(d) }
                        5 -> if (idx >= 0) askNewPage(idx + 1)
                        6 -> {
                            if (idx >= 0 && pdfView.deletePage(idx)) toast("Page ${idx + 1} deleted (Undo brings it back)")
                            else toast("Can't delete the only page")
                        }
                        7 -> if (found != null) copyPdfImage(found)
                    }
                }
                true
            }
            pm.show()
        }
    }

    private fun showInsertMenu(anchor: View) {
        val d = active ?: run { toast("Open a PDF first"); return }
        pendingPlace = null
        insertAfterIndex = -1
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, "Image from gallery")
        pm.menu.add(0, 2, 1, "Take photo")
        pm.menu.add(0, 3, 2, "PDF file (pages after page ${pdfView.currentPageIndex() + 1})")
        pm.menu.add(0, 4, 3, "Paste image").setEnabled(canPaste())
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> pickImage(d)
                2 -> takePhoto(d)
                3 -> pickPdf(d)
                4 -> paste(d)
            }
            true
        }
        pm.show()
    }

    private fun takePhoto(d: Doc) {
        try {
            val dir = File(cacheDir, "camera")
            dir.mkdirs()
            val f = File(dir, "photo_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            pendingInsertId = d.id
            pendingCameraPath = f.path
            takePhotoLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            pendingInsertId = null
            pendingCameraPath = null
            toast("No camera app available")
        } catch (e: Exception) {
            pendingInsertId = null
            pendingCameraPath = null
            toast("Camera failed: ${e.message}")
        }
    }

    /** Stores the picture as an upright JPEG in the tab's folder and places it on the current page. */
    private fun insertImage(d: Doc, open: () -> java.io.InputStream?, cropAfter: Boolean, cleanup: File? = null) {
        lifecycleScope.launch {
            try {
                val rel = "images/${UUID.randomUUID()}.jpg"
                val size = withContext(Dispatchers.IO) {
                    try {
                        Pics.importAsJpeg(open, File(d.dir, rel))
                    } finally {
                        cleanup?.delete()
                    }
                }
                if (d.closed) return@launch
                if (active !== d) activate(d)
                val item = ImageItem(rel, size.first, size.second)
                val place = pendingPlace
                pendingPlace = null
                pdfView.placeImage(item, at = place)
                if (cropAfter) cropSelected()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "insert image", t)
                toast("Couldn't add the image: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** Copies another PDF into the tab and inserts all its pages after the current page. */
    private fun insertPdf(d: Doc, uri: Uri) {
        lifecycleScope.launch {
            val name = "src_${UUID.randomUUID()}.pdf"
            val file = File(d.dir, name)
            try {
                withContext(Dispatchers.IO) {
                    val tmp = File(d.dir, "$name.tmp")
                    val input = contentResolver.openInputStream(uri) ?: throw IOException("can't read the file")
                    input.use { inp -> FileOutputStream(tmp).use { out -> inp.copyTo(out); out.fd.sync() } }
                    if (!tmp.renameTo(file)) throw IOException("can't store the file")
                }
                val sp = withContext(renderDispatcher) { openSource(file, name) }
                if (d.closed) {
                    renderExec.execute { sp.close() }
                    return@launch
                }
                d.sources.add(sp)
                val fi = d.sources.size - 1
                val pages = (0 until sp.w.size).map { Page(fi, it, sp.w[it], sp.h[it]) }
                if (active !== d) activate(d)
                val after = if (insertAfterIndex >= 0) insertAfterIndex else pdfView.currentPageIndex()
                insertAfterIndex = -1
                pdfView.insertPages(after + 1, pages)
                toast("Inserted ${pages.size} page" + (if (pages.size == 1) "" else "s"))
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.e(TAG, "insert pdf", t)
                withContext(Dispatchers.IO) { file.delete() }
                toast("Couldn't insert the PDF: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private val selRect = FloatArray(4)

    /** Keeps the image bar next to the selected image: below it, or above if there's no room. */
    private fun placeSelBar(l: Float, t: Float, r: Float, b: Float) {
        selRect[0] = l; selRect[1] = t; selRect[2] = r; selRect[3] = b
        if (selBar.visibility != View.VISIBLE) return
        val bw = selBar.width
        val bh = selBar.height
        if (bw == 0 || bh == 0) {
            // not measured yet: try again right after layout
            selBar.post { placeSelBar(selRect[0], selRect[1], selRect[2], selRect[3]) }
            return
        }
        val sw = pdfView.width.toFloat()
        val sh = pdfView.height.toFloat()
        val gap = dp(10).toFloat()
        val m = dp(6).toFloat()
        var y = b + gap
        if (y + bh > sh - m) y = t - gap - bh                  // no room below: go above
        if (y < m) y = (sh - bh - m).coerceAtMost(b + gap)     // image fills the screen: stay on screen
        y = y.coerceIn(m, maxOf(m, sh - bh - m))
        val x = ((l + r) / 2f - bw / 2f).coerceIn(m, maxOf(m, sw - bw - m))
        selBar.translationX = x
        selBar.translationY = y
    }

    private fun updateSelBar(item: ImageItem?) {
        if (item == null) {
            selBar.visibility = View.GONE
            return
        }
        lockBtn.text = if (item.locked) "Unlock" else "Lock"
        selBar.visibility = View.VISIBLE
    }

    private fun toggleLock() {
        pdfView.changeSelected { it.locked = !it.locked }
        if (pdfView.selectedImage?.locked == true) pdfView.clearSelection()
    }

    private fun copySelected(cut: Boolean) {
        val d = active ?: return
        val item = pdfView.selectedImage ?: return
        val src = File(d.dir, item.file)
        lifecycleScope.launch {
            try {
                val copy = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "clip")
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val f = File(dir, "clip.jpg")
                    src.copyTo(f, overwrite = true)
                    f
                }
                setClip(Pics.Clip(copy, item.imgW, item.imgH, item.w, item.h, item.cl, item.ct, item.cr, item.cb))
                if (cut && pdfView.selectedImage === item) pdfView.deleteSelected()
                toast(if (cut) "Cut. Hold on a page to paste." else "Copied. Hold on a page to paste.")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                toast("Copy failed: ${t.message}")
            }
        }
    }

    /** Puts an image on Margin's clipboard and on Android's clipboard. */
    private fun setClip(c: Pics.Clip) {
        Pics.clip = c
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", c.file)
            getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newUri(contentResolver, "Zen image", uri))
        } catch (e: Exception) {
            Log.w(TAG, "system clipboard", e)
        }
    }

    /** Copies a picture that is part of the PDF page itself. */
    private fun copyPdfImage(found: PdfImages.Found) {
        lifecycleScope.launch {
            try {
                val c = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "clip")
                    dir.deleteRecursively()
                    dir.mkdirs()
                    val f = File(dir, "clip.jpg")
                    val size = Pics.saveJpeg(found.bitmap, f)
                    Pics.Clip(f, size.first, size.second, found.w, found.h, 0f, 0f, 1f, 1f)
                }
                setClip(c)
                toast("Image copied. Hold on a page to paste.")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                toast("Copy failed: ${t.message}")
            }
        }
    }

    /** An image on Android's clipboard (e.g. a screenshot from Mac2Tab), if there is one. */
    private fun clipboardImageUri(): Uri? {
        try {
            val cm = getSystemService(ClipboardManager::class.java) ?: return null
            val clip = cm.primaryClip ?: return null
            val desc = clip.description
            var descIsImage = false
            for (k in 0 until desc.mimeTypeCount) {
                if (desc.getMimeType(k).startsWith("image/")) descIsImage = true
            }
            for (k in 0 until clip.itemCount) {
                val uri = clip.getItemAt(k).uri ?: continue
                if (descIsImage) return uri
                val type = try { contentResolver.getType(uri) } catch (e: Exception) { null }
                if (type != null && type.startsWith("image/")) return uri
            }
        } catch (e: Exception) {
            Log.w(TAG, "clipboard", e)
        }
        return null
    }

    private fun canPaste(): Boolean = clipboardImageUri() != null || Pics.clip?.file?.exists() == true

    /** Pastes the clipboard image: Margin's own copy keeps its size and crop; others are imported. */
    private fun paste(d: Doc) {
        val uri = clipboardImageUri()
        val own = Pics.clip
        if (uri != null && !(uri.authority == "$packageName.files" && own != null && own.file.exists())) {
            insertImage(d, { contentResolver.openInputStream(uri) }, cropAfter = false)
        } else if (own != null && own.file.exists()) {
            pasteImage(d)
        } else {
            pendingPlace = null
            toast("There's no image on the clipboard")
        }
    }

    private fun pasteImage(d: Doc) {
        val c = Pics.clip ?: return
        lifecycleScope.launch {
            try {
                val rel = "images/${UUID.randomUUID()}.jpg"
                withContext(Dispatchers.IO) {
                    val target = File(d.dir, rel)
                    target.parentFile?.mkdirs()
                    c.file.copyTo(target, overwrite = true)
                }
                if (d.closed) return@launch
                if (active !== d) activate(d)
                val item = ImageItem(rel, c.imgW, c.imgH)
                item.cl = c.cl; item.ct = c.ct; item.cr = c.cr; item.cb = c.cb
                val place = pendingPlace
                pendingPlace = null
                pdfView.placeImage(item, c.w, c.h, place)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                toast("Paste failed: ${t.message}")
            }
        }
    }

    /** Full-screen crop editor for the selected image. */
    private fun cropSelected() {
        val d = active ?: return
        val item = pdfView.selectedImage ?: return
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { Pics.decodeForScreen(File(d.dir, item.file), 2048) }
            if (bmp == null) {
                toast("Couldn't load the image")
                return@launch
            }
            val crop = CropView(this@MainActivity, bmp, item.cl, item.ct, item.cr, item.cb)
            val dialog = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.BLACK)
            }
            box.addView(crop, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val bar = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(12))
            }
            bar.addView(button("Cancel") { dialog.dismiss() })
            bar.addView(button("Reset") { crop.reset() }, lp(10))
            bar.addView(button("Done") {
                val r = crop.result()
                if (pdfView.selectedImage === item) {
                    pdfView.changeSelected {
                        it.cl = r[0]; it.ct = r[1]; it.cr = r[2]; it.cb = r[3]
                        // keep the width, adjust height to the new shape
                        it.h = it.w / it.cropAspect
                    }
                }
                dialog.dismiss()
            }, lp(10))
            box.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            dialog.setContentView(box)
            dialog.show()
        }
    }
}
