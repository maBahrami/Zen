# Zen

A minimal PDF annotator for the Samsung Galaxy Tab S9+ (S Pen), built for studying papers and lecture notes.

**Version 1.1** · [Download the latest APK](https://github.com/maBahrami/Zen/releases/latest)

## Features

- **Tabs** – several PDFs open at once; drag to reorder, hold to rename. The counter left of the tabs lists every open file: the current one is highlighted, tap to switch, swipe left or right to close. Files with unsaved changes show a dot and always ask before closing.
- **Margins** – add blank space left/right of every page or a single page, with a live preview; margins can be blank, lined or grid. The original page is never scaled, and a faint dashed outline shows its edge.
- **Pages** – Samsung-Notes-style overview with thumbnails, a ⋮ menu per page, drag to reorder, and a + tile at the end. New pages can match the document or be A4/A5, portrait or landscape, blank, lined or grid, with a live preview. Insert another PDF, delete pages, and see a dim page counter at the bottom right.
- **Pen** – solid, dashed or dotted; 7 colours; adjustable width with −/+ buttons; no pressure sensitivity; low-latency inking. Hold still at the end of a stroke to snap to a straight line, circle or rectangle.
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
