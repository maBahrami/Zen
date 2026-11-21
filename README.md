# Zen

A minimal, Apple-style PDF annotator for the Samsung Galaxy Tab S9+ (S Pen), built for studying papers and lecture notes.

## Features

- **Tabs** – several PDFs open at once; hold a tab to reorder, hold and release to rename or close; tab list for many files.
- **Margins** – add blank space left/right of every page or a single page, with a live preview. The original page is never scaled.
- **Pen** – solid, dashed or dotted; per-tool colour and width; hold still at the end of a stroke to snap to a straight line, circle or rectangle.
- **Highlighter** – freehand or straight (magnetic at 0°/90°); multiply blending so text stays readable and overlaps don't darken.
- **Eraser** – stroke eraser; the S Pen side button erases too.
- **Images** – from gallery, camera (with crop), clipboard (e.g. Mac2Tab screenshots) or copied from the PDF itself; move across pages, resize, crop, lock.
- **Pages** – Samsung-Notes-style overview with thumbnails; drag to reorder; insert blank pages or another PDF; delete.
- **Save / Export / Share** – Save keeps everything editable in Zen (original, strokes and images are embedded in the PDF); Export/Share give a plain vector PDF that opens anywhere.
- **Focus mode** – hides the system bars and top bar.
- **Never loses work** – autosave within 0.2 s, crash-safe writes with backups, tabs restored after restarts, closed tabs kept 7 days under *Recently closed*.

## Build

1. Open the folder in Android Studio (JDK 21 for Gradle).
2. Enable USB debugging on the tablet, select it, press **Run**.

Kotlin, no UI frameworks; PDF rendering with Android `PdfRenderer`, writing with [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android).
