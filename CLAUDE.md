# Clouseau Log Viewer

A modern, pluggable log viewer for software engineers — a clean-slate redesign of OtrosLogViewer.

## Architecture

Five Gradle modules with strict dependency rules:

```
clouseau-api        ← plugin contract (interfaces only, no Swing, no internals)
clouseau-core       ← engine: LogEntry, LogIndex, ClouseauEventBus
plugin-runtime      ← PF4J plugin loader, lifecycle, hot-reload
clouseau-ui         ← Swing MVP UI (FlatLaf, MigLayout, RSyntaxTextArea)
plugins/
  builtin-parsers   ← ships with app; depends only on clouseau-api
```

**Dependency rules (enforce these):**
- `clouseau-api` has zero internal dependencies
- `clouseau-core` may only import `clouseau-api`
- `plugin-runtime` may only import `clouseau-core`
- `clouseau-ui` may import `clouseau-core` and `plugin-runtime`
- Plugins may only import `clouseau-api` (compileOnly)

## Tech Stack

| Concern | Library |
|---|---|
| Look & feel | FlatLaf 3.4.1 + One Dark theme |
| Layout manager | MigLayout 11.3 |
| Log text rendering | RSyntaxTextArea 3.4.0 |
| Event bus | Guava 33 AsyncEventBus |
| Plugin system | PF4J 3.12.0 |
| Build | Gradle 8.8 (Kotlin DSL), Java 21 |
| Test | JUnit 5 + AssertJ + Mockito |

## Key Classes

- `LogEntry` (api) — immutable record: timestamp, level, logger, thread, message, rawLine, fields
- `LogParser` (api) — implement to support a new log format; annotate with `@Extension`
- `LogFilter` (api) — implement to add custom filter types
- `LogSource` (api) — implement to add new log sources (file tail, socket, k8s, etc.)
- `LogIndex` (core) — thread-safe entry store; fires `LogEntryAddedEvent` / `LogBatchAddedEvent`
- `ClouseauEventBus` (core) — SYNC bus for UI events, ASYNC bus for log ingestion
- `ClouseauPluginManager` (runtime) — wraps PF4J; call `loadAll()` on startup, `refresh()` for hot-reload
- `MainFrame` (ui) — top-level window: toolbar + log table + detail panel via MigLayout

## What's Built

- [x] All Gradle module structure and build files
- [x] Plugin API interfaces (LogParser, LogFilter, LogSource, LogEntry)
- [x] LogIndex with thread-safe storage and event firing
- [x] ClouseauEventBus (sync + async)
- [x] ClouseauPluginManager (PF4J wrapper)
- [x] MainFrame skeleton (toolbar, table, detail panel)
- [x] ClouseauApp entry point with FlatLaf bootstrap
- [x] BuiltinParsersPlugin entry point

## What Needs Building Next (priority order)

1. **LogTableModel** — `AbstractTableModel` subscribed to `LogIndex` events; updates table on EDT
2. **Log4jPatternParser** — first real `LogParser` implementation in `builtin-parsers`
3. **JsonLogParser** — second parser for structured JSON logs
4. **FileTailSource** — `LogSource` that tails a file (like `tail -f`)
5. **Wire startup** — connect `ClouseauPluginManager` → parsers → `LogIndex` → table in `MainFrame`
6. **LevelFilter** — basic `LogFilter` for filtering by log level
7. **RSyntaxTextArea detail panel** — replace the plain JTextArea with syntax-highlighted viewer

## Coding Conventions

- Java records for immutable data classes
- All event types are records nested inside their owning class
- EDT discipline: only touch Swing components on the Event Dispatch Thread
- Use `SwingUtilities.invokeLater()` when posting results from async event handlers back to UI
- No `System.out` — use `java.util.logging` for now (can swap later)
