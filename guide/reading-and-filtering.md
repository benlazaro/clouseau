---
title: Reading and filtering
parent: User Guide
nav_order: 2
---

# Reading and filtering
{: .no_toc }

1. TOC
{:toc}

![Main view](../docs/screenshots/main-view.png)

## The table

Each line of the log is a row with its timestamp, level, thread, logger and message. Rows
are colored by level, so errors and warnings stand out. Formats with extra fields (such as
a request ID or a process ID) get a column for each. Continuation lines, such as the lines
of a stack trace, stay with the entry they belong to.

The status bar shows the file's path, the [source timezone](#timezones), and how many lines
are shown out of how many there are.

- `Ctrl+Home` and `Ctrl+End` jump to the first and last row.
- Right-click a row for [highlights](#highlights), [notes](annotations-and-review.md),
  **Reload File** (read the file again from the start) and **Clear Table** (remove every
  row).

## The detail panel

Select a row to see the whole entry below the table: every field, the full message and
its stack trace.

- JSON in a message is pretty-printed and colored. The **Formatter** and **Syntax
  Highlight** buttons above the panel turn that off and on.
- **Copy Message** copies the formatted message.
- **A−** and **A+** change the font size.
- Drag the bar between the table and the panel to resize them.

## Filters

The filter bar narrows the table. All filters combine, and **Clear All Filters** resets
them.

- **Levels**: click a level button to hide or show it. `Alt`+click a level to show only
  that level; `Alt`+click it again to show all.
- **Loggers**: a tree of every logger in the file. Untick a package or class to hide its
  lines, or use **All** and **None**. The button shows how many loggers are shown, for
  example **Loggers (15 / 20)**.
- **Fields**: for formats with extra fields, untick values to hide their lines. The button
  shows a dot while a field filter is active.
- **Sources**: in a [merged view](merged-views.md), hide whole files.
- **Time**: a time range. The quickest way is from the table: right-click a line and
  choose **Show From This Line** or **Show Up to This Line**; do both to bracket an
  incident. Or click **Time** and fill in **From**, **To** or both, then press **Apply**
  or `Enter`; an empty field leaves that end open, and **Selected row** next to a field
  fills in the selected line's time. You can type:
  - just a time, `10:00` or `10:00:05` (with or without `.SSS` milliseconds), which means
    that time on the day the log starts;
  - a date and time, `yyyy-MM-dd HH:mm` or `yyyy-MM-dd HH:mm:ss`, or just a date;
  - a span counted from the other end: `+5m` in **To** means five minutes after **From**,
    and `-90s` in **From** means 90 seconds before **To** (`ms`, `s`, `m`, `h`, `d`).

  While a range is set, the button shows it, for example **10:00:00 → 10:15:00**;
  **Clear** in the same popup removes it.
- **Filter message…**: show only lines containing the text, in any column. The **×** at
  its right end clears it.

![Logger filter](../docs/screenshots/logger-filter.png)

Level, logger, field and time filters take milliseconds even on a million lines. A text
filter reads the text of the remaining rows in the background; the status bar shows
"filtering n%" while it runs, and you can keep working.

## Find

`Ctrl+F` opens the find bar. It searches every column of the rows the filters show.

- `Enter` or **▼** goes to the next match, `Shift+Enter` or **▲** to the previous one.
- The counter shows how many rows match ("Searching n%" while it's still counting).
- `Escape` closes the bar.

## Highlights

Highlights mark rows you want to come back to.

1. Select one or more rows, right-click, and pick a color.
2. The **Highlights** bar appears above the table. Click a color to step through only
   that color (or **All**), and use **▲** and **▼** to go to the previous or next
   highlighted row.
3. **Clear highlight** removes the color from the selected rows; **Clear all highlights**
   removes every highlight in the tab.

Highlights are saved with the file's [notes](annotations-and-review.md), so they're back
when you open the file again, and they travel in review bundles. Each color follows the
current theme.

## Columns

Right-click any column header:

- **Columns**: tick or untick a column to show or hide it. **Show all columns** brings
  every column back.
- **Save current layout…** saves the order, visibility and width of the columns under a
  name. Pick a saved layout in the same menu to apply it.
- **Manage layouts…** deletes layouts, and sets one as the **default**, applied to every
  file you open. The default is marked ★.

Drag a column header to move the column. Columns size themselves to their content when a
file opens.

## Timezones

Log timestamps usually don't say which timezone they're in. Each tab has a **source
timezone**, used to read its timestamps; it starts as your computer's timezone and is
shown in the status bar as `[src: …]`. Click that label, or right-click the tab, to change
it. Right-click a tab and choose **Set Source Timezone for All Tabs…** to change every tab.

**Display timezone** in [Settings](settings.md) is a different thing: the timezone
timestamps are *shown* in, and the one you type **From** and **To** times in.
