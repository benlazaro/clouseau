---
title: Coming from OtrosLogViewer
parent: User Guide
nav_order: 8
---

# Coming from OtrosLogViewer
{: .no_toc }

Clouseau grew out of years of using OtrosLogViewer, and much of it will feel familiar: a
table of parsed entries, level colors, filters, marking lines and adding notes. This page
covers where things moved, how to bring your log formats over, and what Clouseau doesn't
do (yet).

1. TOC
{:toc}

## What carries over

| In OtrosLogViewer | In Clouseau |
|---|---|
| Opening a log, choosing a parser | **File → Open…** (`Ctrl+O`) or drag the file onto the window. The parser is detected from the first lines; the [open dialog](opening-files.md#the-open-dialog) lets you choose one. |
| Tailing a log | **Follow** in the filter bar, for any file. See [Following live logs](following.md). |
| Level, thread, class and time filters | The [filter bar](reading-and-filtering.md): levels, a logger tree, extra fields, a time range and text, all combined. |
| Searching | **Find** (`Ctrl+F`) searches every column of the rows the filters show. |
| Marking lines | [Highlights](reading-and-filtering.md#highlights): right-click rows and pick a color, then step through them by color. |
| Notes | [Notes](annotations-and-review.md) on any line. |
| Merging logs | [Merged views](merged-views.md): several logs in one timeline, each with its own parser, timezone and clock correction. |
| Remote logs over SFTP | [SSH](following.md) follows a server's log with `tail -F`, and can browse the server's folders. |
| Pattern-based log formats | The [parser editor](parsers.md#the-parser-editor) (**Tools → Parser Editor…**). See below for converting your `.pattern` files. |
| Plugins | Clouseau [plugins](settings.md#plugins) add parsers, formatters, syntax highlighters and live log sources. |

## What works differently

- **Nothing to save.** Highlights and notes are saved as you make them, in a file next to
  the log (`app.log` gets `app.log.clouseau`), and come back whenever the log is opened.
- **Sharing findings.** Instead of saving marked lines to a new file, **File → Export for
  Review…** packs the log and your notes into one `.zip`. Your colleague opens it like any
  log and sees every note and highlight. See [Review bundles](annotations-and-review.md#exporting-for-review).
- **Large files.** Clouseau doesn't load a file into memory: it indexes it on disk, at
  about 29 bytes of memory per line, so you rarely need to raise the memory limit, and no
  line is dropped from a file you open.
- **Compressed logs.** `.gz` files and `.zip` archives open directly; for a zip with several
  logs, you choose which ones, in separate tabs or merged.
- **Sessions.** Clouseau reopens your windows and tabs, with their filters, the next time
  it starts. Named sessions keep one set of logs per project.
- **Files no parser reads** open as plain text, with an offer to create a parser for them.

## Bringing over your log formats

### The easy way: paste your logging pattern

If you know the pattern your application logs with (from its `logback.xml`,
`log4j2.xml` or `application.properties`), you don't need your old `.pattern` file:

1. Open **Tools → Parser Editor…** and click **New Parser**.
2. Choose **Made from: Logback / Log4j pattern** and paste the pattern, such as
   `%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger - %msg%n`.
3. Paste a line from your log as the **Sample line**, or use **Test with File…**, to check
   it matches, then click **OK**.

### Converting a `.pattern` file

An OtrosLogViewer `.pattern` file describes a line with keywords, which Clouseau's
**Template** parsers also use. For a file like this:

```properties
name=My App
type=log4j
pattern=TIMESTAMP [THREAD] LEVEL LOGGER - MESSAGE
dateFormat=yyyy-MM-dd HH:mm:ss,SSS
```

1. Create a new parser in the parser editor and give it the same **Name**.
2. Choose **Made from: Template**.
3. Copy the `pattern` line, and put the `dateFormat` in braces after `TIMESTAMP`:

   ```
   TIMESTAMP{yyyy-MM-dd HH:mm:ss,SSS} [THREAD] LEVEL LOGGER - MESSAGE
   ```

4. Test it with a sample line or a file, and click **OK**.

Keywords map like this:

| `.pattern` keyword | Clouseau template |
|---|---|
| `TIMESTAMP` with `dateFormat` | `TIMESTAMP{format}` |
| `LEVEL`, `THREAD`, `LOGGER`, `MESSAGE` | The same keywords |
| `CLASS` | `LOGGER`, if the pattern has no `LOGGER`; the class name then fills the logger column and the logger tree |
| Any other value in brackets (`NDC`, `PROP(key)`) | `{name}`, such as `[{request_id}]`, shown as its own column |

Date formats are mostly the same letters (`yyyy`, `MM`, `dd`, `HH`, `mm`, `ss`, `SSS`).
If a format can't be read, the parser editor says why under the field.

A format a template can't describe, such as values that aren't in brackets, can be
written as a **Regex** parser with named groups. See [Parsers](parsers.md).

## Not in Clouseau (yet)

Some OtrosLogViewer features have no equivalent today:

- **Receiving logs over a socket** (a Log4j or Logback socket appender). Live sources come
  from files, SSH, or a [source plugin](following.md#source-plugins).
- **XML log formats** (Log4j's XML layout, `java.util.logging`'s XML). Clouseau reads
  line-based logs; a plugin parser could add these.
- **Remote files over SMB, FTP or HTTP.** Clouseau reads local files and follows logs over
  SSH. Copy the file locally, or open it from a mounted share.
- **Jumping to the source line in your IDE.**
- **Automatic marking rules.** Highlights are set by hand.

If one of these is what keeps you on OtrosLogViewer, please
[open an issue](https://github.com/benlazaro/clouseau/issues) and say how you use it.
