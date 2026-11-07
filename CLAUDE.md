# Clouseau Log Viewer

A modern, pluggable desktop log viewer for software engineers — a clean-slate redesign of
OtrosLogViewer.

## Read these first

- **[docs/USE_CASES.md](docs/USE_CASES.md)** — what Clouseau is for: personas, use cases
  (UC-1…) with their support status, quality goals, and non-goals.
- **[docs/DESIGN.md](docs/DESIGN.md)** — how it works: modules, data model, extension
  points, threading, persistence, decisions (D-1…), and known gaps (G-1…). The gaps list
  is the technical roadmap.

**Keep them current.** A change that adds or alters user-visible behavior updates the
relevant use case (including its ✅/🟡/❌ status and "Current behavior" note). A change to
a module boundary, extension point, threading rule, file format, or decision updates
DESIGN.md. Fixing a gap removes or narrows its G-entry. Do this in the same commit or PR
as the code.

**Keep the GitHub Pages site current too.** The site (https://benlazaro.github.io/clouseau/)
is built by GitHub Pages (Jekyll, Just the Docs theme, from the root of `main`) on every
push to `main`; `_config.yml` sets it up. Its home page is `README.md` and its user guide is
`guide/*.md`, one page per topic (`guide/developers.md` is for plugin authors and
contributors). Everything else, including `docs/*.md` and this file, is excluded from the
site. A new feature, or a change to how one works, updates the guide page that covers it
(and the README's Features list if it's a headline feature) in the same commit, written
for users rather than developers. Screenshots live in `docs/screenshots/`; guide pages link
them as `../docs/screenshots/<name>.png` so they work on the site and on GitHub. Help →
Documentation opens the guide.

## Modules

```
clouseau-api      ← plugin contract: LogEntry, LogParser, LogFormatter, LogSyntaxHighlighter,
                    LogSource, ConnectingLogSource, LogSourceProvider, SourceSetting. No Swing, no implementation.
clouseau-core     ← UI-independent engine: RegexLogParser, LineParser, LogReaders, LogFileReader, FileIndexer, IndexedFollow,
                    FileFollower, FileTail, FileChangeWatcher, Utf8Bom,
                    MemoryGuard, ParserList, SshLogSource, SshConfig, SourceSettings,
                    AnnotationStore, Notes, MergedNotes, ZipLogs, LineScanner, EntryIndex, IndexedEntries,
                    EntryFilter, CombinedFilter, LogCache, CopyingInputStream, ReviewBundle,
                    LogSniffer, TextLines, MergeOrder, EntryRows, MergedEntries, MergeLoad,
                    MergedFollow, TimeOffset, FilePattern, Session, SessionStore, SourceConnection
clouseau-plugin   ← ClouseauPluginManager (PF4J wrapper); Java package ...clouseau.runtime
clouseau-ui       ← the Swing application
clouseau-example-plugin ← example third-party plugin (logfmt LogParser, demo LogSourceProvider); not shipped
```

**Dependency rules (enforce these):**
- `clouseau-api` has no internal dependencies (PF4J `compileOnly` only, for `ExtensionPoint`).
- `clouseau-core` depends only on `clouseau-api`.
- `clouseau-plugin` depends only on `clouseau-core`.
- `clouseau-ui` may depend on `clouseau-core` and `clouseau-plugin`.
- Third-party plugins depend only on `clouseau-api`, as `compileOnly`. `clouseau-example-plugin`
  follows the same rule; only `clouseau-plugin`'s tests use its JAR (as a file, never on the classpath).
- Anything that doesn't need Swing belongs in `clouseau-core`, not `clouseau-ui`.

There is no built-in parser plugin module: built-in parsers are regex definitions in
`clouseau-ui/src/main/resources/.../default-parsers.json`, loaded by `UserParsersLoader`
(user parsers in `<data>/parsers.json` override them by name).

## Tech stack

| Concern | Library |
|---|---|
| Look & feel | FlatLaf 3.4.1 (built-in Dark and Light themes, plus user `.properties` themes) |
| Layout | MigLayout 11.3 |
| Detail panel text | RSyntaxTextArea 3.4.0 |
| Plugins | PF4J 3.12.0 |
| SSH | sshj 0.38.0 |
| JSON | Gson 2.11 |
| Logging | SLF4J via Lombok `@Slf4j`, backed by Log4j 2 |
| Build | Gradle 8.8 (Kotlin DSL), Java 21 toolchain |
| Test | JUnit 5 + AssertJ + Mockito |

## Building and running

Gradle 8.8 can't run on JDK 25, so point `JAVA_HOME` at a JDK 17 or 21 (Temurin 21 is
the project's JDK) before running `./gradlew`.

```bash
./gradlew test                                   # all tests
./gradlew :clouseau-ui:run --args="C:/path/to/file.log"   # run from source; use forward slashes
./gradlew :clouseau-ui:shadowJar                 # fat jar (the "edge" build)
./gradlew :clouseau-ui:distZipImage -PappVersion=0.1.1    # release zip with bundled JRE
```

- `gradle.properties` turns on parallel builds, the build cache and the configuration
  cache. Build logic must not touch `project` while a task runs (read values at
  configuration time instead). The release tasks (`jre`, `jpackageImage`, `jpackage`,
  `distZipImage`) are marked incompatible because the `org.beryx.runtime` plugin isn't, so
  release builds skip the configuration cache.
- `distZipImage` and `jpackage` are skipped for `-SNAPSHOT` versions, so pass a release
  version to build them locally.
- Only one Clouseau runs at a time (localhost port 54823). A second launch forwards its
  files to the running instance and exits, so close any running Clouseau before testing a
  new build.
- Running from source stores data in `./data/` under the working directory; see
  `AppDirs` for the release locations.
- `samples/sample.log` and `samples/gen_log.ps1` provide test logs.

CI (`.github/workflows`): **Edge** runs tests and publishes `clouseau-edge.jar` on every
push to `main`; **Release** runs tests and publishes a portable zip per platform on `v*`
tags.

## Key classes

- `LogEntry` (api) — immutable record: timestamp, level, logger, thread, message, rawLine,
  fields. Lines no parser accepts become `UNKNOWN` entries (continuation lines).
- `LogParser` / `LogFormatter` / `LogSyntaxHighlighter` (api) — PF4J extension points.
- `LogSource` (api) — pushes live lines; `ConnectingLogSource` (api) also reports when it is connected (D-20); `LogSourceProvider` (extension point) creates one
  from declared `SourceSetting`s, shown under File → Open Source by `SourceSettingsDialog`.
- `RegexLogParser` (core) — the parser behind every JSON-defined format.
- `FileTail` (core) — reads complete UTF-8 lines appended to a file, for follow mode.
- `FileChangeWatcher` (core) — wakes follow mode when the OS reports the file changed.
- `LogReaders` (core) — opens a plain, `.gz` or `.zip` log as UTF-8 text.
- `LineParser` (core) — one line → `LogEntry` (chosen parser or per-line auto-detection, source timezone).
- `LogFileReader` (core) — reads a static log, stopping early when memory runs low.
- `FileFollower` (core) — follow loop for a local file: reads appends, handles rotation.
- `FileIndexer`, `IndexedFollow` (core) — G-11: index one file (unpacking and anchoring notes) and
  follow one file into its index; single-file tabs and every file of a merged view use both.
- `LineScanner`, `EntryIndex`, `IndexedEntries` (core) — D-16: byte-offset line splitting, the compact
  per-entry index, and reading entries back from disk. Every static file is indexed.
- `LogCache`, `CopyingInputStream` (core) — a `.gz` or zip entry is unpacked to
  `<data>/cache/` while it's indexed, and rows are read from that copy; deleted with the tab.
- `EntryFilter`, `CombinedFilter` (core) — a filter that can also be decided from index
  columns; `CombinedFilter` is the filter bar's (levels, loggers, fields, time, text).
- `ClouseauPluginManager` (plugin) — `loadAll()` at startup, `refresh()` to hot-reload.
- `WindowManager` (ui) — UC-16, D-18: every window, the one used last, and what they share
  (parser/formatter/highlighter/source lists, "one tab per file" across windows).
- `MainFrame` (ui) — one window: its menus and tabs, and moving tabs between windows.
- `LogPanel` (ui) — one tab: wires the load/follow workers, table, filters, menus, find bar, annotations.
- `LogTableView`, `RowMenu` (ui) — G-9: a tab's table (renderer, header menu, column sizing,
  visibility and saved layouts) and its rows' right-click menu (highlights, notes, merged-file items).
  `MergedFileSettings` (ui) holds a merged tab's per-file offsets, timezones and parsers, with their dialogs.
- `DetailPanel` (ui) — the selected entry's fields and formatted message, under the table.
- `FindBar`, `HighlightNavBar` (ui) — a tab's find bar (Ctrl+F, searched in the background) and the bar
  that steps through highlighted rows.
- `LogSniffer`, `TextLines` (core), `PlainTextView` (ui) — UC-13: a file no parser recognizes
  opens as plain text. `LogSniffer` samples the first lines (parser match, binary check),
  `TextLines` keeps every line's offset and reads text back from disk, `PlainTextView` shows it.
- `MergeOrder`, `EntryRows`, `MergedEntries`, `MergeLoad` (core) — UC-12, D-17: several files
  in one timeline. `MergeOrder` is the merged order, `EntryRows` what the table model reads
  (one `IndexedEntries` or a `MergedEntries`), `MergeLoad` checks, indexes and merges the
  files in the background; `LogPanel.loadMerged` shows them. `MergedFollow` (core) and
  `MergedTailer` (ui) follow its files: hold-back, late rows, re-merge (D-17 step 5).
  `TimeOffsetDialog` (ui) and `TimeOffset` (core) correct a file's clock (step 6).
- `LogTableModel` (ui) — entry store for a tab: filtering on a background executor, row
  highlights, and the per-tab entry limit.
- `AnnotationStore` (core) — annotations for one file, anchored by entry number and content
  hash (D-9); saved next to the log, or in `<data>/annotations/` if that fails.
- `Notes`, `MergedNotes` (core) — the notes interface a tab uses; `MergedNotes` keeps a
  merged view's notes in each file's own `AnnotationStore` (D-17 step 4).
- `ReviewBundle` (core) — D-10: writes and recognizes the review bundle `.zip` (manifest, log,
  annotations; version 2 holds every file of a merged view, D-17); `ReviewExporter` (ui) is
  `File → Export for Review…`. `FilePattern` (core) expands `app.log*` for Open Merged.
- `Session`, `SessionStore` (core), `SessionManager`, `ManageSessionsDialog` (ui) — UC-17, D-19: the session model, reading
  and writing `session.json` (atomic, `.bad` if damaged) and named sessions, and the autosave
  (debounced, compared before writing). `WindowManager.restore` reopens it at startup;
  `WindowManager.openNamedSession` opens a named session (File → Sessions).
- `FilterBar`, `ParserEditorDialog`, `ThemeManager`, `AppPrefs`, `AppDirs` (ui) — filters,
  parser editor, themes, settings, data folder.

## Coding conventions

- Java records for immutable data; event types are records nested in their owning class.
- EDT discipline: only touch Swing components on the Event Dispatch Thread. Run I/O,
  parsing and filtering off the EDT, cancellably, and hand results back with
  `SwingWorker.process` or `SwingUtilities.invokeLater()`.
- Logging: annotate classes with Lombok `@Slf4j` and use `log`. No `System.out`.
- User-visible strings go in `messages.properties` and are read with `Messages.get(...)`.
- Color long-lived components (anything in a tab or the main window) with
  `ClouseauColors.themedForeground`/`themedBackground`, not `setForeground`, or the color
  is lost on a live theme switch (DESIGN.md 6.8.1).
- New persistent files go in the data folder (`AppDirs.dataDir()`); credentials only
  through `CredentialStore`.
- Read text files as UTF-8 with malformed input replaced and a leading BOM skipped
  (see `LogReaders`, `FileTail` and `Utf8Bom`).

## Parallel sessions

While the project has a single developer: when a request splits into **3 or more**
independent pieces (none needs another's result, none touches the same files, none has to
land in the same change), start each in its own worktree session without asking first.
With fewer than 3 pieces, work here or propose sessions as usual.

- Ask before merging those sessions' branches into this checkout, and never push them.
- Only one Clouseau runs at a time (port 54823), so parallel sessions must not launch the
  GUI. Do GUI checks in the main session after their branches are merged.
