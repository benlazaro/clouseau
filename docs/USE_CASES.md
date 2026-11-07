# Clouseau Use Cases

> **Status:** Draft · **Last updated:** 2026-11-06
>
> This document describes *what Clouseau is for*: who uses it, what they are trying to
> accomplish, and what it deliberately does not do. It is the reference reviewers use to
> judge whether a contribution fits the project. If your change adds, removes, or alters
> a user-visible behavior, update the relevant use case in the same pull request.
>
> The companion document [`DESIGN.md`](DESIGN.md) describes *how* the system meets these
> use cases.

---

## 1. Vision

Production logs are hard to read. Clouseau is a desktop log viewer that makes them easy
to read: errors and warnings stand out at a glance, irrelevant noise can be filtered
away, and compressed archives open directly. It should stay stable when left running for
long periods.

Clouseau is also a **collaboration tool**. People in the field investigate a log, annotate
what they found, and hand the log plus their notes to the development team, who pick up
exactly where the field left off.

## 2. Personas

| Persona | Who they are | What they need |
|---|---|---|
| **Field engineer** | Support, operations, or on-site staff who first see a problem. May not know the codebase. | Open whatever log they were sent, quickly find errors, mark what looks suspicious, and pass it on. |
| **Developer** | Engineer on the product team who receives escalations or debugs locally. | Read logs from tickets, email, and their own machine; filter down to the components they own; see the field's notes in context. |
| **Plugin author** | Developer extending Clouseau for a log format, source, or formatter it doesn't ship with. | A small, stable API and a way to distribute plugins. |

## 3. Where logs come from

| Source | Typical form | How it reaches Clouseau |
|---|---|---|
| Support tickets | Attachments, often `.zip` or `.gz` | Downloaded to disk, then opened as a local file |
| Email | Attachments, often compressed | Saved to disk, then opened as a local file |
| Local development | Plain files written by the running app, still growing | Opened directly, optionally followed live |
| Remote hosts | Files on a server reachable over SSH | Streamed via SSH (`tail -F`) |
| Log aggregators | Loki, CloudWatch, and similar services | Third-party source plugins only, opened from File → Open Source (UC-11) |

Logs from tickets and email are **local files the user has already saved**. Opening them
should be as easy as possible (drag-and-drop, "Open with"), but Clouseau does not fetch
them from ticketing or mail systems itself.

---

## 4. Use cases

Each use case lists its current support status: ✅ supported · 🟡 partial · ❌ not yet.

### UC-1 · Open a log file 🟡

**Actor:** Field engineer, Developer
**Goal:** View a log someone sent me, or one my app just wrote.

1. User opens a file with `File → Open` (`Ctrl+O`), by dragging it onto the window, or
   by choosing Clouseau from the operating system's "Open with" menu.
2. Clouseau detects the log format from the first lines and shows a parser preview.
3. User accepts, or picks a different parser.
4. Entries appear in a table with timestamp, level, thread, logger, and message columns.

**Success:** The file is readable within seconds, and columns are parsed correctly
without manual configuration for common formats (Log4j, Logback/Spring Boot, JSON).

**Variations:**
- *Unknown format:* The file opens as plain text, with an offer to create a parser for
  it (UC-13).
- *Very large file:* Every line of a static file stays available, however large the file
  is (see [Scale](#5-quality-goals)). If a file cannot be fully loaded, Clouseau warns the
  user clearly instead of silently dropping lines.
- *Export bundle:* Opening a review bundle (UC-8) loads the log together with its
  annotations.

**Current behavior:** Files dragged onto the window open anywhere they're dropped (table,
detail panel, filter bar, tab strip or welcome screen), each in its own tab with the
parser auto-detected, as with "Open with"; folders are ignored. The native installers
(Windows exe, macOS dmg, Linux deb) register `.log` files with Clouseau, so double-click
and "Open with" work; `.gz` and `.zip` aren't claimed, since they're general archives.
Releases publish only the portable zip, which registers nothing: there, "Open with" works
if the user picks the Clouseau launcher manually. Either way the file goes to an
already-running window if there is one. Static files are never trimmed by the entry limit. Every static file is
indexed (D-16): the heap keeps about 29 bytes per line and text is read back from disk,
so a 1M-line, 125 MB log uses under 50 MB of heap. Lines followed after the file opens
(UC-6) are indexed the same way, so they are never dropped either. Compressed files are
indexed through an unpacked copy (UC-2). In the rare case the index itself fills the heap (tens of
millions of lines), Clouseau stops reading, warns the user with the number of lines
loaded and how to raise the memory limit, and marks the tab "partly loaded" in the
status bar.

### UC-2 · Open compressed logs directly ✅

**Actor:** Field engineer, Developer
**Goal:** Read a `.gz` or `.zip` attachment without extracting it first.

1. User opens a `.gz` or `.zip` file exactly like a plain log.
2. Clouseau decompresses it on the fly and continues as in UC-1.

**Success:** No manual extraction step or temp files the user has to clean up.

**Variation:** A `.zip` containing several logs lets the user choose which ones to open,
and opens them as a merged view (UC-12).

**Current behavior:** A `.zip` with one file opens it directly. With several, a picker
lets the user tick the files to open (with a filter box and Select all); with "Merge into
one view" they open as one merged view (UC-12), otherwise each in its own tab. A `.gz` or zipped file is
unpacked to a copy in the data folder's `cache/` while it's indexed (D-16), so it takes
no more heap than a plain file of the same size. The user never handles the copy: it's
deleted when the tab closes, and the folder is emptied at startup and exit. If the disk
fills while unpacking, Clouseau stops there, warns the user (naming the folder), and
marks the tab "partly loaded (disk full)". If a zip records a size the disk can't hold,
the file is loaded into memory instead, as before. A `.gz` inside a zip is unpacked
too. A zip that is a review bundle (UC-9) opens its log directly.

### UC-3 · Spot errors and warnings at a glance ✅

**Actor:** Field engineer, Developer
**Goal:** Find the problems in a wall of text without reading every line.

1. When a log opens, each row is color-coded by level (ERROR, WARN, INFO, DEBUG, TRACE).
2. User scans the table; errors and warnings are visually distinct.
3. User selects a row to see the full entry in the detail panel, with the stack trace
   and any structured payload (such as JSON) formatted and syntax-highlighted.
4. Optionally, the user applies their own highlight colors to rows and jumps between
   highlights of a given color.

**Success:** A user can find the first ERROR in a log much faster than in a text editor.

### UC-4 · Focus on specific loggers ✅

**Actor:** Developer, Field engineer
**Goal:** See only the lines from the components I care about.

1. User opens the logger filter, a tree built from the logger names in the file.
2. User includes or excludes packages or individual classes with one click.
3. The table updates to show only the matching entries.

**Success:** Noise from unrelated components disappears without regex knowledge.

### UC-5 · Narrow down by level, time, and text ✅

**Actor:** Developer, Field engineer
**Goal:** Zero in on the window and kind of events around an incident.

1. User toggles which levels to show, and filters by a time range and/or free text.
2. Filters combine with the logger filter (UC-4).
3. User uses Find (`Ctrl+F`) to step through matches within the visible rows.

**Success:** The user can get from "something broke around 14:05" to the relevant lines
in a few interactions.

**Current behavior:** On an indexed file, level, logger, field and time filters are
decided from the index and take milliseconds even on a million lines. A text filter and
Find read the text of the rows the other filters keep, in the background: the status bar
shows "filtering n%" and the Find counter "Searching n%", and the window stays
responsive (about 1.6 s for 1M lines). Times are compared to the millisecond.

### UC-6 · Follow a live log for a long time ✅

**Actor:** Developer
**Goal:** Watch a running application's log, locally or on a remote host, for hours.

1. User opens a file with follow mode on, or connects to a remote host over SSH.
2. New entries appear as they are written; the view can auto-scroll.
3. If the file is rotated, Clouseau keeps existing rows and continues with the new file.
4. Clouseau stays responsive and does not crash, however long it is left running.

**Success:** It can be left running overnight without running out of memory.

**Rotation (local files):** If the file is renamed (logrotate's default, log4j and
logback rolling), the existing rows stay readable from the old file and the new file is
followed. If it is truncated in place (logrotate's `copytruncate`), the existing rows keep
their time, level, logger and thread, their message reads "Text no longer in the file
(rotated)", and the status bar says the file was rotated.

**SSH tab names:** An SSH tab is titled with the file name and host (`app.log — 10.0.0.5`);
its tooltip shows user, host, full path, and the port when it isn't 22.

**Rotation and lost connections (SSH):** The server runs `tail -F`, which follows the file
by name, so a rotated or replaced log keeps streaming (the rows already shown stay). A
wrong path or an unreadable file is reported as an error instead of an empty tab. If the
connection dies without being closed (a router or VPN dropping it), Clouseau notices
within about 75 seconds and reports that the connection was lost, instead of silently
showing nothing new. Closing an SSH tab returns at once, even when the connection
has already gone quiet.

**Signing in (SSH):** Password, private key (with or without a passphrase), or "No
password" for accounts without one, common on embedded devices. "No password" works with
servers that let such accounts in by any of the usual methods (none, an empty password,
or keyboard-interactive).

**Memory limit:** A followed local file is indexed (UC-1, D-16), so its lines cost about
29 bytes of heap each and are never dropped. SSH and plugin sources hold their entries
in memory, at most a configurable number per tab (default 200,000); the oldest are
dropped when the limit is reached. This limit protects live sessions only; it never
applies to opening a static file (UC-1). If a local file couldn't be indexed and was
loaded into memory instead, following it uses the same limit, and a tab that loaded more
than the limit keeps as many entries as it loaded, dropping one old entry per new one.

### UC-7 · Annotate lines during an investigation ✅

**Actor:** Field engineer
**Goal:** Record what I noticed, right where I noticed it.

1. User selects a line and adds a note. The note records its author (defaulting to the
   operating-system user name) and the date.
2. Annotated lines are marked in the table, and all annotations are listed in a panel
   with their author and date.
3. Annotations are saved automatically: next to the log if that folder is writable,
   otherwise in Clouseau's data folder.
4. Each annotation stays attached to the line it was written on, even if the same text
   appears on other lines.

**Success:** Notes survive closing and reopening the file, and always point at the right
line.

**Current behavior:** Annotations are matched by line content only, so identical lines
(common in retry loops) share one annotation. If the log's folder is read-only, the
annotations cannot be saved.

### UC-8 · Hand off a log with annotations ✅

**Actor:** Field engineer → Developer
**Goal:** Send my findings to the dev team so they don't have to rediscover them.

1. Field engineer finishes annotating (UC-7).
2. Field engineer chooses `File → Export for review…`.
3. Clouseau writes a single `.zip` bundle containing the original log (still compressed
   if it was) and its annotations.
4. Field engineer attaches the bundle to a ticket or email.

**Success:** Sending takes one action and one file, and nothing gets lost along the way.

**Current behavior:** `File → Export for Review…` asks where to save, suggesting
`<log>-review.zip` next to the log, and writes the bundle in the background (D-10). The
bundle holds the log byte-for-byte (a `.gz` stays a `.gz`; a file opened from a zip is
copied out of it), every annotation and highlight of the tab, including ones that no
longer match a line, and a manifest. A tab opened from a bundle can be exported again,
with the reviewer's notes added. A merged view (UC-12) exports all its files in one
bundle, with each file's notes, timezone and time offset. SSH and plugin-source tabs
can't be exported, and a file can't be exported while it is still loading.

### UC-9 · Review a handed-off log ✅

**Actor:** Developer
**Goal:** Pick up where the field engineer left off.

1. Developer opens the review bundle (UC-8) like any other file.
2. The log and its annotations load together; each annotation is shown on its line
   with its author.
3. Developer clicks an annotation in the annotations panel to jump to its line.

**Success:** The developer sees exactly what the field engineer flagged, on the right
lines, and knows who flagged it.

**Current behavior:** A bundle opens like any `.zip` (File → Open, Open Recent, the
command line or "Open with"): Clouseau recognizes it by its manifest, opens its log in
one tab without asking which file, and attaches each annotation and highlight to its
line (D-9), with its author. Notes the developer adds are saved next to the bundle in
`<bundle>.zip!<log>.annotations`, never inside the bundle, and from then on that file is
what loads, so the received bundle stays as it was sent. A `.zip` without a Clouseau
manifest still opens as an ordinary compressed log (UC-2).

### UC-10 · Define a parser for a new format ✅

**Actor:** Developer, Field engineer
**Goal:** Teach Clouseau a log format it doesn't know, so those logs get columns,
colors, and filters.

1. User opens the parser editor and describes the line format with a simple template
   (for example `{date} [{thread}] {level} {logger} - {message}`), or pastes a
   Log4j/Logback pattern.
2. Clouseau shows the generated regex and tests it against a sample line.
3. User saves the parser; it is available from then on and in auto-detection.

**Success:** No Java code or regex expertise is required.

**Variation:** When started from a file no parser recognizes (UC-13), the editor is
pre-filled with a new parser and the file's first non-blank line as the sample. Saving
the parser reopens the file with it.

### UC-11 · Extend Clouseau with plugins ✅

**Actor:** Plugin author, Developer
**Goal:** Add a parser, formatter, syntax highlighter, or log source that Clouseau doesn't
ship with.

1. Plugin author implements an interface from `clouseau-api` and packages a JAR.
2. User installs it by dropping the JAR in the plugins folder, or from a plugin repository
   (Nexus or Maven Central) in the Plugin Manager.
3. The new capability is available without restarting.
4. For a log source, the user picks it under **File → Open Source**, fills in the form
   Clouseau builds from the settings the plugin declares, and the lines stream into a new
   tab.

**Success:** Plugins depend only on `clouseau-api` and keep working across Clouseau
releases that don't change that API.

**Current behavior:** Parsers, formatters, syntax highlighters and log sources
(`LogSourceProvider`) can all come from plugins. A source's settings are remembered per
plugin, with passwords encrypted. Plugins can't add filters. The example plugin ships a
logfmt parser and a demo live source.

**Scope note:** Sources that read from log aggregators (Loki, CloudWatch, and similar)
are welcome as plugins. The core application does not ship them.

### UC-12 · View related logs in one merged view ✅

**Actor:** Developer, Field engineer
**Goal:** Read several related logs as one timeline: the rolled files of one app
(`app.log`, `app.log.1`, `app.log.2.gz`), or the logs of several services or servers
involved in the same incident.

1. User chooses the logs to merge in any of these ways:
   - selecting several files in the open dialog;
   - opening a file pattern or folder, such as `app.log*`;
   - opening a `.zip` that contains several logs, and ticking the ones to include in a
     picker that lists its files, with a filter box and a parser preview for each;
   - dragging several files onto the window.
2. Clouseau parses each file with its own parser (detected or chosen per file), so a
   merged view can mix formats.
3. Entries from all files are interleaved by timestamp into one table. Each file's
   timestamps are read in that file's own source timezone.
4. A **Source** column shows which file each entry came from.
5. All filters, Find, highlighting, and the detail panel work across the merged view.
6. Every file that is still being written is followed (UC-6). Files that don't grow,
   such as rotated or compressed ones, simply stay as they are.

**Success:** The user can follow one request or incident across files and services
without switching tabs or lining up timestamps by hand.

**Rules:**
- **Shared columns:** Timestamp, level, logger, thread, and message line up for every
  format. Format-specific fields (for example `traceId` or `pid`) each get a column;
  cells stay empty for files without that field, and fields with the same name share
  one column.
- **Continuation lines** (such as stack traces) stay attached to their entry and are
  never separated from it by lines from another file.
- **Equal timestamps** keep each file's original line order.
- **Files without timestamps** can't be placed on a shared timeline. Clouseau says so
  and offers to open them in their own tab instead.
- **Fixed file set:** A merged view follows the files it opened. It never adds files
  that start matching the pattern later, because a newly rotated `app.log.1` would
  repeat lines already shown.
- **Annotations stay per file:** A note on an entry is saved with the file that entry
  came from (UC-7). Exporting a merged view for review (UC-8) bundles every file with
  its annotations.

**Variation: correcting a wrong clock.** A file's clock can be off, by milliseconds on a
server or by minutes or hours on a customer's device. The usual sign is effect before
cause: a request received before it was sent, or a reply logged before its request,
often visible through an ID that appears in both files.

1. User sets a time offset on one file (for example, shift `device.log` by +3 min 12 s).
2. That file's entries move to their corrected position in the merged view.
3. The table shows corrected timestamps and marks the file as shifted. The detail panel
   still shows the original line, so the correction is never mistaken for what the
   file says.
4. The offset is part of the review bundle (UC-8), so the developer sees the same
   corrected timeline the field engineer did.

The offset is applied after the file's source timezone: the timezone setting says how to
read the timestamps, and the offset corrects a clock that is simply wrong.

**Current behavior:** Choosing several files in the open dialog, dropping them, or
passing them on the command line asks whether to open them in one merged view or in
separate tabs; "Remember my choice" sets this, and Settings → "When opening several
files" changes it later. A merged view interleaves the files by timestamp, keeps stack
traces with their entry, keeps each file's order for equal timestamps, and shows a
**Source** column; plain and `.gz` files can be mixed. Fields with the same name share
one column. Filters, Find and the detail panel work across all files, and the filter
bar's **Sources** button hides whole files. Notes and highlights are saved with the file
each entry came from, so they show again when that file is opened alone, and the
annotations panel names each note's file. Files no parser recognizes, or whose lines
have no timestamps, are listed and offered in their own tabs. With Follow on, every
plain file is followed (compressed ones stay as they are): new lines are held back for
up to a second so files written a moment apart still land in time order, and a line
that arrives later than that moves to its place, with the selection, highlights and
notes staying on their entries. Each file is read in its own source timezone (set it
from the row's right-click menu or the tab's **Sources** menu). A file's clock can be
corrected with **Set Time Offset for …**: its entries move to their corrected place,
the Source column shows the offset (`db.log (+3m 12s)`), the time filter uses the
corrected time, and the detail panel says what the file wrote. Every file uses the
tab's parser choice unless **Set Parser for …** gives it its own (a parser that doesn't
recognize the file is refused); with automatic detection each is read in its own format. Files
picked in the zip picker merge with "Merge into one view"; **File → Open Merged…**
merges without asking, and a pattern such as `app.log*` typed in the open dialog opens
every matching file in that folder, merged. A merged view exports for review as one
bundle with every file, its notes, timezone, offset and parser, and opens again as the same
merged view.

### UC-13 · Read a file no parser recognizes ✅

**Actor:** Field engineer, Developer
**Goal:** Read any log, even one in a format Clouseau doesn't know yet, without
switching to a text editor.

1. User opens a file (UC-1) and no parser recognizes any of its first lines.
2. Clouseau opens the file anyway, as **read-only plain text**: every line as written,
   with no columns, level colors, or level/logger/time filters.
3. A banner above the text says no parser recognized the file and offers **Create a
   parser for this file**.
4. If the user accepts, the parser editor opens with the file's first lines as samples
   (UC-10). When the parser is saved, the tab reopens the file with it and switches to
   the normal table view.
5. If the user ignores the offer, they keep reading the plain text.

**Success:** Opening a log never leads to an empty screen or a disabled Open button.
The user can always read the file, and turning it into a fully parsed log is one click
away.

**Rules:**
- **Plain text still has the basics:** line numbers, Find (`Ctrl+F`), copying text, and
  follow mode for a file that's still growing (UC-6).
- **No annotations in plain text:** To keep this view simple, annotating lines (UC-7)
  needs a parser. Creating one is the path to annotating an unknown format.
- **A chosen parser that doesn't match:** If the user picks a parser in the open dialog
  that doesn't recognize the file, Clouseau warns and offers plain text, rather than
  showing an empty table.
- **Binary files:** If the file looks binary rather than text, Clouseau warns before
  showing it.
- **Merged views:** A file no parser recognizes has no timestamps to merge on, so it
  opens in its own plain-text tab (UC-12).

**Current behavior:** However the file is opened, Clouseau checks its first 50 lines
(the same sample the open dialog previews). If no parser recognizes any of them, or the
chosen parser doesn't, the tab shows plain text: line numbers, every line including blank
ones, tabs shown as spaces, Find (`Ctrl+F`), copy (the selected lines' text only) and a
Follow toggle in the banner. A rotated file is reloaded from the start. The open dialog
keeps Open enabled and says in amber that the file will open as plain text. A file that
looks binary (more than 5% control or undecodable characters) asks first, and closes its
tab if declined. **Create a Parser…** opens the parser editor on a new parser, with the
file's first non-blank line as the sample. After saving, the tab switches to the table
if a parser now recognizes the file, and otherwise says it still doesn't. A compressed
file in plain text isn't followed.

### UC-14 · Customize the app's appearance ✅

**Actor:** Field engineer, Developer
**Goal:** Make Clouseau comfortable to look at for hours, for example dark or light to
suit the room, or a theme that matches my other tools.

1. From the main window, the user picks a theme from a list of the built-in themes
   (Dark, Light) and any custom themes they've installed.
2. The theme applies immediately to the whole window and every open tab, with no
   restart.
3. Clouseau remembers the choice for next time.

**Success:** Switching themes takes a couple of clicks from where the user is working,
and logs stay just as readable in every theme.

**Variation: custom themes.**
1. User installs a FlatLaf theme file (`.properties`) through the theme manager.
2. The theme appears in the theme list alongside the built-in ones.
3. User can remove a custom theme they no longer want. If it was the active theme,
   Clouseau switches back to the default.

**Rules:**
- **Level colors stay readable:** Every built-in theme colors ERROR, WARN, and the other
  levels so they're easy to tell apart (UC-3). A custom theme can set its own level
  colors; if it doesn't, Clouseau's defaults are used.
- **Settings stays in sync:** The theme can also be picked in Settings, and both places
  always show the same current theme.

**Current behavior:** View → Theme lists the built-in and installed themes, with the
current one checked; picking one applies and saves it at once. The same submenu ends with
Manage Themes…, which opens the theme manager. Settings still offers the theme dropdown
(with a live preview, applied on OK); both read and save the same preference, so they
always agree.

### UC-15 · Work with several logs in tabs ✅

**Actor:** Developer, Field engineer
**Goal:** Keep several logs open at once and move between them easily, for example the
logs from a ticket alongside my local one.

1. Each file the user opens appears in its own tab.
2. User switches between logs by clicking their tabs, or from the keyboard:
   - `Ctrl+Tab` / `Ctrl+Shift+Tab` go to the next / previous tab, wrapping around at
     the ends;
   - `Ctrl+PageDown` / `Ctrl+PageUp` do the same;
   - `Ctrl+1` to `Ctrl+8` jump to that tab, and `Ctrl+9` jumps to the last tab.
3. User rearranges tabs by dragging one to a new position; a floating preview of the tab
   follows the mouse while dragging.
4. User closes a tab with its close button or `Ctrl+W`. Clouseau asks for confirmation
   first, unless the user has turned that off with "Don't ask again".
5. Right-clicking a tab offers per-tab actions, such as setting its source timezone or
   applying a timezone to all tabs.

**Success:** Juggling several logs never means losing track of which is which or
opening the same file twice.

**Rules:**
- **One tab per file:** Opening a file that's already open switches to its existing tab
  instead of opening a second copy.
- **Tabs are independent:** Each tab keeps its own filters, follow mode, highlights,
  column layout, and source timezone.
- **Shortcuts work anywhere in the window,** whether focus is in the table, the detail
  panel, or a filter field, and they're listed in Help → Keyboard Shortcuts.

**Current behavior:** All of the above works, including the keyboard shortcuts, which
apply wherever focus is in the main window (not in dialogs) and are listed in Help →
Keyboard Shortcuts. `Ctrl+1` to `Ctrl+8` past the last open tab do nothing. The
shortcuts use `Ctrl` on every platform, including macOS.

### UC-16 · Spread logs across several windows ✅

**Actor:** Developer, Field engineer
**Goal:** Watch several logs at once on different monitors, for example the API's log on
one screen and the database's on the other while reproducing a problem.

1. User right-clicks a tab and chooses **Move to New Window**. The tab opens in a new
   window, with its table, filters, follow mode and notes exactly as they were.
2. User drags the new window to another monitor.
3. To move a tab later, the user right-clicks it and chooses **Move to ▸ Window N**, or
   drags it:
   - dropping it outside every Clouseau window opens a new window there;
   - dropping it on another window's tabs moves it into that window.
4. User works in any window as in the first one: every window has the full menu bar, its
   own tabs and the same keyboard shortcuts.
5. User closes a window with its close button. Its tabs close with it.

**Success:** The user can lay logs out across all their monitors and keep each one live,
without running a second copy of Clouseau.

**Rules:**
- **One application, several windows.** Every window belongs to the same running
  Clouseau, so settings, parsers, plugins, themes and Recent Files are shared, and a
  second launch still hands its files to the running one (UC-1).
- **Menus act on their own window.** Open, Close Tab, Export for Review and the other
  menu actions apply to the window whose menu was used.
- **New files open where you'd expect.** The open dialog and Recent Files open in their
  own window, a dropped file in the window it was dropped on, and files sent by "Open
  with" or a second launch in the window used last.
- **One tab per file, across windows:** Opening a file that's already open in any window
  brings that window to the front and selects its tab (UC-15).
- **A moved tab keeps working.** Moving a tab never reloads it: following, loading and
  Find carry on, and merged views and SSH tabs move like any other tab.
- **Closing a window closes its tabs,** with the same confirmation as closing a tab
  (UC-15). Closing the last window quits Clouseau, without asking, as it always has. A
  window emptied by moving its last tab away closes by itself.
- **All windows are equal.** No window is the "main" one; any of them can be closed.
- **Shortcuts stay in their window.** `Ctrl+Tab`, `Ctrl+1` to `Ctrl+9` and `Ctrl+W` act on
  the tabs of the window in front. Switching windows is left to the operating system.

**Current behavior:** All of the above works. Besides **Move to New Window** and **Move
to ▸ Window N** on a tab's right-click menu and dragging tabs between windows, **File →
New Window** (`Ctrl+N`) opens an empty window. Move to New Window is greyed out for a
window's only tab, and Move to lists each other window by its number and selected tab.
A tab dragged out of every window opens a new window at the drop point, kept on screen.
A new window takes its size from the window it came from, offset down and right. Set
Source Timezone for All Tabs applies to the tabs of every window. Design: DESIGN.md D-18.

### UC-17 · Pick up where I left off ✅

**Actor:** Developer, Field engineer
**Goal:** Close Clouseau at the end of the day and find the same logs open, in the same
windows, the next morning, especially remote logs that take several steps to open.

1. User quits Clouseau with several windows and tabs open.
2. Next time it starts, Clouseau reopens every window where it was (position, size,
   monitor, maximized) with its tabs in the same order and the same tab selected.
3. Each tab opens as it was: the same file or files, parser, source timezone, time
   offsets, follow mode, column layout and filters.
4. Each tab whose filters were restored shows a strip saying so, for example *"Filters
   restored from your last session: levels, 3 loggers, time range"*, with **Clear
   filters** and a close button.

**Success:** Starting Clouseau puts the user straight back into their investigation,
with nothing to reopen or re-filter by hand.

**Variation: named sessions.** Keep one set of open logs per project or incident and
switch between them.
1. User opens the logs for a project and chooses **File → Sessions → Save Session As…**,
   naming it, for example, *Payments*.
2. The window titles show the session's name. **File → Sessions → Save Session** updates
   it later with what is open now.
3. Another day, the user picks *Payments* from **File → Sessions**. If logs are already
   open, Clouseau asks whether to **Close current windows** (the default), **Add to
   current**, or **Cancel**, then opens the session's windows and tabs.
4. **File → Sessions → Manage Sessions…** renames and deletes saved sessions.

**Rules:**
- **On by default, and optional.** Settings → **On startup** offers **Restore last
  session** (the default) and **Start empty**.
- **The last session is what was open when Clouseau quit.** If a named session was
  active, it is still active after the restart. **File → Exit** quits with every window;
  closing windows one at a time takes each out of the session, so only the last one is
  restored. Files passed at startup ("Open with", the command line) open alongside the
  restored session.
- **A named session is a snapshot.** It changes only when the user saves it; opening or
  closing tabs afterwards doesn't change it.
- **Passwords are never saved in a session.** An SSH or plugin source whose password is
  saved in Clouseau reconnects by itself. Otherwise its tab opens with a **Reconnect**
  button, so starting Clouseau never shows a pile of password prompts.
- **Nothing is silently dropped.** A file that no longer exists, or a source whose plugin
  is gone, opens as a tab that says so, with **Try Again**. The tab stays in the session
  until the user closes it, so a log on a drive that isn't connected yet comes back later.
- **Restored filters are announced.** The filter strip names what was restored. It calls
  out a time range that hides new lines of a followed log, and it goes away when the user
  closes it or changes a filter. A filter on a logger or field the file doesn't have
  (yet) is kept, so it applies again if that logger appears.
- **A missing monitor is handled.** A window saved on a monitor that isn't connected,
  for example a laptop away from its desk, opens on the main screen instead, offset so
  it doesn't hide another window. Its place on the missing monitor is remembered, so it
  goes back there the next time that monitor is connected at startup, unless the user
  moved the window in the meantime. A window whose title bar would be off every screen
  is moved the same way, so it can always be grabbed.
- **Large sessions start quickly.** Only the tab in front in each window loads at start;
  the others load when they are first selected.
- **Sessions stay on this machine.** They hold paths and host names that mean nothing
  elsewhere. To hand logs to someone, export them for review (UC-8).

**Current behavior:** All of the above works. Files, merged views, review bundles, SSH tabs and
plugin sources all come back, each with its parser, timezone, time offsets, follow mode,
column layout and filters. A time range comes back as it was typed. A tab that hasn't
been selected yet says *"Opens when you select this tab."* A file that has gone shows
its path and **Try Again**. An SSH tab connects by itself when it needs no password, or a
saved SSH connection to the same host, port and user has it; otherwise it offers
**Reconnect…**, which opens the SSH dialog filled in. A plugin source works the same way
with the provider's saved settings. Plain-text files are recognized again when they
open. If `session.json` can't be read, Clouseau starts empty, says so, and keeps the file
as `session.json.bad`.

In **File → Sessions**, Save Session names the session in use (*Save Session
"Payments"*) and is greyed out when there is none; Save Session As… asks before
replacing a different session of the same name; the saved sessions are listed with the
one in use ticked. When nothing is open, a session opens without asking. Adding a session
skips files that are already open, and a window left with nothing to add isn't opened.
A restored filter strip names the session (*"Filters restored from session "Payments":
…"*). Manage Sessions… opens, renames and deletes (after asking) sessions, and marks the
one in use; renaming or deleting it updates the window titles. Design: DESIGN.md D-19.

---

## 5. Quality goals

| Goal | Target |
|---|---|
| **Readability** | Level colors, formatting, and highlighting make problems obvious. This is Clouseau's main reason to exist. |
| **Modern UI** | A clean, consistent, themeable look (FlatLaf); no dated Swing feel. |
| **Stability** | Never crash or run out of memory, including during long follow sessions. Prefer degrading gracefully (for example, dropping the oldest entries) over failing. |
| **Scale** | File size is not capped by design. 2 GB is a current benchmark, not a ceiling. A static file never loses lines because of a memory limit. |
| **Responsiveness** | Opening, filtering, and scrolling must not freeze the UI; long work runs off the Event Dispatch Thread. |
| **Portability** | Runs on Windows, macOS, and Linux from a portable zip with a bundled JRE. |

## 6. Non-goals

Clouseau is a desktop viewer. It is **not**:

- **A server.** It doesn't run as a service or host a web UI, and it doesn't need any
  backend to work. Collaboration happens by passing files, not through a shared server.
- **An alerting or monitoring tool.** It doesn't watch logs unattended or send
  notifications, and it has no dashboards or metrics.
- **A log aggregator.** It doesn't collect, ship, or store logs centrally from many
  machines (the job of ELK, Grafana Loki, Splunk, or Datadog). It reads logs that already
  exist. Reading *from* an aggregator through a plugin is fine (UC-11).
- **A ticketing or email client.** It doesn't fetch attachments from ticket systems or
  mailboxes. Users save the file and open it locally.
