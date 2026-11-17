Title: Clouseau plugin API 0.2.0 — Highlighters name roles, and the theme colors them

The first release of the plugin API on its own, now on Maven Central. Build your plugin against
`com.droidenx.clouseau:clouseau-api:0.2.0` as a `compileOnly` dependency (Clouseau provides the
API at runtime, so don't bundle it), and set
`Plugin-Requires: >=0.2.0` in your JAR's manifest. A Clouseau with a different API version lists
your plugin as Failed and says why, instead of loading it and failing later.

## Breaking change: highlighters return roles, not colors

`LogSyntaxHighlighter.highlight` now returns `List<Span>`, where each `Span(start, end, Role)`
says what a piece of text is: `KEY`, `STRING`, `NUMBER`, `LITERAL`, `KEYWORD`, `OPERATOR`,
`PUNCTUATION`, `MUTED`, `ACCENT` or `ERROR`. The theme picks the colors, so your highlighting
reads well in Dark, Light and custom themes. `ColorSpan` and its RGB colors are gone.

Every enabled highlighter that accepts a message now colors it, so mark only what yours
recognizes. Where spans overlap, the narrower one wins. Give a highlighter the same name as the
formatter whose output it colors, and the two share one button in the detail panel.

## Also new

- `ClouseauApi.VERSION`: the API's own version, separate from Clouseau's.

The [plugin guide](https://benlazaro.github.io/clouseau/guide/developers.html#writing-a-plugin)
has examples.
