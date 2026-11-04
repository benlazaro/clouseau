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
   │                     │   detail panel, annotations, dialogs, SSH source,
   │                     │   parser loader, settings, themes
   │                     ▼
   │               clouseau-plugin   ClouseauPluginManager (PF4J wrapper)
   │                     │
   ▼                     │
clouseau-core ◀──────────┘   RegexLogParser, FileLogSource, LogIndex, event bus
   │
   ▼
clouseau-api                 LogEntry, LogParser, LogSource, LogFormatter,
   ▲                         LogSyntaxHighlighter, LogFilter
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
| `LogParser` | Turn a raw line into a `LogEntry` | Declared, but **not used** (see [G-2](#11-known-gaps)) | `RegexLogParser`, configured from JSON |
| `LogFormatter` | Reformat a message for the detail panel (for example, pretty-print JSON) | Yes | `JsonMessageFormatter` |
| `LogSyntaxHighlighter` | Color spans in the detail panel | Yes | `JsonSyntaxHighlighter` |
| `LogSource` | Deliver lines from somewhere | No: not an `ExtensionPoint` (see [G-3](#11-known-gaps)) | `FileLogSource` (unused), `SshLogSource` |
| `LogFilter` | Accept or reject entries | No: not an `ExtensionPoint` | None; filtering uses predicates in the UI |

**Contract notes:**

- `LogParser.canParse` must be cheap: during auto-detection it is called on every line
  for every parser until one accepts.
- `LogParser.customFields()` lists the `fields` keys that should become columns.
- `LogFormatter` and `LogSyntaxHighlighter` work on message text, so one implementation
  works for any log format.
- `LogSource.open(Consumer<String>)` **pushes** lines to the consumer. The source decides
  how to get them: reading to the end of a file, a server-side `tail -f`, or a streaming
  API. This is what lets aggregator sources such as Loki or CloudWatch exist as plugins
  (UC-11).

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

## 6. Runtime behavior

### 6.1 Threading model

| Thread | Work |
|---|---|
| **EDT** | All Swing access. Table model mutations (`appendBatch`, `append`, clearing, highlights). |
| **Load worker** (`SwingWorker`, one per tab) | Read and parse the file; `publish` entries in chunks. |
| **Tail worker** (`SwingWorker`, one per tab) | Read new lines while following. |
| **Filter executor** (`clouseau-filter`, one per tab) | Re-run the active filter over all entries; a generation counter discards stale results. |
| **SSH worker** (`SwingWorker`, one per tab) | Receive lines from the remote `tail -f`. |

**Rules:**

1. Only the EDT touches Swing components or mutates `LogTableModel`.
2. Background work hands results to the EDT through `SwingWorker.process` or
   `SwingUtilities.invokeLater`.
3. Anything that can take longer than a few milliseconds (I/O, parsing, filtering a large
   set) runs off the EDT and must be cancellable. Opening a new file cancels the tab's
   previous load and tail workers.

### 6.2 Opening a file (UC-1, UC-2)

1. `LogPanel.load` clears the tab and starts a load worker.
2. `openReader` picks the decoder from the file name: plain, `.gz` (`GZIPInputStream`),
   or `.zip` (`ZipInputStream`, **first entry only**). Bytes are decoded as UTF-8, with
   malformed input replaced rather than failing.
3. For each non-blank line, the parser is either the one the user picked, or (with
   auto-detection) the first parser whose `canParse` accepts the line. Auto-detection
   happens **per line**, so a file mixing formats still parses.
4. Entries are published in chunks; on the EDT, `LogTableModel.appendBatch` stores them
   and `FilterBar` learns new logger names for the logger tree.
5. When loading finishes, Clouseau sizes columns, applies the default column layout,
   loads annotations, and starts following if follow mode is on.

### 6.3 Following a log (UC-6)

- **Local files:** The tail worker checks the file size every 500 ms and reads new bytes
  from the last position with `FileTail` (core). Like the initial load, it decodes UTF-8
  with malformed input replaced. It only consumes complete lines: a line still being
  written is held back until its newline arrives, so it never becomes two entries. If the
  file shrinks, Clouseau treats it as rotated and reads from the start; existing rows are
  kept. Compressed files are never followed.
- **SSH:** `SshLogSource` runs `tail -n <N> -f <path>` on the server over sshj and
  receives lines as they're written. Nothing on the client polls.
- **Memory bound:** `LogTableModel` keeps at most `max.entries.per.tab` entries (default
  200,000, range 10,000–2,000,000) and evicts the oldest, along with their highlights.

### 6.4 Filtering (UC-4, UC-5)

`FilterBar` combines the per-level toggles, logger tree, time range, and text filters
into one `Predicate<LogEntry>` (`buildPredicate()`). `LogTableModel` re-evaluates it on the filter executor and swaps
in the visible rows on the EDT. New entries arriving during a session are checked against
the active filter as they are appended. Find (`Ctrl+F`) searches the visible rows,
including their continuation lines.

### 6.5 Detail panel (UC-3)

When a row is selected, the detail panel shows the entry and its continuation lines. Each
`LogFormatter` whose `canFormat` accepts the message transforms it, then each matching
`LogSyntaxHighlighter` returns color spans. The detail toolbar can turn individual
formatters and highlighters on or off.

### 6.6 Annotations (UC-7 to UC-9)

`AnnotationStore` holds the annotations for one file, keyed by the first 16 hex
characters of the SHA-256 of the line's `rawLine`. Each annotation records its note,
author (defaulting to the OS user name), and date. The store is saved as JSON to the
sidecar `<logfile>.annotations` after every change and loaded when the file is opened.

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
**Accepted** (agreed, not built yet), or **Superseded**.

### D-1 · Swing with FlatLaf · Adopted

A desktop UI that needs no server and runs anywhere Java does. FlatLaf, MigLayout, and
RSyntaxTextArea give it a modern look. JavaFX and web UIs were rejected: JavaFX adds a
second toolkit to bundle, and a web UI implies a server (a non-goal).

### D-2 · PF4J for plugins · Adopted

PF4J provides plugin isolation, `@Extension` discovery, and runtime reloading. Plugins
compile only against `clouseau-api`.

### D-3 · Sources push lines · Adopted, partly built

`LogSource` hands lines to a consumer instead of being polled through a file abstraction
like the Apache Commons VFS layer in OtrosLogViewer. Each source picks its own streaming
model, such as a real server-side `tail -f` over sshj for SSH. This avoids polling and the
unmaintained JSch dependency. Local follow still polls (see [G-4](#11-known-gaps)).

### D-4 · Parsers as data · Adopted

Regex-based parsers are JSON configuration, not code, so users can add formats without
writing a plugin (section 5.1).

### D-5 · Annotations in a sidecar file · Adopted

Annotations are stored as JSON next to the log, never inside it. The original log is never
modified.

### D-6 · Collaboration through files, not a server · Adopted

Sharing findings means sending files (the log plus annotations). There is no account,
sync, or backend. See the non-goals in the use cases.

### D-7 · The entry limit applies to live sessions only · Accepted

The per-tab entry limit protects long follow sessions (UC-6). Opening a static file must
never drop lines (UC-1). Today the limit applies to both (see [G-5](#11-known-gaps)).

### D-8 · Static files are indexed, not held in memory · Accepted (direction)

To have no size ceiling, a static file is read once to build a compact index (byte offset,
timestamp, level, and logger per entry). Text is read from disk on demand for visible
rows, the detail panel, and text filters. Compressed files would first be decompressed to
a temporary file in the data folder. Live sessions keep the current in-memory model with
its limit. Until this exists, a file that can't be fully loaded must produce a clear
warning (UC-1). The details need a design pass before implementation.

### D-9 · Annotations anchored by line number and content · Accepted

Each annotation stores both its line number and its line's content hash. On load, it
attaches to the line at that number if the hash matches there; otherwise it falls back to
the nearest line with a matching hash. This keeps each annotation on its own line among
identical lines (UC-7). Old sidecars without line numbers keep working through the
fallback.

If the log's folder is not writable, the sidecar goes in `<data>/annotations/`, named
after a hash of the log's absolute path.

### D-10 · Review bundle · Accepted

`File → Export for review…` (UC-8) writes one `.zip` containing:

```
manifest.json          format version, Clouseau version, original file name, export date
<original file name>   the log, byte-for-byte (still .gz if it was)
annotations.json       the annotations, same format as the sidecar
```

When opening a `.zip`, Clouseau checks for `manifest.json`. If it's there, the file is a
bundle: it opens the log and loads its annotations. Otherwise it's an ordinary compressed
log (UC-2). The format is an interchange format (section 7, rule 3).

### D-11 · Aggregator sources are plugins · Accepted

Readers for Loki, CloudWatch, and similar services are welcome, but only as plugins. This
requires `LogSource` to become a discoverable extension point (see
[G-3](#11-known-gaps)).

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

## 10. Planned features

Accepted in the use cases, with no design decision needed beyond the above:

- **Drag-and-drop:** A `TransferHandler` on the main window that opens dropped files
  through the same path as `File → Open`.
- **File associations:** Register `.log` (and optionally `.gz`/`.zip`) with the
  jpackage-based packages, so "Open with" and double-click work without manual setup.
  The single-instance manager already routes the file to the running window.
- **Plain-text view (UC-13):** When no parser recognizes any of the first 50 lines (the
  same sample the open dialog's preview uses), `LogPanel` shows a read-only text card
  instead of the table, reading through the same `openReader` path and following with
  `FileTail`. "Create a parser for this file" opens `ParserEditorDialog` with those lines
  as samples; saving reloads the tab with the new parser. Large files need the same
  on-demand reading as D-8 rather than loading the whole text into one component.
- **Theme menu (UC-14):** A new **View** menu in the main window with a **Theme**
  submenu: one radio item per `ThemeManager.getAllThemes()` entry, then **Manage
  themes…** (`ThemeManagerDialog`). Picking an item calls `ThemeManager.applyTheme` and
  `updateUI`, and saves it with `AppPrefs.setTheme`, the same path the Settings dropdown
  uses, so the two stay in sync. The submenu rebuilds its items each time it opens, so
  newly installed themes appear.

## 11. Known gaps

Places where the code differs from this design. Together they're the technical roadmap.
Remove an entry once it's fixed.

| ID | Gap | Impact |
|---|---|---|
| **G-1** | `LogIndex` and `ClouseauEventBus` are bypassed. `LogPanel` creates a `LogIndex` and clears it but never adds to it; entries go straight to `LogTableModel`. `LogTableModel` still subscribes to the global async bus, so if `LogIndex` ever posted events, **every open tab** would receive every other tab's entries. | Dead code with a hidden cross-tab bug waiting to trigger. Either remove both or make `LogIndex` the per-tab store with per-tab events. |
| **G-2** | Plugin parsers are never used. `MainFrame` gets parsers only from `UserParsersLoader`; it never asks the plugin manager for `LogParser` extensions, though the Plugin Manager lists them. | Breaks UC-11 for parsers, the most important plugin type. |
| **G-3** | `LogSource` and `LogFilter` are not `ExtensionPoint`s, and the UI constructs sources directly. `FileLogSource` (core) is unused; `SshLogSource` lives in `clouseau-ui`. | Plugins can't add sources (blocks D-11). Violates section 3, rule 2. |
| **G-4** | Local follow polls the file size every 500 ms instead of being notified of changes. | Up to half a second of latency and a little wasted work. Contradicts D-3. |
| **G-5** | The entry limit applies to static files, and the default heap is 2 GB. | A large file silently loses its earliest lines. Contradicts D-7 and UC-1. |
| **G-6** | `.zip` files read only their first entry. | A zip containing several logs shows just one, with no indication. The zip picker in UC-12 will close this. |
| **G-7** | `SshLogSource` builds the remote command by concatenating the path unquoted: `"tail ... -f " + remotePath`. | Paths with spaces fail; shell metacharacters in the path are executed by the remote shell. |
| **G-8** | Annotations are keyed by content hash only, and saving fails silently if the log's folder is read-only. | Contradicts D-9 and UC-7. |
| **G-9** | `LogPanel` is about 2,000 lines and mixes UI with reading, parsing, tailing, and annotation logic. | Hard to test and review; much of it belongs in `clouseau-core` (section 3, rule 2). |
| **G-10** | Row highlights live only in memory. | They're lost when the tab closes and can't be shared. Whether they should be persisted or bundled like annotations is undecided. |
| **G-11** | `CLAUDE.md` is out of date: it lists a `builtin-parsers` module and a `plugin-runtime` module that don't exist under those names, says to use `java.util.logging` (the code uses SLF4J with Log4j 2), and its build list is long finished. | Misleads contributors and AI assistants. |
