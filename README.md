# Zen

A minimal PDF annotator for the Samsung Galaxy Tab S9+ (S Pen), built for studying papers and lecture notes.

**Version 1.3** · [Download the latest APK](https://github.com/maBahrami/Zen/releases/latest)

## Features

- **Favorite folders** – the Open menu lists folders you add with *Add favorite folder…*; tap one and the file picker opens right there. Hold one to remove it.
- **Tabs** – several PDFs open at once; drag to reorder; tap the open tab again (or hold a tab) for its options, like rename. The counter left of the tabs lists every open file: the current one is highlighted, tap to switch, swipe left or right to close. Files with unsaved changes show a dot and always ask before closing.
- **Side by side** – drag a tab onto another tab and rest there for a moment (it lights up blue), let go and confirm; or hold a tab and choose *Open side by side with this file* to pair it with the current file. The pair shares one capsule in the tab bar and opens side by side whenever you pick either of its files; any other tab opens full width while the pair stays grouped. Drag the handle between the two sides to change their widths, tap it for a swap button that switches the two files left and right, or hold it for half and half. Drag it to the far left or right and keep it there for a moment to ungroup. The active side has a thin blue outline; touching the other side makes it active, and the pen bar, menus and Undo act on the active side. Hold a tab for *Show on the other side* or *Ungroup side by side*.
- **Margins** – add blank space left/right of every page or a single page, with a live preview; margins can be blank, lined or grid. The original page is never scaled, and a faint dashed outline shows its edge.
- **Pages** – Samsung-Notes-style overview with thumbnails, a ⋮ menu per page, drag to reorder, and a + tile at the end. New pages can match the document or be A4/A5, portrait or landscape, blank, lined or grid, with a live preview. At the last page, pull further up and hold to add a page at the end (at the first page, pull down to add one before it); it starts like the last page you added (or like the document's first page). Insert another PDF, delete pages, and see a dim page counter at the bottom right. Tap the magnifier next to the page counter to lock the zoom of a file (pinching then only moves the pages). While scrolling, a handle appears on the right edge: drag it to move through long documents fast, with the page number shown beside it.
- **Pen** – solid, dashed, dotted or corrugated (wavy); 7 colours; adjustable width with −/+ buttons; no pressure sensitivity; low-latency inking. Hold still at the end of a stroke to snap to a straight line, circle or rectangle.
- **Highlighter** – freehand or straight (magnetic at 0°/90°), with its own colour and width memory. Multiply blending keeps text readable, and overlapping highlights don't get darker, on screen and in exported PDFs.
- **Eraser and lasso** – stroke eraser (the S Pen side button erases too); lasso selection to move, copy, cut, recolour or delete.
- **Undo / redo** – up to 100 steps.
- **Images** – from gallery, camera (with crop), clipboard (e.g. Mac2Tab screenshots) or copied from the PDF itself; move across pages, resize, crop, lock.
- **Save / Export / Share** – Save keeps everything editable in Zen (original, strokes and images are embedded in the PDF); Export and Share give a plain vector PDF that opens anywhere.
- **New notes** – choose a light or dark page, blank, lined or grid paper, A4 or A5, portrait or landscape, with a live preview.
- **Dark view** – the moon icon at the top right shows the open document on dark paper: black text and ink turn white, other colours stay readable, and photos keep their natural colours. It is display only, so saved and exported files keep the original colours.
- **Focus mode** – in steps: the first tap hides the status bar, the second also hides the top bar (Insert, Pages, Margins and Exit then move into the floating toolbar), and Exit shows everything again.
- **Never loses work** – autosave within 0.2 s, crash-safe writes with backups, tabs restored after restarts, closed tabs kept 7 days under *Recently closed*.

## Install

Open the [latest release](https://github.com/maBahrami/Zen/releases/latest) on an Android device (Android 11+), download the `.apk` and open it. Allow installing from that app when Android asks.

## Build

1. Open the folder in Android Studio (JDK 21 for Gradle).
2. Enable USB debugging on the tablet, select it, press **Run**.

Kotlin, no UI frameworks; PDF rendering with Android `PdfRenderer`, writing with [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android).
