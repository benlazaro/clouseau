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
color of error rows; levels a theme doesn't set keep Clouseau's defaults, so logs stay
readable in any theme.

## Settings

**File → Settings…** (`Ctrl+,`):

| Section | Setting | What it does |
|---|---|---|
| Table | Row height | The height of table rows (24 pixels by default) |
| | Stripe alternate rows | Shades every other row, to help follow a long row across a wide window |
| Detail Panel | Font size | The detail panel's font size (also changed with **A−** / **A+**) |
| | Wrap long lines | Wrap long lines in the detail panel instead of scrolling sideways |
| Tabs | Confirm when closing a tab | Ask before closing a tab |
| | Confirm when clearing all highlights | Ask before **Clear all highlights** |
| | Confirm when deleting a note | Ask before deleting a note |
| | Follow new lines by default | Start new tabs with [Follow](following.md) on |
| | Max entries per tab | How many lines an SSH or plugin-source tab keeps in memory (10,000 to 2,000,000; default 200,000). Files you open are never trimmed. |
| Files | Max recent files | How many files **Open Recent** lists |
| | When opening several files | **Ask each time**, **Merge them** into a [merged view](merged-views.md), or open **Separate tabs** |
| | On startup | **Restore last session** (the default) reopens the windows and tabs you had open; **Start empty** opens one empty window. See [Picking up where you left off](opening-files.md#picking-up-where-you-left-off). |
| Appearance | Theme | The theme, with a live preview (also in **View → Theme**) |
| | Display timezone | The timezone timestamps are shown in. See [Timezones](reading-and-filtering.md#timezones). |

## Keyboard shortcuts

**Help → Keyboard Shortcuts** lists them in the app. They work anywhere in a Clouseau
window, and act on that window.

| Shortcut | Action |
|---|---|
| `Ctrl+N` | New window |
| `Ctrl+O` | Open a file |
| `Ctrl+W` | Close the tab |
| `Ctrl+R` | Reconnect an SSH or source-plugin tab |
| `Ctrl+,` | Settings |
| `Ctrl+Tab` / `Ctrl+Shift+Tab` | Next / previous tab (also `Ctrl+PageDown` / `Ctrl+PageUp`) |
| `Ctrl+1` … `Ctrl+8` | Go to tab 1 to 8 |
| `Ctrl+9` | Go to the last tab |
| `Ctrl+Home` / `Ctrl+End` | First / last row |
| `Alt`+click a level | Show only that level / show all again |
| `Ctrl+F` | Find |
| `Enter` / `Shift+Enter` | Next / previous match |
| `Escape` | Close the find bar |
| `Ctrl+Shift+N` | All notes |

The shortcuts use `Ctrl` on every platform, including macOS.

## Plugins

**Tools → Plugins…** opens the Plugin Manager. Plugins add parsers, message formatters,
syntax highlighters and [live sources](following.md#source-plugins).

- The table lists installed plugins; select one to see what it adds. Untick **On** to
  disable a plugin, or **Delete** it.
- **Download…** searches a plugin repository (Nexus or Maven Central) and installs a
  plugin. **Manage…** next to the repository list adds a repository. For Nexus, choose an
  authentication method: none, a username and password, or a Nexus user token.
- **Open Folder** opens the `plugins` folder. Dropping a plugin JAR there and clicking
  **Refresh** works too.

New plugins are available without restarting; tabs you open afterwards use them.

> Nexus answers wrong credentials as if you weren't signed in, so a search that finds
> nothing may mean the password or token is wrong.

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
| Windows release zip | `data` next to `Clouseau.exe` (so extract it somewhere you can write to, not `C:\Program Files`) |
| macOS and Linux | `~/.clouseau/` |
| The Edge jar, or from source | `data` in the folder you start it from |
| With `-Dclouseau.data.dir=<path>` | `<path>` |
