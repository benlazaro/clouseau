# Clouseau Log Viewer

A modern, pluggable desktop log viewer for software engineers — a clean-slate redesign of
OtrosLogViewer.

## Read these first

- **[docs/USE_CASES.md](docs/USE_CASES.md)** — what Clouseau is for: personas, use cases
  (UC-1…) with their support status, quality goals, and non-goals.
- **[docs/DESIGN.md](docs/DESIGN.md)** — how it works: modules, data model, extension
  points, threading, persistence, decisions (D-1…), and known gaps (G-1…). The gaps list
  is the technical roadmap.
- **[Clouseau design system](https://claude.ai/artifact/8nUx714s2Jk1WsZHWgFJED)** — the visual reference: colors
  and type for Dark and Light, and the rules for dialogs, bars, the log table and the detail
  pane, with previews. It's a claude.ai artifact, so only sessions with access to Ben's
  artifacts can read it; the theme files and DESIGN.md hold the same rules. Read it before
  changing how something looks, and keep it in sync when you do.

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
                    LogSource, ConnectingLogSource, LogSourceProvider, SourceSetting, ClouseauApi. No Swing, no implementation.
clouseau-core     ← UI-independent engine: RegexLogParser, LineParser, LogReaders, LogFileReader, FileIndexer, IndexedFollow,
                    FileFollower, FileTail, FileChangeWatcher, Utf8Bom,
                    MemoryGuard, ParserList, SshLogSource, SshRemote, SshConfig, SourceSettings,
                    AnnotationStore, Notes, MergedNotes, ZipLogs, LineScanner, EntryIndex, IndexedEntries,
                    EntryFilter, CombinedFilter, LogCache, CopyingInputStream, ReviewBundle,
                    LogSniffer, TextLines, MergeOrder, EntryRows, MergedEntries, MergeLoad,
                    MergedFollow, TimeOffset, FilePattern, Session, SessionStore, SourceConnection,
                    UpdateCheck, SshSaved, SyntaxLayers, TextMatches
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
  follows the same rule, using the released API from Maven Central (its `clouseauApi` version),
  not `project(":clouseau-api")`, so it can use a new API only once that's released. Only
  `clouseau-plugin`'s tests use its JAR (as a file, never on the classpath), which checks that a
  plugin built for the released API still loads.
- Anything that doesn't need Swing belongs in `clouseau-core`, not `clouseau-ui`.

There is no built-in parser plugin module: built-in parsers are regex definitions in
`clouseau-ui/src/main/resources/.../default-parsers.json`, loaded by `UserParsersLoader`
(user parsers in `<data>/parsers.json` override them by name).

## Tech stack

| Concern | Library |
|---|---|
| Look & feel | FlatLaf 3.4.1 (built-in Dark and Light themes, plus user `.properties` themes) |
| Fonts | Inter 4.1 (interface) and JetBrains Mono 2.304 (log text, code), bundled via FlatLaf's font modules; see `ClouseauFonts` |
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
- Only one Clouseau runs at a time (localhost port 41823). A second launch of the same
  version forwards its files to the running instance and exits; a different version (a
  new build, or `-SNAPSHOT` vs a release) says which version is running and exits. Close
  any running Clouseau before testing a new build.
- Running from source stores data in `./data/` under the working directory; see
  `AppDirs` for the release locations.
- `samples/sample.log` and `samples/gen_log.ps1` provide test logs.

CI (`.github/workflows`) runs `./gradlew check` (the tests, the third-party notices and plugin API
compatibility) before anything else: **Edge** then publishes `clouseau-edge.jar` on every
push to `main`; **Release** publishes a portable zip per platform on `v*`
tags. Write a release's notes for users in `docs/releases/<tag>.md` (first line
`Title: Clouseau <version> — <headline>`, then Markdown) and commit them before tagging: the
Release workflow publishes them as the release's title and notes, or GitHub's list of commits
when there's no file.

**API Release** publishes the plugin API (`com.droidenx.clouseau:clouseau-api`) on its own, on
`api-v*` tags, with its own version: `ClouseauApi.VERSION`, which `clouseau-api/build.gradle.kts`
reads, so bump only that constant. Release it only when the API changes: tag the commit
`api-v<VERSION>` (the workflow fails if they differ), with notes for plugin authors in
`docs/releases/api-v<VERSION>.md`, same format. It runs the tests, builds the jar, sources, Javadoc
and POM (signed with the `SIGNING_KEY`/`SIGNING_PASSWORD` secrets), uploads them to Maven Central
(namespace `com.droidenx`, Portal user token in `CENTRAL_USERNAME`/`CENTRAL_PASSWORD`) and waits
for Central's checks, then attaches them to a GitHub release that is never marked Latest (a pre-release while the API
is 0.x, or GitHub shows it as Latest anyway, since every app release is a pre-release); the
update check ignores `api-v*` tags. The Central deployment is `USER_MANAGED`: after the run, click
**Publish** under Publish → Deployments at central.sonatype.com (a version on Central can never be
replaced or removed). The signing key is RSA 4096 `7541ACF1F02F4686` (`gpg@droidenx.com`, on
keys.openpgp.org). `./gradlew :clouseau-api:publishToMavenLocal` puts the API in `~/.m2` for local
plugin builds.

`checkApiCompatibility` (part of `check`) fails if the API would break plugins built against the
last release on Maven Central, `apiBaseline` in `clouseau-api/build.gradle.kts`; the report is in
`clouseau-api/build/reports/japicmp/`. Once a new API version is published (and on Maven Central,
which can take half an hour after clicking Publish), set `apiBaseline` to it and move
`clouseau-example-plugin`'s `clouseauApi` to it. For a deliberate
break, bump the minor version while 0.x (the major after 1.0) and set `allowBreakingChanges` for that
release only.

## Key classes

- `LogEntry` (api) — immutable final class (not a record, so it can grow; build with `LogEntry.builder`):
  timestamp, level, logger, thread, message, rawLine, fields. Lines no parser accepts become `UNKNOWN`
  entries (continuation lines). API data types are never records, and interfaces grow only by default
  methods (DESIGN.md 5, forward compatibility).
- `LogParser` / `LogFormatter` / `LogSyntaxHighlighter` (api) — PF4J extension points. Core calls
  `LogParser.parse(rawLine, writtenIn)`, so the parser reads zoneless times in the log's timezone. A highlighter
  returns spans with a `Role`, never a color; the theme's `Clouseau.syntax.<role>` keys color them, and
  `SyntaxLayers` (core) layers every highlighter that applies (D-26).
- `ClouseauApi` (api) — `VERSION`, the plugin API's own version, which PF4J checks `Plugin-Requires`
  against. Bump it with any API change (minor while 0.x if it breaks plugins), and note it in its javadoc.
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
  `TabDragHandler` (ui) drags a tab IntelliJ-style (empty slot, midpoint swaps, Esc cancels).
- `TabPanel` (ui) — one tab (D-21): lays out its parts, keeps the window's methods, and swaps what it shows.
  `SharedTabContext` (ui) owns what every tab has, whatever it shows (table, model, bars, detail pane, notes,
  follow mode, timezone, status bar, cards), and does the steps that touch several of them.
- `TabContent` (ui) — D-21: what a tab shows, one object per load, reaching the tab only through its own
  `TabContext`, which is withdrawn when the content is replaced. `FileContent` is one file (load, follow,
  unpacked copy, plain text), `MergedContent` a merged view (its load, copies, notes and followed files),
  `LiveContent` an SSH or plugin source, with its connection state. New per-kind behavior goes in the content,
  not in `TabPanel`.
- `FollowThreads` (ui) — D-22: starts a `SwingWorker` that never ends by itself (follow loops, live
  sources) on its own daemon thread. Never `execute()` one: that pool has 10 threads for the whole JVM.
- `LogTableView`, `RowMenu` (ui) — a tab's table (renderer, header menu, column sizing,
  visibility and saved layouts) and its rows' right-click menu (copy, highlights, time and logger filters, notes, merged-file items;
  its swatch row is shared with `FindBar`'s Highlight popup).
  `LogRowSorter` (ui) sorts by # or Timestamp without reading entries (time order worked out off the EDT).
  `MergedFileSettings` (ui) holds a merged tab's per-file offsets, timezones and parsers, with their dialogs.
  `TabStatusBar` (ui) is a tab's path, timezone badge and row counts; `RestoredTab` (ui) its session message card,
  deferred load and "filters restored" strip (D-19).
- `DetailPanel` (ui) — the selected entry's fields and formatted message, under the table.
  `DetailFindBar` (ui) is its find strip (Ctrl+F with focus in the pane), matching with `TextMatches` (core).
  `Toast` (ui) is the brief centered note (copy, text size, find wraps) over the pane or the table.
  `NoMatchState` (ui) turns a find field red, with one pulse, while its query matches nothing.
- `FindBar`, `HighlightNavBar` (ui) — a tab's find bar (Ctrl+F, searched in the background) and the bar
  that steps through highlighted rows. `BusyIndicator` (ui) is the bar over the rows, and their fade,
  while a filter or find reads the file (DESIGN.md 6.16).
- `DialogFooter`, `NoticeLabel`, `PromptDialog`, `ManagerDialogs` (ui) — the dialog building blocks
  (DESIGN.md 6.12): the footer with its hairline, the "Still needed: …" / result notice line, a small
  modal form (never a form inside a `JOptionPane`), and the managers' shared list, actions and sizes.
- `SshRemote` (core), `SshConnectionDialog`, `RemoteFileDialog` (ui) — the SSH Connections window: Connect
  checks first (sign in and check the file) and Browse… picks the file over SFTP; `SshRemote` also holds the
  sign-in `SshLogSource` uses. `SshSaved` (core) is its saved connections (D-24): sign-ins (user, host, port,
  auth, optional name) with their logs, stored by `AppPrefs` under `ssh.connections`.
- `LogSniffer`, `TextLines` (core), `PlainTextView` (ui) — UC-13: a file no parser recognizes
  opens as plain text. `LogSniffer` samples the first lines (parser match, binary check),
  `TextLines` keeps every line's offset and reads text back from disk, `PlainTextView` shows it.
- `MergeOrder`, `EntryRows`, `MergedEntries`, `MergeLoad` (core) — UC-12, D-17: several files
  in one timeline. `MergeOrder` is the merged order, `EntryRows` what the table model reads
  (one `IndexedEntries` or a `MergedEntries`), `MergeLoad` checks, indexes and merges the
  files in the background; `TabPanel.loadMerged` shows them as a `MergedContent`. `MergedFollow` (core) and
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
  `File → Export for Review…`. `FilePattern` (core) expands `app.log*` typed in the open dialog.
- `Session`, `SessionStore` (core), `SessionManager`, `ManageSessionsDialog` (ui) — UC-17, D-19: the session model, reading
  and writing `session.json` (atomic, `.bad` if damaged) and named sessions, and the autosave
  (debounced, compared before writing). `WindowManager.restore` reopens it at startup;
  `WindowManager.openNamedSession` opens a named session (File → Sessions).
- `UpdateCheck` (core), `UpdateNotifier` (ui) — UC-18, D-23: find a newer release on GitHub's
  releases list, and the daily check, notice and Help → Check for Updates… (`WindowManager.updates()`).
- `CrashHandler` (ui) — the default uncaught-exception handler: logs, then shows "Clouseau ran into a
  problem" with Copy Details, Open Log Folder and Report Issue… (DESIGN.md 6.1, rule 5).
- `LicensesDialog` (ui) — Help → About → Licenses. `legal/THIRD-PARTY-NOTICES.txt` lists every shipped
  library; `checkThirdPartyNotices` (part of `check`) fails if a runtime dependency is missing from it, so
  add an entry with any new dependency. The release image gets it in a `legal` folder (DESIGN.md 8).
- `FilterBar`, `ParserEditorDialog`, `ThemeManager`, `AppPrefs`, `AppDirs` (ui) — filters,
  parser editor, themes, settings, data folder. `FilterBar`'s time range sits behind its
  **Time** button (a From/To popup); `RowMenu` can set either end from a line.
- `ClouseauColors`, `ClouseauFonts` (ui) — the theme's named colors, and the bundled fonts
  (Inter for the interface, JetBrains Mono for log text and code). Use `ClouseauFonts.mono`
  for code-like text, never the logical `Monospaced` font, and never `new Font(MONO_FAMILY, …)`:
  it has no fallback fonts, and through Java's shared metrics cache it takes the fallbacks
  away from every equal font, the detail pane's included. Theme files are
  `ClouseauDarkLaf.properties` and `ClouseauLightLaf.properties` in `ui/theme`, one per
  `ClouseauDarkLaf` / `ClouseauLightLaf` class: the same keys and design, colors for each.

## Coding conventions

- Java records for immutable data; event types are records nested in their owning class.
- EDT discipline: only touch Swing components on the Event Dispatch Thread. Run I/O,
  parsing and filtering off the EDT, cancellably, and hand results back with
  `SwingWorker.process` or `SwingUtilities.invokeLater()`.
- Logging: annotate classes with Lombok `@Slf4j` and use `log`. No `System.out`.
- User-visible strings go in `messages.properties` and are read with `Messages.get(...)`.
- Color long-lived components (anything in a tab or the main window) with
  `ClouseauColors.themedForeground`/`themedBackground`, not `setForeground`, or the color
  is lost on a live theme switch (DESIGN.md 6.8.1). State that holds theme colors itself
  (a table model's highlights) refreshes in `ClouseauColors.onThemeChange`.
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
- Only one Clouseau runs at a time (port 41823), so parallel sessions must not launch the
  GUI. Do GUI checks in the main session after their branches are merged.
