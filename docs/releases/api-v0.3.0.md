Title: Clouseau plugin API 0.3.0 — Parsers know the log's timezone; entries can grow

Build against `com.droidenx.clouseau:clouseau-api:0.3.0` (`compileOnly`) and set
`Plugin-Requires: >=0.3.0` to use what's new. **Plugins built for 0.2.0 keep loading and
running unchanged**: nothing was removed, and every class and method they call is still there.

## Parsers are told the log's timezone

Users can set the timezone a log was written in. Clouseau now calls a new method,
`LogParser.parse(String rawLine, ZoneId writtenIn)`:

- A timestamp **without** an offset is wall time in `writtenIn`.
- A timestamp **with** an offset or zone (`2026-03-27T08:00:00Z`, `+02:00`) keeps it.

Its default calls your `parse(rawLine)` and moves the time from the computer's timezone to
`writtenIn`, which is what Clouseau did before. That's right for formats whose timestamps
never have an offset. **If yours can, override `parse(rawLine, writtenIn)`**: until you do,
setting a log's timezone moves those times. `DateTimeFormatter.withZone(writtenIn)` does it in
one step, since an offset in the text wins over it.

## Build entries with `LogEntry.builder`

```java
LogEntry.builder(line)
        .timestamp(time)
        .level(LogEntry.LogLevel.WARN)
        .logger("com.acme.Billing")
        .message(text)
        .field("traceId", traceId)
        .build();
```

`LogEntry`, `SourceSetting` and `LogSyntaxHighlighter.Span` are now final classes instead of records, with the same
methods. A record can't gain a value without changing its constructor, which would break
every plugin that calls it. Values added in later versions come with a builder (or `with…`)
method and a default, so code that uses the builder and the factory methods keeps working. The
existing constructors stay as they are. `toBuilder()` and `withTimestamp(...)` make changed
copies.

## Also new

- The jar declares `Automatic-Module-Name: com.droidenx.clouseau.api`, so a plugin built as
  a Java module can `requires com.droidenx.clouseau.api;` under a name that won't change.

## Clearer contracts

The Javadoc now says what Clouseau already did:

- One parser instance serves every tab, from several background threads at once, so keep it
  stateless or thread-safe.
- If `canParse` or `parse` throws, or `parse` returns `null`, the line is shown as unparsed.
