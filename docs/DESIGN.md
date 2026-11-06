# Clouseau Design

> **Status:** Draft · **Last updated:** 2026-11-04
>
> This document describes *how* Clouseau works and *why* it is built that way. It
> complements [`USE_CASES.md`](USE_CASES.md), which describes *what* Clouseau is for.
>
> **For contributors:** If your change alters a module boundary, an extension point, a
> threading rule, a file format, or anything listed under [Decisions](#9-decisions),
> update this document in the same pull request. If your change closes a
> [gap](#11-known-gaps), remove it from the list.
>
> **For reviewers:** Check the change against the rules in sections 3, 6, and 7. A change
> that breaks one of them needs a decision recorded here, not just a code comment.

---

## 1. Goals

Section 5 of the use cases ([Quality goals](USE_CASES.md#5-quality-goals)) lists the full
quality goals. The ones that shape the design most:

1. **Readability first.** Level colors, formatting, and highlighting are core features, not
   decoration.
2. **Never freeze, never crash.** All I/O and parsing happen off the Event Dispatch Thread
   (EDT). Memory use is bounded in live sessions.
3. **No size ceiling for static files.** Opening a file never drops lines.
4. **Pluggable.** Formats, sources, and presentation can be extended without changing
   the core, through a small API.
5. **Self-contained desktop app.** No server or backend. Collaboration happens by passing
   files.

## 2. System overview

```
clouseau-ui ─────────────┐   MainFrame, tabs, LogPanel, LogTableView, RowMenu,
   │                     │   LogTableModel, FilterBar,
   │                     │   DetailPanel, annotations, dialogs, SSH source,
   │                     │   parser loader, settings, themes
   │                     ▼
   │               clouseau-plugin   ClouseauPluginManager (PF4J wrapper)
   │                     │
   ▼                     │
clouseau-core ◀──────────┘   RegexLogParser, ParserList, LineParser, LogReaders,
                             LogFileReader, FileIndexer, IndexedFollow, FileFollower,
                             FileTail, FileChangeWatcher,
                             SshLogSource, SourceSettings, AnnotationStore, ZipLogs, …
   │
   ▼
clouseau-api                 LogEntry, LogParser, LogFormatter, LogSyntaxHighlighter,
   ▲                         LogSource, LogSourceProvider, SourceSetting
   │ compileOnly
third-party plugins
```

A log flows through the system like this:

**source** (file, `.gz`/`.zip`, SSH) → **lines** → **parser** → **`LogEntry`** →
**table model** (store, filter, highlight) → **table and detail panel** (format, highlight)

## 3. Modules and dependency rules

| Module | Responsibility | May depend on |
|---|---|---|
| `clouseau-api` | The plugin contract: data types and extension interfaces. No Swing, no implementation. | Nothing internal (PF4J `compileOnly`, for `ExtensionPoint`) |
| `clouseau-core` | UI-independent engine: parsing, sources, storage. | `clouseau-api` |
| `clouseau-plugin` | Plugin loading and lifecycle (wraps PF4J). | `clouseau-core` |
| `clouseau-ui` | The Swing application. | `clouseau-core`, `clouseau-plugin` |
| Plugins | Third-party extensions. | `clouseau-api` only, as `compileOnly` |
| `clouseau-example-plugin` | A complete plugin (a logfmt `LogParser`), as a template for plugin authors. Not shipped with the app. `clouseau-plugin`'s tests load its JAR through PF4J to check discovery end to end. | `clouseau-api` only, as `compileOnly` |

**Rules:**

1. `clouseau-api` is the only module plugins see. Changing it can break every plugin, so
   additions are fine but changes to existing signatures need a recorded decision.
2. Anything that doesn't need Swing belongs in `clouseau-core`, not `clouseau-ui`. This
   keeps the engine testable without a display.
3. Swing types never appear in `clouseau-api` or `clouseau-core`.

## 4. Data model

### 4.1 `LogEntry`

`LogEntry` (in `clouseau-api`) is an immutable record:

| Field | Meaning |
|---|---|
| `timestamp` | `Instant`; `null` if the line has none |
| `level` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `FATAL`, or `UNKNOWN` |
| `logger`, `thread` | Optional; `null` if the format doesn't have them |
| `message` | The message text |
| `rawLine` | The original line exactly as read |
| `fields` | Extra named values (for example MDC fields or JSON properties), shown as custom columns |

### 4.2 Lines that don't parse

Every non-blank line becomes exactly one `LogEntry`. A line no parser accepts becomes an
`UNKNOWN` entry with a `null` timestamp. The most common case is a stack-trace line
following an error.

Such entries are **continuation lines**: they belong to the parsed entry before them. They
are hidden from the table by default (the `UNKNOWN` level toggle starts off), but the
detail panel and Find still include them with their parent entry (`LogTableModel`
collects the continuation lines after a row).

### 4.3 Timezones

Timestamps without a zone are interpreted in a per-tab **source timezone** (default: the
system zone). Each entry is corrected as it is read, and the user can change the zone per
tab.

## 5. Extension points

| Interface | Purpose | Discovered from plugins? | Built-in implementations |
|---|---|---|---|
| `LogParser` | Turn a raw line into a `LogEntry` | Yes, after the JSON-defined parsers (section 5.1) | `RegexLogParser`, configured from JSON |
| `LogFormatter` | Reformat a message for the detail panel (for example, pretty-print JSON) | Yes | `JsonMessageFormatter` |
| `LogSyntaxHighlighter` | Color spans in the detail panel | Yes | `JsonSyntaxHighlighter` |
| `LogSourceProvider` | Create a `LogSource` (live lines from somewhere) from settings the user fills in | Yes; listed under File → Open Source (section 6.3) | None; SSH has its own dialog and creates `SshLogSource` directly |

**Contract notes:**

- `LogParser.canParse` must be cheap: during auto-detection it is called on every line
  for every parser until one accepts.
- `LogParser.customFields()` lists the `fields` keys that should become columns.
- `LogFormatter` and `LogSyntaxHighlighter` work on message text, so one implementation
  works for any log format.
- `LogSource.open(Consumer<String>)` **pushes** lines to the consumer. The source decides
  how to get them: a server-side `tail -F`, or a streaming API. `open` runs on a
  background thread and blocks until the stream ends or `close()` is called; `close()`
  comes from another thread, maybe more than once, maybe before `open` connects. This is
  what lets aggregator sources such as Loki or CloudWatch exist as plugins (UC-11, D-11).
- `LogSourceProvider` is the discoverable part (D-14). PF4J shares one instance, so
  per-connection state lives in the `LogSource` it creates. `settings()` declares
  `SourceSetting`s (text, password, number, choice; required or optional; default);
  `create(values)` runs on the EDT, so it only checks values and builds the source, and
  throws `IllegalArgumentException` with a message for the user if a value is wrong.

### 5.1 Parsers as data

Built-in and user-defined parsers are not Java classes. They are entries in JSON
(`pattern`, `timestampFormat(s)`) that `UserParsersLoader` turns into `RegexLogParser`
instances, using regex named groups (`timestamp`, `level`, `logger`, `thread`, `message`,
plus any custom ones).

- Defaults ship in `default-parsers.json` on the classpath.
- User parsers live in `<data>/parsers.json` and replace defaults with the same name.
- The parser editor (UC-10) creates them from a template such as
  `{date} [{thread}] {level} {logger} - {message}` (`TemplatePatternConverter`) or a
  Logback/Log4j pattern (`LogbackPatternConverter`).

This lets anyone add a format without writing a plugin. Plugins are for formats regex
can't handle.

**Combined list.** `MainFrame.reloadParsers` builds the list the open dialog, SSH dialog
and tabs use with `ParserList.combine` (core): JSON-defined parsers first, then plugin
`LogParser` extensions. Order matters because auto-detection takes the first parser
that accepts a line, so a plugin can't take over a format that already works. A plugin
parser whose name is already used, or that has no name, is skipped with a warning, so
`parsers.json` stays the way to override by name. Each plugin parser is wrapped so its
exceptions can't abort a load: a throwing `canParse` counts as no match, and a throwing
(or `null`-returning) `parse` yields an UNKNOWN entry; the first failure of each kind is
logged. The list is rebuilt at startup, when `parsers.json` is saved, and after a plugin
refresh; open tabs keep the list they were created with.

## 6. Runtime behavior

### 6.1 Threading model

| Thread | Work |
|---|---|
| **EDT** | All Swing access. Table model mutations (`appendBatch`, `append`, clearing, highlights). |
| **Load worker** (`SwingWorker`, one per tab) | Read and parse the file; `publish` entries in chunks. |
| **Tail worker** (`SwingWorker`, one per tab) | Wait for the file to change, then read new lines while following; on an indexed tab, add them to the index. Holds the tab's tail lock, so a cancelled worker finishes before the next one starts. |
| **Filter executor** (`clouseau-filter`, one per tab) | Re-run the active filter over all entries; a generation counter discards stale results. |
| **Source worker** (`SwingWorker`, one per live-source tab) | Run `LogSource.open` (SSH or a plugin source), which blocks while it pushes lines; parse and `publish` them. |
| **Source close** (`source-close`, a daemon thread per close) | Run `LogSource.close` when a live tab is closed or reloaded. Closing can wait on the network or a plugin, so it never runs on the EDT (issue #1). |

**Rules:**

1. Only the EDT touches Swing components or mutates `LogTableModel`.
2. Background work hands results to the EDT through `SwingWorker.process` or
   `SwingUtilities.invokeLater`.
3. Anything that can take longer than a few milliseconds (I/O, parsing, filtering a large
   set) runs off the EDT and must be cancellable. Opening a new file cancels the tab's
   previous load and tail workers. Network calls count, closing included: an SSH
   connection that went silent can take seconds to give up.

### 6.2 Opening a file (UC-1, UC-2)

0. **Zips:** `MainFrame` lists a `.zip`'s files with `ZipLogs` (core; names read as
   UTF-8, else CP437). One file opens directly; for several, `ZipPickerDialog` lets the
   user tick files (filter box, Select all), and each opens in its own tab as
   `load(zip, entry, …)`, or in one merged view when "Merge into one view" is ticked (D-17). The tab keys its annotations to `ZipLogs.entryKey`,
   `<zip>!<entry>` next to the zip. A zip that `ReviewBundle.read` recognizes as a
   review bundle (D-10) skips the picker and opens the log the manifest names. A zip
   entry ending in `.gz` is unpacked as well.
1. `LogPanel.load` clears the tab and starts a load worker. A plain file is indexed with
   `LineScanner` into an `EntryIndex` and read back through `IndexedEntries` (D-16). A
   `.gz` or a zip entry is indexed the same way, through a decompressed copy written to
   `<data>/cache/` during the same pass. Only if that copy can't be made (no room for a
   zip entry's recorded size, or the file can't be created) is it read with
   `LogFileReader` (core) and each entry held in memory.
2. `LogReaders.open` (core) picks the decoder from the file name: plain, `.gz` (`GZIPInputStream`),
   or one file of a `.zip` (`ZipLogs`, core). Bytes are decoded as UTF-8, with
   malformed input replaced rather than failing. A leading UTF-8 byte-order mark (written
   by PowerShell, Notepad and other Windows tools) is skipped with `Utf8Bom` (core), after
   decompression, so it doesn't break the first line.
3. For each non-blank line, `LineParser` (core) uses either the parser the user picked, or (with
   auto-detection) the first parser whose `canParse` accepts the line. Auto-detection
   happens **per line**, so a file mixing formats still parses.
4. Entries are published in chunks; on the EDT, `LogTableModel.appendBatch` stores them
   and `FilterBar` learns new logger names for the logger tree. `appendBatch` never
   trims: the entry limit doesn't apply to static loads (D-7).
5. Every 10,000 lines the worker asks `MemoryGuard` (core) whether the heap is nearly
   full (headroom below 10% of the maximum heap, at least 64 MiB, after one GC). If it
   is, loading stops, the user gets a warning with the line count and heap size, the
   status bar marks the tab as partly loaded, and follow mode isn't started, since new
   lines would follow a gap.
6. When loading finishes, Clouseau sizes columns, applies the default column layout,
   loads annotations, and starts following if follow mode is on.

### 6.3 Following a log (UC-6)

- **Local files:** The tail worker runs `IndexedFollow` (core), which keeps the position
  between runs and adds each line to the file's index; it reads with `FileFollower` (core), which waits on a `FileChangeWatcher` (core). It uses the
  OS change notifications (`WatchService`) for the file's folder. When the file is written,
  created or replaced, it reads the new bytes from the last position with `FileTail`
  (core), through a channel it holds open on the file. If no notification comes within
  2 s it reads anyway, as a backstop for file systems that don't send them (some network
  drives); if the folder can't be watched at all, that 2 s read is all there is.
  Stopping follow interrupts the wait.
- Lines are split by `LineScanner`, exactly as the first pass splits them, and decoded
  as UTF-8 with malformed input replaced; a BOM is skipped when reading from offset 0.
  The tail only consumes complete lines: a line still being written is held back until
  its line ending arrives, so it never becomes two entries. Each line is reported with
  its byte offset and length, so on an indexed tab (D-16) the tail worker adds it to the
  `EntryIndex` and publishes the entry; `LogTableModel.appendFollowed` counts it in as an
  indexed row. Following starts where the first pass ended. Compressed files are never
  followed.
- **Rotation:** `FileFollower` tells the two kinds apart with the channel it holds. If
  the held file is shorter than the position, it was **truncated in place**
  (copytruncate). If the path is smaller than what was read, or (where the platform has
  file keys) names another file, it was **renamed** and a new file created: what was
  still written to the old file is read first, then the new one is followed from its
  start. Either way the tab keeps its rows. On an indexed tab, `IndexedEntries.rotated`
  starts a new segment for the rows that follow; a renamed file stays open, so its rows
  stay readable, and after a truncation the older rows read as their index columns with
  the message "Text no longer in the file (rotated)", and the status bar says the file was
  rotated. `IndexedEntries` decides whether text was lost from its own channel's size,
  so a rename while follow was off isn't mistaken for a truncation.
- **SSH:** `SshLogSource` runs `sh -c 'test -r "$1" || exit 66; exec tail -n <N> -F -- "$1"' sh '<path>'`
  on the server over sshj and receives lines as they're written. Nothing on the client
  polls. `-F` follows the file by name, so rotation or replacement doesn't stop the
  stream; the `test -r` comes first because `-F` would otherwise wait forever for a
  missing file, and exit 66 becomes `UnreadableFileException`. `sh -c` keeps this working
  under any login shell (csh, fish). The path is single-quoted, so spaces and shell
  metacharacters are taken literally; a leading `~/` is left unquoted so it still expands
  to the remote home. stderr is drained on its own thread (unread, it would eventually
  fill the channel window and stall stdout) and its last lines become the error if
  `tail` exits. sshj keepalives every 15 s fail the stream after 5 unanswered ones, so a
  connection that died silently ends with an error instead of hanging in `readLine`.
  `close` disconnects the transport without closing the channel first: a channel close
  waits up to 30 s for a reply that a silent connection never sends.
  "No password" (`SshConfig` with neither a password nor a key file) tries the `none`
  method, then `password` with an empty password, then `keyboard-interactive` answering
  password prompts with an empty one; servers differ in which an account without a
  password is let in by. `SshLogSourceServerTest` runs the source against an embedded
  Apache MINA SSHD server (a test-only dependency).
- **Plugin sources (D-14):** **File → Open Source** lists every `LogSourceProvider`,
  rebuilt each time the menu opens. Choosing one opens `SourceSettingsDialog`, a form
  with one field per `SourceSetting` plus the parser. On Open, `SourceSettings.check`
  (core) trims values, drops empty optional ones, and checks required values, numbers
  and choices; then `create` is called. The values are remembered per provider (keyed by
  its class name) in `settings.json`, with password values encrypted by
  `CredentialStore`. Exceptions from a plugin's `getName`, `settings` or `create` are
  logged and shown, never thrown into the UI.
- **Live tabs:** SSH and plugin sources share `LogPanel.loadSource`: a source worker
  calls `open`, parses each line with `LineParser` (chosen parser or auto-detection), and appends
  live entries. Columns are sized after the first batch arrives, and again when a later
  line brings a new field. Closing the tab (or loading something else) calls `close()`.
- **Memory bound:** A followed file on an indexed tab is never trimmed (D-16). Live
  entries held in memory (SSH and plugin sources, and following a file that couldn't be
  indexed) go through `LogTableModel.appendLive`, which keeps the tab at most at its cap
  and evicts the oldest entries, along with their highlights. The cap is
  `max.entries.per.tab` (default 200,000, range 10,000–2,000,000), or the number of
  entries the static load added if that was larger, so following a big file doesn't cut
  it to the limit all at once. `clear()` resets the cap and the trimmed count. Clear
  Table on an indexed tab starts a new, empty index on the same file, so what is followed
  afterwards is still indexed.

### 6.4 Filtering (UC-4, UC-5)

`FilterBar` combines the per-level toggles, logger tree, field values, time range, and
text filters into one `CombinedFilter` (core, `buildFilter()`), an `EntryFilter`: a
`Predicate<LogEntry>` that can also be tested on an index row (D-16). `LogTableModel`
re-evaluates it on the filter executor and swaps in the visible rows on the EDT. New
entries arriving during a session are checked against the active filter as they are
appended. Find (`Ctrl+F`) searches the visible rows, including their continuation lines,
on its own executor (`LogTableModel.find`) and returns matching ordinals to the EDT.

### 6.5 Detail panel (UC-3)

When a row is selected, the detail panel shows the entry and its continuation lines. Each
`LogFormatter` whose `canFormat` accepts the message transforms it, then each matching
`LogSyntaxHighlighter` returns color spans. The detail toolbar can turn individual
formatters and highlighters on or off.

### 6.6 Annotations (UC-7 to UC-9)

`AnnotationStore` (core) holds the annotations and row highlights for one file. Each records its note,
author (defaulting to the OS user name) and date, plus two anchors (D-9): the entry
number (the 1-based position of the entry in the file, blank lines not counted) and the
first 16 hex characters of the SHA-256 of the line's `rawLine`.

- **Ordinals:** `LogTableModel` gives every entry a 0-based ordinal, its position in the
  source, which doesn't change when old live entries are dropped. Visible rows are kept
  as a sorted list of ordinals, so row to ordinal and ordinal to row are cheap. The
  store is keyed by ordinal.
- **Loading:** The load worker reads the sidecar first. If it has annotations, the
  worker hashes each line as it reads it (only then) and attaches each annotation: to
  its own entry if the hash still matches there, otherwise to the nearest free entry
  with the same hash. Annotations that match nothing are kept as orphans, listed last
  in the annotations panel and written back on save, so they're never lost.
- **Saving:** After every change, through a temp file and a rename, to
  `<logfile>.annotations` next to the log. If that fails, it saves to
  `<data>/annotations/<hash of the log's path>.annotations` and stays there. When both
  exist, the newer one is read. If neither can be written, the tab warns once and keeps
  the notes in memory.
- **Highlights (D-15):** stored in the same sidecar under `highlights`, each with the
  same two anchors and a palette slot (0–7) rather than an RGB color. After a load, the
  attached ones are applied to the model; every highlight change saves the store.
- **Review bundles (D-10):** the load worker reads the store with
  `ReviewBundle.loadAnnotations`, which, for a bundle's log with no sidecar saved yet,
  adds the bundle's `annotations.json` as orphans before anchoring. Exporting reads
  `AnnotationStore.toJson()` on the EDT (the store is EDT-only once loaded) and writes
  the zip on a `SwingWorker`.
- SSH and plugin-source tabs have an in-memory store.

### 6.7 Plugin lifecycle (UC-11)

- At startup, `ClouseauApp` creates a `ClouseauPluginManager` over `<data>/plugins` and
  calls `loadAll()`.
- The Plugin Manager can install JARs from Nexus or Maven Central repositories, then
  calls `refresh()`, which reloads plugins without a restart.
- After a refresh, `MainFrame.reloadExtensions()` fetches formatters and highlighters
  again. **Tabs that are already open keep the extensions they started with**; only new
  tabs see the change.

### 6.8 Startup and single instance

`SingleInstanceManager` binds a localhost socket (port 54823). If another Clouseau is
already running, a new launch forwards its file arguments to it and exits, so
double-clicking several files opens tabs in one window.

- **Protocol:** one connection per launch, carrying every path as an absolute path (the
  running instance has its own working directory), one per line in UTF-8, until the
  connection closes. The listener queues up to 50 connections, so many launches at once
  (selecting several files in Explorer and choosing "Open with") all get through.
- **Early arrivals:** paths that arrive before the main window is shown wait in the same
  list as macOS open-file events and open when it appears.
- **No answer:** the sender retries for about a second. If the running instance still
  doesn't accept the connection (it hangs, or another program holds the port), the new
  launch shows an error naming the files that didn't open, instead of exiting silently.

### 6.8.1 Colors across theme switches

A live theme switch (`ThemeManager.updateUI`) calls FlatLaf's `updateUI`, which replaces
every `UIResource` color on a component with the look and feel's default. Theme colors
read from `UIManager` are `UIResource`s, so a component colored with a plain
`setForeground(ClouseauColors.x())` loses its color at the first switch. Components that
outlive a switch (anything in a tab or the main window) set colors with
`ClouseauColors.themedForeground`/`themedBackground`, which take a supplier;
`ThemeManager.updateUI` then calls `ClouseauColors.reapplyThemed` on every window. Dialogs
built each time they open, cell renderers and styled text built per selection don't need
it.

### 6.9 Tab switching shortcuts (UC-15)

`TabShortcuts` is a `KeyEventDispatcher` registered with the `KeyboardFocusManager` by
`MainFrame`, not a set of ordinary key bindings, because two Swing behaviors would
otherwise swallow the keys:

- `Ctrl+Tab` and `Ctrl+Shift+Tab` are default focus-traversal keys, consumed by the
  focus manager before any key binding runs (`JTable` also uses them to move focus).
- `JTable` binds `Ctrl+PageUp`/`Ctrl+PageDown` for horizontal scrolling, and a focused
  component's bindings are checked before window-level (`WHEN_IN_FOCUSED_WINDOW`) ones.

A dispatcher sees key events before both, so the shortcuts work wherever focus is. It
acts only while the main window is active, so dialogs keep their own keys, and it
consumes only the shortcuts it handles (a `Ctrl+digit` past the last tab is left alone).
Plain `Tab` and `Shift+Tab` still move focus. The index arithmetic is a static method,
unit-tested without a display.

### 6.10 Dropping files on the window (UC-1)

Swing delivers a drop to the deepest component under the cursor that has a drop target,
and every component with a `TransferHandler` has one: the log table, text fields and the
detail text area get one from their UI, and those reject a file list. So
`FileDropHandler.install` puts a handler on the frame (for empty areas such as the
welcome screen and tab strip) and wraps the existing handler of every component in the
window. It also wraps components added later, through one AWT container listener, which
covers new tabs and components rebuilt by a theme switch. A wrapped handler opens a
dropped file list and passes everything else (text drops, copy, paste, dragging out) to
the original. It hides the drop location, so a drop doesn't move the table's selection.
Dropped files open as `openFile(Path)` does for "Open with": auto-detected parser, one tab
per file, a picker for a zip with several logs. Folders are skipped, and files are opened
after the drop completes, so a dialog never holds up the drag source.

### 6.11 Plain-text view (UC-13)

- **Deciding:** The load worker first reads a sample with `LogSniffer.read` (core): up to
  50 lines or 64K characters, decoded as `LogReaders` decodes them, and flagged binary
  when more than 5% of the characters are control characters or U+FFFD.
  `LogSniffer.detect` is the open dialog's check as well, so the dialog and the tab agree.
  If the sample has a non-blank line and no parser (or not the chosen one) recognizes any
  of them, the worker skips indexing and `LogPanel` switches to the plain-text card. A
  binary sample asks first; declining closes the tab.
- **Storing:** `TextLines` (core) keeps each line's byte offset and length (12 bytes per
  line, in fixed 64K-line chunks that never move, so the EDT can read while the loader
  adds) and reads text back from disk. `line()` has a 4,000-line LRU cache for painting;
  `scan()` reads in 1 MiB blocks on its own channel, for Find. Lines come from
  `LineScanner.scanAllLines`, which unlike `scan` keeps blank lines so numbers match the
  file. A compressed log is unpacked to the tab's cache copy during the same pass, as in
  D-16, and isn't followed.
- **Following:** `FileFollower.follow(…, keepBlank = true)` feeds `TextLines.add`. With
  blank lines kept, `FileTail` skips the `\n` of a `\r\n` split across two reads, so it
  isn't counted as an empty line. On rotation the view reloads the file from the start,
  since its offsets point into the old content.
- **UI:** `PlainTextView` (ui) holds the banner (Create a Parser…, Follow), its own
  `FindBar` searching through `TextLines.scan` on a background thread, and a two-column
  table (line number, text) whose copy action copies only the text. The filter bar, the
  table's find bar and the highlight bar are hidden. **Create a Parser…** calls the hook
  `MainFrame` sets, which opens `ParserEditorDialog` with the samples. On save,
  `LogPanel.retryWithParsers` reloads with auto-detection if a parser now recognizes the
  sample, and otherwise updates the banner.

## 7. Persistence

All state lives in a single **data folder** resolved by `AppDirs`:

1. `-Dclouseau.data.dir=<path>`, if set (the macOS and Linux packages set `~/.clouseau`).
2. Otherwise, in the Windows portable image, `<install>/data/`.
3. Otherwise (development), `./data/` in the working directory.

| File | Contents |
|---|---|
| `settings.json` | Preferences (`AppPrefs`) |
| `parsers.json` | User-defined parsers |
| `plugins/` | Installed plugin JARs |
| `keystore.p12`, `machine-id` | Key material for encrypted credentials (`CredentialStore`, AES-256-GCM) |
| `themes/` | User-installed FlatLaf themes (`ThemeManager`). A theme sets level colors with `Clouseau.level.<LEVEL>` keys (for example `Clouseau.level.ERROR`); missing keys fall back to built-in defaults. |
| `<logfile>.annotations` | **Next to the log, not in the data folder.** Annotations for that file. |
| `annotations/` | Annotations for logs whose own folder is read-only, named after a hash of the log's path (D-9). |
| `cache/` | Decompressed copies of the compressed logs open in tabs (D-16), named `<hash of path and entry>-<unique>.log`. Not persistent: a copy is deleted when its tab closes, and the folder is emptied at startup (by the running instance only) and at exit. |

**Rules:**

1. Any new persistent file goes in the data folder, never in the user's home directory or
   the working directory.
2. Passwords and tokens are stored only through `CredentialStore`, never in plain text.
3. File formats that leave the machine (annotations, review bundles) are **interchange
   formats**: other people's Clouseau versions read them, so keep them backward compatible.

### 7.1 Interchange formats

**Annotations sidecar** (`<log>.annotations`, `annotations.json` in a bundle). UTF-8
JSON, written by `AnnotationStore`:

```json
{
  "version": 2,
  "annotations": [
    { "lineHash": "3f2a…16 hex", "line": 42, "logTimestamp": "…", "logPreview": "…",
      "note": "…", "author": "…", "createdAt": "…" }
  ],
  "highlights": [ { "lineHash": "…", "line": 57, "color": 3 } ]
}
```

`line` is the 1-based entry number and may be missing (version 1 sidecars); `lineHash`
is required (D-9). `highlights` is omitted when there are none; `color` is a palette slot
0–7 (D-15). Orphans are written like attached ones.

**Review bundle** (`*.zip`, D-10). A zip (entry names in UTF-8) with, at its root:

| Entry | Contents |
|---|---|
| `manifest.json` | UTF-8 JSON, below. Written first. |
| the log | The log's bytes as they were stored, under the name in `log` (normally the original file name; `log-<name>` if that name is `manifest.json` or `annotations.json`). A `.gz` stays a `.gz`. |
| `annotations.json` | The sidecar format above: every note and highlight of the tab, orphans included. |

```json
{
  "format": "clouseau-review-bundle",
  "formatVersion": 1,
  "clouseauVersion": "0.1.1",
  "originalFileName": "app.log.gz",
  "log": "app.log.gz",
  "annotations": "annotations.json",
  "exportedAt": "2026-11-04T09:30:00.123Z"
}
```

Reading rules (`ReviewBundle.read`): a zip is a bundle only if `manifest.json` exists,
parses, has `format` equal to `clouseau-review-bundle`, and the entry named by `log`
(else `originalFileName`) exists. Anything else is an ordinary zip. Unknown fields are
ignored, `annotations` defaults to `annotations.json`, and a newer `formatVersion` is
opened anyway (with a log warning), so new fields must stay optional. A later version
can add entries but must keep these meanings.

**Version 2** (D-17) bundles a merged view: a `sources` list gives each log's entry, its
annotations entry, original name, parser, timezone and offset, and the version 1 fields
describe the first log, so an older Clouseau opens the bundle as that log with its notes.
A bundle of one log is still written as version 1. If a source names an entry the zip
doesn't hold, the bundle opens as its first log only.

## 8. Packaging

Clouseau builds with Gradle 8.8 (Kotlin DSL) on Java 21 (see D-12). The `org.beryx.runtime` plugin
produces a jpackage image with a bundled JRE, published as a **portable zip**. The default
heap is `-Xmx2g`. Icons and the splash image are pre-rendered at build time, so the
runtime needs no SVG library.

The native installers (`jpackage` task: exe, dmg, deb; local builds only for now) pass
`--file-associations packaging/file-associations/log.properties`, registering `.log`
only: `.gz` and `.zip` are general archives, and the Windows installer would make
Clouseau their default program. The OS starts Clouseau with the file as an argument, and
`SingleInstanceManager` forwards it to a running window. macOS instead sends an
open-files event, which `ClouseauApp` handles with `Desktop.setOpenFileHandler`.

## 9. Decisions

Short records of choices that shape the design. Status is **Adopted** (in the code),
**Accepted** (agreed, not built yet), **Proposed** (written up, waiting for review), or
**Superseded**.

### D-1 · Swing with FlatLaf · Adopted

A desktop UI that needs no server and runs anywhere Java does. FlatLaf, MigLayout, and
RSyntaxTextArea give it a modern look. JavaFX and web UIs were rejected: JavaFX adds a
second toolkit to bundle, and a web UI implies a server (a non-goal).

### D-2 · PF4J for plugins · Adopted

PF4J provides plugin isolation, `@Extension` discovery, and runtime reloading. Plugins
compile only against `clouseau-api`.

### D-3 · Sources push lines · Adopted

`LogSource` hands lines to a consumer instead of being polled through a file abstraction
like the Apache Commons VFS layer in OtrosLogViewer. Each source picks its own streaming
model, such as a real server-side `tail -F` over sshj for SSH. This avoids polling and the
unmaintained JSch dependency. Local follow is woken by the OS when the file changes, with a
slow read as a backstop for file systems that send no notifications (section 6.3).

### D-4 · Parsers as data · Adopted

Regex-based parsers are JSON configuration, not code, so users can add formats without
writing a plugin (section 5.1).

### D-5 · Annotations in a sidecar file · Adopted

Annotations are stored as JSON next to the log, never inside it. The original log is never
modified.

### D-6 · Collaboration through files, not a server · Adopted

Sharing findings means sending files (the log plus annotations). There is no account,
sync, or backend. See the non-goals in the use cases.

### D-7 · The entry limit applies to live sessions only · Adopted

The per-tab entry limit protects long follow sessions (UC-6). Opening a static file must
never drop lines (UC-1). Static loads are never trimmed, and neither is a followed file
on an indexed tab (D-16). Live entries held in memory are, down to the larger of the
limit and the static load's size (section 6.3). A file whose index fills the heap stops
loading with a warning (section 6.2).

### D-8 · Static files are indexed, not held in memory · Adopted

To have no size ceiling, a static file is read once to build a compact index (byte offset,
timestamp, level, and logger per entry). Text is read from disk on demand for visible
rows, the detail panel, and text filters. Compressed files are first decompressed to
a temporary file in the data folder. A followed local file extends its index. SSH and
plugin sources keep the in-memory model with its limit. A file that still can't be fully
loaded (its index fills the heap, or its unpacked copy fills the disk) produces a clear
warning (UC-1). The detailed design is D-16.

### D-9 · Annotations anchored by line number and content · Adopted

Each annotation stores both its line number and its line's content hash. On load, it
attaches to the line at that number if the hash matches there; otherwise it falls back to
the nearest line with a matching hash. This keeps each annotation on its own line among
identical lines (UC-7). Old sidecars without line numbers keep working through the
fallback.

If the log's folder is not writable, the sidecar goes in `<data>/annotations/`, named
after a hash of the log's absolute path.

The "line number" is the entry number: blank lines are skipped when reading, so they
aren't counted. It is stable for an unchanged file, which is all the anchor needs. When
lines are inserted above a group of identical lines, a note can move to a different one
of them; the content still matches, so this is accepted. Details in section 6.6.

### D-10 · Review bundle · Adopted

`File → Export for review…` (UC-8) writes one `.zip` containing:

```
manifest.json          format version, Clouseau version, original file name, export date
<original file name>   the log, byte-for-byte (still .gz if it was)
annotations.json       the annotations and highlights, same format as the sidecar
```

When opening a `.zip`, Clouseau checks for `manifest.json`. If it's there, the file is a
bundle: it opens the log and loads its annotations. Otherwise it's an ordinary compressed
log (UC-2). The format is an interchange format (section 7, rule 3); section 7.1 has the
details. `ReviewBundle` (core) writes and reads it; `ReviewExporter` (ui) is the menu
action.

- **A bundle is recognized by its manifest's `format`, not just by `manifest.json`.**
  Other zips have a `manifest.json` too (browser extensions, for one), and those must
  still open as logs.
- **The reviewer's notes are saved next to the bundle, not in it.** The log is a zip
  entry, so its sidecar is the zip-entry one, `<bundle>.zip!<log>.annotations` (section
  6.2, step 0). Until that file exists the store starts from the bundle's
  `annotations.json`; the first change writes the whole store, bundled notes included,
  and from then on the sidecar is what loads (so deleting a bundled note sticks). The
  bundle stays exactly as received, so it can be compared with or re-sent, and the
  reviewer passes their notes on by exporting again, which bundles the same log with
  everything in the store. Rejected: rewriting `annotations.json` inside the bundle
  (rewrites a possibly large zip on every keystroke-sized change and modifies a file the
  user received), and saving only in the data folder (the notes would not travel with the
  bundle's folder).
- **The log is copied as stored.** A `.gz` goes in as a `.gz` (stored, not deflated
  again); a log opened from an ordinary zip goes in as that entry's bytes, under its file
  name. Reading a bundle's `.gz` uses the compressed-file path (`LogReaders` unpacks a
  `.gz` zip entry, `LogCache` holds the copy), like any other zipped log.

### D-11 · Aggregator sources are plugins · Adopted

Readers for Loki, CloudWatch, and similar services are welcome, but only as plugins.
Plugins provide them through `LogSourceProvider` (D-14).

### D-12 · Java 21 · Adopted

The whole project, including `clouseau-api`, targets Java 21, and the release zips bundle
a Java 21 runtime. Plugin authors and contributors need JDK 21 or later.

Java 21 replaced Java 17 for a longer support window on the bundled runtime and access to
newer language features. Java 25 was benchmarked and rejected (7-run medians, 1M-line
log):

| Config | Main window | Load 1M lines | Heap |
|---|---|---|---|
| Java 17 | 3.03 s | 5.63 s | 118 MB |
| Java 25, defaults | 3.05 s | 5.68 s | 119 MB |
| Java 25 + compact object headers | 3.07 s | 5.68 s | 115 MB |
| Java 25 + AOT cache | 2.65 s | 4.95 s | 118 MB |

Java 25 alone made no difference. Compact headers saved only about 3% of heap, because
memory is mostly line text. The AOT cache saved about 12%, but it adds roughly 61 MB per
platform, is tied to the exact JVM build and paths, and was measured before Batik was
removed from startup (which already cut much of the class loading it speeds up). Gradle
8.8 also can't run on JDK 25.

Revisit when Java 21 nears the end of its free updates, or if a Java 25 feature becomes
compelling. Re-measure first.

### D-13 · A tab shows one or more sources · Adopted

To support merged views (UC-12), a tab holds a list of **sources** instead of one file.
Each source has its own file (or zip entry), parser, source timezone, time offset, tail
worker, and `AnnotationStore`. A single-file tab is just a tab with one source.
Built as D-17 describes. A single-file tab shares the per-file loading and following
classes with a merged view's files rather than going through the merge itself (D-17,
question 4).

- **Entry origin:** The table model records which source each entry came from, so the
  Source column, annotations, and export can find it. This lives in the UI/core model,
  not in `LogEntry`, so `clouseau-api` and plugins are unaffected.
- **Merging:** Each source is read into groups of an entry plus its continuation lines.
  The groups are merged by timestamp (a k-way merge), keeping each source's order for
  equal timestamps.
- **Live entries:** Followed sources can deliver entries older than the newest row from
  another source, so live entries are inserted at their timestamp position, not
  appended. The view keeps scrolling to the bottom only if the user is already there.
- **Columns:** The standard `LogEntry` fields always line up. Custom columns are the
  union of every source's `customFields()`, matched by name.
- **Time offset:** Applied after the source timezone, to the timestamp used for sorting
  and display. `rawLine` is never changed, so the detail panel shows what the file
  actually says. Changing an offset re-sorts that source's entries into place.
- **Review bundles:** A bundle exported from a merged view (D-10) contains every source
  file and its annotations, and the manifest lists each file with its parser, source
  timezone, and time offset, so the view opens the same way on the other end.
- **Scale:** A merged view multiplies the data in one tab, so it relies on D-7 and D-8
  even more than a single file does.

The detailed design is D-17.

### D-14 · Plugin sources declare their settings; no plugin filters · Adopted

A `LogSource` needs settings for each connection (a URL, a query, a token), but PF4J
creates one instance of each extension, with no arguments. So the extension point is a
factory, `LogSourceProvider`, and the source itself stays a plain interface.

- **Settings are declared, not drawn.** A provider lists `SourceSetting`s, and Clouseau
  draws one generic form for all providers. This keeps Swing out of `clouseau-api`
  (section 3, rule 3), makes every source look and behave the same, and lets Clouseau
  own validation and credential storage. Rejected: a single location string such as
  `loki://…` (awkward for several values and secrets, and each plugin invents a syntax),
  and letting plugins show their own dialog (puts Swing in the API).
- **`LogFilter` was removed from `clouseau-api`.** It was never discoverable, so no
  plugin could have used it, and no use case asks for plugin filters. It can come back
  with a use case behind it.
- **SSH stays special.** Its dialog has auth modes, key files and saved connections, so
  it keeps its own form, but it streams through the same `LogPanel.loadSource` path.
  `SshLogSource` lives in `clouseau-core`.

### D-15 · Highlights are saved with annotations · Adopted

Row highlights are part of an investigation, like notes, so they're saved in the same
sidecar and anchored the same way (D-9). They come back when the file is reopened and go
into a review bundle (D-10) with the notes. Rejected: keeping them in memory only (lost
on close), and saving them only in the data folder (private, so they can't be shared).

A highlight stores its palette slot, not an RGB value: themes define the palette, so a
colleague with another theme sees the same slots in their own colors.

### D-16 · How static files are indexed · Adopted

The design for D-8, written to close G-5 (now closed). Accepted on 2026-11-04 with the
recommended answer to each question at the end, and built in the six steps below. Every
static file is indexed, compressed ones through a decompressed copy, and a followed
local file extends its index.

**Measured after step 2** (a 1M-line, 125 MB log in the sample format; heap after a full
GC, whole app): 478 MB without the index, **81 MB with it**.

**Measured after step 3** (the same log, about 1M lines, on a `LogPanel` off-screen): load
2 s, heap after GC 47 MB. A level filter took 31 ms. A text filter took 1.6 s, and so did
Find over every visible row; both showed progress, and the EDT never waited more than
9 ms.

**Measured after step 4** (a 1M-line `.gz`, 8 MB packed and 89 MB unpacked, on a
`LogPanel` off-screen): load 2.5 s, heap after GC 49 MB, EDT never waited more than
3 ms. Closing the tab deleted the copy.

**Why.** A loaded entry costs about 120 bytes of heap per line today (the D-12 benchmark:
118 MB for 1M lines), so the default 2 GB heap stops somewhere under 17M lines. That is a
few GB of log. An index keeps a few fixed-size numbers per entry and reads text from disk
only when it's needed.

**Scope.** Local static files and what follows them (UC-1, UC-2, UC-6 on a local file).
SSH and plugin sources keep the in-memory model and its entry limit (D-7).

**Pieces** (all in `clouseau-core` unless noted):

- **`LineScanner`** reads the file as **bytes**, not characters, and reports each line's
  byte offset and length. Lines end at `\n`, `\r\n` or a lone `\r`, exactly as
  `BufferedReader.readLine` splits them, so entry numbers match the in-memory loader;
  neither byte occurs inside a multi-byte UTF-8 character. It skips a leading BOM (the
  offsets still count it, so they are true file positions) and skips blank lines, as
  `LogFileReader` does. A `BufferedReader` can't be used, because it hides byte
  positions.
- **`EntryIndex`** holds one row per entry, in file order, as columns of primitives:

  | Column | Type | Bytes |
  |---|---|---|
  | offset | `long` | 8 |
  | length | `int` | 4 |
  | timestamp (epoch millis, `Long.MIN_VALUE` if none) | `long` | 8 |
  | level (`UNKNOWN` marks a continuation line) | `byte` | 1 |
  | logger id | `int` | 4 |
  | thread id | `int` | 4 |

  That's 29 bytes per entry, about a quarter of today's cost. Loggers and threads are
  stored once each in a dictionary, with ids in the columns. Custom fields are stored the
  same way, one id column per field the parser declares, up to 1,000 distinct values
  per field. A field with more values stops being indexed: the Fields filter doesn't
  offer it, and filtering on it reads text (as the text filter does below).
  Columns are kept in chunks of 1M rows, so no single array becomes huge and appending
  is cheap.
- **`IndexedEntries`** turns an index row back into a `LogEntry`: a positioned read of
  `length` bytes at `offset` (a `FileChannel` held open for the tab), UTF-8 decoding with
  malformed input replaced, then `LineParser` on the line. A cache of the last 10,000
  entries covers scrolling, the detail panel and the renderers, which ask for the same
  rows many times; it is used on the EDT only. `scan` reads a range of rows in order, in
  1 MB sequential reads, for the filter and find executors. It reads through an
  `AsynchronousFileChannel`, also held open: the executors are cancelled by interrupts,
  and interrupting a thread inside `FileChannel.read` closes that channel, but
  interrupting one that waits for an asynchronous read doesn't. (Opening a channel per
  scan, as step 3 did, can't work after a rename, which leaves the old file without its
  name.) Rows are kept in segments, one per file the followed path has named, each with
  its own pair of channels (see Rotation).
- **`LogTableModel`** (ui) holds an optional `IndexedEntries` (read through `EntryRows`
  since D-17 step 2) in front of its in-memory
  list: ordinals below the indexed count are read through it. Followed lines are added
  to the index (`appendFollowed`); the list after it stays empty on an indexed tab, which
  is never trimmed. Rows
  stay a sorted list of ordinals, and an ordinal is still the entry's position in the
  file. (The design first called for an `EntryStore` interface with two
  implementations; one optional prefix turned out simpler, with the same effect.)
- **Threads.** The load worker appends to the `EntryIndex` and publishes each entry, and
  after it the tail worker does (one at a time: each holds the tab's tail lock); the
  model, the filter executor and Find only read rows they have been handed through
  `publish`. A new segment is published the same way (a volatile list, replaced) before
  the first row in it. Columns are published safely (each column's chunk list is a volatile field
  that's replaced, never changed in place, when it grows). Dictionaries do the same with
  their value array and use a `ConcurrentHashMap` for lookups; a field that overflows is
  marked with a volatile flag rather than removed, so a reader never sees half of it.
- **Level `null`.** A plugin parser may give no level. The index stores that apart from
  `UNKNOWN`, so filters treat it the same way on the index as on the entry (it always
  shows).

**Loading.** One pass: `LineScanner` → `LineParser` → `EntryIndex`. The parsed
`LogEntry` is dropped after its columns are written. The table shows rows as chunks
finish, as it does now. Logger names reach `FilterBar` as they are first seen. If the
sidecar has annotations, the pass hashes each line and anchors them exactly as now
(section 6.6). Entry numbers don't change, so annotations, highlights and the sidecar
format (D-9, D-15) carry over unchanged. The `MemoryGuard` stop stays as a safety net
for the index itself.

**Compressed files.** A `.gz`, or a file inside a `.zip`, is decompressed during the same
pass to `<data>/cache/<hash of path and entry>-<unique>.log` (`LogCache`), and the index
points into that copy. The unique part keeps two tabs on the same log from sharing one.
`LogReaders.openBytes` gives the decompressed bytes as stored (the BOM is left for
`LineScanner`, so offsets match the copy), and `CopyingInputStream` writes every block to
the copy, unbuffered, before `LineScanner` sees it, so a row can be read back as soon as
it's indexed.

- **Free space.** Before starting, Clouseau checks the free space against the
  uncompressed size plus 256 MB when the size is known (`LogReaders.uncompressedSize`: a
  zip entry records it; a `.gz` keeps it only modulo 4 GiB, so it's never trusted). If
  there isn't room, the file is loaded into memory as before D-16.
- **Disk full mid-way.** `CopyingInputStream` ends the stream at the last block it
  wrote instead of throwing, so the index holds exactly what the copy holds. The tab
  stops with a partial-load warning that names the cache folder, and the status bar
  says "partly loaded (disk full)".
- **Cleanup.** The copy is deleted when the tab closes or reloads. The load worker may
  still be writing it then; Java opens files with delete sharing on Windows, so the
  delete succeeds and the file goes when the last handle closes. The running instance
  empties `cache/` at startup (a second launch, which only forwards its files, never
  does) and from a shutdown hook at exit, since the window closes with `EXIT_ON_CLOSE`.
- Compressed files are still never followed.

**Filtering.** `FilterBar` produces an `EntryFilter` (core): a `Predicate<LogEntry>` with
two more methods, so one object carries both parts:

- `testIndex(index, row)`, the **index filter** over the columns (level, logger, indexed
  fields, time range). It needs no I/O. It must never reject a row `test` would keep.
- `needsText(index)`: true when there is message text, or an excluded value on a field
  with too many values to index. Then the filter executor reads only the rows
  `testIndex` passed, in file order, in large sequential reads (`IndexedEntries.scan`
  with a row predicate), and gives each to `test`. It reports progress to the status bar
  and is cancelled by the next filter change.

`CombinedFilter` is the filter bar's implementation; `CombinedFilterTest` checks that the
index and the entry decide alike. A plain lambda is also an `EntryFilter` whose rows are
all read, which the tests use. Time ranges are compared in whole milliseconds on both
paths, since that's what the index keeps.

If the load is still running, rows indexed during a text run are filtered by another
run rather than read on the EDT (only up to 2,000 are caught up on the EDT).

Find (`Ctrl+F`) runs on its own executor with the same scan. It reads the visible rows,
and the continuation lines that follow them (from the index's level and timestamp
columns), in one ordered pass: a continuation line that matches marks the visible
entries it belongs to. It returns ordinals; `LogPanel` maps them to model rows and
orders them by the view.

The generation counter and cancellation in section 6.1 are unchanged.

**Not covered yet:** sorting by a column header reads every row on the EDT, which on an
indexed file means reading the whole file. That predates the index (sorting a large
in-memory tab is also slow), but the index makes it much slower.

**Continuation lines.** They are rows with level `UNKNOWN`, as today. The detail panel and
find read the rows that follow an entry until the next row with a timestamp.

**Following.** `FileFollower` reports every line it reads with its byte offset and
length, split by `LineScanner` as in the first pass, so appended lines extend the index
the same way the first pass does (section 6.3). Following resumes where the first pass
ended. Followed rows are never trimmed: the D-7 entry limit was there to protect the
heap, and with an index an entry costs too little for that to matter.

**Rotation.** Index rows point into the file, so what happens to older rows depends on
how the file was rotated. `FileFollower` reports which it saw (section 6.3);
`IndexedEntries.rotated` starts a new segment for the rows that follow, on the file the
path names now, and checks its own channel to decide whether older text was lost:

- **Renamed** (logrotate's default, log4j/logback rolling): the tab keeps its open
  channels on the old file, so older rows stay readable. On Windows this needs the
  channels opened with delete sharing, or the writer can't rename the file.
  `FileChannel.open`, `Files.newByteChannel` and `AsynchronousFileChannel.open` share
  delete by default; `RandomAccessFile` doesn't, so it must not be used. Lines written to
  the old file before the rename are read before the new file. The new file is followed
  as new rows.
- **Truncated in place** (copytruncate): the old text is gone. Older rows keep what the
  index holds (time, level, logger, thread, indexed fields). The message reads "Text no
  longer in the file (rotated)", and the status bar says the file was rotated. The rows
  are kept, so annotations and highlights on them aren't lost (question 4).

**Size ceiling after this.** About 29 bytes of heap per entry plus dictionaries: about
70M entries in the default 2 GB heap, roughly 10 to 15 GB of typical log. Open question 1
removes that ceiling too.

**Order of work** (each step ships and is tested on its own):

1. ✅ `LineScanner` and `EntryIndex`, with tests against the current reader on the sample
   logs (same entries, same entry numbers).
2. ✅ `IndexedEntries` behind `LogTableModel`, at first behind a system property.
3. ✅ The two-part filter, and the text scan with progress (Find included). Plain static
   files are indexed by default; the property is gone. Until step 5, followed lines on an
   indexed tab were held in memory and trimmed to the entry limit.
4. ✅ Compressed files through `cache/`.
5. ✅ Follow and rotation on the index, with tests for appends, renaming and
   copytruncate (`FollowIndexTest`).
6. ✅ Update UC-1, close G-5, mark D-8 and D-16 Adopted.

**Questions settled at review** (each took the recommendation):

1. **Heap or memory-mapped columns?** In the heap is simpler and enough for about 70M
   entries. Writing the columns to a file in `cache/` and memory-mapping them removes
   the ceiling, costs about 29 bytes of disk per entry, and needs cleanup like the
   decompressed copies. *Recommendation:* heap first, with columns behind a small
   interface so mapping can be added later without touching anything else.
2. **Index every static file, or only big ones?** One path for every file is simpler to
   test. But a text filter on a small file becomes a file scan instead of a list scan
   (fast while the OS caches the file, slower than today on a network drive).
   *Recommendation:* index every static file, and measure the text filter on the 1M-line
   benchmark before step 3 ships.
3. **Keep the index between runs?** Reopening a big file would be instant. But it needs
   an invalidation rule (size, modified time, a hash of the first and last 64 KB) and
   disk space. *Recommendation:* not now. The first pass is needed anyway to anchor
   annotations.
4. **Rows lost to copytruncate:** show them with the placeholder text as above, or drop
   them from the tab? *Recommendation:* keep them, so annotations and highlights on them
   aren't lost.

### D-17 · How merged views work · Adopted

The design for D-13 and UC-12. Accepted on 2026-11-04 with the recommended answer to
each question at the end, and built in the eight steps below (all done on 2026-11-04).
What was built differently is noted in each step. Nothing is left: G-10 (a parser per
file, step 6) and G-11 (single-file tabs, question 4) were closed afterwards.

**Scope.** A merged view combines **local files**: plain files, `.gz` files and zip
entries, each indexed as in D-16. SSH and plugin sources stay one per tab. Merging them
would mean merging in-memory entries with an entry limit, a second path that's worth
building only when someone asks for it. A file that falls back to in-memory loading
(no room for its unpacked copy) can still be part of a merge, through the same
interface as an indexed one (see *Rows*).

**Sources.** A tab holds a list of `MergeSource`s (ui). Each has its path (and zip
entry), parser (chosen, or detected from its own sample), source timezone, time offset,
`IndexedEntries`, `AnnotationStore`, cache copy, and tail worker. A single-file tab is
a tab with one source, so there is one code path. Every source's lines are parsed by
its own `LineParser`, so formats can be mixed.

**Identity and position.** Today an ordinal is both an entry's position in the table
and its identity: the key for highlights and annotations, and its entry number in the
file. A merged view separates the two:

- **Position:** the merged ordinal, 0 to *N*−1 in timeline order. `rows` stays a
  sorted list of merged ordinals, so filtering, Find and row/ordinal mapping work as
  now.
- **Identity:** `(source, row)`, the source's index in the tab and the entry's row in
  that source's index. It never changes. Highlights in the model are keyed by it, and
  each source's `AnnotationStore` keeps its own entry numbers (D-9), so the sidecar
  format doesn't change.

**Merge order** (`MergeOrder`, core). Two arrays, in chunks as in `EntryIndex`:

- merged ordinal → source (`byte`) and source row (`int`): 5 bytes per entry;
- per source, source row → merged ordinal (`int`): 4 bytes per entry.

With the 29 bytes of D-16 that's 38 bytes per entry, so the default 2 GB heap still
holds about 50M entries across all of a tab's sources. With one source neither array
exists: position equals row, and a single-file tab costs exactly what it does now.

It's built by a k-way merge over **groups**: an entry and the continuation lines after
it. Only the index's timestamp and level columns are read, with no file I/O. A group's
key is its first row's timestamp plus that source's offset. Lines before a source's
first timestamp take that first timestamp, so they stay at the start of their file.
Equal keys are ordered by source (the order the files were listed), then row, which
keeps each file's own order (UC-12 rule). A group is never split, so continuation lines
stay with their entry. A merge of 1M entries across a few files should take well under
a second; it runs on the filter executor, with progress, and is swapped in on the EDT.

**Rows.** `LogTableModel` reads through a small interface, `EntryRows` (core), instead
of holding `IndexedEntries` directly: `count`, `get(ordinal)`, `isContinuation`,
`timestamp`, `testIndex(filter, ordinal)`, `needsText(filter)` and `scan`.
`IndexedEntries` is the one-source implementation (identity map). `MergedEntries` is the
merged one, built from the sources' `IndexedEntries` and a `MergeOrder`. D-16's step 2
chose a plain prefix over this interface; a second implementation is what makes the
interface pay off now.

- `get` maps the ordinal to `(source, row)` and reads from that source's file.
- `testIndex` tests against that source's `EntryIndex`. Logger, thread and field ids
  are per index, so `CombinedFilter` resolves names to ids once for each index it sees.
  The time filter compares the corrected time (timestamp plus offset), the time the
  table shows.
- `scan` (text filters and Find) reads **each source in file order**, one after the
  other, so reads stay sequential, and returns merged ordinals. The filter executor
  sorts them before swapping them in. Find's continuation-line rule (a matching
  continuation marks its entry) works per source, because a group is contiguous in
  both orders.

**Columns.** The standard columns line up for every format. A **Source** column is
shown when the tab has more than one source, holding the file name (or `zip!entry`),
with a mark such as `device.log (+3m 12s)` when the source has an offset. Custom fields
are the union of the sources' `customFields()`, matched by name; a source without a
field has an empty cell. The Source column is also a filter: the filter bar gains a
Sources list, like Loggers, for hiding whole files.

**Opening a merged view.**

- **Several files** chosen in the open dialog, or dropped on the window, ask once:
  "Open 3 files in one merged view, or each in its own tab?" The answer can be
  remembered (Settings). Today they always open in separate tabs.
- **File → Open Merged…** opens the file dialog, preselected for merging.
- **Zip picker:** with several entries ticked, a "Merge into one view" checkbox.
- **A pattern** typed in the dialog's file name field (`app.log*`) is expanded in its
  folder, sorted by name, and opened as a merged view. The file set is fixed once
  opened (UC-12 rule).
- **Sniffing:** each file is sniffed first (`LogSniffer`, as in 6.11). A file no parser
  recognizes, or whose sample has no timestamps, can't go on the timeline. Clouseau
  lists them ("2 files have no timestamps") and offers to open them in their own tabs;
  the rest are merged. One file left is just a normal tab.
- **Loading:** the sources are indexed **in parallel**, one load worker each (they're
  separate files). The table stays empty with a progress bar until all are indexed,
  then shows the merge. Showing rows while files load would mean re-merging and moving
  rows as each file arrives.

**Following.** Each source that can be followed (not compressed) has its own tail
worker, as now. Followed sources deliver lines at about the same wall time, but not in
order: one file can be written a second later than another, or its writer can buffer.

- **Hold back:** followed entries are put into a queue on the EDT and released when
  every followed source has delivered something at least as new, or after **1 s**,
  whichever comes first. Released entries are appended in key order. That covers
  ordinary write delays without moving rows.
- **Late entries:** an entry older than the last row already shown (a clock skew larger
  than the hold-back, or a writer that flushed late) triggers a **re-merge** of the
  tail of the order, debounced to one per second. Only rows after the late entry's
  position move. The selection and scroll position follow their entry, by identity.
- **Rotation** is per source, as in D-16; a renamed source keeps its old rows and
  continues in a new segment. New files that start matching a pattern are never added.
- **Scrolling:** the view keeps scrolling to the bottom only if the user is already
  there (D-13).

**Time offset.** Right-click a row or the Source cell → **Set Time Offset for
device.log…** (a dialog for ± hours, minutes, seconds and milliseconds), also in a
**Sources** menu that lists the tab's files. Setting it re-merges (the whole order,
since every entry of that source moves), keeps the selection by identity, and updates
the Source mark. The table and time filter use the corrected time; the detail panel
shows the original line and adds "Shifted +3m 12s; written as 10:02:04.120". The
source timezone stays per source and is applied first, as UC-12 says.

**Annotations and highlights.** Each source has its own store, loaded and anchored by
its own load worker exactly as now (6.6), and saved next to its own file. The
annotations panel lists all of them, with the source's name. Highlights are stored per
source too (D-15). The model maps between identity and position through `MergeOrder`.

**Review bundles.** Exporting a merged view writes every source's file and its notes.
`formatVersion` 2 adds a `sources` list; the version 1 fields stay and describe the
first source, so an older Clouseau opens the bundle as that one file (with its notes)
rather than failing:

```json
{
  "format": "clouseau-review-bundle",
  "formatVersion": 2,
  "log": "app.log", "annotations": "annotations.json", "originalFileName": "app.log",
  "sources": [
    { "log": "app.log", "annotations": "annotations.json",
      "parser": "Logback", "timezone": "Europe/Berlin", "offsetMillis": 0 },
    { "log": "device.log", "annotations": "device.log.annotations.json",
      "parser": "Android logcat", "timezone": "UTC", "offsetMillis": 192000 }
  ]
}
```

A parser the reviewer doesn't have falls back to the tab's choice (auto-detection unless
the open dialog chose one); the timezone and
offset come from the bundle, so the timeline matches.

**Not covered:** sorting by a column header (D-16) is no slower here than on one big
file; merging sources from different machines over SSH; saving a merged view's file set
as a reusable "workspace".

**Order of work** (each step ships and is tested on its own):

1. ✅ `MergeOrder` (core): groups, ties, leading lines, offsets, appending;
   `MergeOrderTest`. `EntryIndex` is its `Timeline`. **Measured:** 1M entries in four
   files (one row in five a continuation line), both interleaved and as four rotated
   files that don't overlap: the test's three merges and their setup take 0.2 s in all.
   The arrays cost 9 bytes per entry, as estimated.
2. ✅ `EntryRows` with `IndexedEntries` as its one-source implementation, behind
   `LogTableModel`; no change in behavior (the existing tests pass unchanged). `scan`
   takes a `Visitor` and reports ordinals in **runs** (one per source): the filter
   executor sorts what several runs found, and Find starts its continuation tracking
   again at each run. `EntryRowsRunsTest` checks both with a fake two-run source.
3. ✅ `MergedEntries`, and a static merged tab from the open dialog and drops: the prompt,
   sniffing and the no-timestamp offer, parallel loading, the Source column and filter,
   union columns, filters and Find across sources. As built:
   - `MergeLoad` (core) does the background work: `LogSniffer` on each file (a file is
     left out if no parser recognizes its sample, no sample line has a timestamp, or a
     compressed file can't be unpacked to `cache/`), then one indexing thread per file
     (up to the number of processors), then `MergeOrder`. Entries are handed to the
     filter bar as they're indexed, so it learns loggers and field values. Low memory or
     a full disk stops the whole load and merges nothing; a partial timeline would
     mislead. `MergeLoadTest`, `MergedEntriesTest`, `MergedViewTest`.
   - `LogPanel.loadMerged` shows it; `MergeListener` tells `MainFrame` which files were
     left out (offered in their own tabs), when fewer than two could be merged (the tab
     closes and the one file opens normally), and which files the view shows (the tab
     title, `app.log +2`). A reload merges only those.
   - **Opening:** several files from the open dialog, dropped, or given on the command
     line ask "merged view or separate tabs?", with "Remember my choice"; Settings →
     "When opening several files" (`open.several` in `settings.json`) changes it later.
     Zips open as before.
   - **Source column and filter:** the Source column is the first custom column, keyed
     `LogTableModel.SOURCE_COLUMN` (a name no parser can declare). The filter bar's
     **Sources** button hides whole files; `EntryFilter.testSource` carries that, and
     `MergedEntries.testIndex` checks it before the index columns.
   - **Not yet (later steps):** single-file tabs keep their own loader (`LogPanel.load`; it now indexes through `FileIndexer`, question 4);
     only the model reads both through `EntryRows`. Every file uses the tab's parser
     choice (chosen, or per-line detection). A merged tab can't be exported for review
     (step 7). A compressed file that can't be unpacked is left out rather than loaded
     into memory.
4. ✅ Annotations and highlights per source, keyed by identity. As built: `Notes` (core)
   is what `LogPanel`, the annotations panel and the highlight menus use; one file's
   `AnnotationStore` implements it, and `MergedNotes` puts one store per source behind
   it, mapping merged ordinals to each store's own entry numbers through `MergeOrder`
   (`setOrder` takes a new order, for steps 5 and 6). `MergeLoad` loads and anchors each
   file's store on that file's indexing thread, so notes and highlights are saved in
   the file's own `.annotations` sidecar (or `annotations/`) and show again when the
   file is opened alone. The annotations panel prefixes each preview with `[file]`.
   `MergedNotesTest`.
5. ✅ Following: hold-back, late entries, per-source rotation. As built:
   - `MergeLoad` returns where each plain file's index ends; `MergedTailer` (ui) runs a
     `FileFollower` from there for each of them (compressed files aren't followed), adding
     lines to that file's index as a single-file tab does, so rotation is per source.
   - `MergedFollow` (core, one thread) holds the rows back. A file counts towards the
     hold-back once it has written something while followed, so a rotated `app.log.1`
     that never grows doesn't hold anything up. Held rows go when every such file has
     delivered something at least as new, or after 1 s; a Swing timer checks every
     200 ms.
   - `MergeOrder.placeFrom` finds where new rows go: never before their own file's
     earlier rows, after equal keys from lower sources. If that is the end,
     `MergeOrder.extend` appends in place. Otherwise it returns a **new order** that
     shares the old one's full chunks below that point (copying only the partial chunk)
     and merges the rest again; `MergedEntries.setOrder` installs it, and readers take
     the order once per call, so a filter run in progress keeps a consistent view and is
     restarted. Re-merges happen at most once a second; rows wait meanwhile.
   - `LogTableModel.reorderTail` re-filters from the changed point (in the background
     if a long tail needs text), with highlights moved to their entry's new ordinal;
     `MergedNotes.setOrder` does the same for notes, and the selection stays on its
     entry. Find's matches are cleared. With Follow on the table scrolls to the bottom,
     as on a single-file tab.
   - Clear on a merged tab stops following it; reload follows again.
   - `MergeOrderExtendTest` (including that extending in random steps gives the same
     order as one merge), `MergedFollowTest`, `MergedViewTest`.
   - **Since joined:** single-file tabs follow through `IndexedFollow` too (question 4);
     they keep their own tail worker, without the hold-back a single file doesn't need.
6. ✅ Time offsets: dialog, Sources menu, re-merge, the detail panel line. As built:
   - **Per-file setup:** `MergeLoad.run` takes a `PerFile` (source timezone and offset)
     for each file. A file's timezone is its own saved one (`source.timezones` in
     `settings.json`, as for a single file), else the tab's; the tab's "Set Source
     Timezone…" sets it for every file, and the status bar shows "per file" when they
     differ. Changing a file's timezone saves it and reloads the tab. Offsets are kept
     by the tab across reloads, not saved (the review bundle carries them, step 7).
   - **Offsets** live in `MergedEntries`, swapped together with the order. `get` and
     `scan` return entries with the corrected timestamp (the raw line is untouched), and
     `testIndex` passes the offset to `EntryFilter.testIndex(index, row, offset)`, so
     `CombinedFilter`'s time range compares the corrected time. `sourceLabel` adds the
     mark, `device.log (+3m 12s)` (`TimeOffset.format`).
   - **Setting one** (`TimeOffsetDialog`: sign, hours, minutes, seconds, milliseconds,
     or "No Offset") rebuilds the whole order in the background; on the EDT, rows
     followed meanwhile are placed with `extend` before the new order is swapped in,
     so nothing is lost or counted twice. Only the latest change is shown.
     `LogTableModel.reorderTail(0, …)` re-filters: on the EDT for up to 100K rows from
     the index alone (2K if the filter needs text), otherwise in the background, with
     the selection restored once rows are in (`whenFiltered`).
   - **Where:** right-click a row → "Set Time Offset for app.log…" and "Set Source
     Timezone for app.log…"; the tab's context menu has a **Sources** submenu listing
     every file with both. The detail panel adds "Shifted: +3m 12s; written as …".
   - `TimeOffsetTest`, `MergedEntriesTest`, `MergeLoadTest`, `MergedViewTest`.
   - **Parser per file** (added later, closing G-10): right-click a row → "Set Parser for
     app.log…" (also in the **Sources** submenu) reads that file with one parser, or with
     "Same as the tab". It's `MergeLoad.PerFile.parser`, used for the file's sniff, index,
     reading back and following (`MergedTailer` takes one parser per file); the tab keeps
     it across reloads, not saved. A parser that doesn't recognize the file's first lines
     is refused, since the file would be left out of the merge. `MergeLoadTest`,
     `MergedViewTest`.
7. ✅ Zip picker merge, file patterns, Open Merged…, review bundle version 2. As built:
   - The zip picker has "Merge into one view" (on unless the "When opening several
     files" setting is Separate Tabs), enabled once two files are ticked.
   - **File → Open Merged…** opens the dialog in merged mode: what is chosen opens merged
     without asking. A pattern typed in the file name field (`app.log*`, `*` and `?` in
     one folder) is expanded by `FilePattern`, sorted by name, and opened merged; the
     set is fixed.
   - `ReviewBundle.writeMerged` writes version 2 (section 7, review bundle): log entries keep their
     names, with `2-app.log` for a name used twice; the first log's notes are
     `annotations.json`, the others `<log>.annotations.json`. `ReviewExporter` exports a
     merged tab through it (`LogPanel.reviewParts`). Opening a version 2 bundle opens a
     merged view of its logs with their timezones and offsets
     (`LogPanel.presetMerged`); a timezone changed there stays in that tab. Each file's
     parser is taken from the bundle by name (G-10); one this Clouseau doesn't have falls
     back to the tab's choice.
   - `FilePatternTest`, `ReviewBundleTest`, `MergedViewTest`.
8. ✅ Update UC-12 (✅), mark D-13 and D-17 Adopted; G-10 and G-11 (both since closed) record what was
   left out.

**Questions settled at review** (each took the recommendation):

1. **Several files chosen: ask, or always merge?** Asking costs a click every time, and
   always merging changes what multi-select does today. *Recommendation:* ask, with
   "Remember my choice".
2. **Show rows while loading?** Waiting for every file is simpler and nothing moves; the
   cost is a blank table for the few seconds of a big load. *Recommendation:* wait, with
   one progress bar for all sources.
3. **Late followed entries: re-merge, or append where they arrive?** Appending is
   simpler and nothing ever moves, but a late line then sits in the wrong place, which
   breaks the point of a merged view. *Recommendation:* re-merge the tail, with the
   1 s hold-back so it's rare.
4. **Single-file tabs on the same path?** One path (a tab with one source) means the
   merged code is exercised by every tab, but the refactor touches all of
   `LogPanel`'s loading. *Recommendation:* one path, with step 2 as a pure refactor so
   any regression shows up before merging exists.
   *As built (closing G-11):* single-file tabs share the **per-file** machinery with every
   file of a merged view: `FileIndexer` (core) indexes one file, unpacking a compressed
   one and anchoring its notes, and `IndexedFollow` (core) follows one file into its
   index, keeping the position and the one-reader lock. `MergeLoad`, `MergedTailer` and
   `LogPanel.load`/`startTailing` all go through them. A single-file tab doesn't go
   through `MergeLoad` itself, because it deliberately behaves differently: rows appear
   while it loads, a load cut short by low memory is kept (marked "partly loaded"), a
   file no parser recognizes opens as plain text (UC-13), a compressed file with no room
   for its copy is read into memory, and lines need no timestamps.
5. **Live sources (SSH, plugins) in a merge:** *Recommendation:* not now, as in *Scope*.

## 10. Planned features

Accepted in the use cases, with no design decision needed beyond the above: none at
the moment. (The plain-text view, UC-13, is now built; see 6.11.)

## 11. Known gaps

Places where the code differs from this design. Together they're the technical roadmap.
Remove an entry once it's fixed.

| ID | Gap | Impact |
|---|---|---|
| **G-9** | Reading, parsing, indexing, following and annotation anchoring live in `clouseau-core` (`LogReaders`, `LineParser`, `FileIndexer`, `IndexedFollow`, `AnnotationStore`); the table and its columns (`LogTableView`), the row menu (`RowMenu`), the detail pane, find bar and highlight navigation bar (`DetailPanel`, `FindBar`, `HighlightNavBar`) are their own classes. `LogPanel` is still about 1,450 lines: the load and follow wiring for single files, merged views and live sources, the merged files' offset, timezone and parser dialogs, plain-text mode, and the status bar. | Reviewable, but still the biggest UI class. Next split if it grows: a merged tab's per-file settings (offsets, timezones and parsers, with their dialogs; about 200 lines). |
