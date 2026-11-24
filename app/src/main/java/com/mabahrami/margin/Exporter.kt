package com.mabahrami.margin

import android.content.Context
import android.graphics.Color
import android.net.Uri
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentNameDictionary
import com.tom_roush.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Writes the annotated PDF. Each original page is kept as-is (vector text and images,
 * links), its visible box is widened to make the side margins, and strokes are added
 * as vector paths. Nothing is rasterised, so the original quality is untouched.
 *
 * "Save" additionally embeds the untouched original PDF and the editable stroke data
 * as attachments inside the file, so Margin can reopen it fully editable.
 */
object Exporter {

    private const val EMBED_LEGACY_SOURCE = "margin-source.pdf"
    private const val EMBED_STATE = "margin-state.json"
    private const val EMBED_FILE_PREFIX = "margin-file:"

    class ImgSnap(
        val path: File,
        val x: Float, val y: Float, val w: Float, val h: Float,
        val cl: Float, val ct: Float, val cr: Float, val cb: Float,
        val front: Boolean = false,
    )

    class PageSnap(
        val file: Int, val src: Int, val w: Int, val h: Int,
        val strokes: List<Stroke>, val images: List<ImgSnap>,
        val paper: Int,
        /** Paper in the margin areas. */
        val marginPaper: Int,
        /** This page's effective margins (its own, or the document's). */
        val mL: Float, val mR: Float,
    )

    class Job(
        val sources: List<File>,
        val marginL: Float,
        val marginR: Float,
        val pages: List<PageSnap>,
        /** Non-null for Save: the editable state to embed. */
        val embedState: String?,
        /** For Save: every working file (relative name -> file) needed to reopen it editable. */
        val embedFiles: List<Pair<String, File>>,
    )

    fun snapshot(d: Doc, withEditData: Boolean): Job {
        val pages = d.pages.map { p ->
            PageSnap(p.file, p.src, p.w, p.h, ArrayList(p.strokes), p.images.map {
                ImgSnap(File(d.dir, it.file), it.x, it.y, it.w, it.h, it.cl, it.ct, it.cr, it.cb, it.front)
            }, if (p.isBlank) p.paper else PAPER_BLANK, d.marginPaperOf(p), d.leftOf(p), d.rightOf(p))
        }
        val files = ArrayList<Pair<String, File>>()
        if (withEditData) {
            for (s in d.sources) files.add(s.name to File(d.dir, s.name))
            val imgs = LinkedHashSet<String>()
            for (p in d.pages) for (im in p.images) imgs.add(im.file)
            for (f in imgs) files.add(f to File(d.dir, f))
        }
        return Job(
            sources = d.sources.map { File(d.dir, it.name) },
            marginL = d.marginL,
            marginR = d.marginR,
            pages = pages,
            embedState = if (withEditData) Store.serialize(d, withView = false) else null,
            embedFiles = files,
        )
    }

    /** Builds the PDF into [out] (a local file). */
    fun build(job: Job, out: File) {
        PDDocument.load(job.sources[0], MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true)
            val others = HashMap<Int, PDDocument?>()
            try {
                val images = HashMap<File, PDImageXObject?>()
                val n = doc.numberOfPages
                val srcPages = (0 until n).map { doc.getPage(it) }
                val used = BooleanArray(n)
                fun isMain(p: PageSnap) = p.file == 0 && p.src in 0 until n

                // 1) original pages: widen + draw, in place (keeps links and bookmarks)
                for (p in job.pages) {
                    if (isMain(p) && !used[p.src]) {
                        used[p.src] = true
                        processPage(doc, srcPages[p.src], job, p, images)
                    }
                }
                // 2) drop original pages the user deleted
                for (k in 0 until n) if (!used[k]) doc.removePage(srcPages[k])
                // 3) blank pages and pages from inserted PDFs, each placed before the next original page
                for ((idx, p) in job.pages.withIndex()) {
                    if (isMain(p)) continue
                    var newPage: PDPage? = null
                    if (p.file > 0 && p.src >= 0) {
                        val od = others.getOrPut(p.file) { openOther(job.sources.getOrNull(p.file)) }
                        if (od != null && p.src < od.numberOfPages) {
                            val imported = doc.importPage(od.getPage(p.src))
                            doc.removePage(imported)
                            processPage(doc, imported, job, p, images)
                            newPage = imported
                        }
                    }
                    val page = newPage ?: makeBlank(doc, job, p, images)
                    var next: PDPage? = null
                    for (j in idx + 1 until job.pages.size) {
                        val q = job.pages[j]
                        if (isMain(q)) {
                            next = srcPages[q.src]; break
                        }
                    }
                    if (next != null) doc.pages.insertBefore(page, next) else doc.addPage(page)
                }
                // 4) editable data for Save
                if (job.embedState != null) embed(doc, job)
                doc.save(out)
            } finally {
                for (od in others.values) try { od?.close() } catch (ignored: Exception) {}
            }
        }
    }

    private fun openOther(f: File?): PDDocument? = try {
        if (f == null || !f.exists()) null
        else PDDocument.load(f, MemoryUsageSetting.setupTempFileOnly()).also {
            if (it.isEncrypted) it.setAllSecurityToBeRemoved(true)
        }
    } catch (e: Exception) {
        null
    }

    /** Builds the PDF and writes it to a content Uri. */
    /** [keepCopy]: also leave a copy of what was written there (Zen's local copy of a cloud file). */
    fun writeTo(ctx: Context, job: Job, uri: Uri, keepCopy: File? = null) {
        val tmp = File(ctx.cacheDir, "out_${System.nanoTime()}.pdf")
        try {
            build(job, tmp)
            openOut(ctx, uri).use { out -> tmp.inputStream().use { it.copyTo(out) } }
            if (keepCopy != null) {
                try {
                    keepCopy.parentFile?.mkdirs()
                    tmp.copyTo(keepCopy, overwrite = true)
                } catch (e: Exception) {
                    keepCopy.delete() // only a speed-up: never a half copy
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun openOut(ctx: Context, uri: Uri): OutputStream {
        val cr = ctx.contentResolver
        // "wt" (write + truncate) first. Some storage apps don't know it: then "rwt", and only last plain "w",
        // which on some of them doesn't cut the old content off (a shorter new file would end in old bytes)
        val s = try {
            cr.openOutputStream(uri, "wt")
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            try {
                cr.openOutputStream(uri, "rwt")
            } catch (e2: SecurityException) {
                throw e2
            } catch (e2: Exception) {
                cr.openOutputStream(uri, "w")
            }
        }
        return s ?: throw IOException("Can't write to the chosen location")
    }

    // ---------- reading back a Margin file ----------

    /**
     * If [file] (dir/source.pdf) was saved by Margin, replaces the working folder's contents with
     * the embedded original, inserted PDFs, images and state. Returns true when editable data was found.
     */
    fun unpack(file: File, dir: File): Boolean {
        val tmpDir = File(dir, "unpack.tmp")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        var stateText: String? = null
        try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                val names = doc.documentCatalog.names ?: return cleanup(tmpDir)
                val map = names.embeddedFiles?.names ?: return cleanup(tmpDir)
                val st = map[EMBED_STATE]?.embeddedFile ?: return cleanup(tmpDir)
                for ((key, spec) in map) {
                    val rel = when {
                        key == EMBED_LEGACY_SOURCE -> "source.pdf"
                        key.startsWith(EMBED_FILE_PREFIX) -> key.removePrefix(EMBED_FILE_PREFIX)
                        else -> continue
                    }
                    if (!Store.safeRelPath(rel)) continue
                    val ef = spec.embeddedFile ?: continue
                    val target = File(tmpDir, rel)
                    target.parentFile?.mkdirs()
                    ef.createInputStream().use { inp -> FileOutputStream(target).use { out -> inp.copyTo(out); out.fd.sync() } }
                }
                stateText = st.createInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (e: Exception) {
            return cleanup(tmpDir)
        }
        val text = stateText ?: return cleanup(tmpDir)
        val newSource = File(tmpDir, "source.pdf")
        if (!newSource.exists() || newSource.length() == 0L) return cleanup(tmpDir)
        // move everything into place (source.pdf last, so a failure never leaves a half-replaced original)
        val files = tmpDir.walkTopDown().filter { it.isFile && it != newSource }.toList()
        for (f in files) {
            val target = File(dir, f.relativeTo(tmpDir).path)
            target.parentFile?.mkdirs()
            target.delete()
            if (!f.renameTo(target)) throw IOException("can't unpack the saved file")
        }
        Store.writeAtomic(File(dir, "state.json"), text)
        if (!newSource.renameTo(file)) {
            file.delete()
            if (!newSource.renameTo(file)) throw IOException("can't unpack the saved file")
        }
        tmpDir.deleteRecursively()
        return true
    }

    private fun cleanup(tmpDir: File): Boolean {
        tmpDir.deleteRecursively()
        return false
    }

    // ---------- internals ----------

    private fun embed(doc: PDDocument, job: Job) {
        val catalog = doc.documentCatalog
        val names = catalog.names ?: PDDocumentNameDictionary(catalog)
        val map = HashMap<String, PDComplexFileSpecification>()
        try {
            names.embeddedFiles?.names?.let { map.putAll(it) }
        } catch (ignored: Exception) {
        }
        map.keys.removeAll { it.startsWith("margin-") }
        for ((rel, f) in job.embedFiles) {
            if (!f.exists()) continue
            val mime = if (rel.endsWith(".pdf")) "application/pdf" else "image/jpeg"
            FileInputStream(f).use { inp ->
                map[EMBED_FILE_PREFIX + rel] = fileSpec(doc, EMBED_FILE_PREFIX + rel, mime, inp, f.length().toInt())
            }
        }
        val stateBytes = job.embedState!!.toByteArray(Charsets.UTF_8)
        map[EMBED_STATE] = fileSpec(doc, EMBED_STATE, "application/json", ByteArrayInputStream(stateBytes), stateBytes.size)
        val tree = PDEmbeddedFilesNameTreeNode()
        tree.setNames(map)
        names.setEmbeddedFiles(tree)
        catalog.setNames(names)
    }

    private fun fileSpec(doc: PDDocument, name: String, mime: String, data: InputStream, size: Int): PDComplexFileSpecification {
        val ef = PDEmbeddedFile(doc, data)
        ef.setSubtype(mime)
        ef.setSize(size)
        val fs = PDComplexFileSpecification()
        fs.setFile(name)
        fs.setEmbeddedFile(ef)
        return fs
    }

    private fun makeBlank(doc: PDDocument, job: Job, p: PageSnap, images: HashMap<File, PDImageXObject?>): PDPage {
        val mL = p.mL
        val mR = p.mR
        val page = PDPage(PDRectangle(mL + p.w + mR, p.h.toFloat()))
        val rules = paperLines(p.paper, p.w.toFloat(), p.h.toFloat())
        if (p.strokes.isNotEmpty() || p.images.isNotEmpty() || rules.isNotEmpty() ||
            (p.marginPaper != PAPER_BLANK && (p.mL > 0f || p.mR > 0f))) {
            PDPageContentStream(doc, page).use { cs ->
                cs.saveGraphicsState()
                cs.transform(Matrix(1f, 0f, 0f, -1f, mL, p.h.toFloat()))
                drawMarginRules(cs, p)
                if (rules.isNotEmpty()) {
                    // dim ruling, under everything else
                    cs.setStrokingColor(PDColor(floatArrayOf(0.82f, 0.82f, 0.84f), PDDeviceRGB.INSTANCE))
                    cs.setLineWidth(0.5f)
                    var k = 0
                    while (k + 3 < rules.size) {
                        cs.moveTo(rules[k], rules[k + 1]); cs.lineTo(rules[k + 2], rules[k + 3])
                        k += 4
                    }
                    cs.stroke()
                }
                drawOverlay(doc, cs, p, images)
                cs.restoreGraphicsState()
            }
        }
        return page
    }

    /** Dim ruling in the margin areas only (page-local visual coordinates). */
    private fun drawMarginRules(cs: PDPageContentStream, p: PageSnap) {
        if (p.marginPaper == PAPER_BLANK) return
        val h = p.h.toFloat()
        val parts = ArrayList<Pair<Float, FloatArray>>()
        if (p.mL > 0f) parts.add(-p.mL to paperLines(p.marginPaper, p.mL, h))
        if (p.mR > 0f) parts.add(p.w.toFloat() to paperLines(p.marginPaper, p.mR, h))
        if (parts.all { it.second.isEmpty() }) return
        cs.saveGraphicsState()
        cs.setStrokingColor(PDColor(floatArrayOf(0.82f, 0.82f, 0.84f), PDDeviceRGB.INSTANCE))
        cs.setLineWidth(0.5f)
        cs.setLineDashPattern(FloatArray(0), 0f)
        for ((x0, lines) in parts) {
            var k = 0
            while (k + 3 < lines.size) {
                cs.moveTo(x0 + lines[k], lines[k + 1]); cs.lineTo(x0 + lines[k + 2], lines[k + 3])
                k += 4
            }
        }
        cs.stroke()
        cs.restoreGraphicsState()
    }

    /** Draws images then strokes, in page-local "visual" coordinates (y down). */
    private fun drawOverlay(doc: PDDocument, cs: PDPageContentStream, p: PageSnap, images: HashMap<File, PDImageXObject?>) {
        drawImages(doc, cs, p.images.filter { !it.front }, images)
        drawWriting(doc, cs, p)
        drawImages(doc, cs, p.images.filter { it.front }, images) // brought to the front: over the writing
    }

    private fun drawImages(doc: PDDocument, cs: PDPageContentStream, list: List<ImgSnap>, images: HashMap<File, PDImageXObject?>) {
        for (im in list) {
            val xo = images.getOrPut(im.path) {
                try {
                    FileInputStream(im.path).use { JPEGFactory.createFromStream(doc, it) }
                } catch (e: Exception) {
                    null
                }
            } ?: continue
            val cw = (im.cr - im.cl).coerceAtLeast(0.01f)
            val ch = (im.cb - im.ct).coerceAtLeast(0.01f)
            val fullW = im.w / cw
            val fullH = im.h / ch
            val fx = im.x - im.cl * fullW
            val fy = im.y - im.ct * fullH
            cs.saveGraphicsState()
            cs.addRect(im.x, im.y, im.w, im.h)
            cs.clip()
            // unit square -> full image rect, flipped so the picture is upright
            cs.drawImage(xo, Matrix(fullW, 0f, 0f, -fullH, fx, fy + fullH))
            cs.restoreGraphicsState()
        }
    }

    private fun drawWriting(doc: PDDocument, cs: PDPageContentStream, p: PageSnap) {
        if (p.strokes.isNotEmpty()) {
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            // Highlighter first. All highlight strokes are drawn opaque inside one transparency group
            // (so overlapping passes don't get darker); the group is then painted once with Multiply
            // and partial alpha, which tints the paper but never covers the text.
            if (p.strokes.any { it.highlighter }) {
                val group = PDAppearanceStream(doc)
                group.setBBox(PDRectangle(-20000f, -20000f, 40000f, 40000f))
                group.setResources(PDResources())
                val g = COSDictionary()
                g.setItem(COSName.TYPE, COSName.getPDFName("Group"))
                g.setItem(COSName.S, COSName.getPDFName("Transparency"))
                group.getCOSObject().setItem(COSName.getPDFName("Group"), g)
                PDPageContentStream(doc, group).use { gcs ->
                    gcs.setLineCapStyle(1)
                    gcs.setLineJoinStyle(1)
                    for (s in p.strokes) if (s.highlighter) drawStroke(gcs, s)
                }
                fun paint(blend: String, alpha: Float) {
                    val gs = PDExtendedGraphicsState()
                    val dict = gs.getCOSObject()
                    dict.setItem(COSName.BM, COSName.getPDFName(blend))
                    dict.setFloat(COSName.getPDFName("ca"), alpha)
                    dict.setFloat(COSName.CA, alpha)
                    cs.setGraphicsStateParameters(gs)
                    cs.drawForm(group)
                }
                if (p.images.isEmpty()) {
                    cs.saveGraphicsState()
                    paint("Multiply", HIGHLIGHT_ALPHA)
                    cs.restoreGraphicsState()
                } else {
                    // as on screen: multiply on the paper, a plain see-through colour over pasted images
                    cs.saveGraphicsState()
                    cs.addRect(-20000f, -20000f, 40000f, 40000f)
                    for (im in p.images) cs.addRect(im.x, im.y, im.w, im.h)
                    cs.clipEvenOdd() // everywhere except the images
                    paint("Multiply", HIGHLIGHT_ALPHA)
                    cs.restoreGraphicsState()
                    cs.saveGraphicsState()
                    for (im in p.images) cs.addRect(im.x, im.y, im.w, im.h)
                    cs.clip() // only the images
                    paint("Normal", HIGHLIGHT_OVER_IMAGE_ALPHA)
                    cs.restoreGraphicsState()
                }
            }
            for (s in p.strokes) if (!s.highlighter) drawStroke(cs, s)
        }
    }

    private fun processPage(doc: PDDocument, page: PDPage, job: Job, p: PageSnap, images: HashMap<File, PDImageXObject?>) {
        val crop = page.cropBox
        val llx = crop.lowerLeftX
        val lly = crop.lowerLeftY
        val urx = crop.upperRightX
        val ury = crop.upperRightY
        val boxW = crop.width
        val boxH = crop.height
        val rot = ((page.rotation % 360) + 360) % 360
        val sideways = rot == 90 || rot == 270
        val visW = if (sideways) boxH else boxW
        val visH = if (sideways) boxW else boxH
        val sx = visW / p.w
        val sy = visH / p.h
        val mL = p.mL * sx
        val mR = p.mR * sx
        val marginRules = p.marginPaper != PAPER_BLANK && (p.mL > 0f || p.mR > 0f)
        val hasOverlay = p.strokes.isNotEmpty() || p.images.isNotEmpty() || marginRules
        val widen = mL > 0f || mR > 0f
        if (!widen && !hasOverlay) return

        if (widen) {
            // Clip the original content to its old box so nothing hidden outside it shows up in the margins.
            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true, false).use { cs ->
                cs.saveGraphicsState()
                cs.addRect(llx, lly, boxW, boxH)
                cs.clip()
            }
        }

        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, !widen).use { cs ->
            if (widen) cs.restoreGraphicsState()
            if (hasOverlay) {
                cs.saveGraphicsState()
                // Map "visual" coordinates (origin top-left of the page as displayed, y down) to PDF user space.
                cs.transform(visualToUser(rot, llx, lly, urx, ury))
                cs.transform(Matrix.getScaleInstance(sx, sy))
                drawMarginRules(cs, p)
                drawOverlay(doc, cs, p, images)
                cs.restoreGraphicsState()
            }
        }

        if (widen) {
            // Visual left/right map to different box edges depending on page rotation.
            val nb = when (rot) {
                90 -> PDRectangle(llx, lly - mL, boxW, boxH + mL + mR)
                180 -> PDRectangle(llx - mR, lly, boxW + mL + mR, boxH)
                270 -> PDRectangle(llx, lly - mR, boxW, boxH + mL + mR)
                else -> PDRectangle(llx - mL, lly, boxW + mL + mR, boxH)
            }
            val media = page.mediaBox
            val mx0 = minOf(media.lowerLeftX, nb.lowerLeftX)
            val my0 = minOf(media.lowerLeftY, nb.lowerLeftY)
            val mx1 = maxOf(media.upperRightX, nb.upperRightX)
            val my1 = maxOf(media.upperRightY, nb.upperRightY)
            page.mediaBox = PDRectangle(mx0, my0, mx1 - mx0, my1 - my0)
            page.cropBox = nb
            val dict = page.getCOSObject()
            dict.removeItem(COSName.TRIM_BOX)
            dict.removeItem(COSName.BLEED_BOX)
            dict.removeItem(COSName.ART_BOX)
        }
    }

    private fun visualToUser(rot: Int, llx: Float, lly: Float, urx: Float, ury: Float): Matrix = when (rot) {
        90 -> Matrix(0f, 1f, 1f, 0f, llx, lly)
        180 -> Matrix(-1f, 0f, 0f, 1f, urx, lly)
        270 -> Matrix(0f, -1f, -1f, 0f, urx, ury)
        else -> Matrix(1f, 0f, 0f, -1f, llx, ury)
    }

    private fun drawStroke(cs: PDPageContentStream, s: Stroke) {
        val p = s.pts
        val n = p.size / 2
        if (n == 0) return
        val c = s.color
        cs.setStrokingColor(
            PDColor(floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f), PDDeviceRGB.INSTANCE)
        )
        // pressure stroke: the same filled outline as on screen
        val outline = if (s.pw != null) {
            try { Pressure.outline(s) } catch (t: Throwable) { null }
        } else null
        if (outline != null) {
            cs.setNonStrokingColor(
                PDColor(floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f), PDDeviceRGB.INSTANCE)
            )
            var any = false
            for (poly in outline) {
                if (poly.size < 6) continue
                cs.moveTo(poly[0], poly[1])
                var i = 2
                while (i + 1 < poly.size) {
                    cs.lineTo(poly[i], poly[i + 1]); i += 2
                }
                cs.closePath()
                any = true
            }
            if (any) {
                cs.fill() // non-zero: the union of all parts
                return
            }
        }
        cs.setLineWidth(s.width)
        if (s.style == LINE_WAVY && n >= 2) {
            // corrugated: the same wavy polyline as on screen, as vectors
            val w = wavyPoints(p, p.size, s.poly, s.width)
            cs.setLineDashPattern(FloatArray(0), 0f)
            cs.moveTo(w[0], w[1])
            var k = 2
            while (k + 1 < w.size) {
                cs.lineTo(w[k], w[k + 1]); k += 2
            }
            cs.stroke()
            return
        }
        cs.setLineDashPattern(linePattern(s.style, s.width) ?: FloatArray(0), 0f)
        cs.moveTo(p[0], p[1])
        when {
            s.poly && n > 1 -> for (k in 1 until n) cs.lineTo(p[2 * k], p[2 * k + 1])
            n == 1 -> cs.lineTo(p[0] + 0.01f, p[1])
            n == 2 -> cs.lineTo(p[2], p[3])
            else -> {
                // Same curve as on screen: quadratic segments, written as cubic Béziers.
                var cx = p[0]
                var cy = p[1]
                val k23 = 2f / 3f
                for (k in 1 until n - 1) {
                    val qx = p[2 * k]
                    val qy = p[2 * k + 1]
                    val ex = (qx + p[2 * k + 2]) / 2f
                    val ey = (qy + p[2 * k + 3]) / 2f
                    cs.curveTo(
                        cx + k23 * (qx - cx), cy + k23 * (qy - cy),
                        ex + k23 * (qx - ex), ey + k23 * (qy - ey),
                        ex, ey,
                    )
                    cx = ex; cy = ey
                }
                cs.lineTo(p[2 * n - 2], p[2 * n - 1])
            }
        }
        cs.stroke()
    }
}
