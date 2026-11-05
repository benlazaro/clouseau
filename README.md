# Clouseau Log Viewer

A log viewer for software engineers, born from a love of OtrosLogViewer and a desire to bring its core functionality into a cleaner, more modern interface. Open logs of any size, merge the logs of several services into one timeline, filter in milliseconds, and hand your annotated findings to a colleague in one file.

![Java 21](https://img.shields.io/badge/Java-21-blue) ![Gradle 8.8](https://img.shields.io/badge/Gradle-8.8-02303A) ![License MIT](https://img.shields.io/badge/License-MIT-green)

---

## Screenshots

**Main view** — level colors, row highlights with the highlight bar for jumping between them, and the detail panel pretty-printing a JSON payload.
![Main view](docs/screenshots/main-view.png)

**Merged view** — three services' logs, in two formats, interleaved into one timeline. The **Source** column shows which file each line came from, and the selected error shows its full stack trace.
![Merged view](docs/screenshots/merged-view.png)

**Logger filter** — a tree of every logger in the file; untick a package or class to hide its lines. The button shows how many loggers are shown.
![Logger filter](docs/screenshots/logger-filter.png)

**Plain text** — a file no parser recognizes still opens, with line numbers, Find and Follow, and a one-click path to a parser for it.
![Plain text](docs/screenshots/plain-text.png)

**Parser editor** — define a log format with a simple template; the generated regex is shown live and can be tested against a real file before saving.
![Parser editor](docs/screenshots/parser-editor.png)

**Open file dialog** — pinned favorites, compressed `.gz` and `.zip` files, and a live parser preview before you open.
![Open file dialog](docs/screenshots/open-dialog.png)

---

## Features

- **Any file size** — every file is indexed on disk rather than loaded into memory, at about 29 bytes of heap per line, so a 1M-line, 125 MB log uses under 50 MB. Lines are never dropped from a file you open.
- **Multi-format parsing** — Log4j pattern and Spring Boot built in, auto-detected on open; add your own formats without code
- **Merged views** — open several logs as one timeline, interleaved by timestamp: the rolled files of one app, or the logs of several services. Formats can be mixed, each file can have its own parser and timezone, and a file whose clock is off can be shifted into place.
- **Compressed files** — open `.gz` and `.zip` files directly; for a zip with several logs, tick the ones you want, in separate tabs or merged
- **Plain text fallback** — a file no parser recognizes opens as plain text, with an offer to create a parser for it
- **Row highlighting** — color-code rows for visual triage; navigate between highlights by color
- **Filtering** — by level, logger, time range and free text, combined; level, logger and time filters take milliseconds even on a million lines
- **Follow mode** — tail a live file like `tail -f`, locally or over SSH; survives log rotation without losing existing rows
- **Detail panel** — the full entry with its stack trace, and JSON payloads pretty-printed and syntax-highlighted
- **Find bar** — `Ctrl+F` searches all columns across visible rows with `↑`/`↓` navigation
- **Annotations** — attach notes to individual log lines while investigating; they're saved next to the log and come back when anyone opens the same file
- **Review bundles** — `File → Export for Review…` packs the log (or every file of a merged view) and its notes into one `.zip` for a colleague, who opens it like any log
- **Column management** — hide/show columns, drag to reorder, and save named layouts; a default layout can apply to every file you open
- **Themes** — built-in Dark and Light themes, plus any FlatLaf theme file you install, switched live from `View → Theme`
- **Plugin system** — add parsers, formatters, syntax highlighters or live log sources from a JAR, or install them from a Nexus repository in the Plugin Manager (`Tools → Plugins…`)

---

## Getting Started

### Download

Each [release](https://github.com/benlazaro/clouseau/releases) has a portable zip per platform with its own Java runtime: unzip it and run the launcher. The [Edge build](https://github.com/benlazaro/clouseau/releases/tag/edge) is a single `clouseau-edge.jar` built from every push to `main`; run it with `java -jar clouseau-edge.jar` on Java 21 or later.

Only one Clouseau runs at a time: opening a file while it's running sends the file to the open window.

### Requirements

- Java 21+ (to run from source or the Edge jar; release zips bundle their own)

### Run from source

```bash
git clone https://github.com/benlazaro/clouseau.git
cd clouseau
./gradlew :clouseau-ui:run
```

### Build a distribution

```bash
./gradlew :clouseau-ui:installDist
./clouseau-ui/build/install/clouseau-ui/bin/clouseau-ui
```

---

## Usage

**Open a file** — `File → Open` or `Ctrl+O`, drag files onto the window, or pass them on the command line. Clouseau auto-detects the format from the first lines of the file; you can also pick a parser in the open dialog. Each file opens in its own tab, and opening a file that's already open switches to its tab. `.gz` and `.zip` files open directly.

**Open several files as one timeline** — select several files in the open dialog, drop them, or pass them on the command line, and choose **Merged View** (tick "Remember my choice" to skip the question; change it later in Settings). `File → Open Merged…` merges without asking, and a pattern such as `app.log*` typed in the open dialog's file name opens every matching file in that folder, merged.
- Lines from all files are interleaved by timestamp; stack traces stay with their entry, and lines with equal timestamps keep each file's order.
- The **Source** column shows each line's file, and the filter bar's **Sources** button hides whole files. Filters, Find, highlights and notes work across all files.
- Right-click a row (or use the tab's **Sources** menu) to set that file's **source timezone**, its own **parser**, or a **time offset** to correct a clock that's off (`db.log (+3m 12s)` in the Source column). The detail panel always shows what the file itself wrote.
- With Follow on, every plain file is followed and new lines land in time order.
- Files without timestamps, or that no parser recognizes, are offered in their own tabs.

**Files no parser recognizes** open as plain text: line numbers, Find, copy and Follow. Click **Create a Parser…** in the banner to open the parser editor with the file's first line as a sample; when you save, the tab switches to the table.

**Source timezone** — the `[src: …]` label in the status bar shows which timezone a tab's timestamps are read in. Click it, or right-click the tab, to change it.

**Hand off a log for review** — `File → Export for Review…` writes a `.zip` with the log, exactly as it was (a `.gz` stays a `.gz`), and every note and highlight. A merged view exports all its files, with each file's notes, timezone, offset and parser. Your colleague opens the bundle like any file and sees your notes on the right lines, with your name; notes they add are saved next to the bundle, so the bundle stays as you sent it.

**Live sources** — `File → Connect via SSH…` follows a log on a remote host with a server-side `tail -f`. Source plugins appear under `File → Open Source`.

**Highlight rows** — right-click one or more rows and choose a colour. Use the highlight nav bar to jump between highlighted rows, filtered by colour. Highlights are saved in the same `.annotations` file as notes, so they come back when you reopen the log. A highlight stores its palette position, so it follows the theme of whoever opens it.

**Filter** — use the level toggles, logger picker, time range, and message filter box. Filters combine (AND). Click **Clear All Filters** to reset all.
- **Level solo** — Alt+click a level button to show only that level; Alt+click it again to restore all.
- **Logger picker** — hierarchical tree with checkboxes; click the **Loggers** button to open. Click **Close** to dismiss without accidentally hitting **None**.

**Navigate the table** — `Ctrl+Home` jumps to the first row; `Ctrl+End` jumps to the last row.

**Find** — press `Ctrl+F` to open the find bar. Searches all columns (message, logger, thread, level, custom fields) across visible rows. `Enter` / `Shift+Enter` or `▲` / `▼` to navigate matches. `Escape` to close.

**Detail panel** — select any row to see all fields expanded. The message is automatically formatted (JSON is pretty-printed) and syntax-highlighted (Monokai palette).
- Toggle individual formatters and syntax highlighters from the toolbar above the detail pane.
- Click **Copy Message** to copy the formatted message text to the clipboard (the message text flashes to confirm).

**Follow** — enabled by default. Toggleable per-tab in the filter bar. New lines are appended as they arrive; opening a file always starts at the top. Log rotation is handled transparently — existing rows are kept and the new file is tailed from the beginning.

**Annotations** — attach a note to any log line to record your findings during an investigation.
- Right-click a row and choose **Add Note…** to open the annotation editor. Enter your note and author name, then click **OK**.
- Annotated rows show a ✎ indicator in the `#` column in amber.
- The note appears at the top of the detail panel when the row is selected.
- Open **Annotations** (`Ctrl+Shift+N`) or right-click → **View All Notes…** to see every annotation in a floating window. Click any row to jump straight to that log line.
- Annotations are saved to a `.annotations` sidecar file next to the log (e.g. `app.log.annotations`). Share it with colleagues — they place it next to the same log file and all notes load automatically. If the log's folder is read-only, notes are saved in the `annotations` folder of the [data directory](#data-directory) instead.
- Each note stays on the line it was written on, even when the same text appears on many lines, and follows that line if lines are added above it.

**Table context menu** — right-click one or more rows to:
- **Highlight rows** — assign a colour, or clear highlights.
- **Add Note… / Remove Note** — annotate the row (see Annotations above).
- **Set Time Offset / Source Timezone / Parser for …** — in a merged view, change how that row's file is read (see above).
- **View All Notes…** — open the annotations panel.
- **Reload File** — re-read the file from scratch.
- **Clear Table** — remove all rows (with confirmation).

**Columns** — right-click any column header to manage columns:
- **Layouts section** — lists your saved column layouts. The default layout (if set) is marked with ★. Click a layout name to apply it immediately. **Save current layout…** captures the current column order, visibility, and widths under a name you choose. **Manage layouts…** opens a dialog where you can set or clear the default layout and delete layouts you no longer need.
- **Columns section** — a checkbox per column; uncheck to hide, check to show. At least one column is always kept visible. Column color-coding follows the column as you drag it to a new position.

**Themes** — `View → Theme` switches between Dark, Light and any installed theme at once, in every tab. `View → Theme → Manage Themes…` installs or removes FlatLaf `.properties` theme files.

**Tabs** — `Ctrl+Tab` / `Ctrl+Shift+Tab` (or `Ctrl+PageDown` / `Ctrl+PageUp`) move between tabs, `Ctrl+1`…`Ctrl+8` jump to a tab and `Ctrl+9` to the last, and `Ctrl+W` closes one. Drag a tab to reorder. `Help → Keyboard Shortcuts` lists every shortcut.

**Settings** — `File → Settings` (`Ctrl+,`). Stored as `settings.json` in the [data directory](#data-directory).

### Built-in parsers

#### Log4j Pattern

Pattern: `%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger - %msg`

```
{date} [{thread}] {level} {logger} - {message}
```

Supports space or `T` as the date/time separator, and `.` or `,` as the millisecond separator.

```
2024-01-15 10:30:45.123 [main] INFO  com.example.App - Application started
2024-01-15 10:30:45,456 [http-nio-8080-exec-1] ERROR com.example.OrderService - Order not found
2024-01-15T10:30:46.001 [scheduler-1] WARN  com.example.Cache - Cache miss rate high
```

#### Spring Boot

Default Logback console format. Two variants are supported:

**Pre-3.4** — `%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p ${PID} --- [%t] %-40.40logger{39} : %m`

```
{date+tz}  {level} {pid} --- [{thread}] {logger} : {message}
```

```
2024-01-15T10:30:45.123+01:00  INFO 12345 --- [           main] com.example.App           : Started App in 2.3s
2024-01-15T10:30:46.007+01:00  WARN 12345 --- [pool-1-thread-2] com.example.Cache         : Eviction rate above threshold
2024-01-15T10:30:47.512+01:00  ERROR 12345 --- [http-nio-8080-exec-3] com.example.OrderService  : Order not found
```

**3.4+** — adds an `[appName]` group between `---` and the thread:

```
{date+tz}  {level} {pid} --- [{appName}] [{thread}] {logger} : {message}
```

```
2025-06-10T23:43:06.269-05:00  INFO 31280 --- [demo] [  restartedMain] com.example.App           : Started App in 1.8s
2025-06-10T23:43:07.001-05:00  ERROR 31280 --- [demo] [http-nio-8080-exec-1] com.example.OrderService  : Order #4291 not found
```

---

## Project Structure

```
clouseau/
├── clouseau-api/       # Plugin contract — interfaces only (LogParser, LogSource, LogSourceProvider, …)
├── clouseau-core/      # Engine, no Swing — parsing, indexing, following, merging, annotations, review bundles, SSH source
├── clouseau-plugin/    # PF4J plugin loader and lifecycle
├── clouseau-ui/        # Swing UI — MainFrame, LogPanel, LogTableView, FilterBar, dialogs
└── clouseau-example-plugin/  # Example plugin (logfmt parser, demo source) — a template for your own
```

**Dependency rules:**

```
clouseau-api  ←  clouseau-core  ←  clouseau-plugin  ←  clouseau-ui
                                                         (plugins depend only on clouseau-api)
```

---

## Custom Parsers

You can add your own log format without writing a plugin. Create `parsers.json` in the [data directory](#data-directory) as a JSON array of parser definitions:

```json
[
  {
    "name": "My App",
    "pattern": "(?<timestamp>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) \\[(?<thread>[^\\]]+)\\] (?<level>\\w+) (?<logger>\\S+) - (?<message>.*)",
    "timestampFormat": "yyyy-MM-dd HH:mm:ss"
  }
]
```

| Field | Required | Description |
|---|---|---|
| `name` | Yes | Display name shown in the file chooser |
| `pattern` | Yes | Java regex with named capture groups |
| `timestampFormat` | Yes | `DateTimeFormatter` pattern for the `timestamp` group (e.g. `yyyy-MM-dd HH:mm:ss.SSS`). See note below. |

**Named capture groups** map to log entry fields:

| Group | Maps to |
|---|---|
| `timestamp` | Parsed timestamp (requires `timestampFormat`) |
| `level` | Log level (`TRACE` `DEBUG` `INFO` `WARN` `ERROR` `FATAL`) |
| `logger` | Logger name |
| `thread` | Thread name |
| `message` | Log message |
| anything else | Stored as an extra field, visible in the detail panel |

All groups are optional — only define what your format includes. Parsers are loaded on startup; restart the app after editing the file. Invalid entries are skipped with a warning in the log.

> **`timestampFormat`** accepts any [`DateTimeFormatter`](https://docs.oracle.com/en/java/docs/api/java.base/java/time/format/DateTimeFormatter.html) pattern string (e.g. `yyyy-MM-dd HH:mm:ss.SSS`). One special value is also supported: `ISO_OFFSET_DATE_TIME`, which matches timestamps that include a timezone offset or `Z` suffix — for example `2024-01-15 10:30:45.123+01:00` or `2024-01-15 10:30:45.789Z`. Use this for Spring Boot logs. Note that `,` is automatically normalised to `.` before parsing, so `yyyy-MM-dd HH:mm:ss.SSS` matches both `10:30:45.123` and `10:30:45,123`.

---

## Plugin Manager

Open it from **Tools → Plugins…**. The manager connects to a Nexus Maven repository and lists available plugins. Select any entry to see which extensions (parsers, formatters, syntax highlighters, etc.) it provides before installing.

**Connecting to a repository** — click **Configure** to enter the Nexus base URL and choose an authentication method:

| Auth type | When to use |
|---|---|
| None | Public or anonymous repository |
| Basic | Standard username + password credentials |
| User Token | Nexus-generated user token (token name code + token passcode) |

After installing a plugin the new extensions are immediately available — no restart required.

---

## Writing a Plugin

A plugin adds a `LogParser`, `LogFormatter`, `LogSyntaxHighlighter` or
`LogSourceProvider` from `clouseau-api`. For a format a regex can describe,
you don't need a plugin: add it to `parsers.json` instead (see above).

The quickest start is to copy **[`clouseau-example-plugin`](clouseau-example-plugin)**, a
complete plugin that parses [logfmt](https://brandur.org/logfmt) lines
(`time=… level=info msg="…" key=value`). It shows everything a plugin needs:

- **Dependencies:** `clouseau-api` and PF4J as `compileOnly` (the app provides both), plus
  PF4J as an `annotationProcessor`, which writes `META-INF/extensions.idx` from your
  `@Extension` classes.
- **Manifest:** `Plugin-Id` and `Plugin-Version` (semantic version) in the JAR manifest.
- **The extension:**

```java
@Extension
public class LogfmtParser implements LogParser {
    @Override public String getName() { return "logfmt (example plugin)"; }

    // Called for every line during auto-detection, so keep it cheap.
    @Override public boolean canParse(String line) { /* sniff the line */ }

    // Keys other than the standard ones go into fields(); each becomes a table column.
    @Override public LogEntry parse(String line) { /* parse it */ }
}
```

Build the JAR with `./gradlew :clouseau-example-plugin:jar` (it lands in
`clouseau-example-plugin/build/libs/`), drop it in the `plugins` folder of the
[data directory](#data-directory), and restart or refresh in the Plugin Manager. New tabs
use it right away.

Plugin parsers come after the built-in and `parsers.json` parsers during auto-detection,
and a plugin parser can't reuse a name that's already taken. Exceptions thrown by a plugin
parser are logged and the line is shown as UNKNOWN, so a bug can't stop a file loading.

### Log sources

A `LogSourceProvider` brings in live lines from somewhere else (a log aggregator, a
message queue, …). It declares its settings, and Clouseau draws the form under
**File → Open Source**, checks required fields, numbers and choices, and remembers the
values (passwords encrypted). The example's `DemoStreamSource` shows the shape:

```java
@Extension
public class DemoStreamSource implements LogSourceProvider {
    @Override public String getName() { return "Demo log stream (example plugin)"; }

    @Override public List<SourceSetting> settings() {
        return List.of(
                SourceSetting.text("service", "Service name").withDefault("checkout"),
                SourceSetting.number("interval", "Milliseconds between lines").withDefault("500"),
                SourceSetting.number("count", "Stop after this many lines").optional());
    }

    // Called on the UI thread: check values and build the source, but do no I/O here.
    @Override public LogSource create(Map<String, String> values) { /* new Stream(...) */ }
}
```

The `LogSource` it returns does the work: `open(consumer)` runs on a background thread
and blocks, pushing each line to the consumer, until the stream ends or `close()` is
called (from another thread, when the user closes the tab).

---

## Tech Stack

| Concern | Library |
|---|---|
| Look & feel | FlatLaf 3.4.1 (Dark and Light, plus installable themes) |
| Layout | MigLayout 11.3 |
| Log text rendering | RSyntaxTextArea 3.4.0 |
| Plugin system | PF4J 3.12.0 |
| SSH | sshj 0.38.0 |
| JSON | Gson 2.11 |
| Build | Gradle 8.8 (Kotlin DSL), Java 21 |
| Tests | JUnit 5, AssertJ, Mockito |

---

## Settings

### Data directory

Settings, custom parsers, themes, plugins, logs and the credential keystore live in one data directory:

| How you run Clouseau | Data directory |
|---|---|
| `-Dclouseau.data.dir=<path>` | `<path>` |
| Windows release zip | `data/` next to `Clouseau.exe` (extract it to a folder you can write to, not `C:\Program Files`) |
| macOS and Linux packages | `~/.clouseau/` (the launcher sets `-Dclouseau.data.dir=~/.clouseau`) |
| Anything else (`gradlew :clouseau-ui:run` → `clouseau-ui/data/`, bare `java -jar`) | `data/` in the current working directory |

**Why macOS and Linux packages set the flag:** without a property, Clouseau uses `data/` relative to the working directory. That is fine for development and for the Windows build, where the app locates its own install folder. An installed macOS or Linux app has no meaningful working directory (a Finder-launched app starts in `/`, a menu-launched Linux app in `/` or the home folder), and the install location itself (inside the `.app` bundle or under `/opt`) isn't a place to write user data. So the macOS and Linux packages pass `-Dclouseau.data.dir=~/.clouseau` in their launcher (see `jpackage { jvmArgs }` in `clouseau-ui/build.gradle.kts`). A leading `~/` in the property means the home folder. To use a different location, set the property yourself.

User settings are stored in `settings.json` there:

```json
{
  "tab.close.confirm": true,
  "follow.by.default": true,
  "detail.font.size": 12,
  "recent.max": 5,
  "recent.files": [
    "/home/user/logs/app.log"
  ],
  "max.entries.per.tab": 200000,
  "column.layout.default": "my-layout",
  "column.layouts": [
    {
      "name": "my-layout",
      "columns": [
        { "modelIndex": 0, "visible": true, "width": 160 },
        { "modelIndex": 1, "visible": true, "width": 60 },
        { "modelIndex": 2, "visible": false, "width": 120 }
      ]
    }
  ]
}
```

| Key | Default | Description |
|---|---|---|
| `max.entries.per.tab` | `200000` | Maximum entries an SSH or plugin-source tab keeps in memory; the oldest are dropped past the limit. Files you open are indexed and never trimmed. Range: 10,000 – 2,000,000. |
| `column.layout.default` | _(none)_ | Name of the layout applied automatically when a file is opened. |
| `column.layouts` | `[]` | Saved column layouts. Each entry records column order, visibility, and width. |

---

## License

MIT
