---
title: Notes and review bundles
parent: User Guide
nav_order: 4
---

# Notes and review bundles
{: .no_toc }

Notes record what you found, right on the line where you found it. A review bundle hands
the log and your notes to someone else in one file.

1. TOC
{:toc}

## Adding notes

1. Right-click a row and choose **Add Note…**, or click the left edge of the table, beside
   the row (a dim pencil shows there under the mouse), or use **Edit → Add Note…**.
2. Type the note. **Author** starts as your user name; change it if you like.
3. Click **OK**.

Rows with a note show an amber pencil in the strip at the table's left edge, whichever
columns you show. Point at it to read the note, or click it to edit it. The note also
appears at the top of the detail panel when the row is selected. Right-click the row to
**Edit Note…** or **Remove Note**.

Each note stays on the line it was written on, even when the same text appears on many
lines (as in retry loops), and follows that line if lines are added above it.

## Seeing all notes

**Edit → All Notes…** (`Ctrl+Shift+N`, or **View All Notes…** in the row menu) opens the
**Notes** window: every note with its line, the log line, author and date. Click a note to
jump to its line; double-click it (or press `Enter`) to edit it. The trash can on the row
under the mouse, or `Delete`, removes it. The list updates as you add or change notes, and
keeps the column widths you set.

The **Line** column numbers lines the way the log table does: the file line, or `db.log:16`
in a [merged view](merged-views.md); on a live source it's **#**, the entry's position.
A note whose line is filtered out shows –.

## Where notes are saved

Notes and [highlights](reading-and-filtering.md#highlights) are saved automatically, in a
file next to the log named after it: `app.log` gets `app.log.clouseau`. Anyone who opens
the same log with that file beside it sees the same notes.

If the log's folder is read-only, notes go to the `annotations` folder of the
[data folder](settings.md#the-data-folder) instead. If even that fails, Clouseau says so and
keeps the notes until you close the tab.

## Exporting for review

**File → Export for Review…** writes a single `.zip` you can attach to a ticket or an email.
Clouseau suggests `<log>-review.zip` next to the log.

The bundle holds:

- the log exactly as it is (a `.gz` stays a `.gz`; a file opened from a zip is copied out
  of it);
- every note and highlight in the tab;
- for a [merged view](merged-views.md), every file, with each file's notes, timezone, time
  offset and parser.

SSH and plugin-source tabs can't be exported, and a file can't be exported while it's
still loading.

## Opening a review bundle

Open the bundle like any other file (**File → Open**, drag and drop, or double-click).
Clouseau recognizes it, opens its log (or its merged view) without asking which file, and
puts each note and highlight on its line, with the author's name.

Notes you add while reviewing are saved next to the bundle, never inside it, so the bundle
stays exactly as it was sent. You can export it again to send it back with your notes
added.
