---
title: Following live logs
parent: User Guide
nav_order: 5
---

# Following live logs
{: .no_toc }

1. TOC
{:toc}

## Follow mode

**Follow** (at the right of the filter bar) shows new lines as the application writes
them, like `tail -f`. It's on by default for files you open; **Follow new lines by
default** in [Settings](settings.md) changes that. Turn it off and on per tab at any time:
no line is lost or repeated.

While you're at the bottom of the table, it scrolls to show new lines as they arrive.
Scroll up to read earlier lines and the table stays where you put it, though new lines
keep coming in below. Scroll back down to the end, press **Ctrl+End**, or turn Follow off
and on, and it scrolls with new lines again. This works the same for files, merged views,
SSH and other live sources.

A file always opens at the top. Clouseau wakes up as soon as the operating system reports
that the file changed, so new lines appear without delay.

A followed file is indexed like any other, so it can run for hours or days without
running out of memory, and no line is ever dropped.

## Log rotation

Clouseau keeps your rows when the log is rotated:

- **Renamed** (logrotate's default, and log4j and logback rolling): the existing rows stay
  readable from the old file, and the new file is followed from its start.
- **Truncated in place** (logrotate's `copytruncate`): the existing rows keep their time,
  level, logger and thread, but their message reads "Text no longer in the file
  (rotated)", and the status bar says the file was rotated.

## Remote logs over SSH

**File → Open Remote → SSH…** follows a log on another machine.

1. Under **Server**, enter the **Host**, **Port** and **Username**. You can also type or
   paste `user@host` or `user@host:2222` into Host; Clouseau moves the user and port into
   their own fields.
2. Under **Authentication**, choose the **Method**: **Password**, **Private key**, or
   **None** for an account without a password (common on embedded devices). For a key,
   Clouseau fills in `~/.ssh/id_ed25519`, `id_ecdsa` or `id_rsa`, whichever you have; leave
   **Passphrase** empty if the key has none.
3. Under **Log**, enter the **Remote path** of the log, and pick a **Parser** if
   auto-detection isn't right.
4. Click **Connect**. While it's grayed out, the line above the buttons says what's still
   missing.

The tab is named after the file and the host, such as `app.log — 10.0.0.5`, so the same
log followed on two servers gets two distinct tabs. Point at the name to see the user, the
full path, and the port if it isn't 22.

Clouseau runs `tail -F` on the server, so lines arrive as soon as they're written, and it
keeps going when the log is rotated or replaced. If the path is wrong or you can't read
the file, Clouseau says so. If the connection drops without closing (a router or VPN
timing it out), Clouseau notices within about a minute and a quarter and shows an error,
instead of the tab quietly going still. Closing an SSH tab is immediate, even if the connection has
dropped. To keep a connection for next time, tick
**Save this connection** before you click **Connect** (passwords and passphrases are stored
encrypted). Saved connections are listed on the left of the dialog, grouped by host, each
log by its file name with its folder below; type in the filter box to narrow the list by host
or file. Click one to fill in the form, or double-click to connect right away.

- **To change a saved connection**, click it, edit anything (the host, user and path
  included), and click **Connect**: it's updated in place.
- **To follow another file on the same server**, point at a saved log and click the
  duplicate icon at the right of its row (or press `Ctrl+D`). The form keeps every detail
  and selects the file name in **Remote path**; type the new one and click **Connect** to
  save it as a new entry.
- **To forget one**, click the trash can at the right of its row (or select it and press
  `Delete`). The trash can on a host forgets every log saved on it, after asking.

The **Save this connection** box remembers whether you ticked it last time.

### Connection dot and Reconnect

SSH and source-plugin tabs show a dot next to the title:

- **Yellow**: connecting.
- **Green**: connected.
- **Gray**: disconnected. The stream ended or failed; point at the dot to see why.

To connect again, right-click the tab and choose **Reconnect**, or press `Ctrl+R`
(**File → Reconnect**, `Ctrl+R`) on the tab. Reconnecting empties the tab and streams the log again
from the last lines, as if you had just opened it, so no line is shown twice. The tab
keeps its title, parser, filters and columns; notes on its lines are not kept. It signs
in with the same details you entered, without asking again.

An SSH tab keeps its lines in memory, at most **Max entries per tab** (200,000 by default,
in [Settings](settings.md)); past that, the oldest lines are dropped. The status bar shows
how many were trimmed.

## Source plugins

Plugins can bring in lines from other places, such as a log service or a message queue.
Installed source plugins appear under **File → Open Remote**, after SSH, each under its own
name. **Browse Plugins…** at the bottom opens the Plugin Manager's Browse tab. If one
can't open its source, the error names the plugin. Each shows a form for its
settings; Clouseau checks required fields, remembers the values, and stores passwords
encrypted. Lines stream into a new tab, with the same limit, connection dot and
**Reconnect** as SSH tabs.

See [Plugins](settings.md#plugins) to install one, or [For developers](developers.md) to
write one.
