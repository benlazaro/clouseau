# Clouseau Log Viewer

A log viewer for software engineers, born from a love of OtrosLogViewer and a desire to bring its core functionality into a cleaner, more modern interface. Open logs of any size, merge the logs of several services into one timeline, filter in milliseconds, and hand your annotated findings to a colleague in one file.

![Java 21](https://img.shields.io/badge/Java-21-blue) ![Gradle 8.8](https://img.shields.io/badge/Gradle-8.8-02303A) ![License MIT](https://img.shields.io/badge/License-MIT-green)

**[Download](https://github.com/benlazaro/clouseau/releases)** · **[User Guide](guide/index.md)** · **[For developers](guide/developers.md)**

---

![Main view](docs/screenshots/main-view.png)

*Level colors, row highlights with the bar for jumping between them, and the detail panel pretty-printing a JSON payload.*

![Merged view](docs/screenshots/merged-view.png)

*Three services' logs, in two formats, interleaved into one timeline. The **Source** column shows which file each line came from.*

---

## Features

- **Any file size** — every file is indexed on disk rather than loaded into memory, at about 29 bytes of heap per line, so a 1M-line, 125 MB log uses under 50 MB. Lines are never dropped from a file you open. [More](guide/opening-files.md#large-files)
- **Multi-format parsing** — Log4j pattern and Spring Boot built in, auto-detected on open; add your own formats in the parser editor, without code. [More](guide/parsers.md)
- **Merged views** — open several logs as one timeline, interleaved by timestamp: the rolled files of one app, or the logs of several services. Formats can be mixed, each file can have its own parser and timezone, and a file whose clock is off can be shifted into place. [More](guide/merged-views.md)
- **Several windows** — move tabs into their own windows to watch logs on more than one monitor; a moved tab keeps following without reloading. [More](guide/opening-files.md#several-windows)
- **Compressed files** — open `.gz` and `.zip` files directly; for a zip with several logs, tick the ones you want, in separate tabs or merged. [More](guide/opening-files.md#compressed-files)
- **Plain text fallback** — a file no parser recognizes opens as plain text, with an offer to create a parser for it. [More](guide/opening-files.md#files-no-parser-recognizes)
- **Filtering and Find** — by level, logger, field, time range and text, combined; level, logger and time filters take milliseconds even on a million lines. `Ctrl+F` searches every column. [More](guide/reading-and-filtering.md)
- **Highlights** — color-code rows for triage and jump between them by color. [More](guide/reading-and-filtering.md#highlights)
- **Detail panel** — the full entry with its stack trace, and JSON payloads pretty-printed and syntax-highlighted. [More](guide/reading-and-filtering.md#the-detail-panel)
- **Notes** — annotate lines while you investigate; notes are saved next to the log and come back whenever anyone opens it. [More](guide/annotations-and-review.md)
- **Review bundles** — `File → Export for Review…` packs the log (or every file of a merged view) and its notes into one `.zip` for a colleague, who opens it like any log. [More](guide/annotations-and-review.md#exporting-for-review)
- **Follow mode** — tail a live file like `tail -f`, locally or over SSH; survives log rotation without losing rows. [More](guide/following.md)
- **Columns and layouts** — hide, reorder and size columns, and save named layouts. [More](guide/reading-and-filtering.md#columns)
- **Themes** — Dark and Light built in, plus any FlatLaf theme you install, switched live. [More](guide/settings.md#themes)
- **Plugins** — add parsers, formatters, syntax highlighters or live log sources from a JAR, or install them from a Nexus or Maven Central repository. [More](guide/settings.md#plugins)

---

## Getting started

Each [release](https://github.com/benlazaro/clouseau/releases) has a portable zip per platform with its own Java runtime: unzip it and run the launcher. The [Edge build](https://github.com/benlazaro/clouseau/releases/tag/edge) is a single `clouseau-edge.jar` built from every push to `main`; run it with `java -jar clouseau-edge.jar` on Java 21 or later.

Then open a log with `File → Open` (`Ctrl+O`) or drag it onto the window. The [User Guide](guide/index.md) covers everything else.

To build Clouseau yourself or write a plugin, see [For developers](guide/developers.md).

---

## License

MIT
