package com.mabahrami.margin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Image helpers: importing photos as upright JPEGs, decoding for screen, and the in-app clipboard. */
object Pics {

    private const val MAX_IMPORT_SIDE = 3000

    /**
     * Decodes an image (any format Android reads), applies EXIF rotation, flattens transparency
     * onto white and stores it as a JPEG at [out]. Returns the stored pixel size.
     */
    fun importAsJpeg(open: () -> InputStream?, out: File): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (open() ?: throw IOException("can't read the image")).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not a supported image")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_IMPORT_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = (open() ?: throw IOException("can't read the image")).use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("not a supported image")
        val orientation = try {
            open()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
                ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        bmp = applyOrientation(bmp, orientation)
        return saveJpeg(bmp, out)
    }

    /** Saves a bitmap as a JPEG (transparency flattened onto white). Returns its pixel size. */
    fun saveJpeg(src: Bitmap, out: File): Pair<Int, Int> {
        var bmp = src
        if (bmp.hasAlpha() || bmp.config != Bitmap.Config.ARGB_8888) {
            val flat = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply {
                drawColor(Color.WHITE)
                drawBitmap(bmp, 0f, 0f, null)
            }
            bmp = flat
        }
        out.parentFile?.mkdirs()
        val tmp = File(out.parentFile, out.name + ".tmp")
        FileOutputStream(tmp).use {
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)) throw IOException("can't store the image")
            it.flush()
            it.fd.sync()
        }
        if (!tmp.renameTo(out)) throw IOException("can't store the image")
        return bmp.width to bmp.height
    }

    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f); m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f); m.postScale(-1f, 1f)
            }
            else -> return src
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Decodes a stored JPEG for display, at most [maxSide] pixels on its long side. */
    fun decodeForScreen(file: File, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (t: Throwable) {
        null
    }

    // ---------- in-app clipboard (works across tabs) ----------

    class Clip(
        val file: File,
        val imgW: Int, val imgH: Int,
        val w: Float, val h: Float,
        val cl: Float, val ct: Float, val cr: Float, val cb: Float,
    )

    var clip: Clip? = null

    /** Last lasso selection copied (strokes + images), shared across tabs. */
    var selClip: LassoClip? = null

    /** Label put on Android's clipboard when a lasso selection is copied. */
    const val SEL_LABEL = "Zen selection"
}
