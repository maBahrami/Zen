package com.mabahrami.margin

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Crash-safe persistence. Every tab lives in filesDir/docs/<id>/ with
 *  - source.pdf  : private copy of the original PDF (never modified)
 *  - state.json  : pages, margins, strokes, viewport
 * All writes go to a .tmp file, are fsynced, then renamed over the old file,
 * so a crash mid-write never corrupts the previous state.
 */
object Store {

    fun root(ctx: Context): File = File(ctx.filesDir, "docs").also { it.mkdirs() }

    /**
     * Crash-safe write: new content goes to a .tmp file (fsynced), the previous version is kept
     * as .bak, then the .tmp replaces the file. At every moment a complete copy exists on disk.
     */
    fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
            it.fd.sync()
        }
        if (f.exists()) {
            val bak = File(f.parentFile, f.name + ".bak")
            bak.delete()
            f.renameTo(bak)
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("Could not replace ${f.name}")
        }
    }

    /** Reads [f], falling back to its .bak copy if the main file is missing or not valid JSON. */
    fun readJsonText(f: File): String? {
        for (c in listOf(f, File(f.parentFile, f.name + ".bak"))) {
            try {
                if (!c.exists()) continue
                val t = c.readText()
                JSONObject(t) // validate
                return t
            } catch (e: Exception) {
                // try the next copy
            }
        }
        return null
    }

    // ---------- recently closed tabs (kept 7 days) ----------

    class Closed(val dir: File, val name: String, val closedAt: Long)

    private const val TRASH_DAYS = 7L

    fun trashRoot(ctx: Context): File = File(ctx.filesDir, "trash").also { it.mkdirs() }

    /** Moves a closed tab's folder to the trash instead of deleting it. */
    fun moveToTrash(ctx: Context, dir: File) {
        if (!dir.exists()) return
        val target = File(trashRoot(ctx), dir.name)
        target.deleteRecursively()
        try {
            File(dir, "closed_at").writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
        }
        if (!dir.renameTo(target)) {
            dir.copyRecursively(target, overwrite = true)
            dir.deleteRecursively()
        }
    }

    fun listTrash(ctx: Context): List<Closed> {
        val out = ArrayList<Closed>()
        for (dir in trashRoot(ctx).listFiles() ?: emptyArray()) {
            if (!dir.isDirectory || !File(dir, "source.pdf").exists()) continue
            val at = try { File(dir, "closed_at").readText().trim().toLong() } catch (e: Exception) { dir.lastModified() }
            val name = try {
                readJsonText(File(dir, "state.json"))?.let { JSONObject(it).optString("name") }?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            } ?: "Document"
            out.add(Closed(dir, name, at))
        }
        out.sortByDescending { it.closedAt }
        return out
    }

    /** Moves a closed tab back; returns its new folder. */
    fun restoreFromTrash(ctx: Context, c: Closed): File {
        val target = File(root(ctx), c.dir.name)
        if (target.exists()) throw IOException("already open")
        File(c.dir, "closed_at").delete()
        if (!c.dir.renameTo(target)) {
            c.dir.copyRecursively(target, overwrite = true)
            c.dir.deleteRecursively()
        }
        return target
    }

    fun purgeTrash(ctx: Context) {
        val limit = System.currentTimeMillis() - TRASH_DAYS * 24L * 3600L * 1000L
        for (c in listTrash(ctx)) if (c.closedAt < limit) c.dir.deleteRecursively()
    }

    // ---------- document state ----------

    private val SOURCE_NAME = Regex("source\\.pdf|src_[A-Za-z0-9-]+\\.pdf")
    private val IMAGE_NAME = Regex("images/[A-Za-z0-9-]+\\.jpg")

    /** True for file names we create ourselves (guards against paths like ../x inside saved files). */
    fun safeRelPath(rel: String): Boolean = SOURCE_NAME.matches(rel) || IMAGE_NAME.matches(rel)

    /** [withView]: include display-only settings (dark view); false for state embedded in saved PDFs. */
    fun serialize(d: Doc, withView: Boolean = true): String {
        val sb = StringBuilder(4096)
        sb.append("{\"v\":3,\"name\":").append(JSONObject.quote(d.name))
        if (withView && d.dark) sb.append(",\"dark\":true")
        if (withView && d.zoomLocked) sb.append(",\"zl\":true")
        sb.append(",\"save\":").append(JSONObject.quote(d.saveUri ?: ""))
        sb.append(",\"origin\":").append(JSONObject.quote(d.originUri ?: ""))
        sb.append(",\"mL\":"); num(sb, d.marginL)
        sb.append(",\"mR\":"); num(sb, d.marginR)
        sb.append(",\"mp\":").append(d.marginPaper)
        sb.append(",\"scale\":"); num(sb, d.scale)
        sb.append(",\"tx\":"); num(sb, d.tx)
        sb.append(",\"ty\":"); num(sb, d.ty)
        sb.append(",\"unsaved\":").append(d.unsaved)
        d.lastAdded?.let { sb.append(",\"la\":[").append(it[0]).append(',').append(it[1]).append(',').append(it[2]).append(']') }
        sb.append(",\"sources\":[")
        for ((i, src) in d.sources.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append(JSONObject.quote(src.name))
        }
        sb.append("],\"pages\":[")
        for ((i, page) in d.pages.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"f\":").append(page.file)
                .append(",\"src\":").append(page.src)
                .append(",\"w\":").append(page.w)
                .append(",\"h\":").append(page.h)
            if (page.paper != PAPER_BLANK) sb.append(",\"pp\":").append(page.paper)
            page.mL?.let { sb.append(",\"ml\":"); num(sb, it) }
            page.mR?.let { sb.append(",\"mr\":"); num(sb, it) }
            page.mPaper?.let { sb.append(",\"mpp\":").append(it) }
            sb.append(",\"s\":")
            strokesJson(sb, page.strokes)
            sb.append(",\"i\":[")
            for ((k, im) in page.images.withIndex()) {
                if (k > 0) sb.append(',')
                sb.append("{\"file\":").append(JSONObject.quote(im.file))
                    .append(",\"iw\":").append(im.imgW)
                    .append(",\"ih\":").append(im.imgH)
                sb.append(",\"x\":"); num(sb, im.x)
                sb.append(",\"y\":"); num(sb, im.y)
                sb.append(",\"w\":"); num(sb, im.w)
                sb.append(",\"h\":"); num(sb, im.h)
                sb.append(",\"c\":[")
                crop(sb, im.cl); sb.append(','); crop(sb, im.ct); sb.append(',')
                crop(sb, im.cr); sb.append(','); crop(sb, im.cb)
                sb.append("],\"lock\":").append(im.locked).append('}')
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun crop(sb: StringBuilder, v: Float) {
        sb.append(if (v.isFinite()) Math.round(v * 100000.0) / 100000.0 else 0.0)
    }

    /** Names of the PDF files a saved state refers to (index 0 is always the original). */
    fun sourceNames(text: String): List<String> {
        val arr = JSONObject(text).optJSONArray("sources") ?: return listOf("source.pdf")
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val n = arr.optString(i)
            out.add(if (i == 0) "source.pdf" else if (SOURCE_NAME.matches(n)) n else "src_invalid.pdf")
        }
        if (out.isEmpty()) out.add("source.pdf")
        return out
    }

    private fun parseImages(arr: JSONArray?, into: MutableList<ImageItem>) {
        if (arr == null) return
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val file = o.optString("file")
            if (!IMAGE_NAME.matches(file)) continue
            val iw = o.optInt("iw", 0)
            val ih = o.optInt("ih", 0)
            if (iw <= 0 || ih <= 0) continue
            val im = ImageItem(file, iw, ih)
            im.x = o.optDouble("x", 0.0).toFloat()
            im.y = o.optDouble("y", 0.0).toFloat()
            im.w = o.optDouble("w", 100.0).toFloat().coerceIn(2f, 20000f)
            im.h = o.optDouble("h", 100.0).toFloat().coerceIn(2f, 20000f)
            val c = o.optJSONArray("c")
            if (c != null && c.length() == 4) {
                val l = c.optDouble(0, 0.0).toFloat().coerceIn(0f, 1f)
                val t = c.optDouble(1, 0.0).toFloat().coerceIn(0f, 1f)
                val r = c.optDouble(2, 1.0).toFloat().coerceIn(0f, 1f)
                val b = c.optDouble(3, 1.0).toFloat().coerceIn(0f, 1f)
                if (r - l >= 0.01f && b - t >= 0.01f) {
                    im.cl = l; im.ct = t; im.cr = r; im.cb = b
                }
            }
            im.locked = o.optBoolean("lock", false)
            into.add(im)
        }
    }

    private fun strokesJson(sb: StringBuilder, list: List<Stroke>) {
        sb.append('[')
        var first = true
        for (s in list) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"c\":").append(s.color)
            if (s.style != LINE_SOLID) sb.append(",\"st\":").append(s.style)
            if (s.highlighter) sb.append(",\"hl\":true")
            if (s.poly) sb.append(",\"pl\":true")
            sb.append(",\"w\":")
            num(sb, s.width)
            sb.append(",\"p\":[")
            for (j in s.pts.indices) {
                if (j > 0) sb.append(',')
                num(sb, s.pts[j])
            }
            sb.append(']')
            val pw = s.pw
            if (pw != null) {
                sb.append(",\"pw\":[")
                for (j in pw.indices) {
                    if (j > 0) sb.append(',')
                    num(sb, pw[j])
                }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append(']')
    }

    private fun num(sb: StringBuilder, v: Float) {
        if (!v.isFinite()) {
            sb.append('0'); return
        }
        sb.append(Math.round(v * 100.0) / 100.0)
    }

    class State(
        val name: String?,
        val saveUri: String?,
        val originUri: String?,
        val mL: Float, val mR: Float, val mp: Int,
        val scale: Float, val tx: Float, val ty: Float,
        val unsaved: Boolean,
        val pages: List<Page>,
        val dark: Boolean = false,
        val lastAdded: IntArray? = null,
        val zoomLocked: Boolean = false,
    ) {
        fun applyTo(d: Doc) {
            d.dark = dark
            d.zoomLocked = zoomLocked
            d.lastAdded = lastAdded
            d.marginL = mL.coerceIn(0f, 5000f)
            d.marginR = mR.coerceIn(0f, 5000f)
            d.marginPaper = mp
            if (scale > 0f && scale.isFinite()) {
                d.scale = scale; d.tx = tx; d.ty = ty
            }
            d.unsaved = unsaved
            d.saveUri = saveUri
            d.originUri = originUri
            if (pages.isNotEmpty()) {
                d.pages.clear()
                d.pages.addAll(pages)
            }
        }
    }

    private fun parseStrokes(arr: JSONArray?, into: MutableList<Stroke>) {
        if (arr == null) return
        for (k in 0 until arr.length()) {
            val so = arr.optJSONObject(k) ?: continue
            val pa = so.optJSONArray("p") ?: continue
            val n = pa.length()
            if (n < 2 || n % 2 != 0) continue
            val pts = FloatArray(n) { pa.optDouble(it, 0.0).toFloat() }
            val w = so.optDouble("w", 1.5).toFloat().coerceIn(0.1f, 60f)
            val style = if (so.has("st")) so.optInt("st", LINE_SOLID).coerceIn(LINE_SOLID, LINE_WAVY)
            else if (so.optBoolean("d", false)) LINE_DASHED else LINE_SOLID
            // pressure widths: used only if they fit the points; otherwise the stroke is simply even
            val wa = so.optJSONArray("pw")
            val pw = if (wa != null && wa.length() == n / 2) {
                FloatArray(wa.length()) { wa.optDouble(it, 1.0).toFloat().coerceIn(0.3f, 2f) }
            } else null
            into.add(Stroke(so.optInt("c", Color.BLACK), w, pts, style, so.optBoolean("hl", false), so.optBoolean("pl", false), pw))
        }
    }

    /** Reads state.json (format v1, v2 or v3). Page sizes of real pages always come from the PDFs themselves. */
    fun parse(text: String, sources: List<SourcePdf>): State {
        val o = JSONObject(text)
        val main = sources[0]
        val n = main.w.size
        val pages = ArrayList<Page>()
        val arr = o.optJSONArray("pages")
        if (o.optInt("v", 1) >= 2) {
            if (arr != null) for (i in 0 until arr.length()) {
                val po = arr.optJSONObject(i) ?: continue
                val f = po.optInt("f", 0)
                val src = po.optInt("src", -1)
                val sp = sources.getOrNull(f)
                val page = if (sp != null && src in 0 until sp.w.size) {
                    Page(f, src, sp.w[src], sp.h[src])
                } else {
                    val w = po.optInt("w", 595).coerceIn(10, 20000)
                    val h = po.optInt("h", 842).coerceIn(10, 20000)
                    Page(0, -1, w, h, po.optInt("pp", PAPER_BLANK).coerceIn(PAPER_BLANK, PAPER_GRID))
                }
                if (po.has("ml")) page.mL = po.optDouble("ml", 0.0).toFloat().coerceIn(0f, 5000f)
                if (po.has("mr")) page.mR = po.optDouble("mr", 0.0).toFloat().coerceIn(0f, 5000f)
                if (po.has("mpp")) page.mPaper = po.optInt("mpp", PAPER_BLANK).coerceIn(PAPER_BLANK, PAPER_GRID)
                parseStrokes(po.optJSONArray("s"), page.strokes)
                parseImages(po.optJSONArray("i"), page.images)
                pages.add(page)
            }
        } else {
            // v1: one stroke array per original page
            for (i in 0 until n) {
                val page = Page(0, i, main.w[i], main.h[i])
                parseStrokes(arr?.optJSONArray(i), page.strokes)
                pages.add(page)
            }
        }
        return State(
            name = o.optString("name").takeIf { it.isNotEmpty() },
            saveUri = o.optString("save").takeIf { it.isNotEmpty() },
            originUri = o.optString("origin").takeIf { it.isNotEmpty() },
            mL = o.optDouble("mL", 0.0).toFloat(),
            mR = o.optDouble("mR", 0.0).toFloat(),
            mp = o.optInt("mp", PAPER_BLANK).coerceIn(PAPER_BLANK, PAPER_GRID),
            scale = o.optDouble("scale", 0.0).toFloat(),
            tx = o.optDouble("tx", 0.0).toFloat(),
            ty = o.optDouble("ty", 0.0).toFloat(),
            unsaved = o.optBoolean("unsaved", o.optBoolean("dirty", false)),
            pages = pages,
            dark = o.optBoolean("dark", false),
            zoomLocked = o.optBoolean("zl", false),
            lastAdded = o.optJSONArray("la")?.let { a ->
                if (a.length() != 3) null
                else intArrayOf(
                    a.optInt(0, 595).coerceIn(10, 20000),
                    a.optInt(1, 842).coerceIn(10, 20000),
                    a.optInt(2, PAPER_BLANK).coerceIn(PAPER_BLANK, PAPER_GRID),
                )
            },
        )
    }

    // ---------- open tabs ----------

    fun sessionText(ids: List<String>, active: String?): String {
        val o = JSONObject()
        o.put("tabs", JSONArray(ids))
        o.put("active", active ?: "")
        return o.toString()
    }

    fun sessionFile(ctx: Context) = File(ctx.filesDir, "session.json")

    /** Tab ids in saved order, plus any folder that still holds a PDF (so no tab is ever lost). */
    fun loadSession(ctx: Context): Pair<List<String>, String?> {
        val ids = ArrayList<String>()
        var active: String? = null
        try {
            val text = readJsonText(sessionFile(ctx))
            if (text != null) {
                val o = JSONObject(text)
                val arr = o.optJSONArray("tabs")
                if (arr != null) for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isNotEmpty() && id !in ids) ids.add(id)
                }
                active = o.optString("active").ifEmpty { null }
            }
        } catch (ignored: Exception) {
        }
        val dirs = root(ctx).listFiles()?.filter { it.isDirectory } ?: emptyList()
        for (dir in dirs) {
            if (!File(dir, "source.pdf").exists()) dir.deleteRecursively() // failed import leftovers
        }
        dirs.filter { File(it, "source.pdf").exists() && it.name !in ids }
            .sortedBy { it.lastModified() }
            .forEach { ids.add(it.name) }
        ids.retainAll { File(File(root(ctx), it), "source.pdf").exists() }
        return ids to active
    }
}
