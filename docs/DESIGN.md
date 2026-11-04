# Clouseau Design

> **Status:** Draft · **Last updated:** 2026-11-03
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
clouseau-ui ─────────────┐   MainFrame, tabs, LogPanel, LogTableModel, FilterBar,
   │                     │   DetailPanel, annotations, dialogs, SSH source,
   │                     │   parser loader, settings, themes
   │                     ▼
   │               clouseau-plugin   ClouseauPluginManager (PF4J wrapper)
   │                     │
   ▼                     │
clouseau-core ◀──────────┘   RegexLogParser, ParserList, LineParser, LogReaders,
                             LogFileReader, FileFollower, FileTail, FileChangeWatcher,
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
  how to get them: a server-side `tail -f`, or a streaming API. `open` runs on a
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
| **Tail worker** (`SwingWorker`, one per tab) | Wait for the file to change, then read new lines while following. |
| **Filter executor** (`clouseau-filter`, one per tab) | Re-run the active filter over all entries; a generation counter discards stale results. |
| **Source worker** (`SwingWorker`, one per live-source tab) | Run `LogSource.open` (SSH or a plugin source), which blocks while it pushes lines; parse and `publish` them. |

**Rules:**

1. Only the EDT touches Swing components or mutates `LogTableModel`.
2. Background work hands results to the EDT through `SwingWorker.process` or
   `SwingUtilities.invokeLater`.
3. Anything that can take longer than a few milliseconds (I/O, parsing, filtering a large
   set) runs off the EDT and must be cancellable. Opening a new file cancels the tab's
   previous load and tail workers.

### 6.2 Opening a file (UC-1, UC-2)

0. **Zips:** `MainFrame` lists a `.zip`'s files with `ZipLogs` (core; names read as
   UTF-8, else CP437). One file opens directly; for several, `ZipPickerDialog` lets the
   user tick files (filter box, Select all), and each opens in its own tab as
   `load(zip, entry, …)`. The tab keys its annotations to `ZipLogs.entryKey`,
   `<zip>!<entry>` next to the zip.
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

- **Local files:** The tail worker runs `FileFollower` (core), which waits on a `FileChangeWatcher` (core). It uses the
  OS change notifications (`WatchService`) for the file's folder. When the file is written,
  created or replaced, it reads the new bytes from the last position with `FileTail`
  (core). If no notification comes within 2 s it reads anyway, as a backstop for file
  systems that don't send them (some network drives); if the folder can't be watched at
  all, that 2 s read is all there is. Stopping follow interrupts the wait.
- Like the initial load, the tail decodes UTF-8 with malformed input replaced, and skips
  a BOM when reading from offset 0. It only consumes complete lines: a line still being
  written is held back until its newline arrives, so it never becomes two entries. If
  the file shrinks, Clouseau treats it as rotated and reads from the start; existing rows
  are kept. Compressed files are never followed.
- **SSH:** `SshLogSource` runs `tail -n <N> -f -- '<path>'` on the server over sshj and
  receives lines as they're written. Nothing on the client polls. The path is
  single-quoted for a POSIX shell, so spaces and shell metacharacters are taken
  literally; a leading `~/` is left unquoted so it still expands to the remote home.
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
- **Memory bound:** Live entries (tail, SSH and plugin sources) go through `LogTableModel.appendLive`,
  which keeps the tab at most at its cap and evicts the oldest entries, along with their
  highlights. The cap is `max.entries.per.tab` (default 200,000, range
  10,000–2,000,000), or the number of entries the static load added if that was larger,
  so following a big file doesn't cut it to the limit all at once. `clear()` resets the
  cap and the trimmed count. On an indexed tab, only the followed entries (after the
  index) are counted and trimmed, to `max.entries.per.tab`; the dropped ones leave a gap
  in the ordinals between the index and the list. This lasts until D-16 step 5 indexes
  followed lines.

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

`SingleInstanceManager` binds a localhost socket. If another Clouseau is already running,
a new launch forwards its file arguments to it and exits, so double-clicking several files
opens tabs in one window.

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

## 8. Packaging

Clouseau builds with Gradle 8.8 (Kotlin DSL) on Java 21 (see D-12). The `org.beryx.runtime` plugin
produces a jpackage image with a bundled JRE, published as a **portable zip**. The default
heap is `-Xmx2g`. Icons and the splash image are pre-rendered at build time, so the
runtime needs no SVG library.

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
model, such as a real server-side `tail -f` over sshj for SSH. This avoids polling and the
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
never drop lines (UC-1). Static loads are never trimmed; live entries are, down to the
larger of the limit and the static load's size (section 6.3). A file too big for the
heap stops loading with a warning (section 6.2) until D-8 removes that ceiling.

### D-8 · Static files are indexed, not held in memory · Accepted (direction)

To have no size ceiling, a static file is read once to build a compact index (byte offset,
timestamp, level, and logger per entry). Text is read from disk on demand for visible
rows, the detail panel, and text filters. Compressed files are first decompressed to
a temporary file in the data folder. Live sessions keep the current in-memory model with
its limit. Until this exists, a file that can't be fully loaded must produce a clear
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

### D-10 · Review bundle · Accepted

`File → Export for review…` (UC-8) writes one `.zip` containing:

```
manifest.json          format version, Clouseau version, original file name, export date
<original file name>   the log, byte-for-byte (still .gz if it was)
annotations.json       the annotations and highlights, same format as the sidecar
```

When opening a `.zip`, Clouseau checks for `manifest.json`. If it's there, the file is a
bundle: it opens the log and loads its annotations. Otherwise it's an ordinary compressed
log (UC-2). The format is an interchange format (section 7, rule 3).

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

### D-13 · A tab shows one or more sources · Accepted

To support merged views (UC-12), a tab holds a list of **sources** instead of one file.
Each source has its own file (or zip entry), parser, source timezone, time offset, tail
worker, and `AnnotationStore`. A single-file tab is just a tab with one source.

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

### D-16 · How static files are indexed · Accepted

The design for D-8, written to close G-5. Accepted on 2026-11-04 with the recommended
answer to each question at the end. Built so far: steps 1 to 4. Every static file is
indexed, compressed ones through a decompressed copy.

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
  `length` bytes at `offset` (one `FileChannel` per tab), UTF-8 decoding with malformed
  input replaced, then `LineParser` on the line. A cache of the last 10,000 entries
  covers scrolling, the detail panel and the renderers, which ask for the same rows many
  times; it is used on the EDT only. `scan` reads a range of rows in order, in 1 MB
  sequential reads, for the filter executor. It opens its own channel each time,
  because the executor is cancelled by interrupts, and interrupting a thread inside
  `FileChannel.read` closes that channel.
- **`LogTableModel`** (ui) holds an optional `IndexedEntries` in front of its in-memory
  list: ordinals below the indexed count are read through it, and entries that arrive
  later (following) go in the list after them. An indexed tab is never trimmed. Rows
  stay a sorted list of ordinals, and an ordinal is still the entry's position in the
  file. (The design first called for an `EntryStore` interface with two
  implementations; one optional prefix turned out simpler, with the same effect.)
- **Threads.** The load worker appends to the `EntryIndex` and publishes each entry; the
  model, the filter executor and Find only read rows they have been handed through
  `publish`. Columns are published safely (each column's chunk list is a volatile field
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

**Following.** The tail worker already knows the byte position of every line it reads
(`FileFollower`), so appended lines extend the index the same way the first pass does.
They are never trimmed: the D-7 entry limit was there to protect the heap, and with an
index an entry costs too little for that to matter.

**Rotation.** Index rows point into the file, so what happens to older rows depends on
how the file was rotated:

- **Renamed** (logrotate's default, log4j/logback rolling): the tab keeps its open
  channel on the old file, so older rows stay readable. On Windows this needs the channel
  opened with delete sharing, or the writer can't rename the file. A channel from
  `Files.newByteChannel` shares delete by default; `RandomAccessFile` doesn't, so it
  must not be used. The new file is followed as new rows, as it is today. `scan`
  currently opens the file again by name, which after a rename is the new file, so
  step 5 must give it the old one (for example by keeping a second open channel that
  only the filter executor uses, and reopening it if an interrupt closes it).
- **Truncated in place** (copytruncate): the old text is gone. Older rows keep what the
  index holds (time, level, logger, thread). The message reads "Text no longer in the file
  (rotated)", and the status bar says the file was rotated.

**Size ceiling after this.** About 29 bytes of heap per entry plus dictionaries: about
70M entries in the default 2 GB heap, roughly 10 to 15 GB of typical log. Open question 1
removes that ceiling too.

**Order of work** (each step ships and is tested on its own):

1. ✅ `LineScanner` and `EntryIndex`, with tests against the current reader on the sample
   logs (same entries, same entry numbers).
2. ✅ `IndexedEntries` behind `LogTableModel`, at first behind a system property.
3. ✅ The two-part filter, and the text scan with progress (Find included). Plain static
   files are indexed by default; the property is gone. Until step 5, followed lines on an
   indexed tab are held in memory and trimmed to the entry limit (section 6.3).
4. ✅ Compressed files through `cache/`.
5. Follow and rotation on the index.
6. Update UC-1, close G-5, mark D-8 and D-16 Adopted.

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

## 10. Planned features

Accepted in the use cases, with no design decision needed beyond the above:

- **Drag-and-drop:** A `TransferHandler` on the main window that opens dropped files
  through the same path as `File → Open`.
- **File associations:** Register `.log` (and optionally `.gz`/`.zip`) with the
  jpackage-based packages, so "Open with" and double-click work without manual setup.
  The single-instance manager already routes the file to the running window.
- **Plain-text view (UC-13):** When no parser recognizes any of the first 50 lines (the
  same sample the open dialog's preview uses), `LogPanel` shows a read-only text card
  instead of the table, reading through the same `LogReaders` path and following with
  `FileTail`. "Create a parser for this file" opens `ParserEditorDialog` with those lines
  as samples; saving reloads the tab with the new parser. Large files need the same
  on-demand reading as D-8 rather than loading the whole text into one component.

## 11. Known gaps

Places where the code differs from this design. Together they're the technical roadmap.
Remove an entry once it's fixed.

| ID | Gap | Impact |
|---|---|---|
| **G-5** | Static files are indexed (D-16, steps 1 to 4 of 6 done), compressed ones through a decompressed copy in `cache/`. Followed lines on an indexed tab are still held in memory and trimmed to the entry limit until step 5. | A followed file keeps only the newest followed lines (200,000 by default) after what was in the file at load. Any static file, compressed or not, reaches the index's own ceiling (about 70M entries in the default 2 GB heap); a compressed one also needs its unpacked size free on the data folder's disk. |
| **G-9** | Reading, parsing, following and annotation anchoring now live in `clouseau-core` (`LogReaders`, `LineParser`, `LogFileReader`, `FileFollower`, `AnnotationStore`) and the detail pane in `DetailPanel`, but `LogPanel` is still about 1,650 lines of UI: table and renderers, context menus, find bar, highlight navigation and status bar. | Still long to review. Next splits: the find bar and the highlight navigation bar into their own classes. |
