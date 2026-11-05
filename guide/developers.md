---
title: For developers
nav_order: 3
---

# For developers
{: .no_toc }

Building Clouseau, how the code is laid out, and writing plugins.

1. TOC
{:toc}

## Building and running

Clouseau needs Java 21. Gradle 8.8 doesn't run on JDK 25, so point `JAVA_HOME` at a JDK
17 or 21.

```bash
git clone https://github.com/benlazaro/clouseau.git
cd clouseau
./gradlew test                                    # all tests
./gradlew :clouseau-ui:run                        # run from source
./gradlew :clouseau-ui:shadowJar                  # the Edge jar: clouseau-ui/build/libs/clouseau-edge.jar
./gradlew :clouseau-ui:distZipImage -PappVersion=1.0.0   # a release zip with its own Java runtime
```

`distZipImage` skips `-SNAPSHOT` versions, so pass a release version to build it locally.
Running from source keeps its data in `clouseau-ui/data/`.

## Project structure

```
clouseau/
├── clouseau-api/             # Plugin contract — interfaces only (LogParser, LogSource, LogSourceProvider, …)
├── clouseau-core/            # Engine, no Swing — parsing, indexing, following, merging, annotations, review bundles, SSH source
├── clouseau-plugin/          # PF4J plugin loader and lifecycle
├── clouseau-ui/              # Swing UI — MainFrame, LogPanel, LogTableView, FilterBar, dialogs
└── clouseau-example-plugin/  # Example plugin (logfmt parser, demo source) — a template for your own
```

```
clouseau-api  ←  clouseau-core  ←  clouseau-plugin  ←  clouseau-ui
                                                         (plugins depend only on clouseau-api)
```

The design is written up in the repository:
[USE_CASES.md](https://github.com/benlazaro/clouseau/blob/main/docs/USE_CASES.md) (what
Clouseau is for, and what's supported) and
[DESIGN.md](https://github.com/benlazaro/clouseau/blob/main/docs/DESIGN.md) (modules,
threading, file formats, decisions and known gaps).

## Tech stack

| Concern | Library |
|---|---|
| Look & feel | FlatLaf 3.4.1 |
| Layout | MigLayout 11.3 |
| Log text rendering | RSyntaxTextArea 3.4.0 |
| Plugin system | PF4J 3.12.0 |
| SSH | sshj 0.38.0 |
| JSON | Gson 2.11 |
| Build | Gradle 8.8 (Kotlin DSL), Java 21 |
| Tests | JUnit 5, AssertJ, Mockito |

## Writing a plugin

A plugin adds a `LogParser`, `LogFormatter`, `LogSyntaxHighlighter` or
`LogSourceProvider` from `clouseau-api`. For a format a regex can describe,
you don't need a plugin: create it in the [parser editor](parsers.md) instead.

The quickest start is to copy **[`clouseau-example-plugin`](https://github.com/benlazaro/clouseau/tree/main/clouseau-example-plugin)**, a
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
[data folder](settings.md#the-data-folder), and restart or refresh in the Plugin Manager. New tabs
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
