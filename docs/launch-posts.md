# Launch posts for 1.0.0-rc.2

Drafts. Post once the rc.2 release is up with all three zips. Attach or link
`docs/screenshots/main-view.png` (and `merged-view.png` where images are allowed).

Release link: https://github.com/benlazaro/clouseau/releases/tag/v1.0.0-rc.2

---

## Show HN

**Title** (80 characters max):

> Show HN: Clouseau – a desktop log viewer for huge files and merged timelines

**URL:** https://github.com/benlazaro/clouseau

**Text:**

I spend a lot of time reading application logs: a support bundle with a dozen rolled files,
three services that each saw part of one request, a 2 GB `.gz` someone attached to a ticket.
Text editors choke on the big ones, `grep` loses the context, and the hosted log platforms
don't help when all you have is a zip of files. So I built a desktop viewer for that work.
It's free and MIT-licensed, and this is the second release candidate for 1.0.

What it does:

- Opens files of any size. Every file is indexed on disk instead of loaded into memory, at
  about 29 bytes of heap per line, so a million-line, 125 MB log opens in under 50 MB, and
  level, logger and time filters come back in milliseconds.
- Merges several logs into one timeline, in different formats and timezones, with a column
  showing which file each line came from. If one server's clock is off, you can shift its
  file into place.
- Recognizes the formats itself: Log4j and Logback patterns, Spring Boot, JSON lines
  (Logback, ECS, Serilog, pino, zap and others) and syslog. Your own formats go in a regex
  editor, no code needed. A file it doesn't recognize opens as plain text.
- Follows live logs like `tail -f`, locally or over SSH, through rotation, and can listen
  on a TCP or UDP port for an app or container that sends its lines. It accepts text only
  and never deserializes what it receives.
- Lets you write notes on lines as you investigate, then export the log and your notes as
  one zip for a colleague, who opens it like any log.
- Makes stack traces readable: framework frames are dimmed, your own frames stand out, and
  clicking a frame opens that line in IntelliJ, VS Code or Cursor.
- Lays out JSON payloads and logged objects one field per line, and folds the big ones so
  you can open them a level at a time.

It's written in Java 21 with Swing and FlatLaf. I picked Swing on purpose: it starts fast,
renders a virtual table of millions of rows without trouble, and works the same on Windows,
macOS and Linux. Each zip bundles its own Java runtime, so there's nothing to install.
Parsers, formatters and live sources are plugins (PF4J), with the API published on Maven
Central. The app finds plugins on Maven Central or your company's Nexus, and tells you when
one you have has an update.

The builds aren't signed with a paid certificate yet, so Windows and macOS warn you the
first time. The install guide shows how to get past that:
https://benlazaro.github.io/clouseau/guide/installing.html

I'd like to hear about anything that crashes or reads your logs wrong. A few sample lines
from a format it gets wrong are the most useful bug report there is.

---

## r/java

**Title:**

> Clouseau 1.0 RC: an open-source desktop log viewer in Java 21 + Swing, with readable stack traces and jump-to-code

**Post** (text post; add the screenshot as an image or link it):

I've been building a desktop log viewer for the logs Java services write, and the second
release candidate for 1.0 is out. It's MIT-licensed, runs on Windows, macOS and Linux, and
each download bundles its own JRE.

Release: https://github.com/benlazaro/clouseau/releases/tag/v1.0.0-rc.2 · Guide:
https://benlazaro.github.io/clouseau/

**For reading JVM logs:**

- **Stack traces:** the exception and each `Caused by:` / `Suppressed:` stand out. Frames
  from the JDK, Spring, Tomcat, Netty, Hibernate, Jackson, proxies and test runners are
  dimmed, and your own frames show their class and method in an accent color.
- **Jump to code:** clicking `Foo.java:42` opens that line in IntelliJ (through the IDE's
  built-in server, so no plugin), VS Code or Cursor. It finds the file in your source
  folders, so the project doesn't need to be open.
- **Logged objects:** objects logged with `toString()` (Lombok `@ToString`, records, Kotlin
  data classes, `ToStringBuilder`, maps and lists) are laid out one field per line and
  colored. JSON payloads are pretty-printed. A big payload folds into one line and opens a
  level at a time, and copying always gives you the full text.
- **Formats:** Log4j 2 and Logback pattern layouts, Spring Boot's default format, and the
  JSON layouts (Logback's, Log4j's, ECS) are recognized on open. MDC
  values become columns you can filter on.
- **Merged timeline:** merge the logs of several services into one, even in different
  formats and timezones, to follow a request across them.

**Under the hood**, for anyone curious:

- Files are indexed on disk rather than held in memory, at about 29 bytes of heap per line
  (offset, length, timestamp, level, and logger and thread ids), and rows are read back from disk as
  they scroll into view. A million-line, 125 MB file opens in under 50 MB of heap.
- Filtering runs on a background executor and decides most filters from the index columns
  without parsing a line. The EDT only ever gets results.
- Live sources push their lines. The SSH source runs `tail -f` on the server over sshj, so
  the client thread sleeps until data arrives. Local follow mode is woken by the OS's
  `WatchService`.
- Plugins use PF4J. `clouseau-api` is on Maven Central (`com.droidenx.clouseau:clouseau-api`) with
  its own version, and CI runs japicmp against the last release, so a plugin built for an
  older API keeps loading. The Plugin Manager lists the plugins on Maven Central or a Nexus
  (named `<name>-clouseau-plugin`) and checks once a day for updates, which you install
  yourself.
- UI: Swing with FlatLaf, MigLayout and RSyntaxTextArea; Inter and JetBrains Mono are
  bundled. Built with Gradle on Java 21, packaged with jlink.

It's a release candidate, so I'm looking for logs it handles badly. If one of your formats
comes out wrong, an issue with a few sample lines (scrubbed) is the most useful thing you
can send: https://github.com/benlazaro/clouseau/issues/new/choose
