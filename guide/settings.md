---
title: Themes and settings
parent: User Guide
nav_order: 7
---

# Themes and settings
{: .no_toc }

1. TOC
{:toc}

## Themes

**View → Theme** lists the built-in **Dark** and **Light** themes and any you've
installed. Picking one applies it at once to the whole window and every tab,
including highlighted rows, and Clouseau remembers it.

**Dark**, the default, uses calm neutral grays, a dark row selection, bright menus and tab
titles, log colors that stay readable on highlighted and selected rows, and no lines
between log rows. Fields and buttons are outlined, as in IntelliJ's settings; amber marks
what is active: the tab underline, the focused control, ticked checkboxes, a set time
range and a dialog's default button.

**Light** is the same design on white: the same outlined controls and amber accent, a
pale blue row selection, and log colors darkened so every level stays easy to read on
white, highlighted and selected rows.

Every theme uses the fonts that come with Clouseau: Inter for the interface and JetBrains
Mono for log text, so it looks the same on every computer.

**View → Theme → Manage Themes…** installs a FlatLaf theme file (`.properties`) or removes
one. Removing the active theme switches back to the default.

A theme can set Clouseau's own colors too. For example, `Clouseau.level.ERROR` sets the
color of error rows, and `Clouseau.syntax.key` the color of JSON keys and other names in
the detail panel (the other roles are `string`, `number`, `literal`, `keyword`, `operator`,
`punctuation`, `muted`, `accent` and `error`). Colors a theme doesn't set keep Clouseau's
defaults, so logs stay readable in any theme.

## Settings

**File → Settings…** (`Ctrl+,`):

| Section | Setting | What it does |
|---|---|---|
| Table | Row height | The height of table rows (24 pixels by default) |
| | Stripe alternate rows | Shades every other row, to help follow a long row across a wide window |
| Detail Panel | Font size | The detail panel's font size (also changed with **Aa** in its header) |
| | Wrap long lines | Wrap long lines in the detail panel instead of scrolling sideways (also the **Wrap Lines** button in its header) |
| Tabs | Confirm when closing a tab | Ask before closing a tab |
| | Confirm when clearing all highlights | Ask before **Clear All Highlights** (Edit menu, highlight bar) |
| | Confirm when deleting a note | Ask before deleting a note |
| | Start at the newest line | Open files at their newest line, with [Follow](following.md) on; off opens them at the top |
| | Max entries per tab | How many lines an SSH or plugin-source tab keeps in memory (10,000 to 2,000,000; default 200,000). Files you open are never trimmed. |
| Files | Max recent files | How many recent files the **File** menu lists |
| | When opening several files | **Ask each time**, **Merge them** into a [merged view](merged-views.md), or open **Separate tabs** |
| | On startup | **Restore last session** (the default) reopens the windows and tabs you had open; **Start empty** opens one empty window. See [Picking up where you left off](opening-files.md#picking-up-where-you-left-off). |
| Appearance | Theme | The theme, with a live preview (also in **View → Theme**) |
| | Show times in | The timezone timestamps are shown in, in every tab. The status bar's timezone popup changes the same setting. See [Timezones](reading-and-filtering.md#timezones). |
| Source Code | Open source in | Where stack frames and logger classes open: a **JetBrains IDE** (the default), **VS Code** or **Cursor**. See [Jumping to the code](reading-and-filtering.md#jumping-to-the-code). |
| | Source folders | Your projects, or folders of projects, searched for a stack frame's file, for every editor. A file that isn't in them, a JetBrains IDE looks for in its open projects. Leave it empty to search the usual places (`~/git`, `~/src`, `~/repos`, `~/IdeaProjects`, …); the hint underneath shows which were found. |
| Updates | Tell me when a new version is available | On by default. See [New versions](#new-versions). |
| Data Folder | Location | Where your settings, sessions and parsers are kept, with **Open Folder**. Shown, not changed here. See [The data folder](#the-data-folder). |

## New versions

When a new version of Clouseau is released, a small window tells you so a few seconds
after Clouseau starts. It doesn't block what you're doing:

- **Download** opens the release's page, where you download the zip for your system and
  unzip it as you did the first time. Clouseau doesn't update itself: see
  [Upgrading](installing.md#upgrading).
- **Later** closes the notice. You'll hear about it again tomorrow.
- **Skip This Version** closes it for good for that version; you'll hear about the next one.

To install a new version over an old one, close Clouseau first and follow
[Upgrading](installing.md#upgrading).

Clouseau checks at most once a day, and again each day it stays open. If it can't reach
GitHub (no network, a proxy), it says nothing. The check only asks GitHub for the list of
releases; nothing about you or your logs is sent.

**Help → Check for Updates…** checks at once and answers in a message: that you already have
the latest version, or that a new one is available, with **Download** to open its page.

To stop the notices, untick **Tell me about new versions** on the notice, or **Tell me when
a new version is available** in **File → Settings… → Updates**. Check for Updates… still
works when they're off.

The edge build (`clouseau-edge.jar`) and Clouseau run from source never check by
themselves: they're ahead of the latest release.

### Plugin updates

Clouseau also checks your plugins a few seconds after it starts, then once a day while it
stays open, and each time you open the Plugin Manager. When one has a newer version:

- A small window lists the plugins with updates (*logfmt 1.2.0 → 1.3.0*). **Open Plugins**
  opens the Plugin Manager; **Later** closes the notice, and you won't hear about those
  versions again, only about newer ones.
- **Tools → Plugins…** says how many updates there are, and so does the Plugin Manager's
  **Installed** tab.
- In **Installed**, the plugin has an arrow and the new version after its own. Select it
  and click **Update to …** in its details. The new version replaces the old one, and a
  note over the list says which plugin was updated and from which version to which.

A plugin is checked against the repository you installed it from in **Browse**. A plugin
you put in the `plugins` folder yourself is checked only if it's on Clouseau's plugin
list, against Maven Central. The check asks each repository for the plugin's latest
version and sends nothing else. Plugins never update by themselves. The same **Tell me
about new versions** setting turns these notices off.

## Keyboard shortcuts

**Help → Keyboard Shortcuts** lists them in the app. They work anywhere in a Clouseau
window, and act on that window.

| Shortcut | Action |
|---|---|
| `Ctrl+N` | New window |
| `Ctrl+O` | Open a file |
| `Ctrl+W` | Close the tab |
| `Ctrl+R` | Reload the file, or reconnect an SSH or source-plugin tab |
| `Ctrl+,` | Settings |
| `Ctrl+C` | Copy the selected row's message |
| `Ctrl+Alt+C` | Copy its raw line |
| `Ctrl+Shift+C` | Copy the whole entry |
| `Ctrl+Tab` / `Ctrl+Shift+Tab` | Next / previous tab (also `Ctrl+PageDown` / `Ctrl+PageUp`) |
| `Ctrl+1` … `Ctrl+8` | Go to tab 1 to 8 |
| `Ctrl+9` | Go to the last tab |
| `Ctrl+Home` / `Ctrl+End` | First / last row |
| `Alt`+click a level | Show only that level / show all again |
| `Ctrl+F` | Find |
| `F3` / `Shift+F3`, or `Enter` / `Shift+Enter` in the find bar | Next / previous match |
| `Escape` | Close the find bar |
| `Ctrl+F` in the detail panel | Find in the entry |
| `Ctrl+Shift+N` | All notes |
| `Ctrl+Shift+K` | Clear the table |

On macOS, use `Cmd` wherever this guide says `Ctrl`, except for switching tabs with
`Ctrl+Tab` and `Ctrl+PageDown` / `Ctrl+PageUp`, which stay `Ctrl` as in other Mac apps. The
app's own menus, tooltips and shortcut list show `Cmd` there.

The menus work from the keyboard too: `Alt` plus a menu's underlined letter opens it
(`Alt+F` for File, `Alt+E` for Edit, `Alt+V` for View, `Alt+T` for Tools, `Alt+H` for
Help), then an item's underlined letter picks it, so `Alt+F`, `O` is Open. The letters are
underlined while `Alt` is held.

## Plugins

**Tools → Plugins…** opens the Plugin Manager. Plugins add parsers, message formatters,
syntax highlighters and [live sources](following.md#source-plugins).

- **Installed** lists each plugin by name, with its description under it and what it adds
  (parser, source, and so on) on the right. Select one to see the details: what it adds,
  its ID, author, license and file. Untick its box (or press `Space`) to turn it off; the
  trash can on the row under the mouse (or `Delete`) removes it.
- A file in the `plugins` folder that couldn't be loaded shows as **Failed**, in red, with
  the reason, such as "Not a Clouseau plugin: its manifest has no Plugin-Id".
- **Browse** opens on the Clouseau plugins in the selected repository: for Maven Central,
  every plugin on Clouseau's plugin list, at its latest version; for a Nexus, every
  artifact named like a plugin (`<name>-clouseau-plugin`). Hover over a plugin to see its
  coordinates and description, and select it to read the description under the table.
  **Adds** says what it brings (parser, source, and so on).
  - Browse opens on the repository you used last. Until you choose one, it opens on the
    Nexus repository you added last, if you have one. A repository you add is selected
    so you can search it right away.
  - Type to filter the plugins by name, description or what they add: `source` shows
    every source plugin.
  - Double-click a plugin, or select it and click **Install**. The new plugin is then
    selected in Installed, with a note saying what was installed (or updated) and its version.
  - **Installed** shows the version you have. When the repository has a newer one, it
    says *update* next to it and the button says **Update**, which replaces your version
    with the new one. On an older version than yours the button says **Downgrade**: it
    asks first, then replaces your version with that one, to go back after an update that
    broke something. The version you have says **Installed**.
  - For a plugin that isn't listed, type part of its name and press `Enter` (or click
    **Search**) to search the whole repository. Only plugins show up (artifacts named
    `<name>-clouseau-plugin` or `clouseau-<name>-plugin`), not other libraries that match. On Maven Central, `g:` and `a:` search
    the group or the artifact name only (`a:logfmt-clouseau-plugin`); on Nexus, any part of
    the group or artifact name works: `fmt` finds `logfmt-clouseau-plugin` (some servers
    allow only the start of a name: `logfmt`). A Nexus search lists every version of each
    plugin it finds; Maven Central shows the latest. Clear the field to go back to the plugins.
  - Click a column's header to sort by it, again to reverse it, and a third time to go back
    to the repository's order; Clouseau keeps the sort. Drag a column's edge to make it
    wider; Clouseau keeps the widths too.
  - While a slow repository answers, the results area shows what it's waiting for and for
    how long; **Cancel** (where Search was) stops it. A repository that doesn't answer
    within a minute stops with a note saying so.
- **Manage…** next to the repository list adds a Nexus repository (Maven Central is always
  there):
  - **URL** is the Nexus server's address, the part before `/repository/`. If you download
    plugins from `https://nexus.example.com/repository/maven-releases/…`, enter
    `https://nexus.example.com`. A server under a path keeps it:
    `https://example.com/nexus`.
  - **Repository** is optional. Leave it empty to search every repository on the server, or
    enter one repository's name as Nexus lists it, such as `maven-releases`, to search only
    that one.
  - **Authentication**: none, a username and password, or a Nexus user token (its name
    code and passcode).
- **Open Plugins Folder** opens the `plugins` folder. A JAR you drop there, or remove,
  shows up in the list while it's open.

New plugins are available without restarting; tabs you open afterwards use them.

> Nexus answers wrong credentials as if you weren't signed in, so a search that finds
> nothing may mean the password or token is wrong. Try a shorter part of the name first.

## Memory

Clouseau starts with a memory limit of 2 GB, enough for files of tens of millions of
lines. If a file is too big, Clouseau stops reading it, says how many lines it loaded, and
suggests a larger limit.

To raise it:

- **Release zip**: edit the launcher's `Clouseau.cfg` (`app\Clouseau.cfg` on Windows,
  `Clouseau.app/Contents/app/Clouseau.cfg` on macOS, `lib/app/Clouseau.cfg` on Linux) and
  change `-Xmx2g` in its `java-options` line, for example to `-Xmx4g`.
- **Edge jar**: `java -Xmx4g -jar clouseau-edge.jar`.

## The data folder

Settings (`settings.json`), your last session (`session.json`) and named sessions (`sessions/`), your parsers (`parsers.json`), installed themes, plugins,
[notes](annotations-and-review.md) for read-only folders, the cache for compressed files,
Clouseau's own log, and saved passwords (encrypted) all live in one folder:

| How you run Clouseau | Data folder |
|---|---|
| Windows release zip | `.clouseau` in your home folder (`C:\Users\<you>\.clouseau`), or, if there is a `data` folder next to `Clouseau.exe`, that folder ([portable](installing.md#portable-on-windows)) |
| macOS and Linux | `~/.clouseau/` |
| The Edge jar, or from source | `data` in the folder you start it from |
| With `-Dclouseau.data.dir=<path>` | `<path>` |

**File → Settings… → Data Folder** shows the folder in use, with **Open Folder** to show it
in your file manager. On Windows, the hint under it says how to switch between portable and
your home folder.

## When something goes wrong

If Clouseau hits an error it didn't expect, it keeps running and tells you in a
**Clouseau ran into a problem** window. The window shows the details (Clouseau's version, the
plugin API version, Java, your operating system and where the error happened), and has buttons to:

- **Copy Details**: copy them to the clipboard.
- **Open Log Folder**: open the folder with Clouseau's own log, `logs/clouseau.log` in the
  [data folder](#the-data-folder), which records the same error.
- **Report Issue…**: copy the details and open a new issue on GitHub, so you can paste them
  in. Clouseau sends nothing by itself.

If something doesn't look right afterwards, restart Clouseau. The same error shows only
once per run, so a problem that keeps repeating doesn't flood you with windows.

## About Clouseau

**Help → About Clouseau** shows which version of Clouseau you have and the version of the
plugin API it supports (a plugin built for a newer API won't load). **Copy Details** copies
both, with your Java and operating system versions, ready to paste into an issue.

## Licenses

Clouseau is free and open source under the MIT License. **Help → About Clouseau →
Licenses** shows its license and the licenses of the libraries and fonts it includes, one
tab per license: each tab lists the libraries under that license, with their copyright and
website, and then the license text. The
release zips also have them as files, in a `legal` folder: next to `Clouseau.exe` on
Windows, `Clouseau.app/Contents/legal` on macOS and `lib/legal` on Linux.
