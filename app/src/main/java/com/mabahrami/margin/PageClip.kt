package com.mabahrami.margin

import java.io.File

/**
 * One copied page. Everything needed to rebuild it in any tab: its size and paper, which page of which
 * PDF it shows ([srcName] / [srcIndex], or srcIndex = -1 for a blank page), its ink, images and margins.
 */
class ClipPage(
    val w: Int,
    val h: Int,
    val paper: Int,
    /** Name of the PDF in the original tab (e.g. "source.pdf"); null for a blank page or a missing PDF. */
    val srcName: String?,
    val srcIndex: Int,
    /** The page's own margins in its tab (null = it used the tab's). */
    val ownL: Float?,
    val ownR: Float?,
    val ownPaper: Int?,
    /** The margins it actually showed with, used when it goes into another tab. */
    val effL: Float,
    val effR: Float,
    val effPaper: Int,
    val strokes: List<Stroke>,
    val images: List<ClipImage>,
)

/**
 * Zen's page clipboard. [files] maps each PDF name to a private snapshot of that PDF, so the pages
 * can be pasted even after the tab they came from was closed or changed.
 */
class PageClip(val docId: String, val pages: List<ClipPage>, val files: Map<String, File>)

object PageClipboard {
    @Volatile
    var clip: PageClip? = null
}
