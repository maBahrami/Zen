package com.mabahrami.margin

import android.graphics.Bitmap
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/** Finds pictures that are part of a PDF page (not Margin images) so they can be copied. */
object PdfImages {

    /** A picture found on a page: its pixels and where it sits, in page-local points. */
    class Found(val bitmap: Bitmap, val x: Float, val y: Float, val w: Float, val h: Float)

    /**
     * Returns the top-most picture drawn at page-local point (px, py) on page [pageIndex] of [file],
     * or null. [pageW]/[pageH] are the page size Margin uses for that page.
     */
    fun findAt(file: File, pageIndex: Int, pageW: Int, pageH: Int, px: Float, py: Float): Found? {
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (pageIndex !in 0 until doc.numberOfPages) return null
            val page = doc.getPage(pageIndex)
            val crop = page.cropBox
            val llx = crop.lowerLeftX
            val lly = crop.lowerLeftY
            val urx = crop.upperRightX
            val ury = crop.upperRightY
            val rot = ((page.rotation % 360) + 360) % 360
            val sideways = rot == 90 || rot == 270
            val visW = if (sideways) crop.height else crop.width
            val visH = if (sideways) crop.width else crop.height
            val sx = visW / pageW
            val sy = visH / pageH

            val finder = Finder()
            finder.processPage(page)

            // last drawn = top-most
            for (k in finder.found.indices.reversed()) {
                val (img, m) = finder.found[k]
                // corners of the unit square in PDF user space
                val a = m.scaleX
                val b = m.shearY
                val c = m.shearX
                val d = m.scaleY
                val e = m.translateX
                val f = m.translateY
                var minX = Float.MAX_VALUE
                var minY = Float.MAX_VALUE
                var maxX = -Float.MAX_VALUE
                var maxY = -Float.MAX_VALUE
                for (corner in 0..3) {
                    val u = if (corner == 1 || corner == 3) 1f else 0f
                    val v = if (corner >= 2) 1f else 0f
                    val ux = a * u + c * v + e
                    val uy = b * u + d * v + f
                    // user space -> Margin's page coordinates (origin top-left, y down)
                    val vx: Float
                    val vy: Float
                    when (rot) {
                        90 -> { vx = (uy - lly) / sx; vy = (ux - llx) / sy }
                        180 -> { vx = (urx - ux) / sx; vy = (uy - lly) / sy }
                        270 -> { vx = (ury - uy) / sx; vy = (urx - ux) / sy }
                        else -> { vx = (ux - llx) / sx; vy = (ury - uy) / sy }
                    }
                    minX = minOf(minX, vx); maxX = maxOf(maxX, vx)
                    minY = minOf(minY, vy); maxY = maxOf(maxY, vy)
                }
                if (px < minX || px > maxX || py < minY || py > maxY) continue
                if (maxX - minX < 4f || maxY - minY < 4f) continue
                val bmp = try {
                    img.image
                } catch (t: Throwable) {
                    null
                } ?: continue
                return Found(bmp, minX, minY, maxX - minX, maxY - minY)
            }
        }
        return null
    }

    private class Finder : PDFStreamEngine() {
        val found = ArrayList<Pair<PDImageXObject, Matrix>>()

        init {
            addOperator(Concatenate())
            addOperator(SetGraphicsStateParameters())
            addOperator(Save())
            addOperator(Restore())
            addOperator(SetMatrix())
        }

        override fun processOperator(operator: Operator, operands: List<COSBase>) {
            if (operator.name == "Do") {
                val name = operands.firstOrNull() as? COSName ?: return
                val xo = try {
                    resources?.getXObject(name)
                } catch (e: Exception) {
                    null
                }
                when (xo) {
                    is PDImageXObject -> found.add(xo to graphicsState.currentTransformationMatrix.clone())
                    is PDFormXObject -> try {
                        showForm(xo)
                    } catch (e: Exception) {
                        // ignore broken forms
                    }
                }
            } else {
                super.processOperator(operator, operands)
            }
        }
    }
}
