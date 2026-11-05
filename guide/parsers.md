---
title: Parsers
parent: User Guide
nav_order: 6
---

# Parsers
{: .no_toc }

A parser turns a log line into columns: timestamp, level, thread, logger, message and any
extra fields. Clouseau picks one automatically from a file's first lines; you can also
choose one in the open dialog.

1. TOC
{:toc}

## Built-in formats

### Log4j Pattern

Pattern: `%d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %-5level %logger - %msg`

```
2024-01-15 10:30:45.123 [main] INFO  com.example.App - Application started
2024-01-15 10:30:45,456 [http-nio-8080-exec-1] ERROR com.example.OrderService - Order not found
2024-01-15T10:30:46.001 [scheduler-1] WARN  com.example.Cache - Cache miss rate high
```

A space or `T` may separate the date and time, and `.` or `,` the milliseconds.

### Spring Boot

The default Logback console format, before and since Spring Boot 3.4 (which adds the
application name):

```
2024-01-15T10:30:45.123+01:00  INFO 12345 --- [           main] com.example.App           : Started App in 2.3s
2025-06-10T23:43:06.269-05:00  INFO 31280 --- [demo] [  restartedMain] com.example.App           : Started App in 1.8s
```

The process ID and application name become **pid** and **app** columns.

## The parser editor

**Tools → Parser Editor…** lists every parser; the built-in ones can be copied but not
changed. Click **+** to add a parser, the copy button to duplicate one, and **−** to delete
one. Changes are saved when you click **OK**, and new tabs use them right away.

![Parser editor](../docs/screenshots/parser-editor.png)

Each parser has a **Name** and a **Type**, which says how you describe the line:

- **Template** (the easiest): write the line with keywords where the values go.
- **Pattern (Logback / Log4j)**: paste the pattern from your logging configuration, such as
  `%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n`. **Import pattern…**
  helps with this.
- **Regex**: a Java regular expression with named groups, for formats the other two can't
  describe.

The **Generated regex** shows what Clouseau will match. Under **Test**, paste a **Sample
line** to see whether it matches and what each field gets, or click **Test with file…** to
try the parser on a real log.

### Template keywords

Keywords are uppercase. Everything between them must appear in the line as written.

| Keyword | Matches |
|---|---|
| `TIMESTAMP{format}` | The timestamp, read with a [`DateTimeFormatter`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/format/DateTimeFormatter.html) pattern such as `TIMESTAMP{yyyy-MM-dd HH:mm:ss.SSS}`. Required. |
| `LEVEL` | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` or `FATAL` |
| `LOGGER` | The logger name (no spaces) |
| `THREAD` | The thread name (no spaces) |
| `PID` | A process ID (digits) |
| `MESSAGE` | The rest of the line |
| `{name}` | Any other value without spaces, shown in its own column, e.g. `{request_id}` |

For example, the template

```
TIMESTAMP{yyyy-MM-dd HH:mm:ss.SSS} [{instance_id}] [THREAD] LEVEL LOGGER - MESSAGE
```

matches

```
2024-01-15 10:23:45.123 [550e8400-e29b] [main] INFO  com.example.App - Server started
```

### From a file no parser recognizes

When a file opens as [plain text](opening-files.md#files-no-parser-recognizes), **Create a
Parser…** opens the editor with a new parser and the file's first line as the sample.
Saving a parser that recognizes the file switches the tab to the table.

## parsers.json

Your parsers are saved in `parsers.json` in the [data folder](settings.md#the-data-folder).
You can also edit it by hand, as a JSON array; restart Clouseau afterwards. A parser with
the same name as a built-in one replaces it.

```json
[
  {
    "name": "My App",
    "pattern": "(?<timestamp>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) \\[(?<thread>[^\\]]+)\\] (?<level>\\w+) (?<logger>\\S+) - (?<message>.*)",
    "timestampFormat": "yyyy-MM-dd HH:mm:ss"
  }
]
```

| Field | Description |
|---|---|
| `name` | The name shown in parser lists |
| `pattern` | A Java regex with named groups |
| `timestampFormat` | A `DateTimeFormatter` pattern for the `timestamp` group. `ISO_OFFSET_DATE_TIME` matches timestamps with an offset or `Z`, as Spring Boot writes them. A `,` before the milliseconds is read as `.`. |

The groups `timestamp`, `level`, `logger`, `thread` and `message` fill those columns; any
other named group becomes an extra column. All groups are optional. Invalid entries are
skipped, with a warning in Clouseau's own log.

Parsers from [plugins](settings.md#plugins) come after these during auto-detection.
