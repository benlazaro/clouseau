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

## Modules

```
clouseau-api      ← plugin contract: LogEntry, LogParser, LogFormatter, LogSyntaxHighlighter,
                    LogSource, LogFilter. No Swing, no implementation.
clouseau-core     ← UI-independent engine: RegexLogParser, FileLogSource, FileTail, Utf8Bom, MemoryGuard, LogIndex,
                    ClouseauEventBus
clouseau-plugin   ← ClouseauPluginManager (PF4J wrapper); Java package ...clouseau.runtime
clouseau-ui       ← the Swing application
```

**Dependency rules (enforce these):**
- `clouseau-api` has no internal dependencies (PF4J `compileOnly` only, for `ExtensionPoint`).
- `clouseau-core` depends only on `clouseau-api`.
- `clouseau-plugin` depends only on `clouseau-core`.
- `clouseau-ui` may depend on `clouseau-core` and `clouseau-plugin`.
- Third-party plugins depend only on `clouseau-api`, as `compileOnly`.
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
- `LogSource` / `LogFilter` (api) — not discoverable from plugins yet (G-3).
- `RegexLogParser` (core) — the parser behind every JSON-defined format.
- `FileTail` (core) — reads complete UTF-8 lines appended to a file, for follow mode.
- `ClouseauPluginManager` (plugin) — `loadAll()` at startup, `refresh()` to hot-reload.
- `MainFrame` (ui) — window, menus, tabs; holds the parser/formatter/highlighter lists.
- `LogPanel` (ui) — one tab: loading, tailing, table, filters, detail panel, annotations.
- `LogTableModel` (ui) — entry store for a tab: filtering on a background executor, row
  highlights, and the per-tab entry limit.
- `FilterBar`, `AnnotationStore`, `ParserEditorDialog`, `ThemeManager`, `AppPrefs`,
  `AppDirs` (ui) — filters, annotation sidecars, parser editor, themes, settings, data folder.

`LogIndex` and `ClouseauEventBus` exist but are effectively unused (G-1); don't build on
them without resolving that gap.

## Coding conventions

- Java records for immutable data; event types are records nested in their owning class.
- EDT discipline: only touch Swing components on the Event Dispatch Thread. Run I/O,
  parsing and filtering off the EDT, cancellably, and hand results back with
  `SwingWorker.process` or `SwingUtilities.invokeLater()`.
- Logging: annotate classes with Lombok `@Slf4j` and use `log`. No `System.out`.
- User-visible strings go in `messages.properties` and are read with `Messages.get(...)`.
- New persistent files go in the data folder (`AppDirs.dataDir()`); credentials only
  through `CredentialStore`.
- Read text files as UTF-8 with malformed input replaced and a leading BOM skipped
  (see `LogPanel.openReader`, `FileTail` and `Utf8Bom`).
