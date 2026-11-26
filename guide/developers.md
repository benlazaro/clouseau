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

Clouseau needs Java 21. Gradle 8.14 doesn't run on JDK 25, so point `JAVA_HOME` at a JDK
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

`./gradlew check` also runs two checks you may meet when changing things:

- **Third-party notices.** Every library the app ships is listed in
  `clouseau-ui/src/main/resources/com/droidenx/clouseau/ui/legal/THIRD-PARTY-NOTICES.txt`
  with its license. The check fails if a runtime dependency is missing there, so when you
  add or replace one, add its entry too (and its license text, if it's a new license).
- **Plugin API compatibility.** `checkApiCompatibility` compares `clouseau-api` with the
  last release on Maven Central and fails on any change that would break a plugin built
  against it, such as a removed method or a new abstract method in an interface (add a
  `default` method instead). The report is in `clouseau-api/build/reports/japicmp/`.

To retake this guide's screenshots after a UI change, close any running Clouseau and run
`./gradlew :clouseau-ui:screenshots`. It opens each scene from the logs in `samples/` (with
its own empty data folder, so your settings aren't touched), saves them to
`build/screenshots/`, and takes over the screen for about a minute. Check them, then copy
them into `docs/screenshots/`.

The repository's social preview (the picture shown when the repo is linked) is drawn from
the main view screenshot: run `./gradlew :clouseau-ui:socialPreview` to redraw
`docs/social-preview.png`, then upload it under the repository's **Settings → General →
Social preview**. It draws off-screen, so it doesn't need the screen to itself.

To check how Clouseau copes with big logs, run `./gradlew :clouseau-ui:bigLogs`. In about
ten seconds it writes five logs of 1,000,000 entries each (about 125 MB apiece) to
`clouseau-ui/build/big-logs/`, the same files every time:

- `node1.log` to `node4.log` cover the same hours from four services: open them together
  as a merged view of 4 million rows.
- `out-of-order.log` has about 5% of its lines stamped up to ten minutes early, so sorting
  by Timestamp has real reordering to do.

Every 50th entry has a stack trace line under it. Add `-Pentries=N` for a different size.
The files stay in `build/`, so they're never committed; `./gradlew clean` removes them.

## Project structure

```
clouseau/
├── clouseau-api/             # Plugin contract — interfaces only (LogParser, LogSource, LogSourceProvider, …)
├── clouseau-core/            # Engine, no Swing — parsing, indexing, following, merging, annotations, review bundles, SSH source
├── clouseau-plugin/          # PF4J plugin loader and lifecycle
├── clouseau-ui/              # Swing UI — MainFrame, TabPanel, LogTableView, FilterBar, dialogs
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
| Plugin system | PF4J 3.16.1 |
| SSH | sshj 0.41.2 |
| JSON | Gson 2.11 |
| Build | Gradle 8.14.4 (Kotlin DSL), Java 21 |
| Tests | JUnit 5, AssertJ, Mockito |

## Writing a plugin

A plugin adds a `LogParser`, `LogFormatter`, `LogSyntaxHighlighter` or
`LogSourceProvider` from `clouseau-api`. For a format a regex can describe,
you don't need a plugin: create it in the [parser editor](parsers.md) instead.

The quickest start is to copy **[`clouseau-example-plugin`](https://github.com/benlazaro/clouseau/tree/main/clouseau-example-plugin)**, a
complete plugin that parses [logfmt](https://brandur.org/logfmt) lines
(`time=… level=info msg="…" key=value`). It builds against the released API from Maven Central,
just as your plugin will, and its folder builds on its own: copy it out of the Clouseau
repository, rename the plugin, and run `gradle jar` (Gradle 8 on JDK 17 or 21).

**Writing it with a coding assistant?** Point it at
[llms.txt](https://benlazaro.github.io/clouseau/llms.txt), one plain page with the build file,
every extension point and its rules, and the check below, and have it run the
[check](#checking-a-plugin) until nothing fails.

The example shows everything a plugin needs:

- **Dependencies:** `clouseau-api` and PF4J as `compileOnly` (the app provides both, so don't
  bundle them in your JAR), plus PF4J as an `annotationProcessor`, which writes
  `META-INF/extensions.idx` from your `@Extension` classes. The API is released on its own,
  with its own version, on Maven Central as `com.droidenx.clouseau:clouseau-api`; each
  **Clouseau plugin API** release on the [releases page](https://github.com/benlazaro/clouseau/releases)
  (tags `api-v…`) says what changed and has the same jars to download. To try API changes
  that aren't released yet, run `./gradlew :clouseau-api:publishToMavenLocal` from a Clouseau
  checkout and add `mavenLocal()` to your repositories. Depend on it as:

  ```kotlin
  repositories { mavenCentral() }
  dependencies {
      compileOnly("com.droidenx.clouseau:clouseau-api:0.3.0")
      compileOnly("org.pf4j:pf4j:3.16.1")
      annotationProcessor("org.pf4j:pf4j:3.16.1")
  }
  java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
  ```

  Build for Java 21, which is what Clouseau runs on. Gradle won't resolve the API for an older
  target ("only compatible with JVM runtime version 21 or newer").
- **Name:** call your plugin `<name>-clouseau-plugin`, such as `logfmt-clouseau-plugin`, and
  use that as its artifact name, JAR name and `Plugin-Id`. See
  [Naming and publishing](#naming-and-publishing).
- **Manifest:** `Plugin-Id` and `Plugin-Version` (semantic version) in the JAR manifest.
  The Plugin Manager also shows `Plugin-Name` (otherwise the ID, made readable),
  `Plugin-Description`, `Plugin-Provider` and `Plugin-License` when you set them.
  Set `Plugin-Requires` to the plugin API you built against, such as `>=0.3.0`. The API has
  its own version (`ClouseauApi.VERSION`), separate from Clouseau's. A Clouseau whose API
  doesn't satisfy it lists your plugin as Failed and says why, instead of loading it and
  failing later.
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

A few rules for parsers:

- **Build entries with `LogEntry.builder(line)`** and set only what your format has. Values
  added to `LogEntry` in later API versions come with a builder method and a default, so a
  parser written this way keeps working.
- **Timezones.** Users can say which timezone a log was written in. Clouseau calls
  `parse(line, writtenIn)`; its default calls your `parse(line)` and moves the time from your
  computer's timezone to `writtenIn`, which is right if your timestamps never have an offset.
  If they can (`2026-03-27T08:00:00Z`, `+02:00`), override `parse(line, writtenIn)` instead,
  read a time without an offset in `writtenIn`, and keep the offset of one that has it:

  ```java
  @Override public LogEntry parse(String line) { return parse(line, ZoneId.systemDefault()); }

  @Override public LogEntry parse(String line, ZoneId writtenIn) {
      // DateTimeFormatter.withZone(writtenIn): an offset in the text wins over writtenIn
      Instant time = FORMAT.withZone(writtenIn).parse(timeText, Instant::from);
      return LogEntry.builder(line).timestamp(time) /* … */ .build();
  }
  ```
- **Threads.** One instance serves every tab, from several background threads at once, so
  keep a parser stateless (or thread-safe).
- **Failures.** If `canParse` or `parse` throws, or `parse` returns `null`, the line shows as
  an unparsed line; the first failure is logged.

Build the JAR with `./gradlew :clouseau-example-plugin:jar` (it lands in
`clouseau-example-plugin/build/libs/`), drop it in the `plugins` folder of the
[data folder](settings.md#the-data-folder), or restart. The Plugin Manager picks it up while it's open. New tabs
use it right away. [Check it](#checking-a-plugin) with a sample log first, to see what Clouseau will make of it.

Plugin parsers come after the built-in and `parsers.json` parsers during auto-detection,
and a plugin parser can't reuse a name that's already taken. Exceptions thrown by a plugin
parser are logged and the line is shown as UNKNOWN, so a bug can't stop a file loading.

### Formatters

A `LogFormatter` changes how the selected entry's message reads in the detail panel:
pretty-printing a payload, decoding hex, unescaping a string. The table, filters, find and
copied lines keep the message as it was logged.

```java
@Extension
public class HexFormatter implements LogFormatter {
    @Override public String getName() { return "Hex"; }

    // Called for every selected entry: a quick look, not a parse.
    @Override public boolean canFormat(String text) { return text.startsWith("0x"); }

    // Never null: return the text unchanged if it isn't what you expected.
    @Override public String format(String text) { /* decode it */ }
}
```

- **What it gets.** The message, with any continuation lines after a `\n`. Formatters run in
  turn: each one whose `canFormat` accepts the text so far gets it and passes its result on, so
  yours may get another formatter's output. The built-in JSON and `toString()` formatters come
  first.
- **Threads.** Formatters run on the Swing event thread, so the window waits while yours works.
  Keep it fast and stateless, and do no I/O.
- **Failures.** If `canFormat` or `format` throws, or `format` returns `null`, your formatter is
  skipped for that message, and the first failure is logged.
- **Its button.** Each format gets a button in the detail panel's header that turns it off. A
  formatter and a highlighter with the same name share one, so give the highlighter that colors
  your formatter's output the same name.

### Highlighters

A `LogSyntaxHighlighter` colors parts of a message in the detail panel. It works on the
text after the formatters have run, and it says what each part *is*, not what color it
is: the theme picks the colors, so they read well in Dark, Light and custom themes.

```java
@Extension
public class KeyValueHighlighter implements LogSyntaxHighlighter {
    private static final Pattern PAIR = Pattern.compile("(\\w+)(=)(\\S+)");

    @Override public String getName() { return "Key=Value"; }

    @Override public boolean canHighlight(String text) { return PAIR.matcher(text).find(); }

    @Override public List<Span> highlight(String text) {
        List<Span> spans = new ArrayList<>();
        Matcher m = PAIR.matcher(text);
        while (m.find()) {
            spans.add(new Span(m.start(1), m.end(1), Role.KEY));
            spans.add(new Span(m.start(2), m.end(2), Role.OPERATOR));
            spans.add(new Span(m.start(3), m.end(3), Role.STRING));
        }
        return spans;
    }
}
```

The roles are `KEY`, `STRING`, `NUMBER`, `LITERAL` (true, false, null), `KEYWORD` (a word
that gives the structure, such as an HL7 segment name), `OPERATOR`, `PUNCTUATION`, `MUTED`
(text that matters less, such as raw bytes), `ACCENT` (something to notice among muted
text) and `ERROR`. A theme colors them with `Clouseau.syntax.<role>` keys.

Mark only what your highlighter recognizes. Every enabled highlighter that accepts a
message colors it, so a hex highlighter and an HL7 highlighter can both color one message.
Where two spans overlap, the narrower one wins. Spans can come in any order, and ones past
the end of the text are cut. Each highlighter gets a button in the detail panel's header
that turns it off; give a highlighter the same name as the formatter whose output it colors,
and the two share one button (the built-in JSON formatter and highlighter do). An exception from your highlighter is logged once, and the message is
shown without its colors.

### Log sources

A `LogSourceProvider` brings in live lines from somewhere else (a log aggregator, a
message queue, …). It declares its settings, and Clouseau adds **File → Open *name*…**
for it, draws the form, checks required fields, numbers and choices, and remembers the
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

The tab shows a dot for the connection: yellow while connecting, green when connected,
gray once the stream ended or failed. To turn it green as soon as you're connected,
rather than at the first line (a quiet stream may send none for a while), implement
`ConnectingLogSource` instead of `LogSource`. Its `open(consumer, onConnected)` is the one
Clouseau calls; run `onConnected` once you're signed in and streaming, at most once and
from the thread running `open`. If connecting fails, throw and don't call it. The
one-argument `open` is already implemented for you. A plain `LogSource` still works and
turns green at its first line.

```java
public class MySource implements ConnectingLogSource {
    @Override public void open(Consumer<String> lines, Runnable onConnected) throws Exception {
        connectAndSignIn();     // throws if it fails
        onConnected.run();      // dot turns green
        while (...) lines.accept(next());
    }
    // getName(), close() as for any LogSource
}
```

The example plugin's `DemoStreamSource` does this.

A source is used once. **Reconnect** on the tab closes it and calls your provider's
`create` again with the same values, so keep connection state in the source, not the
provider.

### Checking a plugin

Clouseau can check a plugin without opening a window. It loads the JAR the way it loads one from
the plugins folder, and runs the plugin's extensions on a sample log:

```
java -jar clouseau-edge.jar --check-plugin build/libs/acme-clouseau-plugin-1.0.0.jar sample.log
```

Use the [Edge build](https://github.com/benlazaro/clouseau/releases/download/edge/clouseau-edge.jar)
(Java 21). The check runs even while Clouseau is open. It reports on:

- **The JAR:** the manifest (`Plugin-Id` and its name, `Plugin-Version`, `Plugin-Requires`), the
  JAR's file name, `META-INF/extensions.idx`, and whether the JAR bundles the API or PF4J (it
  mustn't). Then whether Clouseau loads it, and which extensions it finds.
- **Each parser,** on the sample's lines: how many it reads and how fast `canParse` is, lines it
  throws on or returns `null` for, entries without times or levels, and fields
  `customFields()` doesn't list. It also checks that times with an offset keep it whatever the
  log's timezone, and that parsing from 8 threads at once gives the same entries. Then it shows
  which lines a built-in parser takes first during auto-detection, and the first entries.
- **Each formatter and highlighter,** on the sample's messages: what they accept, exceptions,
  `null` results, spans past the end of the text, slow calls, and an example of each.
- **Each source:** its settings (unique keys, labels). It never connects: try that in Clouseau.

Every line starts with `OK`, `WARN`, `FAIL` or `NOTE`, and the report ends with
`Result: 1 problem, 2 warnings`. The exit code is 0 when nothing failed and 1 when something did,
so you can run it in a build or in CI. The sample is read up to 5,000 lines (plain, `.gz` or
`.zip`); without one, only the JAR and the names are checked.

```
Parser "logfmt (example plugin)"
  OK    Reads 3 of 4 lines; canParse takes 1.14 microseconds a line.
  NOTE  1 line that no parser reads shows as continuation lines of the entry before them (right for stack traces and wrapped text), such as:
          at com.example.Pay.charge(Pay.java:10)
  OK    Times with an offset keep it, whatever the log's timezone.
  OK    Thread-safe: 8 threads parsing at once get the same entries.
  OK    Auto-detection gives it all 3 lines it reads.
```

## Naming and publishing

Name your plugin's artifact **`<name>-clouseau-plugin`**, under your own group:
`io.example:logfmt-clouseau-plugin`. It's the same idea as Maven's own plugins, where
`maven-<name>-plugin` belongs to Apache and everyone else uses `<name>-maven-plugin`:
**`clouseau-<name>-plugin`** is kept for plugins the Clouseau project publishes
(`com.droidenx.clouseau`). Use the artifact name as the `Plugin-Id` too, and keep the JAR
named `<artifact>-<version>.jar` (Gradle and Maven do by default). That's how the Plugin
Manager tells which version you have installed and offers **Update** when there's a newer one.

**On Maven Central:** publish the JAR as you would any library, then add it to
[`plugins.json`](https://github.com/benlazaro/clouseau/blob/main/plugins.json) with a pull
request. Browse lists every plugin in that file when it opens, at the latest version Central
has (it asks Central for each one, so a new release shows up without another pull request):

```json
{
  "version": 1,
  "plugins": [
    {
      "groupId": "io.example",
      "artifactId": "logfmt-clouseau-plugin",
      "name": "logfmt",
      "description": "Reads logfmt lines: time=… level=info msg=\"…\" key=value.",
      "provides": ["parser"],
      "homepage": "https://github.com/example/logfmt-clouseau-plugin"
    }
  ]
}
```

`groupId` and `artifactId` are required, and the artifact name must follow the convention.
`provides` lists what it adds: `parser`, `formatter`, `highlighter` and `source`. Browse shows
them in its **Adds** column, and typing `source` finds every source. The list is needed because
Maven Central's search index falls far behind what's published, and can't search by the end of a name.

To check an entry before you send it, start Clouseau with
`-Dclouseau.pluginCatalog=C:/path/to/plugins.json` (a file or a URL), and Browse reads that
list instead.

**On a company Nexus:** deploy the plugin there with the conventional name, and Browse
lists it for anyone who adds that Nexus under **Manage…**. No list is involved.
